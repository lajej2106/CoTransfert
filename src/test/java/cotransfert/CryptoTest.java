package cotransfert;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests de Crypto et des sessions TCP chiffrees (main simple, sans JUnit).
 * Code de sortie non nul au premier echec.
 *
 *   java -cp build cotransfert.CryptoTest [--no-bench] [--bench-gib N]
 *
 * Utilise le port fixe Transfer.PORT sur 127.0.0.1 : attend qu'il soit libre et
 * ferme son ecoute apres chaque scenario.
 */
@SuppressWarnings("try")
public final class CryptoTest {

    private static final AtomicInteger CHECKS = new AtomicInteger();
    private static final ByteArrayOutputStream CAPTURE = new ByteArrayOutputStream();

    public static void main(String[] args) throws Exception {
        boolean bench = true;
        long gib = 1;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--no-bench")) bench = false;
            if (args[i].equals("--bench-gib") && i + 1 < args.length) gib = Long.parseLong(args[i + 1]);
        }
        // Double la sortie pour verifier les lignes de journal (et l'absence du secret).
        final PrintStream real = System.out;
        System.setOut(new PrintStream(new OutputStream() {
            @Override public void write(int b) { real.write(b); synchronized (CAPTURE) { CAPTURE.write(b); } }
            @Override public void write(byte[] b, int off, int len) { real.write(b, off, len); synchronized (CAPTURE) { CAPTURE.write(b, off, len); } }
            @Override public void flush() { real.flush(); }
        }, true));
        try {
            testSealOpen();
            testSecret();
            testRequiresSecret();
            testSubnet();
            Crypto.Key good = Crypto.derive(SECRET_A);
            Crypto.Key bad = Crypto.derive(SECRET_B);
            testWirePlain();
            testSession("clair", null);
            testSession("chiffre", good);
            testWrongSecret(good, bad);
            testPlainClientSecureHub(good);
            testSecureClientPlainHub(good);
            String out = captured();
            check(!out.contains(SECRET_A) && !out.contains(SECRET_B), "le secret n'apparait jamais dans le journal");
            if (bench) {
                double plain = throughput(null, gib << 30);
                double sec = throughput(good, gib << 30);
                System.out.printf("DEBIT boucle locale sur %d Gio : clair %.0f Mo/s (%.2f Gbps), chiffre %.0f Mo/s (%.2f Gbps), ratio %.2f%n",
                        gib, plain / 1e6, plain * 8 / 1e9, sec / 1e6, sec * 8 / 1e9, sec / plain);
            }
            waitPortFree();
            System.out.println("OK - " + CHECKS.get() + " verifications");
            System.exit(0);
        } catch (Throwable t) {
            System.out.println("ECHEC: " + t);
            t.printStackTrace(real);
            System.exit(1);
        }
    }

    private static final String SECRET_A = "ABCDE-FGHJK-LMNPQ-RSTUV";
    private static final String SECRET_B = "ABCDE-FGHJK-LMNPQ-RSTUW";

    // --- Primitives ----------------------------------------------------------

    private static void testSealOpen() throws Exception {
        check(Crypto.derive(null) == null, "derive(null) == null");
        check(Crypto.derive("   ") == null, "derive(blanc) == null");
        Crypto.Key k = Crypto.derive(SECRET_A);
        Crypto.Key k2 = Crypto.derive(" " + SECRET_A + "\n");
        Crypto.Key other = Crypto.derive(SECRET_B);
        check(!k.toString().contains(SECRET_A), "Key.toString ne revele rien");
        byte[] aad = "dossier/hub-vers-client".getBytes("UTF-8");
        Random rnd = new Random(42);
        for (int size : new int[] { 0, 1, 15, 16, 17, 1000, 65536, 3 << 20 }) {
            byte[] plain = new byte[size];
            rnd.nextBytes(plain);
            byte[] sealed = Crypto.seal(k, 7, aad, plain);
            check(sealed.length == size + Crypto.SEAL_OVERHEAD, "taille du segment scelle (" + size + ")");
            check(Arrays.equals(plain, Crypto.open(k, 7, aad, sealed)), "aller-retour seal/open (" + size + ")");
            check(Arrays.equals(plain, Crypto.open(k2, 7, aad, sealed)), "meme secret (espaces de bord) -> meme cle");
            check(Arrays.equals(plain, Crypto.open(k, 7, null, Crypto.seal(k, 7, null, plain))), "aad null");
        }
        byte[] plain = "contenu confidentiel".getBytes("UTF-8");
        final byte[] sealed = Crypto.seal(k, 7, aad, plain);
        check(!Arrays.equals(sealed, Crypto.seal(k, 7, aad, plain)), "deux scellements du meme clair different");
        check(indexOf(sealed, plain) < 0, "le clair n'apparait pas dans le segment");
        for (int bit : new int[] { 0, 8, 8 * 5, 8 * 17, 8 * 20 + 3, 8 * (sealed.length - 1) + 7 }) {
            byte[] t = sealed.clone();
            t[bit / 8] ^= (byte) (1 << (bit % 8));
            expectIo(() -> Crypto.open(k, 7, aad, t), "bit " + bit + " inverse -> refuse");
        }
        expectIo(() -> Crypto.open(k, 8, aad, sealed), "mauvais seq -> refuse");
        expectIo(() -> Crypto.open(k, 7, "autre".getBytes("UTF-8"), sealed), "mauvais aad -> refuse");
        expectIo(() -> Crypto.open(k, 7, null, sealed), "aad absent -> refuse");
        expectIo(() -> Crypto.open(other, 7, aad, sealed), "mauvaise cle -> refuse");
        expectIo(() -> Crypto.open(k, 7, aad, Arrays.copyOf(sealed, sealed.length - 1)), "segment tronque -> refuse");
        expectIo(() -> Crypto.open(k, 7, aad, new byte[5]), "segment trop court -> refuse");
        expectIo(() -> Crypto.seal(null, 0, null, new byte[1]), "seal sans cle -> refuse");
    }

    private static void testSecret() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 200; i++) {
            String s = Crypto.generateSecret();
            check(s.matches("[A-HJ-NP-Z2-9]{5}(-[A-HJ-NP-Z2-9]{5}){3}"), "format du secret genere: " + (i == 0 ? "ok" : ""));
            check(seen.add(s), "secrets generes distincts");
        }
        check(Crypto.SECRET_ALPHABET.length() == 32, "alphabet de 32 symboles (20 x 5 = 100 bits)");
        check(Crypto.derive(Crypto.generateSecret()) != null, "un secret genere est derivable");
    }

    private static void testRequiresSecret() throws Exception {
        String[] free = { "127.0.0.1", "127.8.9.10", "169.254.1.2", "169.254.255.254", "::1", "fe80::1" };
        String[] must = { "192.168.1.10", "10.0.0.1", "172.16.5.4", "8.8.8.8", "169.253.1.1", "169.255.0.1",
                "100.64.0.1", "2001:db8::1", "fd00::1", "0.0.0.0" };
        for (String a : free) check(!Crypto.requiresSecret(InetAddress.getByName(a)), "sans secret autorise: " + a);
        for (String a : must) check(Crypto.requiresSecret(InetAddress.getByName(a)), "secret exige: " + a);
        check(Crypto.requiresSecret(null), "adresse inconnue -> secret exige");
        // Le client refuse de composer vers une adresse non locale sans cle (aucune connexion tentee).
        final AtomicInteger called = new AtomicInteger();
        long t0 = System.nanoTime();
        check(!Transfer.dialOnce("192.0.2.1", s -> called.incrementAndGet(), null, 3000), "dialOnce sans cle hors lien direct -> false");
        check(ms(t0) < 1000 && called.get() == 0, "refus immediat, sans connexion");
        check(captured().contains("chiffrement obligatoire"), "refus journalise avec la marche a suivre");
    }

    private static void testSubnet() {
        check(Discovery.sameSubnet("192.168.1.77", "192.168.1.10", 24), "meme /24");
        check(!Discovery.sameSubnet("192.168.2.77", "192.168.1.10", 24), "autre /24");
        check(Discovery.sameSubnet("10.200.3.4", "10.0.0.1", 8), "meme /8");
        check(!Discovery.sameSubnet("224.0.0.22", "192.168.1.10", 24), "multicast hors sous-reseau");
        check(!Discovery.sameSubnet("pas-une-ip", "192.168.1.10", 24), "entree invalide ignoree");
        check(new Discovery("test").resolveHub(0) == null || true, "resolveHub(0) rend la main");
    }

    // --- Sessions --------------------------------------------------------------

    /** Hub de test : lie le port, sert dans un thread, se ferme proprement. */
    private static final class Hub implements AutoCloseable {
        final ServerSocket srv;
        final Thread thread;
        Hub(final Transfer.SessionHandler handler, final Crypto.Key key) throws Exception {
            waitPortFree();
            srv = Transfer.bindHub();
            thread = new Thread(() -> {
                try { Transfer.acceptLoop(srv, handler, key); } catch (IOException expected) { }
            }, "test-hub");
            thread.setDaemon(true);
            thread.start();
        }
        @Override public void close() throws Exception {
            srv.close();
            thread.join(5000);
            check(!thread.isAlive(), "l'ecoute du hub de test est fermee");
        }
    }

    /** Hub echo : renvoie chaque cadre recu (entete + contenu) a l'identique. */
    private static Transfer.SessionHandler echo(final AtomicReference<Throwable> err) {
        return s -> {
            try {
                while (true) {
                    Transfer.Msg m;
                    try { m = s.recvHeader(); } catch (IOException end) { return; }
                    ByteArrayOutputStream body = new ByteArrayOutputStream();
                    s.copyContentTo(m, body);
                    s.sendFile(m, new ByteArrayInputStream(body.toByteArray()));
                }
            } catch (Throwable t) {
                err.set(t);
            } finally { s.close(); }
        };
    }

    private static void testSession(final String label, Crypto.Key key) throws Exception {
        final AtomicReference<Throwable> hubErr = new AtomicReference<>();
        final AtomicReference<Throwable> cliErr = new AtomicReference<>();
        final AtomicInteger frames = new AtomicInteger();
        try (Hub hub = new Hub(echo(hubErr), key)) {
            boolean ok = Transfer.dialOnce("127.0.0.1", s -> {
                try {
                    Random rnd = new Random(1);
                    // petit cadre de controle : doit partir tout de suite (flush) et revenir
                    long t0 = System.nanoTime();
                    roundTrip(s, Transfer.OP_PING, "ping", new byte[0]);
                    check(ms(t0) < 2000, label + ": un petit cadre part sans attendre");
                    frames.incrementAndGet();
                    for (int size : new int[] { 1, 100, 65535, 65536, 65537, 200_000, 5 << 20, (9 << 20) + 13 }) {
                        byte[] data = new byte[size];
                        rnd.nextBytes(data);
                        roundTrip(s, Transfer.OP_FILE, "dossier/fichier-" + size + ".bin", data);
                        frames.incrementAndGet();
                    }
                    // pipelining : plusieurs cadres envoyes avant de lire les reponses
                    for (int i = 0; i < 50; i++) s.sendFile(msg(Transfer.OP_FILE, "p" + i, 300), new ByteArrayInputStream(new byte[300]));
                    for (int i = 0; i < 50; i++) {
                        Transfer.Msg m = s.recvHeader();
                        check(m.relPath.equals("p" + i) && m.size == 300, label + ": ordre des cadres conserve");
                        s.copyContentTo(m, null);
                    }
                    frames.incrementAndGet();
                } catch (Throwable t) {
                    cliErr.set(t);
                } finally { s.close(); }
            }, key, 3000);
            check(ok, label + ": session etablie");
        }
        if (cliErr.get() != null) throw new AssertionError(label + ": client", cliErr.get());
        if (hubErr.get() != null) throw new AssertionError(label + ": hub", hubErr.get());
        check(frames.get() == 10, label + ": tous les cadres echanges dans les deux sens");
    }

    private static Transfer.Msg msg(int op, String path, long size) {
        Transfer.Msg m = new Transfer.Msg();
        m.op = op; m.relPath = path; m.size = size;
        m.modTime = 1_700_000_000_123L; m.hash = 0x1122334455667788L; m.base = Transfer.BASE_ABSENT;
        return m;
    }

    private static void roundTrip(Transfer.Session s, int op, String path, byte[] data) throws IOException {
        Transfer.Msg m = msg(op, path, data.length);
        s.sendFile(m, new ByteArrayInputStream(data));
        Transfer.Msg r = s.recvHeader();
        check(r.op == op && r.relPath.equals(path) && r.size == data.length && r.modTime == m.modTime
                && r.hash == m.hash && r.base == m.base, "entete identique au retour (" + path + ")");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        s.copyContentTo(r, body);
        check(Arrays.equals(data, body.toByteArray()), "contenu identique au retour (" + path + ", " + data.length + " octets)");
    }

    /** Sans cle, le format de fil reste exactement celui d'origine : verifie octet par octet. */
    private static void testWirePlain() throws Exception {
        final AtomicReference<Throwable> hubErr = new AtomicReference<>();
        byte[] content = "bonjour".getBytes("UTF-8");
        ByteArrayOutputStream exp = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(exp);
        byte[] path = "a/b.txt".getBytes("UTF-8");
        d.writeByte(Transfer.OP_FILE); d.writeShort(path.length); d.write(path);
        d.writeLong(content.length); d.writeLong(1_700_000_000_123L); d.writeLong(0x1122334455667788L);
        d.writeLong(Transfer.BASE_ABSENT); d.write(content);
        byte[] expected = exp.toByteArray();
        try (Hub hub = new Hub(echo(hubErr), null); Socket c = new Socket()) {
            c.connect(new InetSocketAddress("127.0.0.1", Transfer.PORT), 3000);
            c.setSoTimeout(5000);
            c.getOutputStream().write(expected); // cadre fabrique a la main, sans passer par Transfer
            c.getOutputStream().flush();
            byte[] got = new byte[expected.length];
            new DataInputStream(c.getInputStream()).readFully(got);
            check(Arrays.equals(expected, got), "clair: format de fil inchange octet pour octet");
        }
        if (hubErr.get() != null) throw new AssertionError("clair brut: hub", hubErr.get());
    }

    private static void testWrongSecret(Crypto.Key good, Crypto.Key bad) throws Exception {
        final AtomicInteger hubCalls = new AtomicInteger(), cliCalls = new AtomicInteger();
        int before = count(captured(), "secret partage incorrect");
        long t0 = System.nanoTime();
        try (Hub hub = new Hub(s -> { hubCalls.incrementAndGet(); s.close(); }, good)) {
            t0 = System.nanoTime();
            boolean ok = Transfer.dialOnce("127.0.0.1", s -> { cliCalls.incrementAndGet(); s.close(); }, bad, 3000);
            check(!ok, "mauvais secret: dialOnce rend false");
            check(ms(t0) < 3000, "mauvais secret: echec rapide cote client (" + ms(t0) + " ms)");
            long t1 = System.nanoTime();
            while (count(captured(), "secret partage incorrect") < before + 2 && ms(t1) < 3000) Thread.sleep(20);
        }
        String out = captured();
        check(count(out, "secret partage incorrect") >= before + 2, "mauvais secret: journalise des deux cotes sans attendre");
        check(out.contains("hub: session chiffree avec") && out.contains("client: session chiffree avec"), "mauvais secret: lignes hub et client");
        check(hubCalls.get() == 0 && cliCalls.get() == 0, "mauvais secret: aucune session ouverte");
    }

    private static void testPlainClientSecureHub(Crypto.Key good) throws Exception {
        final AtomicInteger hubCalls = new AtomicInteger();
        final AtomicReference<String> cliEnd = new AtomicReference<>();
        try (Hub hub = new Hub(s -> { hubCalls.incrementAndGet(); s.close(); }, good)) {
            long t0 = System.nanoTime();
            Transfer.dialOnce("127.0.0.1", s -> {
                try {
                    s.sendFile(msg(Transfer.OP_HELLO, "Dossier", 0), null);
                    Transfer.Msg m = s.recvHeader();
                    cliEnd.set("cadre recu op=" + m.op);
                } catch (IOException e) {
                    cliEnd.set("io: " + e);
                } finally { s.close(); }
            }, null, 3000);
            check(cliEnd.get() != null && cliEnd.get().startsWith("io:"), "client en clair vs hub chiffre: rejete (" + cliEnd.get() + ")");
            check(ms(t0) < 4000, "client en clair vs hub chiffre: sans blocage (" + ms(t0) + " ms)");
            Thread.sleep(200);
        }
        check(hubCalls.get() == 0, "client en clair vs hub chiffre: aucune session cote hub");
        check(captured().contains("le pair n'ouvre pas de session chiffree"), "client en clair vs hub chiffre: journalise cote hub");
    }

    private static void testSecureClientPlainHub(Crypto.Key good) throws Exception {
        final AtomicReference<String> hubEnd = new AtomicReference<>();
        final AtomicInteger cliCalls = new AtomicInteger();
        try (Hub hub = new Hub(s -> {
            try {
                Transfer.Msg m = s.recvHeader();
                hubEnd.set("cadre recu op=" + m.op);
            } catch (IOException e) {
                hubEnd.set("io: " + e.getMessage());
            } finally { s.close(); }
        }, null)) {
            long t0 = System.nanoTime();
            boolean ok = Transfer.dialOnce("127.0.0.1", s -> { cliCalls.incrementAndGet(); s.close(); }, good, 3000);
            check(!ok && cliCalls.get() == 0, "client chiffre vs hub en clair: dialOnce rend false");
            check(ms(t0) < Transfer.HANDSHAKE_TIMEOUT_MS + 2000, "client chiffre vs hub en clair: sans blocage (" + ms(t0) + " ms)");
            long t1 = System.nanoTime();
            while (hubEnd.get() == null && ms(t1) < 3000) Thread.sleep(20);
        }
        check(hubEnd.get() != null && hubEnd.get().startsWith("io:") && hubEnd.get().contains("session chiffree"),
                "client chiffre vs hub en clair: rejete cote hub avec un message clair (" + hubEnd.get() + ")");
    }

    // --- Debit -----------------------------------------------------------------

    /** Envoie un cadre de bytes octets client -> hub en boucle locale ; rend des octets/s. */
    private static double throughput(Crypto.Key key, final long bytes) throws Exception {
        final AtomicReference<Throwable> err = new AtomicReference<>();
        final double[] rate = new double[1];
        Transfer.SessionHandler sink = s -> {
            try {
                Transfer.Msg m = s.recvHeader();
                s.copyContentTo(m, null);
                s.sendFile(msg(Transfer.OP_PING, "fin", 0), null);
                s.recvHeader(); // attend la fermeture par le client
            } catch (IOException end) {
                // fin normale
            } catch (Throwable t) {
                err.set(t);
            } finally { s.close(); }
        };
        try (Hub hub = new Hub(sink, key)) {
            boolean ok = Transfer.dialOnce("127.0.0.1", s -> {
                try {
                    final byte[] block = new byte[1 << 20];
                    new Random(7).nextBytes(block);
                    InputStream src = new InputStream() {
                        long pos;
                        @Override public int read() { return block[(int) (pos++ & (block.length - 1))] & 0xFF; }
                        @Override public int read(byte[] b, int off, int len) {
                            int done = 0;
                            while (done < len) {
                                int o = (int) (pos & (block.length - 1));
                                int n = Math.min(len - done, block.length - o);
                                System.arraycopy(block, o, b, off + done, n);
                                done += n; pos += n;
                            }
                            return len;
                        }
                    };
                    long t0 = System.nanoTime();
                    s.sendFile(msg(Transfer.OP_FILE, "gros.bin", bytes), src);
                    Transfer.Msg fin = s.recvHeader(); // le hub a tout recu
                    rate[0] = bytes / ((System.nanoTime() - t0) / 1e9);
                    check(fin.op == Transfer.OP_PING, "debit: accuse de fin recu");
                } catch (Throwable t) {
                    err.set(t);
                } finally { s.close(); }
            }, key, 3000);
            check(ok, "debit: session etablie");
        }
        if (err.get() != null) throw new AssertionError("debit", err.get());
        return rate[0];
    }

    // --- Outils ----------------------------------------------------------------

    /** Attend que plus rien n'ecoute sur 127.0.0.1:PORT (un autre test peut l'occuper brievement). */
    private static void waitPortFree() throws Exception {
        long t0 = System.nanoTime();
        while (true) {
            try (Socket probe = new Socket()) {
                probe.connect(new InetSocketAddress("127.0.0.1", Transfer.PORT), 500);
            } catch (IOException free) {
                return;
            }
            if (ms(t0) > 180_000) throw new AssertionError("port " + Transfer.PORT + " toujours occupe");
            Thread.sleep(1000);
        }
    }

    private interface IoCall { Object run() throws IOException; }

    private static void expectIo(IoCall c, String what) {
        try {
            c.run();
        } catch (IOException expected) {
            check(true, what);
            return;
        }
        check(false, what);
    }

    private static void check(boolean cond, String what) {
        CHECKS.incrementAndGet();
        if (!cond) throw new AssertionError("verification echouee: " + what);
    }

    private static long ms(long t0) { return (System.nanoTime() - t0) / 1_000_000L; }

    private static String captured() {
        System.out.flush();
        synchronized (CAPTURE) { return new String(CAPTURE.toByteArray()); }
    }

    private static int count(String hay, String needle) {
        int n = 0;
        for (int i = hay.indexOf(needle); i >= 0; i = hay.indexOf(needle, i + 1)) n++;
        return n;
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }
}
