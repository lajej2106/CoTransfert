package cotransfert;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32C;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;

/**
 * Transport "boite aux lettres" : la session echange ses cadres par des fichiers
 * deposes dans un dossier relais (partage reseau, dossier synchronise, support
 * amovible deplace d'un poste a l'autre) au lieu d'une connexion TCP.
 *
 * Vu du moteur c'est un flux ORDONNE et FIABLE de cadres dans chaque sens, comme
 * TCP mais avec une latence enorme. Le flux sortant (octets de
 * {@link Transfer#writeFrame}) est decoupe en lots numerotes NNNNNN.lot ; le
 * recepteur relit les lots dans l'ordre comme un seul flux continu.
 *
 * Sur le relais :
 *   <relais>/<nom>/hub-vers-client/NNNNNN.lot
 *   <relais>/<nom>/client-vers-hub/NNNNNN.lot
 *   <relais>/<nom>/hub.ack   <relais>/<nom>/client.ack
 * Tout y est ecrit en "nom.part" puis renomme. Le recepteur ne supprime jamais
 * les lots du pair : c'est l'emetteur qui purge ce que l'acquittement couvre.
 *
 * Dans le spool local (survit aux redemarrages et a un relais vide) :
 *   out/NNNNNN.lot    lots scelles, conserves jusqu'a l'acquittement du pair
 *   ouvert.flux       journal du lot en cours (cadres pas encore scelles)
 *   etat.properties   identifiant du flux sortant + prochain numero de lot
 *   lu.pos            position de lecture dans le flux du pair (fait foi)
 *
 * Format d'un lot :
 *   [magic:4][version:1][drapeaux:1][flux:8][seq:8][tailleBrute:8][tailleStockee:8]
 *   [charge utile telle que stockee][CRC-32C de l'entete + charge utile : 4]
 * Drapeaux : COMPRESSE (deflate), SCELLE (passe par le codec, apres compression),
 * DEBUT (ce lot commence a une frontiere de cadre ; tout cadre partiel qui le
 * precede est abandonne). Un lot n'est consomme qu'une fois sa taille et son CRC
 * verifies : une copie partielle est "pas encore la", jamais une erreur.
 *
 * "flux" identifie le flux sortant d'un spool. Il change quand le spool est
 * recree ou quand l'emetteur abandonne ses lots ({@link #discardOutbound}) : la
 * numerotation repart alors a 1, et le recepteur, en voyant un lot 000001 d'un
 * autre flux, se recale dessus au lieu d'attendre un numero qui ne viendra plus.
 *
 * Acquittement : un cadre est considere consomme quand le moteur demande le
 * suivant (recvHeader). Un arret brutal peut donc faire relire le dernier cadre,
 * jamais en perdre un.
 *
 * Le relais est vu a travers un {@link RelayStore} (dossier, WebDAV, API en
 * ligne, ou cascade qui choisit parmi eux). Le flux de lots est INDEPENDANT du
 * magasin : le spool local garde les lots scelles jusqu'a leur acquittement et
 * la position de lecture dans le flux du pair. Quand le magasin sous-jacent
 * change ({@link RelayStore#generation}), la session oublie seulement ce qu'elle
 * croyait y avoir depose : les lots non acquittes sont redeposes, l'acquittement
 * est reecrit, et la lecture reprend au prochain lot attendu. Invariants :
 *   - un lot (flux, numero) est immuable : le relire d'un autre magasin est sans effet ;
 *   - un acquittement du pair lu dans un magasin retrouve est peut-etre ancien,
 *     mais reste vrai pour le flux qu'il nomme (il ne fait que croitre) ;
 *   - rien n'est lu du pair tant que {@link RelayStore#inboundReady} est faux :
 *     un magasin retrouve peut contenir les lots d'un ancien flux, qu'il ne faut
 *     ni rejouer ni prendre pour une reinitialisation du flux.
 */
public final class MailboxSession implements Transfer.Session {

    /** Transformation optionnelle par lot (chiffrement). seq est le numero du lot. */
    public interface Codec {
        byte[] seal(long seq, byte[] plain) throws IOException;
        /** Doit lever une exception si le contenu a ete altere. */
        byte[] open(long seq, byte[] sealed) throws IOException;
    }

    public static final class Options {
        /** Un fichier de lot ne depasse jamais cette taille (limites FAT32 / cloud). */
        public long maxSegmentBytes = 64L << 20;
        /** Le lot en cours est scelle apres ce delai sans ecriture. */
        public long flushIdleMs = 1500;
        /** Periode de scrutation du dossier relais. */
        public long pollMs = 2000;
        /** true : recvHeader leve EOFException des qu'il n'y a plus de lot entrant complet. */
        public boolean oneShot = false;
        /** Valeur renvoyee par channel(). */
        public String channel = "relais";
        /** Chiffrement des lots (null = aucun). Surcout maximal admis : 140 octets par lot. */
        public Codec codec = null;
        /** Etat du canal pour l'IHM (francais, court). */
        public Consumer<String> info = null;
        /** Compression deflate des lots (ignoree lot par lot quand elle ne gagne rien). */
        public boolean compress = true;
    }

    // --- format ------------------------------------------------------------

    static final int F_COMPRESSED = 1, F_SEALED = 2, F_START = 4;
    private static final int MAGIC = 0x43544C54;      // "CTLT"
    private static final int VERSION = 1;
    static final int HEADER = 38, TRAILER = 4;
    private static final int BIND = 17;               // [drapeaux][flux][tailleBrute] lies au contenu scelle
    private static final int CODEC_MARGIN = 160;
    private static final int J_MAGIC = 0x43544A4E;    // journal : [magic][seq:8][drapeaux:1][1re frontiere:8]
    private static final int J_HEADER = 21;
    private static final int P_MAGIC = 0x43545053;    // position : [magic][flux][seq][offset][crc]
    private static final Pattern LOT = Pattern.compile("(\\d{6,18})\\.lot");
    private static final String ACK_TAG = "CTACK1";

    /** Position dans un flux : prochain cadre a l'offset off (octets bruts) du lot seq. */
    record Pos(long sid, long seq, long off) { }

    private static final class RawSeg {
        int flags; long sid, seq, rawLen; int storedLen; byte[] stored;
    }

    // --- configuration -----------------------------------------------------

    private final String name, side, peer, channel;
    private final RelayStore store;
    /** Session construite sur un chemin de dossier : canal et messages d'origine ("relais", "valise"). */
    private final boolean legacy;
    private final Path spoolDir, outDir;
    private final long fixedPollMs, flushIdleMs;
    private final int rawLimit;
    private final long maxSegment;
    private final boolean oneShot, compress;
    private final Codec codec;
    private final Consumer<String> info;

    // --- emission (sous outLock) -------------------------------------------

    private final ReentrantLock outLock = new ReentrantLock();
    private final ConcurrentSkipListSet<Long> sealed = new ConcurrentSkipListSet<>();
    private final FileChannel journal;
    private final Splitter splitter = new Splitter();
    private volatile long streamId;
    private long nextSeq;
    private volatile long openLen;
    private int openFlags;
    private long firstBoundary;      // offset de la 1re frontiere de cadre du journal, -1 = pas encore
    private long frameStart;         // debut du cadre en cours dans le journal, -1 = commence dans un lot precedent
    private volatile long lastWriteMs;
    private volatile boolean stopSend;

    // --- reception (sous recvMon) ------------------------------------------

    private final Object recvMon = new Object();
    private final SegIn segIn = new SegIn();
    private final DataInputStream din = new DataInputStream(segIn);
    private final RandomAccessFile posFile;
    private Pos pos;                 // point de reprise tant qu'aucun lot n'est charge
    private boolean adoptTried;
    private byte[] cur;
    private int curPos;
    private long curSeq, curSid;
    private boolean boundary = true;
    private long contentLeft;
    private volatile Pos committed;
    /** Thread en train de chercher le lot suivant dans le magasin (sous recvGate), interrompu a la fermeture. */
    private final Object recvGate = new Object();
    private Thread recvThread;

    // --- pompe -------------------------------------------------------------

    private final Object pumpLock = new Object();
    private final Object pumpMon = new Object();
    private boolean pumpWake;
    private final Thread pumpThread;
    private long ackedSeq;           // vrai quel que soit le magasin : jamais remis a zero par une bascule
    private final Set<Long> deposited = new HashSet<>();   // lots de ce flux deposes par cette session
    private int storeGen;            // generation du magasin a laquelle deposited / ackWritten se rapportent
    private Pos ackWritten;
    private long ackTime;
    private String lastInfo = "", lastError = "";
    private Boolean relayUp;
    private boolean versionWarned;
    private volatile boolean closed;
    private final AtomicBoolean closeOnce = new AtomicBoolean();

    /**
     * Session sur un dossier relais (chemin, ou "*:\Dossier" cherche sur chaque
     * lecteur). Canal, scrutation et taille des lots viennent des options.
     */
    public MailboxSession(String rootName, String side, Path spoolDir, String relaySpec, Options o) throws IOException {
        this(rootName, side, spoolDir, new DirRelayStore(relaySpec), o, true);
    }

    /**
     * Session sur un magasin quelconque. channel() rend l'identifiant courant du
     * magasin ; la scrutation suit {@link RelayStore#pollMs} et un lot ne depasse
     * ni l'option ni {@link RelayStore#maxLotBytes}. Le magasin appartient a
     * l'appelant : close() ne le ferme pas (il peut servir a la session suivante).
     */
    public MailboxSession(String rootName, String side, Path spoolDir, RelayStore store, Options o) throws IOException {
        this(rootName, side, spoolDir, store, o, false);
    }

    private MailboxSession(String rootName, String side, Path spoolDir, RelayStore store, Options o, boolean legacy)
            throws IOException {
        if (!"hub".equals(side) && !"client".equals(side))
            throw new IllegalArgumentException("side doit valoir hub ou client: " + side);
        if (store == null) throw new IllegalArgumentException("magasin absent");
        if (o == null) o = new Options();
        this.name = safeName(rootName);
        this.side = side;
        this.peer = other(side);
        this.store = store;
        this.legacy = legacy;
        this.channel = o.channel == null ? "relais" : o.channel;
        this.spoolDir = spoolDir.toAbsolutePath().normalize();
        this.outDir = this.spoolDir.resolve("out");
        this.fixedPollMs = Math.max(10, o.pollMs);
        this.flushIdleMs = Math.max(0, o.flushIdleMs);
        this.oneShot = o.oneShot;
        this.compress = o.compress;
        this.codec = o.codec;
        this.info = o.info;
        long cap = legacy ? 1L << 30 : Math.min(1L << 30, store.maxLotBytes());
        this.maxSegment = Math.max(512, Math.min(o.maxSegmentBytes, cap));
        this.storeGen = store.generation();
        this.rawLimit = (int) (maxSegment - HEADER - TRAILER - (codec != null ? CODEC_MARGIN : 0));

        Files.createDirectories(outDir);
        loadState();
        this.journal = FileChannel.open(this.spoolDir.resolve("ouvert.flux"),
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        RandomAccessFile pf = null;
        try {
            recoverJournal();
            openFlags = F_START;   // 1er lot de la session : repart d'une frontiere de cadre
            firstBoundary = 0;
            resetJournal();
            saveState();
            pf = new RandomAccessFile(this.spoolDir.resolve("lu.pos").toFile(), "rw");
            this.pos = loadPos(pf);
            this.committed = pos;
        } catch (IOException | RuntimeException e) {
            try { journal.close(); } catch (IOException ignore) { }
            if (pf != null) try { pf.close(); } catch (IOException ignore) { }
            throw e;
        }
        this.posFile = pf;
        this.lastWriteMs = System.currentTimeMillis();
        this.pumpThread = new Thread(this::pumpLoop, "cotp-mailbox-" + side + "-" + name);
        this.pumpThread.setDaemon(true);
        this.pumpThread.start();
    }

    // =======================================================================
    //  Transfer.Session
    // =======================================================================

    @Override public boolean interactive() { return false; }

    @Override public String channel() { return legacy ? channel : store.id(); }

    /** Nom du canal dans les messages : "relais" pour un dossier donne par chemin, sinon l'identifiant du magasin. */
    private String label() { return legacy ? "relais" : store.id(); }

    private boolean removable() { return legacy || "disque".equals(store.id()); }

    private long pollMs() { return legacy ? fixedPollMs : Math.max(10, store.pollMs()); }

    private String myPath() { return name + "/" + direction(side); }

    private String inPath() { return name + "/" + direction(peer); }

    @Override public void sendFile(Transfer.Msg m, InputStream src) throws IOException {
        outLock.lock();
        try {
            if (stopSend) throw new IOException("session fermee");
            // Un cadre qui ne tient plus dans le lot en cours part dans un lot neuf
            // (decoupe aux frontieres de cadre tant que c'est possible).
            long estimate = 64L + 3L * m.relPath.length() + Math.max(0, m.size);
            if (openLen > 0 && openLen + estimate > rawLimit) sealOpen(false);
            frameStart = openLen;
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(splitter, 8192));
            try {
                Transfer.writeFrame(out, m, src);
                out.flush();
            } catch (IOException | RuntimeException e) {
                rollbackFrame();
                throw e;
            }
            if (firstBoundary < 0) { firstBoundary = openLen; writeJournalHeader(); }
            lastWriteMs = System.currentTimeMillis();
            if (openLen >= rawLimit) sealOpen(false);
        } finally {
            outLock.unlock();
        }
    }

    @Override public Transfer.Msg recvHeader() throws IOException {
        synchronized (recvMon) {
            if (closed) throw new IOException("session fermee");
            // Contenu du cadre precedent non lu par l'appelant : on le saute.
            byte[] skip = contentLeft > 0 ? new byte[(int) Math.min(contentLeft, 1 << 16)] : null;
            while (contentLeft > 0) {
                contentLeft -= segIn.read(skip, 0, (int) Math.min(skip.length, contentLeft));
            }
            // Demander le cadre suivant = le precedent est applique : on acquitte.
            commit(point());
            boundary = true;
            Transfer.Msg m = Transfer.readHeader(din);
            contentLeft = Math.max(0, m.size);
            return m;
        }
    }

    @Override public void copyContentTo(Transfer.Msg m, OutputStream dst) throws IOException {
        synchronized (recvMon) {
            if (closed) throw new IOException("session fermee");
            Transfer.copyContent(segIn, m, dst);
            contentLeft = 0;
        }
    }

    /** Scelle le lot en cours, depose ce qui peut l'etre, publie l'acquittement. Idempotent. */
    @Override public void close() {
        if (!closeOnce.compareAndSet(false, true)) return;
        closed = true;
        stopSend = true;                       // interrompt un sendFile en cours (cadre annule)
        interruptReceiver();
        synchronized (recvMon) { recvMon.notifyAll(); }   // libere un recvHeader en attente
        outLock.lock();
        try {
            try { sealOpen(false); } catch (IOException | RuntimeException e) {
                Agent.log("%s: scellement final impossible (%s)", tag(), e.getMessage());
            }
        } finally {
            outLock.unlock();
        }
        stopPump();
        pumpOnce(true);
        outLock.lock();
        try { journal.close(); } catch (IOException ignore) { } finally { outLock.unlock(); }
        synchronized (recvMon) {
            try { posFile.close(); } catch (IOException ignore) { }
        }
    }

    /** Vrai quand rien n'est en tampon et que tout lot scelle a ete acquitte par le pair. */
    public boolean outboundDrained() {
        return openLen == 0 && sealed.isEmpty();
    }

    /**
     * Abandonne tout ce que le pair n'a pas acquitte : lots du spool, lot en
     * cours, et lots deja deposes sur le relais s'il est joignable (sinon ils
     * seront retires au prochain contact). Le flux sortant est reinitialise
     * (nouvel identifiant, numerotation a 1) : le recepteur s'y recale en voyant
     * le prochain lot 000001. Apres cet appel la session n'emet plus rien
     * (sendFile leve IOException) ; il reste a appeler close().
     */
    public void discardOutbound() {
        stopSend = true;
        outLock.lock();
        try {
            for (Long s : new ArrayList<>(sealed)) {
                try { Files.deleteIfExists(outDir.resolve(lotName(s))); } catch (IOException ignore) { }
            }
            sealed.clear();
            streamId = newStreamId();
            nextSeq = 1;
            openFlags = F_START;
            firstBoundary = 0;
            try {
                if (journal.isOpen()) resetJournal();
                saveState();
            } catch (IOException e) {
                Agent.log("%s: abandon des lots, etat non enregistre (%s)", tag(), e.getMessage());
            }
        } finally {
            outLock.unlock();
        }
        // Une passe de la pompe en attente d'un magasin muet garderait pumpLock pendant
        // tout le delai de sa requete : on l'interrompt (la pompe, elle, continue).
        if (Thread.currentThread() != pumpThread) pumpThread.interrupt();
        synchronized (pumpLock) {
            ackedSeq = 0;
            deposited.clear();
            try {
                if (store.available()) {
                    for (RelayStore.Entry e : store.list(myPath())) {
                        String n = e.name();
                        if (n.endsWith(".part") || lotSeq(n) > 0) store.delete(myPath() + "/" + n);
                    }
                }
            } catch (IOException | RuntimeException ignore) { }
        }
        Agent.log("%s: lots sortants non acquittes abandonnes", tag());
    }

    /** Tests : simule un arret brutal (rien n'est scelle ni depose). */
    void kill() {
        if (!closeOnce.compareAndSet(false, true)) return;
        closed = true;
        stopSend = true;
        interruptReceiver();
        synchronized (recvMon) { recvMon.notifyAll(); }
        stopPump();
        outLock.lock();
        try { journal.close(); } catch (IOException ignore) { } finally { outLock.unlock(); }
        synchronized (recvMon) {
            try { posFile.close(); } catch (IOException ignore) { }
        }
    }

    // =======================================================================
    //  Resolution du relais
    // =======================================================================

    /**
     * Dossier relais designe par spec, ou null s'il n'est pas joignable maintenant.
     * spec est un chemin de dossier, ou "*:\Dossier" : le dossier est alors
     * cherche a la racine de chaque lecteur (support amovible, quelle que soit sa lettre).
     */
    public static Path resolveRelay(String spec) {
        if (spec == null) return null;
        List<Path> roots = new ArrayList<>();
        if (spec.trim().startsWith("*:")) {
            File[] fs = File.listRoots();
            if (fs != null) for (File f : fs) roots.add(f.toPath());
        }
        return resolveRelay(spec, roots);
    }

    static Path resolveRelay(String spec, List<Path> roots) {
        if (spec == null) return null;
        try {
            String s = spec.trim();
            if (s.isEmpty()) return null;
            if (s.startsWith("*:")) {
                String rest = s.substring(2);
                while (rest.startsWith("\\") || rest.startsWith("/")) rest = rest.substring(1);
                if (rest.isEmpty()) return null;   // jamais la racine entiere d'un lecteur
                for (Path r : roots) {
                    Path c = r.resolve(rest);
                    if (Files.isDirectory(c)) return c;
                }
                return null;
            }
            Path p = Paths.get(s);
            return Files.isDirectory(p) ? p : null;
        } catch (InvalidPathException | SecurityException e) {
            return null;
        }
    }

    // =======================================================================
    //  Noms et fichiers d'acquittement (partages avec Valise)
    // =======================================================================

    static String other(String side) { return "hub".equals(side) ? "client" : "hub"; }

    /** Nom du sous-dossier des lots emis par from. */
    static String direction(String from) { return from + "-vers-" + other(from); }

    static String lotName(long seq) { return String.format("%06d.lot", seq); }

    /** Numero du lot d'apres son nom de fichier, -1 si ce n'est pas un lot. */
    static long lotSeq(String fileName) {
        Matcher m = LOT.matcher(fileName);
        return m.matches() ? Long.parseLong(m.group(1)) : -1;
    }

    /** Nom logique rendu utilisable comme nom de dossier (identique des deux cotes). */
    static String safeName(String rootName) {
        String s = rootName == null ? "" : rootName.trim().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        while (s.endsWith(".") || s.endsWith(" ")) s = s.substring(0, s.length() - 1) + "_";
        if (s.isEmpty() || s.equals("..")) s = "_";
        return s;
    }

    /** Lit un acquittement : {flux, seq, offset}, ou null s'il est absent ou incomplet. */
    static long[] readAck(Path f) {
        try {
            if (!Files.isRegularFile(f) || Files.size(f) > 256) return null;
            return parseAck(Files.readAllBytes(f));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static long[] parseAck(byte[] b) {
        try {
            if (b == null || b.length > 256) return null;
            String[] p = new String(b, StandardCharsets.US_ASCII).trim().split(" ");
            if (p.length != 5 || !ACK_TAG.equals(p[0])) return null;
            String body = p[0] + " " + p[1] + " " + p[2] + " " + p[3];
            if (!Long.toHexString(crcOf(body.getBytes(StandardCharsets.US_ASCII))).equals(p[4])) return null;
            return new long[] { Long.parseUnsignedLong(p[1], 16), Long.parseLong(p[2]), Long.parseLong(p[3]) };
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Acquittement lu dans le magasin ; null s'il est absent, incomplet ou illisible pour l'instant. */
    private long[] readAck(String path) {
        try {
            return parseAck(store.read(path, 257));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void writeAck(String path, Pos p) throws IOException {
        String body = ACK_TAG + " " + Long.toHexString(p.sid()) + " " + p.seq() + " " + p.off();
        String line = body + " " + Long.toHexString(crcOf(body.getBytes(StandardCharsets.US_ASCII))) + "\n";
        store.write(path, line.getBytes(StandardCharsets.US_ASCII));
    }

    static long crcOf(byte[] b) {
        CRC32C c = new CRC32C();
        c.update(b, 0, b.length);
        return c.getValue();
    }

    static void moveAtomic(Path tmp, Path dst) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                try {
                    Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (IOException e) {
                // Sous Windows le remplacement echoue tant que le pair (ou un antivirus)
                // lit la cible : on insiste un court instant avant de laisser la main.
                if (attempt >= 5 || !Files.exists(tmp)) throw e;
                try { Thread.sleep(20); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
            }
        }
    }

    private String tag() { return channel() + " " + side + "[" + name + "]"; }

    private static long newStreamId() {
        long id;
        do { id = new SecureRandom().nextLong(); } while (id == 0);
        return id;
    }

    // =======================================================================
    //  Emission : journal, scellement
    // =======================================================================

    /** Decoupe le flux de cadres en lots : scelle le journal quand il est plein. */
    private final class Splitter extends OutputStream {
        @Override public void write(int b) throws IOException { write(new byte[] { (byte) b }, 0, 1); }

        @Override public void write(byte[] b, int off, int len) throws IOException {
            while (len > 0) {
                if (stopSend) throw new IOException("session fermee");
                long room = rawLimit - openLen;
                if (room <= 0) { sealOpen(true); continue; }   // cadre plus gros qu'un lot : il s'etale
                int n = (int) Math.min(len, room);
                ByteBuffer bb = ByteBuffer.wrap(b, off, n);
                long at = J_HEADER + openLen;
                while (bb.hasRemaining()) at += journal.write(bb, at);
                openLen += n;
                off += n;
                len -= n;
            }
        }
    }

    /** Annule le cadre en cours d'ecriture (source tronquee, fermeture...). */
    private void rollbackFrame() {
        try {
            if (!journal.isOpen()) return;
            if (frameStart >= 0) {
                openLen = frameStart;
                journal.truncate(J_HEADER + openLen);
            } else {
                // Le debut du cadre est deja parti dans un lot scelle : le prochain
                // lot portera DEBUT, ce qui fait abandonner ce cadre au recepteur.
                openFlags = F_START;
                firstBoundary = 0;
                resetJournal();
                Agent.log("%s: cadre interrompu apres decoupe, le pair le rejettera", tag());
            }
        } catch (IOException e) {
            Agent.log("%s: annulation du cadre impossible (%s)", tag(), e.getMessage());
        }
    }

    /** Scelle le contenu du journal dans out/NNNNNN.lot. midFrame : un cadre est en cours. */
    private void sealOpen(boolean midFrame) throws IOException {
        if (openLen == 0 || !journal.isOpen()) return;
        int len = (int) openLen;
        ByteBuffer raw = ByteBuffer.allocate(len);
        if (!readFully(journal, raw, J_HEADER)) throw new IOException("journal du lot en cours illisible");
        writeSealed(nextSeq, openFlags, raw.array(), len);
        openFlags = 0;
        firstBoundary = midFrame ? -1 : 0;
        frameStart = midFrame ? -1 : 0;
        resetJournal();
        wakePump();
    }

    private void writeSealed(long seq, int flags, byte[] raw, int len) throws IOException {
        byte[] file;
        try {
            file = buildSegment(seq, flags, raw, len);
        } catch (IOException | RuntimeException e) {
            Agent.log("%s: ERREUR codec, lot %d non scelle (%s)", tag(), seq, e.getMessage());
            throw e instanceof IOException io ? io : new IOException(e);
        }
        if (file.length > maxSegment)
            Agent.log("%s: lot %d de %d octets, au-dela de la limite %d (surcout du codec trop grand)",
                    tag(), seq, file.length, maxSegment);
        Path dst = outDir.resolve(lotName(seq));
        Path tmp = outDir.resolve(lotName(seq) + ".part");
        Files.write(tmp, file);
        moveAtomic(tmp, dst);
        sealed.add(seq);
        nextSeq = seq + 1;
        saveState();
    }

    private byte[] buildSegment(long seq, int flags, byte[] raw, int len) throws IOException {
        byte[] stored = raw;
        int storedLen = len;
        if (compress) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(64, len / 2));
            Deflater d = new Deflater();
            try (DeflaterOutputStream dos = new DeflaterOutputStream(bos, d, 1 << 16)) {
                dos.write(raw, 0, len);
            } finally {
                d.end();
            }
            if (bos.size() < len) { stored = bos.toByteArray(); storedLen = stored.length; flags |= F_COMPRESSED; }
        }
        if (codec != null) {
            // drapeaux, flux et taille sont lies au contenu scelle : les modifier
            // dans l'entete en clair fait rejeter le lot a l'ouverture.
            ByteBuffer plain = ByteBuffer.allocate(BIND + storedLen);
            plain.put((byte) flags).putLong(streamId).putLong(len).put(stored, 0, storedLen);
            stored = codec.seal(seq, plain.array());
            if (stored == null) throw new IOException("le codec n'a rien rendu");
            storedLen = stored.length;
            flags |= F_SEALED;
        }
        ByteBuffer bb = ByteBuffer.allocate(HEADER + storedLen + TRAILER);
        bb.putInt(MAGIC).put((byte) VERSION).put((byte) flags).putLong(streamId).putLong(seq)
          .putLong(len).putLong(storedLen).put(stored, 0, storedLen);
        CRC32C c = new CRC32C();
        c.update(bb.array(), 0, HEADER + storedLen);
        bb.putInt((int) c.getValue());
        return bb.array();
    }

    private void resetJournal() throws IOException {
        journal.truncate(0);
        openLen = 0;
        writeJournalHeader();
    }

    private void writeJournalHeader() throws IOException {
        ByteBuffer h = ByteBuffer.allocate(J_HEADER);
        h.putInt(J_MAGIC).putLong(nextSeq).put((byte) openFlags).putLong(firstBoundary).flip();
        long at = 0;
        while (h.hasRemaining()) at += journal.write(h, at);
    }

    /** Apres un arret brutal : scelle les cadres complets restes dans le journal. */
    private void recoverJournal() throws IOException {
        long size = journal.size();
        if (size <= J_HEADER) return;
        ByteBuffer h = ByteBuffer.allocate(J_HEADER);
        if (!readFully(journal, h, 0)) return;
        if (h.getInt() != J_MAGIC) return;
        long jseq = h.getLong();
        int jflags = h.get() & 0xff;
        long first = h.getLong();
        long len = size - J_HEADER;
        // Journal deja scelle (jseq < nextSeq) ou cadre etale jamais termine : rien a recuperer.
        if (jseq != nextSeq || first < 0 || first > len || len > Integer.MAX_VALUE - 64) return;
        ByteBuffer raw = ByteBuffer.allocate((int) len);
        if (!readFully(journal, raw, J_HEADER)) return;
        int end = lastBoundary(raw.array(), (int) first);
        if (end > 0) {
            writeSealed(jseq, jflags & F_START, raw.array(), end);
            Agent.log("%s: %d octets recuperes du lot en cours apres un arret brutal (lot %d)", tag(), end, jseq);
        }
    }

    /** Fin du dernier cadre complet de raw, en partant de la frontiere from. */
    private static int lastBoundary(byte[] raw, int from) {
        int p = from;
        ByteArrayInputStream bin = new ByteArrayInputStream(raw, from, raw.length - from);
        DataInputStream in = new DataInputStream(bin);
        try {
            while (true) {
                Transfer.Msg m = Transfer.readHeader(in);
                if (m.size < 0 || m.size > bin.available()) break;
                long skipped = bin.skip(m.size);
                if (skipped != m.size) break;
                p = raw.length - bin.available();
            }
        } catch (IOException | RuntimeException endOfData) {
            // fin des donnees au milieu d'une entete : on s'arrete au dernier cadre complet
        }
        return p;
    }

    private void loadState() throws IOException {
        long sid = 0, next = 1;
        Path f = spoolDir.resolve("etat.properties");
        if (Files.isRegularFile(f)) {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(f)) {
                p.load(in);
                sid = Long.parseUnsignedLong(p.getProperty("flux", "0"), 16);
                next = Long.parseLong(p.getProperty("prochain", "1"));
            } catch (IOException | RuntimeException e) {
                Agent.log("%s: etat du spool illisible (%s), reconstruction", tag(), e.getMessage());
                sid = 0; next = 1;
            }
        }
        List<Path> stale = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(outDir)) {
            for (Path p : ds) {
                long s = lotSeq(p.getFileName().toString());
                if (s > 0) sealed.add(s); else stale.add(p);
            }
        }
        for (Path p : stale) Files.deleteIfExists(p);   // .part d'un scellement interrompu
        if (sid == 0 && !sealed.isEmpty()) sid = peekSid(outDir.resolve(lotName(sealed.first())));
        if (sid == 0) sid = newStreamId();
        if (!sealed.isEmpty()) next = Math.max(next, sealed.last() + 1);
        this.streamId = sid;
        this.nextSeq = Math.max(1, next);
    }

    private void saveState() throws IOException {
        Properties p = new Properties();
        p.setProperty("flux", Long.toHexString(streamId));
        p.setProperty("prochain", Long.toString(nextSeq));
        Path f = spoolDir.resolve("etat.properties");
        Path tmp = spoolDir.resolve("etat.properties.part");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            p.store(out, "CoTransfert - flux sortant de la boite aux lettres");
        }
        moveAtomic(tmp, f);
    }

    // =======================================================================
    //  Reception : lecture des lots du pair comme un flux continu
    // =======================================================================

    private final class SegIn extends InputStream {
        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            read(one, 0, 1);
            return one[0] & 0xff;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (closed) throw new IOException("session fermee");
            while (cur == null || curPos >= cur.length) nextSegment();
            int n = Math.min(len, cur.length - curPos);
            System.arraycopy(cur, curPos, b, off, n);
            curPos += n;
            boundary = false;
            return n;
        }
    }

    /** Point de lecture courant (toujours une frontiere de cadre quand on l'acquitte). */
    private Pos point() {
        if (cur == null) return pos;
        return curPos >= cur.length ? new Pos(curSid, curSeq + 1, 0) : new Pos(curSid, curSeq, curPos);
    }

    private void commit(Pos p) throws IOException {
        Pos old = committed;
        if (p.equals(old)) return;
        ByteBuffer bb = ByteBuffer.allocate(32);
        bb.putInt(P_MAGIC).putLong(p.sid()).putLong(p.seq()).putLong(p.off());
        CRC32C c = new CRC32C();
        c.update(bb.array(), 0, 28);
        bb.putInt((int) c.getValue());
        posFile.seek(0);
        posFile.write(bb.array());
        committed = p;
        if (old == null || old.seq() != p.seq() || old.sid() != p.sid()) wakePump();   // changement de lot : ack sans attendre
    }

    private static Pos loadPos(RandomAccessFile f) throws IOException {
        if (f.length() >= 32) {
            byte[] b = new byte[32];
            f.seek(0);
            f.readFully(b);
            ByteBuffer bb = ByteBuffer.wrap(b);
            CRC32C c = new CRC32C();
            c.update(b, 0, 28);
            if (bb.getInt() == P_MAGIC && bb.getInt(28) == (int) c.getValue()) {
                Pos p = new Pos(bb.getLong(), bb.getLong(), bb.getLong());
                if (p.seq() >= 1 && p.off() >= 0) return p;
            }
        }
        return new Pos(0, 1, 0);   // flux du pair encore inconnu
    }

    /**
     * Charge le lot suivant du pair ; bloque (scrutation) tant qu'il n'est pas
     * complet. Pendant ce temps le thread est connu de close(), qui l'interrompt :
     * une lecture en attente d'un magasin en ligne muet (le delai d'une lecture de
     * lot se compte en minutes) ne doit pas retenir la fermeture, donc le retour
     * au lien direct.
     */
    private void nextSegment() throws IOException {
        synchronized (recvGate) { recvThread = Thread.currentThread(); }
        try {
            loadSegment();
        } catch (IOException e) {
            if (closed) { Thread.interrupted(); throw new IOException("session fermee"); }
            throw e;
        } finally {
            synchronized (recvGate) { recvThread = null; }
        }
        // Fermee pendant la lecture : l'interruption ne doit pas deborder sur l'appelant.
        if (closed) { Thread.interrupted(); throw new IOException("session fermee"); }
    }

    /** Libere le thread en attente du lot suivant, s'il y en a un (et seulement tant qu'il y est). */
    private void interruptReceiver() {
        synchronized (recvGate) {
            if (recvThread != null && recvThread != Thread.currentThread()) recvThread.interrupt();
        }
    }

    private void loadSegment() throws IOException {
        long want, sid;
        int skip;
        if (cur == null) { want = pos.seq(); skip = (int) Math.min(pos.off(), Integer.MAX_VALUE); sid = pos.sid(); }
        else { want = curSeq + 1; skip = 0; sid = curSid; }
        while (true) {
            if (closed) throw new IOException("session fermee");
            // Rien n'est lu du pair tant que le magasin ne le permet pas (cascade : le
            // pair n'a pas encore adopte ce magasin, qui peut contenir de vieux lots).
            // gen date les lectures : si le magasin a change entre-temps, ce qui a
            // ete lu ne prouve rien et on recommence au tour suivant.
            if (store.available() && store.inboundReady()) {
                int gen = store.generation();
                String in = inPath();
                if (cur == null && sid == 0 && !adoptTried) {
                    // Spool neuf : on reprend la ou notre ancien acquittement s'etait arrete.
                    adoptTried = true;
                    long[] a = readAck(name + "/" + side + ".ack");
                    if (a != null && a[0] != 0 && a[1] >= 1 && a[2] >= 0 && steady(gen)) {
                        pos = new Pos(a[0], a[1], a[2]);
                        commit(pos);
                        want = pos.seq(); skip = (int) Math.min(pos.off(), Integer.MAX_VALUE); sid = pos.sid();
                        Agent.log("%s: position de lecture reprise depuis le relais (lot %d)", tag(), want);
                    }
                }
                RawSeg r = readVerified(in + "/" + lotName(want));
                if (r != null && r.seq == want && (sid == 0 || r.sid == sid) && steady(gen)) {
                    byte[] raw = decode(r);
                    if ((r.flags & F_START) != 0 && !boundary) {
                        // Le pair a redemarre alors qu'un cadre etait en cours : ce cadre
                        // est abandonne, la prochaine session reprend au debut de ce lot.
                        restartAt(new Pos(r.sid, want, 0));
                        throw new IOException("le pair a redemarre au milieu d'un cadre, reprise au lot " + want);
                    }
                    if (skip > raw.length) {
                        Agent.log("%s: position de reprise hors du lot %d, reprise au lot suivant", tag(), want);
                        skip = raw.length;
                    }
                    cur = raw; curPos = skip; curSeq = want; curSid = r.sid;
                    return;
                }
                // Le pair a-t-il reinitialise son flux (spool recree, lots abandonnes) ?
                // Alors sa numerotation est repartie a 1 sous un autre identifiant.
                if (sid != 0) {
                    String firstLot = in + "/" + lotName(1);
                    // want == 1 : le lot 1 vient d'etre lu en entier, inutile de le redemander.
                    long firstSid = want == 1 ? (r != null ? r.sid : 0) : peekSid(firstLot);
                    if (firstSid != 0 && firstSid != sid) {
                        RawSeg r1 = want == 1 ? r : readVerified(firstLot);
                        if (r1 != null && r1.seq == 1 && r1.sid != sid && steady(gen)) {
                            Agent.log("%s: le pair a reinitialise son flux, reprise a son lot 1", tag());
                            boolean mid = !boundary;
                            restartAt(new Pos(r1.sid, 1, 0));
                            if (mid) throw new IOException("le pair a abandonne le cadre en cours, reprise a son lot 1");
                            want = 1; skip = 0; sid = r1.sid;
                            continue;
                        }
                    }
                }
            }
            if (oneShot) throw new EOFException("plus de lot complet a lire");
            try {
                recvMon.wait(pollMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("attente interrompue");
            }
        }
    }

    private void restartAt(Pos p) throws IOException {
        cur = null;
        curPos = 0;
        pos = p;
        commit(p);
    }

    /** Vrai si les lectures faites depuis gen portent bien sur le magasin courant, toujours lisible. */
    private boolean steady(int gen) {
        return store.generation() == gen && store.inboundReady();
    }

    /** Lit un lot et verifie taille + CRC. null = absent ou pas encore complet. */
    private RawSeg readVerified(String path) {
        try {
            byte[] b = store.read(path, 0);
            if (b == null || b.length < HEADER + TRAILER) return null;
            ByteBuffer h = ByteBuffer.wrap(b);
            if (h.getInt() != MAGIC) return null;
            int ver = h.get() & 0xff;
            RawSeg r = new RawSeg();
            r.flags = h.get() & 0xff;
            r.sid = h.getLong();
            r.seq = h.getLong();
            r.rawLen = h.getLong();
            long storedLen = h.getLong();
            if (ver != VERSION) {
                if (!versionWarned) {
                    versionWarned = true;
                    Agent.log("%s: %s est d'une version de format inconnue (%d) : mettre les deux postes a jour",
                            tag(), path.substring(path.lastIndexOf('/') + 1), ver);
                }
                return null;
            }
            if (storedLen < 0 || r.rawLen < 0 || r.rawLen > Integer.MAX_VALUE - 64
                    || b.length != HEADER + storedLen + TRAILER) return null;
            CRC32C c = new CRC32C();
            c.update(b, 0, HEADER + (int) storedLen);
            if (h.getInt(HEADER + (int) storedLen) != (int) c.getValue()) return null;
            r.stored = Arrays.copyOfRange(b, HEADER, b.length);
            r.storedLen = (int) storedLen;
            return r;
        } catch (IOException | RuntimeException e) {
            return null;   // absent, verrouille, support retire en cours de lecture
        }
    }

    /** Identifiant de flux lu dans l'entete d'un lot du spool (sans verification), 0 si illisible. */
    private static long peekSid(Path f) {
        try (FileChannel ch = FileChannel.open(f, StandardOpenOption.READ)) {
            ByteBuffer h = ByteBuffer.allocate(HEADER);
            if (!readFully(ch, h, 0) || h.getInt() != MAGIC) return 0;
            return h.getLong(6);
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    /** Meme chose pour un lot du magasin : seul le debut du fichier est demande. */
    private long peekSid(String path) {
        try {
            byte[] b = store.read(path, HEADER);
            if (b == null || b.length < HEADER) return 0;
            ByteBuffer h = ByteBuffer.wrap(b);
            return h.getInt() != MAGIC ? 0 : h.getLong(6);
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    /** Remplit bb depuis ch a partir de at, puis le rembobine. false si le fichier est trop court. */
    private static boolean readFully(FileChannel ch, ByteBuffer bb, long at) throws IOException {
        while (bb.hasRemaining()) {
            int n = ch.read(bb, at);
            if (n < 0) return false;
            at += n;
        }
        bb.flip();
        return true;
    }

    /** Charge utile brute d'un lot verifie. Toute anomalie ici est une erreur dure. */
    private byte[] decode(RawSeg r) throws IOException {
        byte[] p = r.stored;
        int off = 0, len = r.storedLen;
        int flags = r.flags;
        if ((flags & F_SEALED) != 0) {
            if (codec == null) throw hard(r, "il est chiffre et aucune cle n'est configuree sur ce poste");
            byte[] plain;
            try {
                plain = codec.open(r.seq, Arrays.copyOf(p, len));
            } catch (IOException | RuntimeException e) {
                throw hard(r, "le codec le rejette (cle differente ou contenu altere) : " + e.getMessage());
            }
            if (plain == null || plain.length < BIND) throw hard(r, "contenu dechiffre trop court");
            ByteBuffer b = ByteBuffer.wrap(plain);
            if ((b.get() & 0xff) != (flags & ~F_SEALED) || b.getLong() != r.sid || b.getLong() != r.rawLen)
                throw hard(r, "son entete ne correspond pas au contenu scelle (lot altere)");
            p = plain; off = BIND; len = plain.length - BIND;
        } else if (codec != null) {
            throw hard(r, "il n'est pas chiffre alors qu'une cle est configuree sur ce poste");
        }
        int rawLen = (int) r.rawLen;
        if ((flags & F_COMPRESSED) == 0) {
            if (len != rawLen) throw hard(r, "taille incoherente");
            return off == 0 && p.length == rawLen ? p : Arrays.copyOfRange(p, off, off + rawLen);
        }
        Inflater inf = new Inflater();
        try {
            inf.setInput(p, off, len);
            byte[] raw = new byte[rawLen];
            int n = 0;
            while (n < rawLen) {
                int k = inf.inflate(raw, n, rawLen - n);
                if (k == 0 && (inf.finished() || inf.needsInput() || inf.needsDictionary())) break;
                n += k;
            }
            if (n != rawLen || (!inf.finished() && inf.inflate(new byte[1]) != 0) || !inf.finished())
                throw hard(r, "decompression incoherente");
            return raw;
        } catch (DataFormatException e) {
            throw hard(r, "decompression impossible (" + e.getMessage() + ")");
        } finally {
            inf.end();
        }
    }

    private IOException hard(RawSeg r, String why) {
        String msg = "lot " + lotName(r.seq) + " du pair inutilisable : " + why;
        Agent.log("%s: ERREUR %s", tag(), msg);
        // Cause en langage courant, pour l'IHM.
        if (why.startsWith("le codec le rejette")) fatal = "le secret partage n'est pas le meme sur les deux postes";
        else if (why.startsWith("il est chiffre")) fatal = "l'autre poste a un secret partage, pas celui-ci";
        else if (why.startsWith("il n'est pas chiffre")) fatal = "ce poste a un secret partage, pas l'autre";
        else fatal = "lot recu inutilisable (" + why + ")";
        return new IOException(msg);
    }

    private volatile String fatal = "";

    /** Cause, en langage courant, de la derniere erreur qui a arrete la lecture ; "" si aucune. */
    public String fatalError() { return fatal; }

    /**
     * Vrai si le plus ancien lot scelle d'un spool ne pourra pas etre ouvert par
     * le pair : il a ete scelle avec un autre secret (ou sans secret) que celui
     * d'aujourd'hui. Ces lots sont perdus pour le pair ; il faut les abandonner
     * ({@link #discardOutbound}) et laisser l'echange des manifestes resynchroniser.
     *
     * @param peerSide codec avec lequel le pair lit nos lots, ou null sans secret
     */
    public static boolean outboundStale(Path spoolDir, Codec peerSide) {
        Path out = spoolDir.toAbsolutePath().normalize().resolve("out");
        long first = Long.MAX_VALUE;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(out)) {
            for (Path p : ds) {
                long s = lotSeq(p.getFileName().toString());
                if (s > 0 && s < first) first = s;
            }
        } catch (IOException | RuntimeException e) {
            return false;   // pas de spool : rien a abandonner
        }
        if (first == Long.MAX_VALUE) return false;
        try {
            byte[] b = Files.readAllBytes(out.resolve(lotName(first)));
            if (b.length < HEADER + TRAILER) return false;
            ByteBuffer h = ByteBuffer.wrap(b);
            if (h.getInt() != MAGIC) return false;
            h.get();
            int flags = h.get() & 0xff;
            h.getLong();
            long seq = h.getLong();
            h.getLong();
            long stored = h.getLong();
            boolean isSealed = (flags & F_SEALED) != 0;
            if (isSealed != (peerSide != null)) return true;
            if (!isSealed) return false;
            if (stored < 0 || b.length != HEADER + stored + TRAILER) return false;
            try {
                peerSide.open(seq, Arrays.copyOfRange(b, HEADER, HEADER + (int) stored));
                return false;
            } catch (IOException | RuntimeException e) {
                return true;
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    // =======================================================================
    //  Pompe : scellement sur inactivite, depot sur le relais, acquittements
    // =======================================================================

    private void wakePump() {
        synchronized (pumpMon) { pumpWake = true; pumpMon.notifyAll(); }
    }

    private void stopPump() {
        wakePump();
        if (Thread.currentThread() == pumpThread) return;
        pumpThread.interrupt();
        boolean interrupted = false;
        try { pumpThread.join(10_000); } catch (InterruptedException e) { interrupted = true; }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private void pumpLoop() {
        long lastPump = 0;
        while (!closed) {
            long pollMs = pollMs();   // peut changer avec le magasin choisi par une cascade
            long tick = Math.max(10, Math.min(pollMs, Math.max(20, flushIdleMs)) / 2);
            boolean flushed = idleFlush();
            boolean wake;
            synchronized (pumpMon) { wake = pumpWake; pumpWake = false; }
            long now = System.currentTimeMillis();
            if (flushed || wake || now - lastPump >= pollMs) {
                pumpOnce(false);
                lastPump = System.currentTimeMillis();
            }
            synchronized (pumpMon) {
                if (!pumpWake && !closed) {
                    try { pumpMon.wait(tick); } catch (InterruptedException e) { if (closed) return; }   // sinon : passe interrompue par discardOutbound
                }
            }
        }
    }

    private boolean idleFlush() {
        if (openLen == 0 || System.currentTimeMillis() - lastWriteMs < flushIdleMs) return false;
        if (!outLock.tryLock()) return false;   // un envoi est en cours : pas inactif
        try {
            if (stopSend || openLen == 0) return false;
            sealOpen(false);
            return true;
        } catch (IOException | RuntimeException e) {
            report("scellement impossible (" + e.getMessage() + ")");
            return false;
        } finally {
            outLock.unlock();
        }
    }

    /**
     * Le magasin sous-jacent a-t-il change depuis la derniere passe ? Si oui, ce
     * que la session croyait y avoir depose ou ecrit ne vaut plus rien : tout est
     * reverifie (lots non acquittes redeposes, acquittement reecrit). ackedSeq est
     * garde : c'est un fait sur le pair, pas sur le magasin. A appeler sous pumpLock.
     */
    private void syncGeneration() {
        int g = store.generation();
        if (g == storeGen) return;
        storeGen = g;
        deposited.clear();
        ackWritten = null;
        relayUp = null;
    }

    /** Une passe : purge de ce qui est acquitte, depot des lots, ecriture de mon acquittement. */
    private void pumpOnce(boolean fin) {
        synchronized (pumpLock) {
            try {
                boolean up = store.available();   // une cascade peut changer de magasin ici
                syncGeneration();
                if (!up) {
                    relayState(false);
                    int n = sealed.size();
                    String err = removable() ? "" : store.lastError();
                    String s = removable() ? label() + " absent"
                            : label() + " indisponible" + (err == null || err.isEmpty() ? "" : " (" + err + ")");
                    say(n == 0 ? s : s + ", " + lots(n) + " en attente");
                    return;
                }
                relayState(true);
                String myDir = myPath();
                Set<String> top = new HashSet<>();
                for (RelayStore.Entry e : store.list(name)) top.add(e.name());

                // 1) acquittement du pair -> purge du spool
                long sid = streamId;
                long[] a = top.contains(peer + ".ack") ? readAck(name + "/" + peer + ".ack") : null;
                if (a != null && a[0] == sid && a[1] > ackedSeq) ackedSeq = a[1];
                for (Long s : new ArrayList<>(sealed.headSet(ackedSeq))) {
                    if (sid != streamId) break;   // flux reinitialise entre-temps
                    Files.deleteIfExists(outDir.resolve(lotName(s)));
                    sealed.remove(s);
                }

                // 2) menage de MON sens sur le relais : lots acquittes ou d'un ancien flux
                Set<Long> present = new HashSet<>();
                List<String> drop = new ArrayList<>();
                for (RelayStore.Entry e : store.list(myDir)) {
                    String n = e.name();
                    long s = lotSeq(n);
                    // Un lot de meme numero peut dater d'un flux abandonne : on ne se fie
                    // a la taille seule que pour les lots deposes par cette session.
                    if (s > 0 && sealed.contains(s) && e.size() == sizeOf(outDir.resolve(lotName(s)))
                            && (deposited.contains(s) || peekSid(myDir + "/" + n) == sid)) { present.add(s); deposited.add(s); }
                    else if (s > 0 || n.endsWith(".part")) drop.add(n);
                }
                for (String n : drop) store.delete(myDir + "/" + n);
                deposited.retainAll(present);

                // 3) depot des lots scelles absents du relais
                for (Long s : sealed) {
                    if (present.contains(s)) continue;
                    Path src = outDir.resolve(lotName(s));
                    if (!Files.isRegularFile(src)) continue;   // purge ou abandonne entre-temps
                    String dst = myDir + "/" + lotName(s);
                    store.write(dst, src);
                    if (!sealed.contains(s)) { store.delete(dst); continue; }   // abandonne pendant la copie
                    deposited.add(s);
                }

                // 4) mon acquittement (position consommee dans le flux du pair)
                Pos c = committed;
                boolean dirty = false;
                if (c != null && c.sid() != 0) {
                    boolean missing = !top.contains(side + ".ack");
                    long now = System.currentTimeMillis();
                    if (missing || !c.equals(ackWritten)) {
                        if (fin || missing || ackWritten == null || ackWritten.seq() != c.seq()
                                || ackWritten.sid() != c.sid() || now - ackTime >= Math.min(2000, pollMs())) {
                            writeAck(name + "/" + side + ".ack", c);
                            ackWritten = c;
                            ackTime = now;
                        } else {
                            dirty = true;
                        }
                    }
                }

                // 5) etat pour l'IHM
                int inbound = 0;
                boolean ready = store.inboundReady();
                if (c != null && ready) {
                    for (RelayStore.Entry e : store.list(inPath())) if (lotSeq(e.name()) >= c.seq()) inbound++;
                }
                int pending = sealed.size();
                String tail = removable() ? " - support retirable" : "";
                if (inbound > 0) say(lots(inbound) + " a lire");
                else if (openLen > 0 || dirty) say("ecriture en cours");
                else if (pending > 0) say(lots(pending) + " en attente du pair" + tail);
                else if (!ready) say("en attente du pair");
                else say("a jour" + tail);
                lastError = "";
            } catch (IOException | RuntimeException e) {
                // relais retire en cours de passe, fichier verrouille, disque plein... : on reessaiera
                report(label() + " inaccessible (" + e + ")");
                int n = sealed.size();
                say(n == 0 ? label() + " inaccessible" : label() + " inaccessible, " + lots(n) + " en attente");
            } finally {
                // Magasin change pendant la passe : elle a pu melanger deux magasins, on la refait.
                if (!closed && store.generation() != storeGen) wakePump();
            }
        }
    }

    /** Taille d'un fichier du spool, -1 s'il n'est pas lisible. */
    private static long sizeOf(Path f) {
        try { return Files.size(f); } catch (IOException e) { return -1; }
    }

    private static String lots(int n) { return n + (n > 1 ? " lots" : " lot"); }

    private void relayState(boolean up) {
        if (relayUp != null && relayUp == up) return;
        relayUp = up;
        Agent.log("%s: %s %s (%s)", tag(), label(), up ? "joignable" : removable() ? "absent" : "indisponible",
                store.describe());
    }

    private void report(String msg) {
        if (msg.equals(lastError)) return;
        lastError = msg;
        Agent.log("%s: %s", tag(), msg);
    }

    private void say(String s) {
        if (s.equals(lastInfo)) return;
        lastInfo = s;
        if (info != null) {
            try { info.accept(s); } catch (RuntimeException ignore) { }
        }
    }
}
