package cotransfert;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Secret partage : authentification du pair et chiffrement (JDK pur, javax.crypto).
 *
 *   - derive()      : secret texte -> cle maitre (PBKDF2-HMAC-SHA256).
 *   - seal()/open() : AES-256-GCM par segment autonome (transports asynchrones).
 *   - handshake()   : session TCP chiffree = poignee de main + flux d'enregistrements
 *                     AES-256-GCM (voir Transfer).
 *
 * Regle : ni le secret ni aucun materiel de cle ne sont jamais journalises.
 */
public final class Crypto {

    private Crypto() { }

    static final int TAG_LEN = 16;
    static final int NONCE_LEN = 12;

    // --- Derivation --------------------------------------------------------

    /** Sel applicatif fixe : le secret est le seul parametre partage entre les postes. */
    private static final byte[] KDF_SALT = ascii("CoTransfert/secret/v1");
    static final int KDF_ITERATIONS = 600_000;

    /** Cle opaque derivee du secret partage. */
    public static final class Key {
        private final byte[] sealKey; // segments autonomes (seal/open)
        private final byte[] tcpKey;  // poignee de main des sessions TCP

        Key(byte[] master) {
            this.sealKey = hmac(master, ascii("CoTransfert seal v1"));
            this.tcpKey = hmac(master, ascii("CoTransfert tcp v1"));
        }

        @Override public String toString() { return "Crypto.Key[masquee]"; }
    }

    /**
     * PBKDF2-HMAC-SHA256, sel applicatif fixe, 600 000 iterations (~0,3 s, une
     * seule fois au demarrage). Secret null ou vide -> null (pas de chiffrement).
     * Le secret est pris tel quel, espaces de bord retires (sensible a la casse).
     */
    public static Key derive(String secret) {
        if (secret == null) return null;
        String s = secret.trim();
        if (s.isEmpty()) return null;
        PBEKeySpec spec = new PBEKeySpec(s.toCharArray(), KDF_SALT, KDF_ITERATIONS, 256);
        try {
            byte[] master = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            Key k = new Key(master);
            Arrays.fill(master, (byte) 0);
            return k;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 indisponible dans ce JDK", e);
        } finally {
            spec.clearPassword();
        }
    }

    // --- Segments autonomes : seal / open ----------------------------------
    // Format : [version:1][alea:16][chiffre + tag:16]
    // Cle du segment = HMAC(cle seal, version || alea) ; nonce = 0000 || seq:8.
    // L'alea de 128 bits rend la reutilisation (cle, nonce) negligeable meme si
    // seq repart de zero (redemarrage, autre dossier, autre sens).

    static final int SEAL_VERSION = 1;
    static final int SEAL_SALT_LEN = 16;
    /** Octets ajoutes par seal() au clair. */
    public static final int SEAL_OVERHEAD = 1 + SEAL_SALT_LEN + TAG_LEN;

    private static final SecureRandom RNG = new SecureRandom();

    public static byte[] seal(Key k, long seq, byte[] aad, byte[] plain) throws IOException {
        if (k == null) throw new IOException("chiffrement demande sans cle");
        byte[] head = new byte[1 + SEAL_SALT_LEN];
        byte[] salt = new byte[SEAL_SALT_LEN];
        RNG.nextBytes(salt);
        head[0] = SEAL_VERSION;
        System.arraycopy(salt, 0, head, 1, SEAL_SALT_LEN);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(hmac(k.sealKey, head), "AES"),
                    new GCMParameterSpec(TAG_LEN * 8, nonce(seq)));
            if (aad != null) c.updateAAD(aad);
            byte[] out = new byte[head.length + plain.length + TAG_LEN];
            System.arraycopy(head, 0, out, 0, head.length);
            c.doFinal(plain, 0, plain.length, out, head.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IOException("chiffrement impossible (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** IOException si le segment n'est pas authentique (secret, seq, aad ou contenu differents). */
    public static byte[] open(Key k, long seq, byte[] aad, byte[] sealed) throws IOException {
        if (k == null) throw new IOException("dechiffrement demande sans cle");
        if (sealed == null || sealed.length < SEAL_OVERHEAD) throw new IOException("segment chiffre tronque");
        if (sealed[0] != SEAL_VERSION) throw new IOException("segment chiffre de version inconnue");
        int headLen = 1 + SEAL_SALT_LEN;
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(hmac(k.sealKey, Arrays.copyOf(sealed, headLen)), "AES"),
                    new GCMParameterSpec(TAG_LEN * 8, nonce(seq)));
            if (aad != null) c.updateAAD(aad);
            return c.doFinal(sealed, headLen, sealed.length - headLen);
        } catch (AEADBadTagException e) {
            throw new IOException("segment chiffre invalide (secret partage incorrect ou donnees alterees)");
        } catch (GeneralSecurityException e) {
            throw new IOException("dechiffrement impossible (" + e.getClass().getSimpleName() + ")");
        }
    }

    // --- Politique ---------------------------------------------------------

    /**
     * true = interdit de parler a cette adresse sans secret : tout ce qui n'est
     * ni boucle locale ni lien local (169.254/16, fe80::/10). Adresse inconnue
     * (null) -> true par prudence.
     */
    public static boolean requiresSecret(InetAddress peer) {
        if (peer == null) return true;
        return !(peer.isLoopbackAddress() || peer.isLinkLocalAddress());
    }

    // --- Generation d'un secret --------------------------------------------

    /** 32 symboles sans ambiguite visuelle (ni I, O, 0, 1) : 5 bits par caractere. */
    static final String SECRET_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    /** Secret aleatoire lisible : 4 groupes de 5 caracteres (100 bits), ex. K7QMD-2XHTB-... */
    public static String generateSecret() {
        StringBuilder sb = new StringBuilder(23);
        for (int i = 0; i < 20; i++) {
            if (i > 0 && i % 5 == 0) sb.append('-');
            sb.append(SECRET_ALPHABET.charAt(RNG.nextInt(SECRET_ALPHABET.length())));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------------
    // Session TCP chiffree.
    //
    // Poignee de main (chaque sens, des la connexion, avant tout cadre) :
    //   HELLO = "COTS"(4) | version(1) | alea(16) | cle publique X25519 ephemere (44, X.509)
    //   prk   = HMAC(cle tcp, "CoTransfert session v1" | HELLO client | HELLO hub | secret X25519)
    //   cle client->hub = HMAC(prk, "c2h") ; cle hub->client = HMAC(prk, "h2c")
    //   Preuve : le hub envoie le 1er enregistrement chiffre, le client le verifie
    //   puis envoie le sien, que le hub verifie. Le client ne prouve donc rien a
    //   un faux hub. Echec -> alerte en clair (longueur 0xFFFFFFFF) puis fermeture.
    //
    // Enregistrement : [longueur:4][chiffre + tag:16], AES-256-GCM,
    //   nonce = 0000 || compteur:8 (par sens, part de 0), AAD = les 4 octets de longueur.
    // ------------------------------------------------------------------------

    static final byte[] HS_MAGIC = ascii("COTS");
    static final int HS_VERSION = 1;
    private static final int HS_NONCE_LEN = 16;
    private static final int X25519_X509_LEN = 44;
    static final int HS_HELLO_LEN = 4 + 1 + HS_NONCE_LEN + X25519_X509_LEN;
    private static final int ALERT = 0xFFFFFFFF;

    /**
     * Clair maximal par enregistrement. Mesure en boucle locale : 64 Kio est le
     * meilleur compromis (16 Kio, 256 Kio et 1 Mio donnent un debit plus faible).
     */
    static final int REC_PLAIN = 64 << 10;

    /** Le pair ne detient pas le meme secret (ou le flux a ete altere). */
    static final class AuthException extends IOException {
        private static final long serialVersionUID = 1L;
        AuthException(String m) { super(m); }
    }

    /** Flux chiffres d'une session etablie. */
    static final class Channel {
        final InputStream in;
        final OutputStream out;
        Channel(InputStream in, OutputStream out) { this.in = in; this.out = out; }
    }

    /** true si ces 4 octets sont le debut d'une poignee de main chiffree. */
    static boolean isHandshakeMagic(byte[] b4) {
        return b4.length >= 4 && b4[0] == HS_MAGIC[0] && b4[1] == HS_MAGIC[1] && b4[2] == HS_MAGIC[2] && b4[3] == HS_MAGIC[3];
    }

    /**
     * Execute la poignee de main sur des flux bruts (l'appelant pose le delai
     * de lecture). IOException au message explicite en cas d'echec.
     */
    static Channel handshake(Key k, boolean hub, InputStream rawIn, OutputStream rawOut) throws IOException {
        if (k == null) throw new IOException("session chiffree demandee sans cle");
        DataInputStream in = new DataInputStream(new BufferedInputStream(rawIn, 4 * (REC_PLAIN + TAG_LEN + 4)));
        try {
            KeyPair eph = KeyPairGenerator.getInstance("X25519").generateKeyPair();
            byte[] pub = eph.getPublic().getEncoded();
            if (pub.length != X25519_X509_LEN) throw new IOException("cle X25519 de format inattendu");
            byte[] mine = new byte[HS_HELLO_LEN];
            System.arraycopy(HS_MAGIC, 0, mine, 0, 4);
            mine[4] = HS_VERSION;
            byte[] rnd = new byte[HS_NONCE_LEN];
            RNG.nextBytes(rnd);
            System.arraycopy(rnd, 0, mine, 5, HS_NONCE_LEN);
            System.arraycopy(pub, 0, mine, 5 + HS_NONCE_LEN, pub.length);
            rawOut.write(mine);
            rawOut.flush();

            byte[] theirs = new byte[HS_HELLO_LEN];
            try {
                in.readFully(theirs, 0, 4);
                if (!isHandshakeMagic(theirs)) {
                    throw new IOException("le pair n'ouvre pas de session chiffree (secret absent de son cotransfert.conf ?)");
                }
                in.readFully(theirs, 4, HS_HELLO_LEN - 4);
            } catch (EOFException e) {
                throw new IOException("connexion fermee pendant la poignee de main (le pair n'attend pas de session chiffree ?)");
            } catch (java.net.SocketTimeoutException e) {
                throw new IOException("pas de reponse a la poignee de main chiffree (le pair n'attend pas de session chiffree ?)");
            }
            if (theirs[4] != HS_VERSION) {
                throw new IOException("version de chiffrement differente chez le pair (mettre a jour le jar des deux cotes)");
            }
            PublicKey peerPub = KeyFactory.getInstance("X25519").generatePublic(
                    new X509EncodedKeySpec(Arrays.copyOfRange(theirs, 5 + HS_NONCE_LEN, HS_HELLO_LEN)));
            KeyAgreement ka = KeyAgreement.getInstance("X25519");
            ka.init(eph.getPrivate());
            ka.doPhase(peerPub, true);
            byte[] dh = ka.generateSecret();

            byte[] helloClient = hub ? theirs : mine, helloHub = hub ? mine : theirs;
            byte[] prk = hmac(k.tcpKey, ascii("CoTransfert session v1"), helloClient, helloHub, dh);
            byte[] c2h = hmac(prk, ascii("c2h")), h2c = hmac(prk, ascii("h2c"));
            Arrays.fill(dh, (byte) 0);
            Arrays.fill(prk, (byte) 0);

            SealedInput sin = new SealedInput(in, hub ? c2h : h2c);
            SealedOutput sout = new SealedOutput(rawOut, hub ? h2c : c2h);
            byte[] proofHub = ascii("COTS-OK-HUB"), proofClient = ascii("COTS-OK-CLIENT");
            if (hub) {
                sout.write(proofHub);
                sout.flush();
                expectProof(sin, proofClient, rawOut);
            } else {
                expectProof(sin, proofHub, rawOut);
                sout.write(proofClient);
                sout.flush();
            }
            return new Channel(sin, sout);
        } catch (GeneralSecurityException e) {
            throw new IOException("poignee de main chiffree invalide (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static void expectProof(SealedInput sin, byte[] expected, OutputStream rawOut) throws IOException {
        byte[] got = new byte[expected.length];
        try {
            int n = 0;
            while (n < got.length) {
                int r = sin.read(got, n, got.length - n);
                if (r < 0) throw new EOFException();
                n += r;
            }
            if (!MessageDigest.isEqual(got, expected)) throw new AuthException("preuve inattendue");
        } catch (PeerAlert e) {
            throw new AuthException("secret partage incorrect (refuse par le pair)");
        } catch (AuthException e) {
            try { // previent le pair en clair : il journalise et ferme sans attendre son delai
                rawOut.write(new byte[] { (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF });
                rawOut.flush();
            } catch (IOException ignore) { }
            throw new AuthException("secret partage incorrect");
        } catch (EOFException e) {
            throw new IOException("connexion fermee pendant la poignee de main (secret partage incorrect ?)");
        } catch (java.net.SocketTimeoutException e) {
            throw new IOException("poignee de main chiffree sans reponse");
        }
    }

    /** Le pair a signale en clair qu'il refuse la session. */
    private static final class PeerAlert extends IOException {
        private static final long serialVersionUID = 1L;
        PeerAlert() { super("session refusee par le pair"); }
    }

    /** Emission : decoupe le flux en enregistrements AES-GCM. Non thread-safe (comme tout flux). */
    static final class SealedOutput extends OutputStream {
        private final OutputStream out;
        private final Cipher cipher;
        private final SecretKeySpec key;
        private final byte[] plain = new byte[REC_PLAIN];
        private final byte[] rec = new byte[4 + REC_PLAIN + TAG_LEN];
        private int fill;
        private long seq;
        private boolean broken;

        SealedOutput(OutputStream out, byte[] key) throws GeneralSecurityException {
            this.out = out;
            this.key = new SecretKeySpec(key, "AES");
            this.cipher = Cipher.getInstance("AES/GCM/NoPadding");
        }

        @Override public void write(int b) throws IOException {
            if (fill == plain.length) drain();
            plain[fill++] = (byte) b;
        }

        @Override public void write(byte[] b, int off, int len) throws IOException {
            while (len > 0) {
                if (fill == 0 && len >= REC_PLAIN) { // gros bloc : chiffre sans copie intermediaire
                    emit(b, off, REC_PLAIN);
                    off += REC_PLAIN; len -= REC_PLAIN;
                    continue;
                }
                int n = Math.min(len, plain.length - fill);
                System.arraycopy(b, off, plain, fill, n);
                fill += n; off += n; len -= n;
                if (fill == plain.length) drain();
            }
        }

        /** Un petit cadre de controle part tout de suite : enregistrement partiel + flush. */
        @Override public void flush() throws IOException {
            drain();
            out.flush();
        }

        @Override public void close() throws IOException {
            try { flush(); } finally { out.close(); }
        }

        private void drain() throws IOException {
            if (fill > 0) { int n = fill; fill = 0; emit(plain, 0, n); }
        }

        private void emit(byte[] src, int off, int len) throws IOException {
            if (broken) throw new IOException("flux chiffre ferme");
            if (seq < 0) throw new IOException("compteur d'enregistrements epuise");
            int clen = len + TAG_LEN;
            rec[0] = (byte) (clen >>> 24); rec[1] = (byte) (clen >>> 16); rec[2] = (byte) (clen >>> 8); rec[3] = (byte) clen;
            try {
                cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LEN * 8, nonce(seq++)));
                cipher.updateAAD(rec, 0, 4);
                cipher.doFinal(src, off, len, rec, 4);
            } catch (GeneralSecurityException e) {
                broken = true;
                throw new IOException("chiffrement impossible (" + e.getClass().getSimpleName() + ")");
            }
            try {
                out.write(rec, 0, 4 + clen);
            } catch (IOException e) {
                broken = true; // un enregistrement partiel est parti : le flux n'est plus utilisable
                throw e;
            }
        }
    }

    /** Reception : verifie et dechiffre les enregistrements. Toute alteration coupe la session. */
    static final class SealedInput extends InputStream {
        private final DataInputStream in;
        private final Cipher cipher;
        private final SecretKeySpec key;
        private final byte[] ct = new byte[REC_PLAIN + TAG_LEN];
        private final byte[] plain = new byte[REC_PLAIN];
        private final byte[] lenBytes = new byte[4];
        private int pos, lim;
        private long seq;
        private boolean eof, broken;

        SealedInput(DataInputStream in, byte[] key) throws GeneralSecurityException {
            this.in = in;
            this.key = new SecretKeySpec(key, "AES");
            this.cipher = Cipher.getInstance("AES/GCM/NoPadding");
        }

        @Override public int read() throws IOException {
            if (pos == lim && !fill()) return -1;
            return plain[pos++] & 0xFF;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (pos == lim && !fill()) return -1;
            int n = Math.min(len, lim - pos);
            System.arraycopy(plain, pos, b, off, n);
            pos += n;
            return n;
        }

        @Override public int available() { return lim - pos; }

        @Override public void close() throws IOException { in.close(); }

        /** Charge le prochain enregistrement non vide ; false = fin propre du flux. */
        private boolean fill() throws IOException {
            if (broken) throw new IOException("flux chiffre invalide");
            while (true) {
                if (eof) return false;
                int b0 = in.read();
                if (b0 < 0) { eof = true; return false; } // coupure entre deux enregistrements
                lenBytes[0] = (byte) b0;
                in.readFully(lenBytes, 1, 3);
                int clen = ((lenBytes[0] & 0xFF) << 24) | ((lenBytes[1] & 0xFF) << 16)
                        | ((lenBytes[2] & 0xFF) << 8) | (lenBytes[3] & 0xFF);
                if (clen == ALERT) { broken = true; throw new PeerAlert(); }
                if (clen < TAG_LEN || clen > ct.length) {
                    broken = true;
                    throw new AuthException("enregistrement chiffre invalide (flux altere ou secret partage incorrect)");
                }
                in.readFully(ct, 0, clen);
                try {
                    cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LEN * 8, nonce(seq++)));
                    cipher.updateAAD(lenBytes, 0, 4);
                    lim = cipher.doFinal(ct, 0, clen, plain, 0);
                    pos = 0;
                } catch (AEADBadTagException e) {
                    broken = true; lim = pos = 0;
                    throw new AuthException("enregistrement chiffre invalide (flux altere ou secret partage incorrect)");
                } catch (GeneralSecurityException e) {
                    broken = true; lim = pos = 0;
                    throw new IOException("dechiffrement impossible (" + e.getClass().getSimpleName() + ")");
                }
                if (lim > 0) return true;
            }
        }
    }

    // --- Outils ------------------------------------------------------------

    /** Nonce GCM de 96 bits : 4 octets nuls puis le compteur sur 8 octets. */
    private static byte[] nonce(long seq) {
        byte[] n = new byte[NONCE_LEN];
        for (int i = 0; i < 8; i++) n[NONCE_LEN - 1 - i] = (byte) (seq >>> (8 * i));
        return n;
    }

    static byte[] hmac(byte[] key, byte[]... parts) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            for (byte[] p : parts) mac.update(p);
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 indisponible dans ce JDK", e);
        }
    }

    private static byte[] ascii(String s) { return s.getBytes(StandardCharsets.US_ASCII); }
}
