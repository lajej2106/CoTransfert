package cotransfert;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * BRIQUE 3 - Transfert haut debit sur le lien direct (~20 Gbps, mesure 4,3 Gbps
 * en Java 8 mono-flux, l'i5 du hub etant le facteur limitant).
 *
 * Topologie imposee par le pare-feu (POC) : le CLIENT compose la connexion, le
 * HUB ecoute. UNE seule connexion TCP persistante, full-duplex : les fichiers
 * transitent dans les deux sens dessus (push hub->client valide).
 *
 * Optimisations :
 *   - SO_SNDBUF / SO_RCVBUF = 8 Mio (fenetre TCP large). Cote serveur, regle
 *     AVANT bind pour que les sockets acceptees en heritent (window scaling).
 *   - Blocs applicatifs de 4 Mio -> peu de syscalls, pas de petits paquets.
 *   - TCP_NODELAY : les entetes de controle partent sans delai.
 *   - Pipelining : entete + contenu ecrits a la suite, sans aller-retour par
 *     fichier -> essentiel pour les milliers de petits fichiers d'un repo.
 *
 * Hors lien direct (autre lien IP : LAN, Wi-Fi), la session est chiffree et
 * authentifiee par un secret partage : poignee de main puis enregistrements
 * AES-256-GCM (voir {@link Crypto}). Sans cle, le format de fil est inchange.
 */
public final class Transfer {

    public static final int PORT = 47777;
    static final int SOCK_BUF = 8 << 20;
    static final int COPY_BUF = 4 << 20;

    public static final int OP_FILE = 1;     // contenu de fichier (size octets suivent)
    public static final int OP_DELETE = 2;    // suppression (pas de contenu)
    public static final int OP_PING = 3;      // keep-alive / controle
    public static final int OP_MANIFEST = 4;  // manifeste initial (payload TSV en contenu)
    public static final int OP_HELLO = 5;     // 1er cadre : nom du dossier a synchroniser (relPath)
    public static final int OP_STATUS = 6;    // remontee d'etat vers le pair (payload TSV en contenu)
    // 7 et 8 : OP_RESOLVED et OP_RESOLVED_ACK (voir SyncEngine)
    public static final int OP_KM = 9;        // 1er cadre d'une connexion dediee au partage clavier-souris (voir KmLink)

    private Transfer() { }

    static void tune(Socket s) throws IOException {
        s.setTcpNoDelay(true);
        s.setSendBufferSize(SOCK_BUF);
        s.setReceiveBufferSize(SOCK_BUF);
        s.setKeepAlive(true);
    }

    public interface SessionHandler { void handle(Session s); }

    /** Delai maximal de chaque lecture de la poignee de main chiffree. */
    static final int HANDSHAKE_TIMEOUT_MS = 5000;

    private static final String FIX_SECRET =
            "definir le meme secret= dans cotransfert.conf sur les deux postes";

    // --- Cote HUB : ecoute et accepte la connexion persistante du client -----

    public static void serveHub(SessionHandler handler) throws IOException {
        serveHub(handler, null);
    }

    /**
     * key null = sessions en clair (lien direct uniquement : un pair hors boucle
     * locale / lien local est refuse). key non null = toute session doit reussir
     * la poignee de main chiffree.
     */
    public static void serveHub(SessionHandler handler, Crypto.Key key) throws IOException {
        try (ServerSocket srv = bindHub()) {
            acceptLoop(srv, handler, key);
        }
    }

    static ServerSocket bindHub() throws IOException {
        ServerSocket srv = new ServerSocket();
        try {
            srv.setReceiveBufferSize(SOCK_BUF); // avant bind : impacte les sockets acceptees
            srv.setReuseAddress(true);
            srv.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), PORT)); // IPv4 explicite
        } catch (IOException e) {
            try { srv.close(); } catch (IOException ignore) { }
            throw e;
        }
        return srv;
    }

    /** Boucle d'acceptation ; se termine par une IOException quand srv est ferme. */
    static void acceptLoop(ServerSocket srv, final SessionHandler handler, final Crypto.Key key) throws IOException {
        Agent.log("hub: ecoute TCP sur :%d%s (1er bind -> autoriser l'alerte pare-feu)", PORT,
                key != null ? ", sessions chiffrees" : "");
        while (true) {
            final Socket c = srv.accept();
            tune(c);
            Agent.log("hub: connexion entrante depuis %s", c.getRemoteSocketAddress());
            Thread t = new Thread(new Runnable() {
                public void run() { // la poignee de main ne bloque pas l'acceptation des autres connexions
                    TcpSession s = establish(c, key, true);
                    if (s != null) handler.handle(s);
                }
            }, "cotp-session");
            t.setDaemon(true);
            t.start();
        }
    }

    /**
     * Ouvre la session sur une socket connectee : en clair sans cle, sinon apres
     * poignee de main chiffree. Echec -> journalise, ferme la socket, rend null.
     */
    private static TcpSession establish(Socket c, Crypto.Key key, boolean hub) {
        String me = hub ? "hub" : "client";
        String peer = String.valueOf(c.getRemoteSocketAddress());
        try {
            if (key == null) {
                if (Crypto.requiresSecret(c.getInetAddress())) {
                    Agent.log("%s: session en clair refusee avec %s (hors lien direct, chiffrement obligatoire) -> %s",
                            me, peer, FIX_SECRET);
                    c.close();
                    return null;
                }
                return new TcpSession(c);
            }
            c.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            Crypto.Channel ch = Crypto.handshake(key, hub, c.getInputStream(), c.getOutputStream());
            c.setSoTimeout(0);
            return new TcpSession(c, ch.in, ch.out);
        } catch (IOException e) {
            Agent.log("%s: session chiffree avec %s refusee : %s -> %s", me, peer, e.getMessage(), FIX_SECRET);
            try { c.close(); } catch (IOException ignore) { }
            return null;
        }
    }

    // --- Cote CLIENT : compose vers le hub et maintient la connexion ---------

    public static void dialHub(String hubIp, SessionHandler handler) {
        dialHub(hubIp, handler, null);
    }

    /** host = IP ou nom d'hote. Boucle sans fin : reconnexion apres chaque fin de session. */
    public static void dialHub(String host, SessionHandler handler, Crypto.Key key) {
        while (true) {
            int r = dial(host, handler, key, 5000);
            if (r == DIAL_OK) Agent.log("client: session fermee, reconnexion...");
            else if (r == DIAL_FAILED) Agent.log("client: nouvel essai dans 2s");
            sleep(r == DIAL_REFUSED ? 30000 : 2000); // refus de politique : inutile d'insister
        }
    }

    /**
     * Une seule tentative : false si la connexion (ou la poignee de main chiffree)
     * n'a pas pu etre etablie, true quand une session a ete etablie puis s'est terminee.
     */
    public static boolean dialOnce(String host, SessionHandler handler, Crypto.Key key, int connectTimeoutMs) {
        return dial(host, handler, key, connectTimeoutMs) == DIAL_OK;
    }

    private static final int DIAL_OK = 0, DIAL_FAILED = 1, DIAL_REFUSED = 2;

    private static int dial(String host, SessionHandler handler, Crypto.Key key, int connectTimeoutMs) {
        InetSocketAddress addr = new InetSocketAddress(host, PORT); // IP ou nom, resolu a chaque tentative
        if (addr.isUnresolved()) {
            Agent.log("client: nom d'hote introuvable: %s", host);
            return DIAL_FAILED;
        }
        if (key == null && Crypto.requiresSecret(addr.getAddress())) {
            Agent.log("client: %s n'est pas sur un lien direct, chiffrement obligatoire -> %s", host, FIX_SECRET);
            return DIAL_REFUSED;
        }
        Socket c = new Socket();
        try {
            c.connect(addr, connectTimeoutMs);
            tune(c);
        } catch (IOException e) {
            try { c.close(); } catch (IOException ignore) { }
            Agent.log("client: connexion a %s echouee (%s)", host, e.getMessage());
            return DIAL_FAILED;
        }
        TcpSession s = establish(c, key, false);
        if (s == null) return DIAL_FAILED;
        Agent.log("client: connecte au hub %s%s", host, key != null ? " (session chiffree)" : "");
        handler.handle(s); // bloque tant que la session vit
        return DIAL_OK;
    }

    // ------------------------------------------------------------------------
    // Session : protocole trame full-duplex, independant du support.
    // ------------------------------------------------------------------------

    /** Version du protocole de cadres, annoncee dans le champ hash du HELLO. */
    public static final long PROTO_VERSION = 2;

    /** Msg.base : l'emetteur ne sait pas ce que le pair possede (pas de controle). */
    public static final long BASE_UNKNOWN = Long.MIN_VALUE;
    /** Msg.base : l'emetteur pense que le pair N'A PAS ce fichier. */
    public static final long BASE_ABSENT = -1L;

    public static final class Msg {
        public int op;
        public String relPath;
        public long size;
        public long modTime; // epoch millis
        public long hash;    // signature contenu (anti-boucle)
        /**
         * Hash de la version que l'emetteur croit presente chez le pair au moment
         * de l'envoi (BASE_ABSENT / BASE_UNKNOWN sinon). Permet au recepteur de
         * detecter un croisement : si son fichier local n'est pas cette version,
         * l'emetteur a ecrase "a l'aveugle" -> conflit. Indispensable des que le
         * transport a de la latence (modes degrades).
         */
        public long base = BASE_UNKNOWN;
    }

    /**
     * Transport de cadres, independant du support. Deux familles :
     *  - flux interactif (TCP : {@link TcpSession}) ;
     *  - boite aux lettres asynchrone (lots de cadres deposes dans un dossier).
     * Contrat : un flux ORDONNE et FIABLE de cadres dans chaque sens. recvHeader
     * bloque jusqu'au prochain cadre ; une IOException signifie la fin de session.
     */
    public interface Session extends AutoCloseable {
        /** Ecrit l'entete puis streame m.size octets depuis src. Thread-safe. */
        void sendFile(Msg m, InputStream src) throws IOException;
        /** Lit la prochaine entete (bloquant). Le contenu se lit via copyContentTo. */
        Msg recvHeader() throws IOException;
        /** Consomme exactement m.size octets vers dst (dst null = jeter). */
        void copyContentTo(Msg m, OutputStream dst) throws IOException;
        @Override void close();
        /** false = transport asynchrone a forte latence (boite aux lettres). */
        default boolean interactive() { return true; }
        /** Identifiant court du canal, affiche dans l'IHM : "direct", "relais", "valise". */
        default String channel() { return "direct"; }
    }

    // --- Serialisation des cadres (partagee par tous les transports) ---------
    // Trame : [op:1][pathLen:2][path][size:8][modtime:8][hash:8][base:8][contenu]

    /** Ecrit un cadre complet (entete + contenu) sur out. Ne flushe pas. */
    public static void writeFrame(DataOutputStream out, Msg m, InputStream src) throws IOException {
        byte[] path = m.relPath.getBytes("UTF-8");
        out.writeByte(m.op);
        out.writeShort(path.length);
        out.write(path);
        out.writeLong(m.size);
        out.writeLong(m.modTime);
        out.writeLong(m.hash);
        out.writeLong(m.base);
        if (m.size > 0) { // tout cadre a contenu (FILE, MANIFEST...) est streame
            byte[] buf = new byte[(int) Math.min(COPY_BUF, m.size)];
            long remaining = m.size;
            while (remaining > 0) {
                int want = (int) Math.min(buf.length, remaining);
                int n = src.read(buf, 0, want);
                if (n < 0) throw new IOException("source tronquee: " + m.relPath);
                out.write(buf, 0, n);
                remaining -= n;
            }
        }
    }

    /** Lit une entete de cadre sur in. */
    public static Msg readHeader(DataInputStream in) throws IOException {
        Msg m = new Msg();
        m.op = in.readUnsignedByte();
        int pathLen = in.readUnsignedShort();
        byte[] path = new byte[pathLen];
        in.readFully(path);
        m.relPath = new String(path, "UTF-8");
        m.size = in.readLong();
        m.modTime = in.readLong();
        m.hash = in.readLong();
        m.base = in.readLong();
        return m;
    }

    /** Consomme exactement m.size octets de in vers dst (dst null = jeter). */
    public static void copyContent(InputStream in, Msg m, OutputStream dst) throws IOException {
        byte[] buf = new byte[(int) Math.max(1, Math.min(COPY_BUF, m.size))];
        long remaining = m.size;
        while (remaining > 0) {
            int want = (int) Math.min(buf.length, remaining);
            int n = in.read(buf, 0, want);
            if (n < 0) throw new IOException("flux coupe pendant: " + m.relPath);
            if (dst != null) dst.write(buf, 0, n);
            remaining -= n;
        }
    }

    /** Transport nominal : une connexion TCP full-duplex, en clair ou chiffree. */
    public static final class TcpSession implements Session {
        private final Socket sock;
        private final DataOutputStream out;
        private final DataInputStream in;
        /** Session en clair : verifier que le pair n'entame pas une poignee de main chiffree. */
        private boolean checkPlain;

        /** Session en clair : format de fil inchange. */
        TcpSession(Socket s) {
            this.sock = s;
            try {
                this.out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), COPY_BUF));
                this.in = new DataInputStream(new BufferedInputStream(s.getInputStream(), COPY_BUF));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            this.checkPlain = true;
        }

        /** Session chiffree : cin/cout sont les flux d'enregistrements (deja tamponnes). */
        TcpSession(Socket s, InputStream cin, OutputStream cout) {
            this.sock = s;
            this.out = new DataOutputStream(cout);
            this.in = new DataInputStream(cin);
        }

        @Override public synchronized void sendFile(Msg m, InputStream src) throws IOException {
            try {
                writeFrame(out, m, src);
                out.flush();
            } catch (IOException e) {
                // Un cadre partiel est peut-etre deja parti (ex. fichier source tronque
                // pendant l'envoi) : le flux n'est plus parsable par le pair. On ferme,
                // la session se termine des deux cotes et la reconnexion re-synchronise.
                close();
                throw e;
            }
        }

        @Override public Msg recvHeader() throws IOException {
            if (checkPlain) { // aucun cadre en clair ne commence par "COTS" (op invalide)
                checkPlain = false;
                byte[] head = new byte[4];
                in.mark(4);
                in.readFully(head);
                in.reset();
                if (Crypto.isHandshakeMagic(head)) {
                    throw new IOException("le pair exige une session chiffree -> " + FIX_SECRET);
                }
            }
            return readHeader(in);
        }

        @Override public void copyContentTo(Msg m, OutputStream dst) throws IOException { copyContent(in, m, dst); }

        public DataInputStream rawIn() { return in; }

        /** Flux d'ecriture de la session : pour une connexion qui quitte le protocole de cadres (clavier-souris). */
        public DataOutputStream rawOut() { return out; }

        @Override public void close() {
            try { sock.close(); } catch (IOException ignore) { }
        }
    }

    // ------------------------------------------------------------------------
    // Benchmark : mesure le debit reel de la boucle.
    // ------------------------------------------------------------------------

    public static void benchServer() throws IOException {
        serveHub(new SessionHandler() {
            public void handle(Session s) {
                try {
                    byte[] buf = new byte[COPY_BUF];
                    long total = 0, t0 = System.nanoTime();
                    int n;
                    while ((n = ((TcpSession) s).rawIn().read(buf)) >= 0) total += n;
                    double dt = (System.nanoTime() - t0) / 1e9;
                    Agent.log("bench hub: recu %d octets en %.2fs = %.2f Gbps (%.0f MB/s)",
                            total, dt, total * 8.0 / dt / 1e9, total / dt / 1e6);
                } catch (IOException e) {
                    Agent.log("bench hub: %s", e.getMessage());
                } finally { s.close(); }
            }
        });
    }

    public static void benchClient(String hubIp, long bytes) throws IOException {
        Socket c = new Socket();
        c.connect(new InetSocketAddress(hubIp, PORT), 5000);
        tune(c);
        OutputStream out = c.getOutputStream();
        byte[] buf = new byte[COPY_BUF];
        long sent = 0, t0 = System.nanoTime();
        while (sent < bytes) {
            int n = (int) Math.min(buf.length, bytes - sent);
            out.write(buf, 0, n);
            sent += n;
        }
        out.flush();
        c.shutdownOutput();
        c.getInputStream().read(); // attend la fermeture du hub
        double dt = (System.nanoTime() - t0) / 1e9;
        c.close();
        Agent.log("bench client: envoye %d octets en %.2fs = %.2f Gbps (%.0f MB/s)",
                sent, dt, sent * 8.0 / dt / 1e9, sent / dt / 1e6);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
