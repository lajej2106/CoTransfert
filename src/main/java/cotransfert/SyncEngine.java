package cotransfert;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BRIQUE 2 (cablage) + BRIQUE 4 : moteur de synchronisation full-duplex.
 *
 * Relie le Watcher (surveillance locale), l'Index (etat de reference) et la
 * Session de transfert (une seule connexion TCP, les deux sens dessus).
 *
 * Deroulement :
 *   - scan initial de l'arbre local -> manifeste ;
 *   - echange des manifestes, reconciliation (dernier modifie gagne, suppressions) ;
 *   - sync live : les evenements du watcher sont pousses au pair ;
 *   - anti-echo : un fichier recu puis ecrit ne doit pas re-declencher un envoi ;
 *   - conflits : divergence des deux cotes -> resolution selon la politique,
 *     jamais de perte silencieuse.
 *
 * CONFLITS EN ATTENTE (keep-both). Tant qu'un fichier a une copie
 * ".conflict-peer-" en attente ici, la decision appartient a l'utilisateur :
 * le fichier local n'est plus jamais ecrase par un envoi ordinaire du pair
 * (une version deja gardee en copie est ignoree, une version nouvelle devient
 * une copie de plus), il n'est pas repousse par la reconciliation, et une
 * suppression venue du pair est ignoree. La resolution ({@link #resolve}, voir
 * {@link Conflicts}) envoie au pair un cadre de marque OP_RESOLVED suivi du
 * fichier (ou de la suppression) : le pair sait que c'est un choix explicite
 * et l'applique si son fichier est bien la version que ce choix ecarte, puis
 * retire ses propres copies devenues sans objet et accuse reception
 * (OP_RESOLVED_ACK). Un pair d'une version anterieure ignore ces deux cadres
 * et traite le fichier comme un envoi ordinaire (base = sa version).
 *
 * Fils : le recepteur applique les cadres entrants, le watcher pousse les
 * changements locaux, la page demande les resolutions. Les decisions du
 * recepteur et les ecritures d'une resolution se font sous le verrou "gate" ;
 * un fil autre que le recepteur n'envoie jamais rien en tenant ce verrou.
 *
 * NOTE D'INTEGRATION : Transfer.sendFile ne streame le contenu que pour OP_FILE.
 * Le manifeste est donc transporte dans un cadre OP_FILE avec le chemin
 * sentinelle MANIFEST_PATH (impossible pour un vrai fichier car il contient un
 * octet nul), pour ne pas desynchroniser le flux. OP_MANIFEST reste disponible
 * si Transfer.sendFile est un jour etendu pour streamer les autres op.
 */
public final class SyncEngine {

    public enum ConflictPolicy { KEEP_BOTH, MASTER_WINS, NEWER_WINS }

    /**
     * Cadre de marque, sans contenu : "le prochain cadre de ce fichier est un choix
     * explicite de l'utilisateur". hash = empreinte du contenu garde (BASE_ABSENT =
     * fichier supprime), base = empreinte du contenu ecarte. Op inconnue des
     * versions anterieures, qui l'ignorent.
     */
    static final int OP_RESOLVED = 7;
    /** Accuse de reception d'un choix explicite : hash = empreinte du contenu garde. */
    static final int OP_RESOLVED_ACK = 8;

    /** Levee par {@link #resolve} quand ce moteur s'arrete : la resolution se fait alors hors moteur. */
    static final class Stopped extends Exception {
        private static final long serialVersionUID = 1L;
        Stopped() { super("moteur arrete", null, false, false); }
    }

    /** Chemin sentinelle du manifeste (ne peut pas etre un vrai chemin relatif). */
    private static final String MANIFEST_PATH = "\u0000MANIFEST";

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final long SUPPRESS_TTL_MS = 10_000;

    private final Path dir;
    private final boolean isMaster;
    private final ConflictPolicy policy;
    private final Index index;
    private final Path metaDir;   // <dir>/.cotransfert
    private final Path tmpDir;    // <dir>/.cotransfert/tmp
    private final Path conflictsLog;
    private final String localHost;
    private SyncIgnore ignore;

    private Transfer.Session session;
    private Watcher watcher;
    private volatile boolean running = true;
    private String rootName = "";
    /** Memoire des choix explicites (voir {@link Conflicts.Memo}). */
    private final Conflicts.Memo memo;
    /** Decisions du recepteur et ecritures d'une resolution ; aussi l'arret du moteur. */
    private final Object gate = new Object();
    private boolean stopped;   // sous gate
    private final Status status = Status.get();

    /** Etat local capture au demarrage (rel -> etat). */
    private final Map<String, Index.State> localManifest = new ConcurrentHashMap<>();

    /** Anti-echo : fichiers que NOUS venons d'ecrire (rel -> hash + horodatage). */
    private final Map<String, long[]> suppressUpsert = new ConcurrentHashMap<>();
    /** Anti-echo : fichiers que NOUS venons de supprimer (rel -> horodatage). */
    private final Map<String, Long> suppressDelete = new ConcurrentHashMap<>();

    public SyncEngine(Path dir, boolean isMaster, ConflictPolicy policy) {
        this.dir = dir.toAbsolutePath().normalize();
        this.isMaster = isMaster;
        this.policy = policy != null ? policy : ConflictPolicy.KEEP_BOTH;
        this.index = new Index(this.dir);
        this.metaDir = this.dir.resolve(".cotransfert");
        this.tmpDir = this.metaDir.resolve("tmp");
        this.conflictsLog = this.metaDir.resolve("conflicts.log");
        this.memo = new Conflicts.Memo(this.dir);
        String h;
        try { h = InetAddress.getLocalHost().getHostName(); } catch (Exception e) { h = "local"; }
        this.localHost = h;
    }

    // =======================================================================
    //  Point d'entree (bloquant jusqu'a la fin de session)
    // =======================================================================

    public void run(Transfer.Session s, String role, String rootName) {
        this.session = s;
        this.rootName = rootName;
        status.setPath(rootName, dir.toString());
        status.setChannel(rootName, s.channel());
        status.setState(rootName, Status.State.CONNECTING);
        try {
            Files.createDirectories(tmpDir);
            ignore = new SyncIgnore(dir); // cree .cotransfert/.syncignore si absent
            // Index et memoire des choix lus sous le verrou du dossier, puis ce moteur
            // devient celui par lequel passent les resolutions de conflit.
            Conflicts.attach(rootName, dir, this, () -> { index.load(); memo.load(); });
            Agent.log("%s: moteur de sync demarre sur %s (maitre=%b, conflits=%s)",
                    role, dir, isMaster, policy);

            // 1) Scan initial de l'arbre local
            scanLocal();
            Agent.log("%s: scan initial = %d fichiers", role, localManifest.size());

            // 1bis) Choix de conflit faits sans session (ou jamais partis), puis
            //       changements faits hors session, pousses AVANT notre manifeste
            flushResolutions();
            pushOfflineChanges();

            // 2) Thread recepteur
            Thread receiver = new Thread(this::receiverLoop, "cotp-receiver");
            receiver.setDaemon(true);
            receiver.start();

            // 3) Envoi de notre manifeste au pair
            sendManifest();

            // 4) Surveillance live
            watcher = new Watcher(dir, this::excluded, 250, this::onLocalEvent);
            watcher.start();
            Agent.log("%s: surveillance live active", role);
            status.recompute(rootName, true);

            // 5) Thread de remontee d'etat vers le pair (pour l'IHM web)
            Thread reporter = new Thread(this::statusLoop, "cotp-status");
            reporter.setDaemon(true);
            reporter.start();

            // run() bloque jusqu'a la fin du flux
            receiver.join();
        } catch (Exception e) {
            Agent.log("%s: moteur arrete (%s)", role, e.getMessage());
        } finally {
            running = false;
            status.setState(rootName, Status.State.OFFLINE);
            if (watcher != null) watcher.close();
            index.save();
            s.close();
            // Plus aucune resolution par ce moteur ; celles en cours ont fini leurs ecritures.
            synchronized (gate) {
                stopped = true;
                index.save();
                memo.save();
            }
            Conflicts.detach(rootName, this);
        }
    }

    /** Remonte periodiquement l'etat de ce dossier au pair (cadre OP_STATUS). */
    private void statusLoop() {
        // Transport asynchrone : chaque cadre finit dans un lot sur le support.
        // On n'emet donc l'etat que s'il a change, et au plus toutes les 10 s
        // (rappel toutes les 5 min), au lieu d'un battement continu.
        boolean slow = !session.interactive();
        String lastPayload = null;
        long lastSent = 0;
        long lastIndexSave = System.currentTimeMillis();
        while (running) {
            try {
                // L'index evolue a chaque fichier applique : on le persiste regulierement
                // pour qu'un arret brutal ne fasse pas passer ces fichiers pour des conflits.
                if (System.currentTimeMillis() - lastIndexSave > 15_000) {
                    index.saveIfDirty();
                    memo.save();
                    lastIndexSave = System.currentTimeMillis();
                }
                status.recompute(rootName, true);
                Status.Root r = status.root(rootName);
                String payload = String.join("\t", status.host(), r.state.name(),
                        String.valueOf(r.remaining.get()), String.valueOf(r.conflicts), safe(r.lastLine));
                if (slow) {
                    long age = System.currentTimeMillis() - lastSent;
                    boolean changed = !payload.equals(lastPayload);
                    if (!(changed && age >= 10_000) && age < 300_000) {
                        try { Thread.sleep(1500); } catch (InterruptedException e) { return; }
                        continue;
                    }
                    lastPayload = payload; lastSent = System.currentTimeMillis();
                }
                Transfer.Msg m = new Transfer.Msg();
                m.op = Transfer.OP_STATUS; m.relPath = rootName;
                byte[] b = payload.getBytes(StandardCharsets.UTF_8);
                m.size = b.length; m.modTime = 0; m.hash = 0;
                session.sendFile(m, new ByteArrayInputStream(b));
            } catch (Exception e) {
                return; // flux coupe : on arrete la remontee
            }
            try { Thread.sleep(1500); } catch (InterruptedException e) { return; }
        }
    }

    private static String safe(String s) { return s == null ? "" : s.replace('\t', ' ').replace('\n', ' '); }

    // =======================================================================
    //  Exclusions (partagees watcher + scan)
    // =======================================================================

    boolean excluded(String rel) {
        if (rel == null || rel.isEmpty()) return true;
        // Les copies de conflit sont propres au poste qui doit trancher : jamais synchronisees.
        if (SyncIgnore.conflictCopy(rel)) return true;
        // Le reste est pilote par .cotransfert/.syncignore (synchronise, 100% iso par defaut).
        return ignore != null && ignore.ignored(rel);
    }

    // =======================================================================
    //  Scan initial
    // =======================================================================

    private void scanLocal() {
        localManifest.clear();
        try {
            // walkFileTree + SKIP_SUBTREE : on NE descend PAS dans les dossiers exclus
            // (node_modules, .git, target...). Sans ca, le parcours enumere des
            // dizaines de milliers de fichiers inutiles (90 s observes sur un vrai repo).
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
                    String rel = rel(d);
                    if (rel.isEmpty()) return FileVisitResult.CONTINUE;
                    // On descend dans .cotransfert pour y trouver .syncignore (synchronise) ;
                    // ses autres fichiers (index.tsv, tmp/) seront filtres par visitFile.
                    if (rel.equals(SyncIgnore.META)) return FileVisitResult.CONTINUE;
                    if (excluded(rel)) return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path p, BasicFileAttributes a) {
                    if (!a.isRegularFile()) return FileVisitResult.CONTINUE;
                    String rel = rel(p);
                    if (excluded(rel)) return FileVisitResult.CONTINUE;
                    try {
                        long hash = Index.hashFile(p);
                        localManifest.put(rel, new Index.State(hash, a.lastModifiedTime().toMillis(), a.size()));
                    } catch (IOException e) {
                        Agent.log("scan: %s illisible (%s)", rel, e.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path p, IOException e) {
                    return FileVisitResult.CONTINUE; // fichier illisible : on ignore, pas de crash
                }
            });
        } catch (IOException e) {
            Agent.log("scan: parcours impossible (%s)", e.getMessage());
        }
    }

    // =======================================================================
    //  Changements hors session (avant l'echange des manifestes)
    // =======================================================================

    /**
     * Pousse ce qui a change pendant que l'agent etait arrete, en comparant le
     * disque a l'index (= dernier etat echange avec le pair).
     *
     *  - Suppressions (tous transports) : envoyees AVANT notre manifeste. Sinon le
     *    pair, voyant le fichier absent de notre manifeste, nous le repousse et la
     *    suppression est annulee.
     *  - Fichiers modifies / nouveaux (transport asynchrone seulement) : sans cela
     *    il faudrait attendre le manifeste du pair, soit un aller-retour complet
     *    du support (cle USB, valise) avant le premier octet utile.
     *
     * Garde-fous : rien si l'index est vide (toute premiere synchro : le manifeste
     * decide), et aucune suppression si le dossier local est vide alors que l'index
     * ne l'est pas (dossier demonte ou vide par erreur : on ne vide pas le pair).
     */
    private void pushOfflineChanges() throws IOException {
        if (index.isEmpty()) return;
        boolean async = !session.interactive();
        int files = 0, deletes = 0;

        if (localManifest.isEmpty()) {
            Agent.log("[ATTENTION] %s est vide alors que l'index connait des fichiers : suppressions NON propagees", dir);
        } else {
            java.util.List<String> gone = new java.util.ArrayList<>();
            for (Map.Entry<String, Index.State> e : index.entries()) {
                String rel = e.getKey();
                if (localManifest.containsKey(rel)) continue;
                if (excluded(rel)) { gone.add(rel); continue; }   // nouvellement ignore : on arrete de suivre, sans supprimer chez le pair
                if (Files.exists(dir.resolve(rel))) continue;     // present mais illisible au scan (verrouille) : pas une suppression
                sendDelete(rel, e.getValue().hash);
                gone.add(rel);
                deletes++;
            }
            for (String rel : gone) index.remove(rel);
        }

        if (async) {
            for (Map.Entry<String, Index.State> e : localManifest.entrySet()) {
                Index.State ref = index.get(e.getKey());
                if (ref != null && ref.hash == e.getValue().hash) continue;
                sendCounted(e.getKey(), null);                     // base = index (ou absent)
                files++;
            }
        }
        if (files > 0 || deletes > 0) {
            index.save();
            Agent.log("changements hors session: %d fichier(s), %d suppression(s) envoye(s)", files, deletes);
        }
    }

    // =======================================================================
    //  Manifeste : envoi / reception / reconciliation
    // =======================================================================

    private void sendManifest() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Index.State> e : localManifest.entrySet()) {
            Index.State s = e.getValue();
            sb.append(s.hash).append('\t').append(s.mtime).append('\t')
              .append(s.size).append('\t').append(e.getKey()).append('\n');
        }
        byte[] payload = sb.toString().getBytes(StandardCharsets.UTF_8);
        Transfer.Msg m = new Transfer.Msg();
        m.op = Transfer.OP_FILE;      // transporte via OP_FILE (cf. note d'integration)
        m.relPath = MANIFEST_PATH;
        m.size = payload.length;
        m.modTime = 0;
        m.hash = 0;
        session.sendFile(m, new ByteArrayInputStream(payload));
        Agent.log("manifeste envoye (%d entrees, %d octets)", localManifest.size(), payload.length);
    }

    private void handleManifest(byte[] payload) throws IOException {
        Map<String, Index.State> peer = new HashMap<>();
        String text = new String(payload, StandardCharsets.UTF_8);
        for (String line : text.split("\n")) {
            if (line.isEmpty()) continue;
            String[] f = line.split("\t", 4);
            if (f.length < 4) continue;
            peer.put(f[3], new Index.State(Long.parseLong(f[0]), Long.parseLong(f[1]), Long.parseLong(f[2])));
        }
        Agent.log("manifeste du pair recu (%d entrees), reconciliation...", peer.size());

        final boolean async = !session.interactive();

        // Q : fichiers que le PAIR va nous pousser (compteur "restants" cote reception)
        int incoming = 0;
        for (Map.Entry<String, Index.State> e : peer.entrySet()) {
            String rel = e.getKey();
            if (excluded(rel)) continue;
            Index.State mine = localManifest.get(rel);
            if (mine == null) { incoming++; continue; }
            if (mine.hash == e.getValue().hash) continue;
            if (e.getValue().mtime > mine.mtime) incoming++;
            else if (e.getValue().mtime == mine.mtime && e.getValue().hash > mine.hash) incoming++;
        }

        // a) nos fichiers a pousser (le pair ne les a pas, ou nous sommes plus recents)
        //    La base annoncee est l'etat EXACT du pair lu dans son manifeste.
        java.util.Map<String, Long> toPush = new java.util.LinkedHashMap<>();
        // Un fichier dont le conflit attend une decision ici n'est pas repousse par
        // la reconciliation : c'est la resolution qui l'enverra, avec la bonne base.
        final java.util.Set<String> undecided = Conflicts.pendingPaths(rootName);
        for (Map.Entry<String, Index.State> e : localManifest.entrySet()) {
            String rel = e.getKey();
            if (undecided.contains(rel)) continue;
            Index.State mine = e.getValue();
            Index.State their = peer.get(rel);
            boolean push;
            if (their == null) push = true;
            else if (their.hash == mine.hash) push = false;
            else if (mine.mtime > their.mtime) push = true;
            else if (mine.mtime == their.mtime) push = mine.hash > their.hash;
            else push = false;
            // Transport asynchrone : le flux est fiable et ordonne, donc ce que l'index
            // dit "deja envoye" arrivera ; le manifeste du pair date d'avant. Ne pas
            // renvoyer evite de doubler le volume sur le support. Seulement si le pair
            // a deja une version du fichier : s'il ne l'a pas du tout, soit notre envoi
            // est encore en route, soit il l'a perdu -> on renvoie (jamais de divergence
            // silencieuse, au pire un doublon).
            if (push && async && their != null) {
                Index.State ref = index.get(rel);
                if (ref != null && ref.hash == mine.hash) push = false;
            }
            // .syncignore : le modele cree d'office ne l'emporte jamais sur des regles
            // ecrites, meme s'il est plus recent (il vient d'etre cree) ; a l'inverse
            // nos regles partent toujours vers un pair qui n'a que le modele.
            if (rel.equals(SyncIgnore.IGNORE_REL) && their != null && their.hash != mine.hash) {
                long template = SyncIgnore.templateHash();
                if (mine.hash == template) push = false;
                else if (their.hash == template) push = true;
            }
            if (push) toPush.put(rel, their == null ? Transfer.BASE_ABSENT : their.hash);
        }
        // Compteur "fichiers restants" = a envoyer + a recevoir. En asynchrone le
        // manifeste du pair est ancien : son "a recevoir" resterait faux indefiniment.
        if (async) incoming = 0;
        status.setRemaining(rootName, toPush.size() + incoming);
        status.recompute(rootName, true);

        // b) suppressions hors-ligne : present dans l'index de reference, absent du disque,
        //    et le pair l'a encore dans l'etat de reference (il ne l'a pas modifie).
        java.util.Map<String, Long> toDelete = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Index.State> e : index.entries()) {
            String rel = e.getKey();
            if (localManifest.containsKey(rel)) continue;      // toujours present : pas une suppression
            if (excluded(rel)) { index.remove(rel); continue; } // nouvellement ignore : on arrete de le suivre SANS supprimer chez le pair
            Index.State their = peer.get(rel);
            if (their == null) continue;                       // le pair ne l'a plus non plus
            if (their.hash == e.getValue().hash) {
                toDelete.put(rel, their.hash);
            } else {
                Agent.log("[CONFLIT] %s supprime ici mais modifie chez le pair -> conserve", rel);
            }
        }

        // Les envois partent d'un autre thread : ce thread-ci est le SEUL lecteur du
        // flux. S'il envoyait lui-meme, deux pairs qui ont chacun beaucoup a pousser
        // rempliraient les tampons TCP l'un de l'autre sans plus jamais lire : blocage
        // definitif. Le recepteur continue donc de vider le flux pendant les envois.
        final int expected = incoming;
        final Thread previous = reconciler;
        Thread t = new Thread(() -> pushReconciled(previous, toPush, toDelete, expected), "cotp-reconcile");
        t.setDaemon(true);
        reconciler = t;
        t.start();
    }

    /** Thread des envois de la reconciliation (voir {@link #handleManifest}). */
    private volatile Thread reconciler;

    private void pushReconciled(Thread previous, Map<String, Long> toPush, Map<String, Long> toDelete, int incoming) {
        int pushed = 0, deleted = 0;
        try {
            if (previous != null) previous.join();   // une reconciliation a la fois, dans l'ordre
            for (Map.Entry<String, Long> p : toPush.entrySet()) {
                if (!running) return;
                if (sendLocalFile(p.getKey(), p.getValue())) pushed++;
                else status.decRemaining(rootName);            // disparu entre-temps : il n'est plus a envoyer
            }
            for (Map.Entry<String, Long> d : toDelete.entrySet()) {
                if (!running) return;
                String rel = d.getKey();
                // Reapparu, ou recu du pair entre-temps : ce n'est plus une suppression.
                if (localManifest.containsKey(rel) || Files.exists(dir.resolve(rel))) continue;
                sendDelete(rel, d.getValue());
                index.remove(rel);
                deleted++;
            }
            index.save();
            Agent.log("reconciliation: %d pousse(s), %d suppression(s)", pushed, deleted);
            status.log(rootName, "reconcilie: " + pushed + " envoye(s), " + incoming + " a recevoir");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            if (running) Agent.log("reconciliation interrompue apres %d envoi(s) (%s)", pushed, e.getMessage());
        } catch (Exception e) {
            Agent.log("reconciliation: erreur (%s)", e);
        }
    }

    // =======================================================================
    //  Envoi live (callback du Watcher)
    // =======================================================================

    private void onLocalEvent(Watcher.Event ev) {
        try {
            if (excluded(ev.rel)) return;
            purgeSuppress();
            if (ev.kind == Watcher.Kind.DELETE) {
                Long delTs = suppressDelete.remove(ev.rel);
                if (delTs != null) return;                     // c'est notre propre suppression
                Index.State known = index.get(ev.rel);
                if (known != null) {
                    sendDelete(ev.rel, known.hash);
                    index.remove(ev.rel);
                    localManifest.remove(ev.rel);
                    index.save();
                } else if (!Files.exists(ev.abs)) {
                    // Ce chemin n'est pas un fichier connu : c'est un dossier supprime d'un
                    // coup. Windows ne signale pas toujours chacun de ses fichiers ; on
                    // propage donc la suppression de tout ce que l'index connait dessous
                    // et qui a reellement disparu du disque.
                    String prefix = ev.rel + "/";
                    java.util.List<String> gone = new java.util.ArrayList<>();
                    for (Map.Entry<String, Index.State> e : index.entries()) {
                        String rel = e.getKey();
                        if (rel.startsWith(prefix) && !excluded(rel) && !Files.exists(dir.resolve(rel))) gone.add(rel);
                    }
                    for (String rel : gone) {
                        if (suppressDelete.remove(rel) != null) continue;   // notre propre suppression
                        Index.State st = index.get(rel);
                        if (st == null) continue;
                        sendDelete(rel, st.hash);
                        index.remove(rel);
                        localManifest.remove(rel);
                    }
                    if (!gone.isEmpty()) index.save();
                }
                return;
            }
            // UPSERT
            Path abs = ev.abs;
            if (!Files.isRegularFile(abs)) return;             // dossier ou disparu : ignore
            long hash = Index.hashFile(abs);
            long[] sup = suppressUpsert.get(ev.rel);
            if (sup != null && sup[0] == hash) {               // notre propre ecriture
                suppressUpsert.remove(ev.rel);
                return;
            }
            Index.State ref = index.get(ev.rel);
            if (ref != null && ref.hash == hash) return;       // aucun vrai changement
            sendCounted(ev.rel, null);
        } catch (IOException e) {
            Agent.log("envoi live %s: %s", ev.rel, e.getMessage());
        }
    }

    /**
     * Envoi d'un fichier qui n'etait pas compte dans "reste a faire" : on le compte,
     * et on le decompte quoi qu'il arrive. Un fichier temporaire disparu avant ou
     * pendant l'envoi ne doit pas laisser le dossier "en synchronisation" pour toujours.
     * @param base hash attendu chez le pair, ou null pour prendre celui de notre index.
     */
    private void sendCounted(String rel, Long base) throws IOException {
        status.addRemaining(rootName, 1);                      // compense le dec de sendLocalFile
        boolean sent = false;
        try {
            if (base == null) {
                Index.State ref = index.get(rel);
                base = ref != null ? ref.hash : Transfer.BASE_ABSENT;
            }
            sent = sendLocalFile(rel, base);
        } finally {
            if (!sent) status.decRemaining(rootName);
        }
    }

    /**
     * @param base hash de la version que le pair est cense avoir (cf. Transfer.Msg.base).
     * @return vrai si le fichier a ete envoye (et decompte), faux s'il n'existe plus.
     */
    private boolean sendLocalFile(String rel, long base) throws IOException {
        Path abs = dir.resolve(rel);
        if (!Files.isRegularFile(abs)) return false;
        long size = Files.size(abs);
        long mtime = Files.getLastModifiedTime(abs).toMillis();
        long hash = Index.hashFile(abs);
        Transfer.Msg m = new Transfer.Msg();
        m.op = Transfer.OP_FILE;
        m.relPath = rel;
        m.size = size;
        m.modTime = mtime;
        m.hash = hash;
        m.base = base;
        // Ce contenu est celui d'un choix explicite pas encore acquitte : le cadre de
        // marque le precede. Tout autre contenu : le choix est depasse, on l'oublie.
        Conflicts.Memo.Mine own = memo.mine(rel);
        boolean choice = own != null && own.kept == hash;
        if (own != null && !choice) memo.dropMine(rel);
        if (choice) sendMark(OP_RESOLVED, rel, hash, own.discarded);
        try (InputStream in = Files.newInputStream(abs)) {
            session.sendFile(m, in);
        }
        if (choice) memo.sent(rel);
        Index.State st = new Index.State(hash, mtime, size);
        index.put(rel, st);
        localManifest.put(rel, st);
        status.decRemaining(rootName);   // un fichier envoye de moins
        return true;
    }

    /** Cadre sans contenu OP_RESOLVED ou OP_RESOLVED_ACK. */
    private void sendMark(int op, String rel, long hash, long base) throws IOException {
        Transfer.Msg m = new Transfer.Msg();
        m.op = op;
        m.relPath = rel;
        m.size = 0;
        m.modTime = 0;
        m.hash = hash;
        m.base = base;
        session.sendFile(m, EMPTY);
    }

    // =======================================================================
    //  Resolution d'un conflit (ecran "Conflits")
    // =======================================================================

    /**
     * Applique le choix de l'utilisateur pour ces copies (un fichier au plus une
     * fois), puis l'envoie au pair. Appele par un fil de la page. Les ecritures
     * (disque, index, memoire des choix) se font sous le verrou du recepteur :
     * aucun cadre entrant n'est applique au meme moment sur ces fichiers. L'envoi
     * se fait hors verrou ; s'il echoue (session coupee), le choix reste note "non
     * envoye" et part au debut de la prochaine session.
     *
     * @return copie -> raison, pour chaque echec
     * @throws Stopped si ce moteur s'arrete (rien n'a ete fait)
     */
    Map<String, String> resolve(java.util.List<Conflicts.Item> items, boolean mine) throws Stopped {
        Map<String, String> ko;
        synchronized (gate) {
            if (stopped) throw new Stopped();
            // Le remplacement de mon fichier par la copie est NOTRE ecriture : pas d'echo par le watcher.
            ko = Conflicts.choose(dir, index, memo, items, mine,
                    (rel, hash) -> suppressUpsert.put(rel, new long[]{ hash, System.currentTimeMillis() }));
        }
        Conflicts.changed(rootName);
        int done = 0;
        boolean cut = false;
        for (Conflicts.Item it : items) {
            if (ko.containsKey(it.copy())) continue;
            done++;
            Agent.log("[CONFLIT] %s : resolu, %s (copie %s)", it.path(),
                    mine ? "ma version est gardee" : "la version de l'autre poste est gardee", it.copy());
            if (cut) continue;
            try {
                announce(it.path());
            } catch (IOException e) {
                cut = true;   // session coupee : le reste partira a la prochaine session
                Agent.log("conflits: envoi du choix differe a la prochaine session (%s)", e.getMessage());
            }
        }
        if (done > 0) status.log(rootName, done + " conflit(s) resolu(s)");
        memo.save();
        return ko;
    }

    /** Envoie au pair le choix note pour ce fichier : marque + fichier, ou marque + suppression. */
    private void announce(String rel) throws IOException {
        Conflicts.Memo.Mine own = memo.mine(rel);
        if (own == null) return;
        Path abs = dir.resolve(rel);
        if (own.kept == Transfer.BASE_ABSENT) {
            // "Ma version" alors que mon fichier n'existe plus : la suppression est le choix.
            if (Files.exists(abs)) { memo.dropMine(rel); return; }
            sendMark(OP_RESOLVED, rel, Transfer.BASE_ABSENT, own.discarded);
            sendDelete(rel, own.base);
            memo.sent(rel);
            index.remove(rel);
            localManifest.remove(rel);
            return;
        }
        if (!Files.isRegularFile(abs)) { memo.dropMine(rel); return; }
        sendCounted(rel, own.base);                            // marque + fichier si le contenu est bien celui du choix
    }

    /** Debut de session : envoie les choix faits sans session, ou dont l'envoi n'est jamais parti. */
    private void flushResolutions() throws IOException {
        int n = 0;
        for (String rel : memo.minePaths()) {
            Conflicts.Memo.Mine own = memo.mine(rel);
            if (own == null || own.sent) continue;
            Index.State here = localManifest.get(rel);
            boolean still = own.kept == Transfer.BASE_ABSENT ? !Files.exists(dir.resolve(rel))
                    : here != null && here.hash == own.kept;
            if (!still || excluded(rel)) { memo.dropMine(rel); continue; }   // le fichier a encore change : le flux normal s'en charge
            announce(rel);
            n++;
        }
        memo.save();
        if (n > 0) {
            index.save();
            Agent.log("conflits: %d choix fait(s) hors session envoye(s) au pair", n);
        }
    }

    /**
     * Retire les copies en attente de ce fichier devenues sans objet : contenu
     * identique a celui du fichier (rien n'est perdu), ou version que
     * l'utilisateur vient d'ecarter explicitement sur l'autre poste.
     */
    private void dropSettledCopies(String rel, long fileHash, Long discardedByPeer) {
        java.util.List<String> copies = Conflicts.copiesOf(rootName, rel);
        if (copies.isEmpty()) return;
        boolean any = false;
        for (String c : copies) {
            try {
                Path p = dir.resolve(c);
                if (!Files.isRegularFile(p)) continue;
                long h = Index.hashFile(p);
                boolean same = h == fileHash;
                if (!same && (discardedByPeer == null || h != discardedByPeer)) continue;
                Files.delete(p);
                any = true;
                Agent.log("[CONFLIT] %s : regle depuis l'autre poste, copie %s retiree (%s)", rel, c,
                        same ? "contenu identique au fichier" : "version ecartee par l'utilisateur sur l'autre poste");
                appendConflictLog(LocalDateTime.now().format(TS) + "\t" + rel + "\t(regle depuis l'autre poste, copie retiree) " + c);
            } catch (IOException e) {
                Agent.log("conflits: copie %s non retiree (%s)", c, e.getMessage());
            }
        }
        if (any) Conflicts.changed(rootName);
    }

    /** Vrai si une copie en attente de ce fichier a deja ce contenu. */
    private boolean alreadyKept(String rel, long hash) {
        for (String c : Conflicts.copiesOf(rootName, rel)) {
            try {
                Path p = dir.resolve(c);
                if (Files.isRegularFile(p) && Index.hashFile(p) == hash) return true;
            } catch (IOException e) {
                // illisible : on ne peut pas affirmer qu'elle est identique
            }
        }
        return false;
    }

    /** @param base hash de la version que l'on supprime chez le pair. */
    private void sendDelete(String rel, long base) throws IOException {
        Transfer.Msg m = new Transfer.Msg();
        m.op = Transfer.OP_DELETE;
        m.relPath = rel;
        m.size = 0;
        m.modTime = System.currentTimeMillis();
        m.hash = 0;
        m.base = base;
        session.sendFile(m, EMPTY);
    }

    private static final InputStream EMPTY = new ByteArrayInputStream(new byte[0]);

    // =======================================================================
    //  Thread recepteur
    // =======================================================================

    private void receiverLoop() {
        try {
            while (running) {
                Transfer.Msg m = session.recvHeader();     // bloque ; EOF/erreur = fin
                switch (m.op) {
                    case Transfer.OP_FILE -> {
                        if (MANIFEST_PATH.equals(m.relPath)) {
                            ByteArrayOutputStream bos = new ByteArrayOutputStream();
                            session.copyContentTo(m, bos);
                            handleManifest(bos.toByteArray());
                        } else {
                            handleIncomingFile(m);
                        }
                    }
                    case Transfer.OP_DELETE -> {
                        session.copyContentTo(m, null);        // 0 octet, par securite
                        handleIncomingDelete(m);
                    }
                    case Transfer.OP_MANIFEST -> {             // compat future
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        session.copyContentTo(m, bos);
                        handleManifest(bos.toByteArray());
                    }
                    case Transfer.OP_STATUS -> {
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        session.copyContentTo(m, bos);
                        String[] f = new String(bos.toByteArray(), StandardCharsets.UTF_8).split("\t", 5);
                        if (f.length >= 4) {
                            int rem = 0, conf = 0;
                            try { rem = Integer.parseInt(f[2]); conf = Integer.parseInt(f[3]); } catch (NumberFormatException ignore) { }
                            status.peerReport(f[0], m.relPath, f[1], rem, conf, f.length > 4 ? f[4] : "", session.channel());
                        }
                    }
                    case OP_RESOLVED -> {
                        session.copyContentTo(m, null);
                        memo.putPeer(m.relPath, m.hash, m.base);
                        memo.save();
                    }
                    case OP_RESOLVED_ACK -> {
                        session.copyContentTo(m, null);
                        memo.ack(m.relPath, m.hash);
                    }
                    case Transfer.OP_PING -> session.copyContentTo(m, null);
                    default -> session.copyContentTo(m, null); // op inconnue : on draine
                }
            }
        } catch (IOException e) {
            if (running) Agent.log("recepteur: flux termine (%s)", e.getMessage());
        } catch (Exception e) {
            Agent.log("recepteur: erreur (%s)", e);
        } finally {
            running = false;
        }
    }

    // =======================================================================
    //  Application d'un fichier entrant + gestion des conflits (BRIQUE 4)
    // =======================================================================

    private void handleIncomingFile(Transfer.Msg m) throws IOException {
        String rel = m.relPath;
        // Toujours consommer le contenu dans un fichier temporaire (evite toute
        // desynchro du flux, meme si on decide ensuite de ne pas l'appliquer).
        Path tmp = tmpDir.resolve(UUID.randomUUID().toString());
        try (OutputStream out = Files.newOutputStream(tmp)) {
            session.copyContentTo(m, out);
        }
        try { Files.setLastModifiedTime(tmp, FileTime.fromMillis(m.modTime)); } catch (IOException ignore) { }
        status.decRemaining(rootName);   // un fichier recu de moins

        if (excluded(rel)) { Files.deleteIfExists(tmp); return; }

        synchronized (gate) {
            // Ce cadre est-il le choix explicite annonce par le pair (cadre de marque) ?
            Conflicts.Memo.Peer mark = memo.takePeer(rel);
            Conflicts.Memo.Peer choice = mark != null && mark.kept() == m.hash ? mark : null;
            try {
                applyIncoming(m, tmp, rel, choice);
            } finally {
                if (choice != null) sendMark(OP_RESOLVED_ACK, rel, m.hash, 0);
            }
        }
    }

    /** Decide du sort d'un fichier recu (deja ecrit dans tmp). Sous le verrou gate. */
    private void applyIncoming(Transfer.Msg m, Path tmp, String rel, Conflicts.Memo.Peer choice) throws IOException {
        Path target = dir.resolve(rel);
        Long localHash = Files.isRegularFile(target) ? Index.hashFile(target) : null;

        // Cas convergent / echo : le pair a le meme contenu que nous.
        if (localHash != null && localHash == m.hash) {
            Files.deleteIfExists(tmp);
            index.put(rel, new Index.State(m.hash, m.modTime, m.size));
            memo.dropMine(rel);
            dropSettledCopies(rel, m.hash, choice != null ? choice.discarded() : null);
            return;
        }

        if (policy == ConflictPolicy.KEEP_BOTH && !rel.equals(SyncIgnore.IGNORE_REL) && localHash != null) {
            // Le pair renvoie justement la version que l'utilisateur a ecartee ICI par un
            // choix explicite pas encore acquitte (choix croises sur les deux postes, ou
            // envoi du pair anterieur a notre choix) : jamais applique en silence, la
            // version revient a cote et le conflit est a trancher de nouveau.
            Conflicts.Memo.Mine own = memo.mine(rel);
            if (own != null && own.discarded == m.hash) {
                memo.dropMine(rel);
                Agent.log("[CONFLIT] %s : l'autre poste renvoie la version ecartee ici -> conservee a cote, a trancher de nouveau", rel);
                keepBoth(tmp, target, rel, m);
                index.save();
                return;
            }
            // Choix explicite du pair, et notre fichier est bien la version qu'il ecarte
            // (ou celle qu'il avait avant son choix) : on l'applique, sans conflit.
            if (choice != null && (localHash == m.base || localHash == choice.discarded())) {
                Agent.log("[CONFLIT] %s : choix fait sur l'autre poste, applique ici", rel);
                applyTmp(tmp, target, rel, m.hash);
                index.put(rel, new Index.State(m.hash, m.modTime, m.size));
                memo.dropMine(rel);
                dropSettledCopies(rel, m.hash, choice.discarded());
                index.save();
                return;
            }
            // Une decision est en attente ici pour ce fichier : rien ne l'ecrase.
            if (!Conflicts.copiesOf(rootName, rel).isEmpty()) {
                if (alreadyKept(rel, m.hash)) {
                    Files.deleteIfExists(tmp);     // cette version du pair est deja gardee en copie
                    return;
                }
                keepBoth(tmp, target, rel, m);
                index.save();
                return;
            }
        }
        memo.dropMine(rel);   // le pair a parle de ce fichier : un ancien choix n'a plus cours

        // .syncignore : le modele commente cree d'office (dossier neuf, fichier
        // recree) n'est le choix de personne. Il ne remplace jamais des regles
        // ecrites par l'utilisateur, quels que soient les dates et le maitre.
        if (rel.equals(SyncIgnore.IGNORE_REL) && localHash != null) {
            long template = SyncIgnore.templateHash();
            if (localHash == template) {
                applyTmp(tmp, target, rel, m.hash);   // nous n'avons que le modele : on adopte les regles du pair
                index.put(rel, new Index.State(m.hash, m.modTime, m.size));
                index.save();
                return;
            }
            if (m.hash == template) {
                Files.deleteIfExists(tmp);
                Agent.log("syncignore: le pair n'a que le modele par defaut -> nos regles sont conservees et renvoyees");
                sendLocalFile(rel, m.hash);
                index.save();
                return;
            }
        }

        // Deux conditions pour ecraser le local sans conflit :
        //  - nous n'y avons pas touche depuis la derniere synchro (index) ;
        //  - l'emetteur savait ce qu'il ecrasait (base) : sinon nos deux envois se
        //    sont croises, ce qui arrive des que le transport a de la latence.
        Index.State ref = index.get(rel);
        boolean baseOk = m.base == Transfer.BASE_UNKNOWN || (localHash != null && localHash == m.base);
        boolean localUntouched = (localHash == null) || (ref != null && ref.hash == localHash && baseOk);

        if (localUntouched) {
            // Cas normal : le pair a change, nous n'avons pas touche -> on applique
            applyTmp(tmp, target, rel, m.hash);
            index.put(rel, new Index.State(m.hash, m.modTime, m.size));
            dropSettledCopies(rel, m.hash, choice != null ? choice.discarded() : null);
            return;
        }

        // Le .syncignore est un fichier de controle : jamais de copie .conflict.
        // Le maitre tranche pour que les regles convergent proprement.
        if (rel.equals(SyncIgnore.IGNORE_REL)) {
            if (isMaster) {
                Files.deleteIfExists(tmp);
                sendLocalFile(rel, m.hash);         // on impose notre .syncignore au pair
            } else {
                applyTmp(tmp, target, rel, m.hash); // on adopte celui du maitre (applyTmp recharge les regles)
                index.put(rel, new Index.State(m.hash, m.modTime, m.size));
            }
            index.save();
            return;
        }

        // VRAI CONFLIT : divergence des deux cotes
        switch (policy) {
            case MASTER_WINS -> {
                if (isMaster) {
                    Files.deleteIfExists(tmp);
                    Agent.log("[CONFLIT] %s : maitre, on conserve le local et on repousse", rel);
                    sendLocalFile(rel, m.hash);     // base = la version que le pair vient d'envoyer
                } else {
                    Agent.log("[CONFLIT] %s : esclave, on applique la version du maitre", rel);
                    applyTmp(tmp, target, rel, m.hash);
                    index.put(rel, new Index.State(m.hash, m.modTime, m.size));
                }
            }
            case NEWER_WINS -> {
                long localMtime = Files.isRegularFile(target) ? Files.getLastModifiedTime(target).toMillis() : Long.MIN_VALUE;
                if (m.modTime > localMtime) {
                    Agent.log("[CONFLIT] %s : version entrante plus recente -> appliquee", rel);
                    applyTmp(tmp, target, rel, m.hash);
                    index.put(rel, new Index.State(m.hash, m.modTime, m.size));
                } else {
                    Files.deleteIfExists(tmp);
                    Agent.log("[CONFLIT] %s : version locale plus recente -> conservee et repoussee", rel);
                    sendLocalFile(rel, m.hash);
                }
            }
            default -> keepBoth(tmp, target, rel, m); // KEEP_BOTH
        }
        index.save();
    }

    /** KEEP_BOTH : on ne touche pas au local, on depose l'entrant a cote sous un nom de conflit. */
    private void keepBoth(Path tmp, Path target, String rel, Transfer.Msg m) throws IOException {
        // Nom libre : deux conflits sur le meme fichier dans la meme seconde ne s'ecrasent pas.
        LocalDateTime stamp = LocalDateTime.now();
        String conflictRel = rel + Conflicts.COPY_TAG + stamp.format(TS);
        Path conflict = dir.resolve(conflictRel);
        for (int i = 0; i < 3600 && Files.exists(conflict, java.nio.file.LinkOption.NOFOLLOW_LINKS); i++) {
            stamp = stamp.plusSeconds(1);
            conflictRel = rel + Conflicts.COPY_TAG + stamp.format(TS);
            conflict = dir.resolve(conflictRel);
        }
        Files.createDirectories(conflict.getParent());
        // L'ecriture du fichier de conflit ne doit PAS etre repropagee
        suppressUpsert.put(conflictRel, new long[]{ m.hash, System.currentTimeMillis() });
        Files.move(tmp, conflict, StandardCopyOption.REPLACE_EXISTING);
        try { Files.setLastModifiedTime(conflict, FileTime.fromMillis(m.modTime)); } catch (IOException ignore) { }

        // On fige l'index sur l'etat local courant pour ne pas re-signaler en boucle
        if (Files.isRegularFile(target)) {
            index.put(rel, new Index.State(Index.hashFile(target),
                    Files.getLastModifiedTime(target).toMillis(), Files.size(target)));
        }
        String msg = LocalDateTime.now().format(TS) + "\t" + rel + "\t-> " + conflictRel;
        Agent.log("[CONFLIT] %s : divergence des deux cotes, entrant conserve sous %s", rel, conflictRel);
        appendConflictLog(msg);
        Conflicts.changed(rootName);     // le compteur de la page est la liste des conflits en attente
        status.log(rootName, "conflit: " + rel);
    }

    /** Pose le contenu temporaire sur la cible de facon atomique, avec anti-echo. */
    private void applyTmp(Path tmp, Path target, String rel, long hash) throws IOException {
        Files.createDirectories(target.getParent());
        suppressUpsert.put(rel, new long[]{ hash, System.currentTimeMillis() });
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFail) {
            // ATOMIC_MOVE indisponible (ex. volumes differents) : repli non atomique
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        localManifest.put(rel, new Index.State(hash, 0, 0));
        // Si le .syncignore vient d'etre recu, on recharge les regles a chaud.
        if (rel.equals(SyncIgnore.IGNORE_REL) && ignore != null) {
            ignore.reload();
            Agent.log("syncignore mis a jour depuis le pair, regles rechargees");
        }
    }

    private void handleIncomingDelete(Transfer.Msg m) {
        String rel = m.relPath;
        if (excluded(rel)) return;
        synchronized (gate) {
            // Suppression annoncee par le pair comme un choix explicite ("garder ma version", fichier disparu) ?
            Conflicts.Memo.Peer mark = memo.takePeer(rel);
            boolean choice = mark != null && mark.kept() == Transfer.BASE_ABSENT;
            try {
                applyDelete(m, rel, choice);
            } finally {
                if (choice) {
                    try { sendMark(OP_RESOLVED_ACK, rel, Transfer.BASE_ABSENT, 0); } catch (IOException ignore) { }
                }
            }
        }
    }

    /** Decide du sort d'une suppression recue. Sous le verrou gate. */
    private void applyDelete(Transfer.Msg m, String rel, boolean choice) {
        Path target = dir.resolve(rel);
        try {
            // Conflit suppression/modification : le pair supprime mais nous avons modifie
            if (Files.isRegularFile(target)) {
                Long localHash = Index.hashFile(target);
                Index.State ref = index.get(rel);
                boolean baseOk = m.base == Transfer.BASE_UNKNOWN || localHash == m.base;
                boolean localUntouched = (ref != null && ref.hash == localHash && baseOk);
                if (policy == ConflictPolicy.KEEP_BOTH) {
                    Conflicts.Memo.Mine own = memo.mine(rel);
                    if (own != null) {
                        // Nous venons de choisir explicitement de garder ce fichier : il reste.
                        Agent.log("[CONFLIT] %s : supprime sur l'autre poste mais garde ici par un choix explicite -> conserve", rel);
                        appendConflictLog(LocalDateTime.now().format(TS) + "\t" + rel + "\t(suppression pair ignoree)");
                        index.remove(rel);   // le fichier repartira vers le pair (la modification l'emporte)
                        memo.dropMine(rel);
                        return;
                    }
                    // Choix explicite du pair et notre fichier est bien la version qu'il ecarte : on supprime.
                    if (choice && localHash == m.base) localUntouched = true;
                    else if (!Conflicts.copiesOf(rootName, rel).isEmpty()) {
                        // Une decision est en attente ici pour ce fichier : rien ne le supprime.
                        Agent.log("[CONFLIT] %s : supprime sur l'autre poste, conflit en attente ici -> conserve", rel);
                        appendConflictLog(LocalDateTime.now().format(TS) + "\t" + rel + "\t(suppression pair ignoree)");
                        return;
                    }
                }
                if (!localUntouched && policy == ConflictPolicy.KEEP_BOTH) {
                    Agent.log("[CONFLIT] %s : supprime chez le pair mais modifie ici -> conserve local", rel);
                    appendConflictLog(LocalDateTime.now().format(TS) + "\t" + rel + "\t(suppression pair ignoree)");
                    index.remove(rel);
                    return;
                }
            }
            suppressDelete.put(rel, System.currentTimeMillis());
            Files.deleteIfExists(target);
            index.remove(rel);
            localManifest.remove(rel);
            pruneEmptyDirs(target.getParent());
        } catch (IOException e) {
            Agent.log("suppression %s: %s", rel, e.getMessage());
        }
    }

    /**
     * Retire les dossiers devenus vides apres une suppression venue du pair, en
     * remontant vers la racine. Ne supprime jamais un dossier qui contient encore
     * quelque chose, ni la racine synchronisee, ni .cotransfert.
     */
    private void pruneEmptyDirs(Path d) {
        try {
            Path root = dir.toAbsolutePath().normalize();
            Path meta = root.resolve(SyncIgnore.META);
            d = d == null ? null : d.toAbsolutePath().normalize();
            while (d != null && d.startsWith(root) && !d.equals(root) && !d.startsWith(meta)) {
                try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(d)) {
                    if (ds.iterator().hasNext()) return;
                }
                Files.delete(d);
                d = d.getParent();
            }
        } catch (IOException | RuntimeException e) {
            // rempli entre-temps, verrouille, deja retire : on laisse
        }
    }

    // =======================================================================
    //  Utilitaires
    // =======================================================================

    private String rel(Path abs) {
        return dir.relativize(abs.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private void purgeSuppress() {
        long now = System.currentTimeMillis();
        suppressUpsert.entrySet().removeIf(e -> now - e.getValue()[1] > SUPPRESS_TTL_MS);
        suppressDelete.entrySet().removeIf(e -> now - e.getValue() > SUPPRESS_TTL_MS);
    }

    private void appendConflictLog(String line) {
        try {
            Files.createDirectories(metaDir);
            Files.writeString(conflictsLog, line + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignore) { }
    }
}
