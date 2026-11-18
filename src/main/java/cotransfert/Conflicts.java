package cotransfert;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.ObjLongConsumer;
import java.util.regex.Pattern;

/**
 * Conflits en attente d'un poste (ecran "Conflits" de la page).
 *
 * Avec la politique keep-both, un fichier modifie des deux cotes n'est jamais
 * ecrase : le poste garde sa version et depose celle de l'autre poste a cote,
 * sous "fichier.conflict-peer-AAAAMMJJ-HHMMSS", avec une ligne dans
 * .cotransfert/conflicts.log ("date TAB chemin TAB -> copie").
 *
 * Cette classe :
 *   - LISTE les conflits en attente d'un dossier : les lignes du journal dont
 *     la copie existe encore. Aucun parcours de l'arbre : lecture du journal
 *     (relu seulement s'il a change), un stat par copie, resultat garde un
 *     court instant. Le compteur de /api/status est la taille de cette liste ;
 *   - RESOUT un conflit, a travers le moteur du dossier s'il tourne (jamais
 *     d'ecriture concurrente hors du moteur), sinon directement sur le disque
 *     et l'index, sous le verrou du dossier (un moteur ne peut pas demarrer au
 *     meme moment) ;
 *   - garde la MEMOIRE des choix ({@link Memo}), pour que l'envoi parte a la
 *     prochaine session quand il n'a pas pu partir, et pour que deux choix
 *     faits en meme temps sur les deux postes ne s'echangent pas en silence.
 *
 * "Garder ma version"  : l'index prend l'empreinte de la copie (la version que
 *   le pair possede), la copie est supprimee, puis mon fichier est envoye au
 *   pair avec cette base. Mon fichier a disparu entre-temps : la copie est
 *   supprimee et la suppression est envoyee au pair avec cette base.
 * "Garder la version de l'autre poste" : la copie remplace mon fichier
 *   (deplacement atomique dans le meme dossier), l'index prend son empreinte,
 *   puis le fichier est annonce au pair (sans effet s'il a deja ce contenu).
 * La copie n'est jamais supprimee avant que le contenu choisi soit en place.
 */
final class Conflicts {

    private Conflicts() { }

    /** Marque des copies de conflit dans un nom de fichier. */
    static final String COPY_TAG = ".conflict-peer-";
    private static final Pattern COPY_NAME = Pattern.compile(".+\\.conflict-peer-\\d{8}-\\d{6}");
    private static final Pattern LOG_DATE = Pattern.compile("\\d{8}-\\d{6}");
    /** Duree de vie du resultat d'une liste (la page interroge toutes les quelques secondes). */
    private static final long CACHE_MS = 1500;

    static final String MSG_UNKNOWN_ROOT = "dossier inconnu";
    static final String MSG_UNKNOWN_CONFLICT = "conflit inconnu";
    static final String MSG_UNKNOWN_COPY = "copie inconnue";
    static final String MSG_LOCKED = "fichier verrouille";

    /** Un conflit en attente (champs de GET /api/conflicts). */
    record Entry(String root, String path, String copy, String when, boolean mineExists,
                 long mineSize, long mineTime, long theirsSize, long theirsTime) { }

    /** Une copie a resoudre : chemin du fichier et chemin de sa copie, relatifs au dossier. */
    record Item(String path, String copy) { }

    record Failure(String root, String copy, String message) { }

    record Outcome(int resolved, List<Failure> failed) {
        String json() {
            StringBuilder sb = new StringBuilder("{\"ok\":").append(failed.isEmpty()).append(",\"resolved\":").append(resolved)
                    .append(",\"failed\":[");
            boolean first = true;
            for (Failure f : failed) {
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"root\":").append(Status.q(f.root())).append(",\"copy\":").append(Status.q(f.copy()))
                  .append(",\"message\":").append(Status.q(f.message())).append('}');
            }
            return sb.append("]}").toString();
        }
    }

    // =======================================================================
    //  Dossiers connus et moteurs en cours
    // =======================================================================

    private static final class Root {
        final String name;
        volatile Path dir;
        /** Moteur en cours sur ce dossier, ou null. */
        volatile SyncEngine engine;
        /** Exclut l'un de l'autre le demarrage d'un moteur et une resolution sans moteur. */
        final ReentrantLock lock = new ReentrantLock();
        // --- cache de la liste (sous synchronized(this)) ---
        long logSize = -1, logTime = -1;
        List<String[]> lines = List.of();          // {date, chemin, copie}
        List<Entry> cached;
        Map<String, List<String>> byPath = Map.of();
        long cachedAt;
        Root(String name, Path dir) { this.name = name; this.dir = dir; }
    }

    private static final Map<String, Root> ROOTS = new ConcurrentHashMap<>();
    /** Ordre d'enregistrement (celui de la configuration), pour une liste stable. */
    private static final List<String> ORDER = Collections.synchronizedList(new ArrayList<>());

    /** Declare un dossier synchronise de ce poste (qu'une session tourne ou non). */
    static void register(String name, Path dir) {
        Path d = dir.toAbsolutePath().normalize();
        Root r = ROOTS.computeIfAbsent(name, k -> { ORDER.add(k); return new Root(k, d); });
        if (!d.equals(r.dir)) {
            synchronized (r) { r.dir = d; r.cached = null; r.logSize = -1; }
        }
    }

    /** Oublie un dossier (essais). */
    static void forget(String name) {
        ROOTS.remove(name);
        ORDER.remove(name);
    }

    /**
     * Un moteur demarre sur ce dossier. load (lecture de l'index et de la memoire
     * des choix) s'execute sous le verrou du dossier : une resolution sans moteur
     * est soit terminee avant, soit passera par ce moteur.
     */
    static void attach(String name, Path dir, SyncEngine engine, Runnable load) {
        register(name, dir);
        Root r = ROOTS.get(name);
        if (r == null) { load.run(); return; }
        r.lock.lock();
        try {
            load.run();
            r.engine = engine;
        } finally {
            r.lock.unlock();
        }
    }

    /** Le moteur s'arrete (il a deja refuse toute nouvelle resolution et sauve son index). */
    static void detach(String name, SyncEngine engine) {
        Root r = ROOTS.get(name);
        if (r == null) return;
        r.lock.lock();
        try {
            if (r.engine == engine) r.engine = null;
        } finally {
            r.lock.unlock();
        }
    }

    /** Le journal ou les copies de ce dossier viennent de changer : la prochaine liste est refaite. */
    static void changed(String name) {
        Root r = ROOTS.get(name);
        if (r == null) return;
        synchronized (r) { r.cached = null; }
    }

    // =======================================================================
    //  Regles de nommage
    // =======================================================================

    /** Vrai pour un chemin de copie de conflit ("x.conflict-peer-AAAAMMJJ-HHMMSS"). */
    static boolean isCopy(String rel) {
        if (rel == null) return false;
        String name = rel.substring(rel.lastIndexOf('/') + 1);
        return COPY_NAME.matcher(name).matches();
    }

    /** Chemin relatif sur, en separateurs '/' : ni absolu, ni "..", ni lecteur, ni dossier interne. */
    static boolean safeRel(String rel) {
        if (rel == null || rel.isEmpty() || rel.length() > 4096) return false;
        if (rel.startsWith("/") || rel.indexOf('\\') >= 0 || rel.indexOf(':') >= 0 || rel.indexOf('\0') >= 0) return false;
        for (String seg : rel.split("/", -1)) {
            if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) return false;
        }
        return !rel.equals(SyncIgnore.META) && !rel.startsWith(SyncIgnore.META + "/");
    }

    // =======================================================================
    //  Liste
    // =======================================================================

    /** Conflits en attente de tous les dossiers connus, dans l'ordre de la configuration puis du journal. */
    static List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        List<String> names;
        synchronized (ORDER) { names = new ArrayList<>(ORDER); }
        for (String n : names) out.addAll(list(n));
        return out;
    }

    /** Conflits en attente d'un dossier (liste vide s'il est inconnu). */
    static List<Entry> list(String name) {
        Root r = ROOTS.get(name);
        return r == null ? List.of() : snapshot(r, false);
    }

    /** Nombre de conflits en attente d'un dossier : le compteur de /api/status. */
    static int count(String name) { return list(name).size(); }

    /** Copies en attente pour ce fichier (chemins relatifs), dans l'ordre du journal. */
    static List<String> copiesOf(String name, String rel) {
        Root r = ROOTS.get(name);
        if (r == null) return List.of();
        snapshot(r, false);
        List<String> l;
        synchronized (r) { l = r.byPath.get(rel); }
        return l == null ? List.of() : l;
    }

    /** Fichiers de ce dossier qui ont au moins une copie en attente. */
    static Set<String> pendingPaths(String name) {
        Root r = ROOTS.get(name);
        if (r == null) return Set.of();
        snapshot(r, false);
        synchronized (r) { return r.byPath.keySet(); }
    }

    private static List<Entry> snapshot(Root r, boolean fresh) {
        synchronized (r) {
            long now = System.nanoTime() / 1_000_000;
            if (!fresh && r.cached != null && now - r.cachedAt < CACHE_MS) return r.cached;
            Path dir = r.dir;
            Path log = dir.resolve(SyncIgnore.META).resolve("conflicts.log");
            long size = -1, time = -1;
            try {
                BasicFileAttributes a = Files.readAttributes(log, BasicFileAttributes.class);
                size = a.size();
                time = a.lastModifiedTime().toMillis();
            } catch (IOException | RuntimeException e) {
                // pas de journal : aucun conflit
            }
            if (size != r.logSize || time != r.logTime) {
                r.lines = size < 0 ? List.of() : parse(log);
                r.logSize = size;
                r.logTime = time;
            }
            List<Entry> out = new ArrayList<>();
            Map<String, List<String>> by = new HashMap<>();
            for (String[] l : r.lines) {
                try {
                    Path copy = dir.resolve(l[2]);
                    BasicFileAttributes ca = Files.readAttributes(copy, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!ca.isRegularFile()) continue;
                    boolean mine = false;
                    long mSize = 0, mTime = 0;
                    try {
                        BasicFileAttributes ma = Files.readAttributes(dir.resolve(l[1]), BasicFileAttributes.class);
                        if (ma.isRegularFile()) { mine = true; mSize = ma.size(); mTime = ma.lastModifiedTime().toMillis(); }
                    } catch (IOException e) {
                        // mon fichier a disparu
                    }
                    out.add(new Entry(r.name, l[1], l[2], l[0], mine, mSize, mTime, ca.size(), ca.lastModifiedTime().toMillis()));
                    by.computeIfAbsent(l[1], k -> new ArrayList<>()).add(l[2]);
                } catch (IOException | RuntimeException e) {
                    // copie absente : conflit deja regle
                }
            }
            r.cached = Collections.unmodifiableList(out);
            r.byPath = by;
            r.cachedAt = now;
            return r.cached;
        }
    }

    /** Lignes "date TAB chemin TAB -> copie" du journal ; toute autre forme est ignoree. Une copie n'apparait qu'une fois. */
    private static List<String[]> parse(Path log) {
        List<String[]> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<String> all;
        try {
            all = Files.readAllLines(log, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return out;
        }
        for (String line : all) {
            String[] f = line.split("\t", -1);
            if (f.length != 3 || !f[2].startsWith("-> ") || !LOG_DATE.matcher(f[0]).matches()) continue;
            String rel = f[1], copy = f[2].substring(3);
            if (!safeRel(rel) || !safeRel(copy) || !isCopy(copy) || !copy.startsWith(rel + COPY_TAG)) continue;
            if (copy.length() != rel.length() + COPY_TAG.length() + 15 || !seen.add(copy)) continue;
            String d = f[0];
            String when = d.substring(0, 4) + "-" + d.substring(4, 6) + "-" + d.substring(6, 8) + " "
                    + d.substring(9, 11) + ":" + d.substring(11, 13) + ":" + d.substring(13, 15);
            out.add(new String[] { when, rel, copy });
        }
        return out;
    }

    /** Corps de GET /api/conflicts. */
    static String json() {
        List<Entry> l = list();
        StringBuilder sb = new StringBuilder("{\"count\":").append(l.size()).append(",\"conflicts\":[");
        boolean first = true;
        for (Entry e : l) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"root\":").append(Status.q(e.root())).append(",\"path\":").append(Status.q(e.path()))
              .append(",\"copy\":").append(Status.q(e.copy())).append(",\"when\":").append(Status.q(e.when()))
              .append(",\"mineExists\":").append(e.mineExists())
              .append(",\"mineSize\":").append(e.mineSize()).append(",\"mineTime\":").append(e.mineTime())
              .append(",\"theirsSize\":").append(e.theirsSize()).append(",\"theirsTime\":").append(e.theirsTime()).append('}');
        }
        return sb.append("]}").toString();
    }

    // =======================================================================
    //  Resolution
    // =======================================================================

    /** Resout UNE copie. Refus : dossier inconnu, copie mal formee, conflit absent de la liste. */
    static Outcome resolve(String rootName, String copy, boolean mine) {
        String c = copy == null ? "" : copy;
        Root r = rootName == null ? null : ROOTS.get(rootName);
        if (r == null) return new Outcome(0, List.of(new Failure(rootName == null ? "" : rootName, c, MSG_UNKNOWN_ROOT)));
        if (!safeRel(c) || !isCopy(c)) return new Outcome(0, List.of(new Failure(r.name, c, MSG_UNKNOWN_COPY)));
        Entry found = null;
        for (Entry e : snapshot(r, true)) if (e.copy().equals(c)) found = e;
        if (found == null) return new Outcome(0, List.of(new Failure(r.name, c, MSG_UNKNOWN_CONFLICT)));
        List<Failure> failed = new ArrayList<>();
        int ok = run(r, List.of(new Item(found.path(), found.copy())), mine, failed);
        return new Outcome(ok, failed);
    }

    /** Resout tous les conflits en attente, d'un dossier (rootName) ou de tous (null). */
    static Outcome resolveAll(String rootName, boolean mine) {
        List<Root> roots = new ArrayList<>();
        if (rootName != null) {
            Root r = ROOTS.get(rootName);
            if (r == null) return new Outcome(0, List.of(new Failure(rootName, "", MSG_UNKNOWN_ROOT)));
            roots.add(r);
        } else {
            List<String> names;
            synchronized (ORDER) { names = new ArrayList<>(ORDER); }
            for (String n : names) { Root r = ROOTS.get(n); if (r != null) roots.add(r); }
        }
        List<Failure> failed = new ArrayList<>();
        int ok = 0;
        for (Root r : roots) {
            List<Item> items = new ArrayList<>();
            for (Entry e : snapshot(r, true)) items.add(new Item(e.path(), e.copy()));
            if (!items.isEmpty()) ok += run(r, items, mine, failed);
        }
        return new Outcome(ok, failed);
    }

    /**
     * Resout ces copies d'un dossier. Plusieurs copies d'un meme fichier sont
     * traitees l'une apres l'autre (une par passe, dans l'ordre du journal) : la
     * derniere, la plus recente, est celle que le pair possede.
     */
    private static int run(Root r, List<Item> items, boolean mine, List<Failure> failed) {
        int ok = 0;
        List<Item> left = new ArrayList<>(items);
        while (!left.isEmpty()) {
            List<Item> round = new ArrayList<>(), later = new ArrayList<>();
            Set<String> paths = new HashSet<>();
            for (Item it : left) (paths.add(it.path()) ? round : later).add(it);
            Map<String, String> ko = pass(r, round, mine);
            for (Item it : round) {
                String why = ko.get(it.copy());
                if (why == null) ok++;
                else failed.add(new Failure(r.name, it.copy(), why));
            }
            left = later;
        }
        changed(r.name);
        return ok;
    }

    /** Une passe : par le moteur du dossier s'il tourne, sinon sur le disque sous le verrou du dossier. */
    private static Map<String, String> pass(Root r, List<Item> round, boolean mine) {
        for (int attempt = 0; attempt < 400; attempt++) {
            SyncEngine e = r.engine;
            if (e != null) {
                try {
                    return e.resolve(round, mine);
                } catch (SyncEngine.Stopped stopped) {
                    // moteur en cours d'arret : il se detache dans un instant
                    try { Thread.sleep(25); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    continue;
                }
            }
            r.lock.lock();
            try {
                if (r.engine != null) continue;
                Path dir = r.dir;
                Index index = new Index(dir);
                index.load();
                Memo memo = new Memo(dir);
                memo.load();
                Map<String, String> ko = choose(dir, index, memo, round, mine, null);
                Agent.log("conflits[%s]: %d choix applique(s) hors session (%s) ; l'envoi au pair partira a la prochaine session",
                        r.name, round.size() - ko.size(), mine ? "ma version" : "version de l'autre poste");
                return ko;
            } finally {
                r.lock.unlock();
            }
        }
        Map<String, String> ko = new LinkedHashMap<>();
        for (Item it : round) ko.put(it.copy(), "dossier occupe, reessayer");
        return ko;
    }

    /**
     * Coeur de la resolution, commun au moteur et au mode sans moteur : applique
     * le choix sur le disque, l'index et la memoire des choix. Chaque fichier
     * n'apparait qu'une fois dans items. Rien n'est envoye ici : la memoire porte
     * ce qui reste a envoyer ({@link Memo.Mine#sent} faux).
     *
     * Ordre (aucune perte en cas d'arret brutal) :
     *   ma version   : memoire + index (empreinte de la copie) enregistres, PUIS
     *                  suppression de la copie ; un echec remet l'index d'avant ;
     *   autre poste  : memoire enregistree, deplacement atomique de la copie sur
     *                  mon fichier, PUIS index.
     *
     * @param beforeReplace prevenu (chemin, empreinte) juste avant qu'un fichier soit remplace, ou null
     * @return copie -> raison, pour chaque echec
     */
    static Map<String, String> choose(Path dir, Index index, Memo memo, List<Item> items, boolean mine,
                                      ObjLongConsumer<String> beforeReplace) {
        Map<String, String> ko = new LinkedHashMap<>();
        record Plan(Item it, Path copy, Path target, long hCopy, long tCopy, long sCopy, boolean has, long hMine, Index.State prev) { }
        List<Plan> plans = new ArrayList<>();
        for (Item it : items) {
            try {
                if (!safeRel(it.path()) || !safeRel(it.copy()) || !isCopy(it.copy())) { ko.put(it.copy(), MSG_UNKNOWN_COPY); continue; }
                Path copy = dir.resolve(it.copy()).normalize(), target = dir.resolve(it.path()).normalize();
                if (!copy.startsWith(dir) || !target.startsWith(dir) || !copy.getParent().equals(target.getParent())) {
                    ko.put(it.copy(), MSG_UNKNOWN_COPY);
                    continue;
                }
                BasicFileAttributes ca = Files.readAttributes(copy, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!ca.isRegularFile()) { ko.put(it.copy(), MSG_UNKNOWN_COPY); continue; }
                if (Files.isDirectory(target)) { ko.put(it.copy(), "un dossier porte le nom du fichier"); continue; }
                boolean has = Files.isRegularFile(target);
                plans.add(new Plan(it, copy, target, Index.hashFile(copy), ca.lastModifiedTime().toMillis(), ca.size(),
                        has, has ? Index.hashFile(target) : Transfer.BASE_ABSENT, index.get(it.path())));
            } catch (NoSuchFileException e) {
                ko.put(it.copy(), MSG_UNKNOWN_COPY);
            } catch (IOException | InvalidPathException e) {
                ko.put(it.copy(), MSG_LOCKED);
            }
        }
        if (plans.isEmpty()) return ko;

        if (mine) {
            for (Plan p : plans) {
                // Reference = la version que le pair possede : mon fichier sera envoye avec cette base.
                memo.putMine(p.it.path(), p.hMine, p.hCopy, p.hCopy);
                if (p.has) index.put(p.it.path(), new Index.State(p.hCopy, p.tCopy, p.sCopy));
                else index.remove(p.it.path());
            }
            memo.save();
            index.save();
            boolean undone = false;
            for (Plan p : plans) {
                try {
                    Files.delete(p.copy);
                } catch (IOException e) {
                    if (!Files.exists(p.copy, LinkOption.NOFOLLOW_LINKS)) continue;   // deja partie : choix applique
                    memo.dropMine(p.it.path());
                    if (p.prev != null) index.put(p.it.path(), p.prev); else index.remove(p.it.path());
                    ko.put(p.it.copy(), MSG_LOCKED);
                    undone = true;
                }
            }
            if (undone) { memo.save(); index.save(); }
        } else {
            for (Plan p : plans) memo.putMine(p.it.path(), p.hCopy, p.hMine, p.hMine);
            memo.save();
            for (Plan p : plans) {
                try {
                    if (beforeReplace != null) beforeReplace.accept(p.it.path(), p.hCopy);
                    try {
                        Files.move(p.copy, p.target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException e) {
                        Files.move(p.copy, p.target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    try { Files.setLastModifiedTime(p.target, FileTime.fromMillis(p.tCopy)); } catch (IOException ignore) { }
                    index.put(p.it.path(), new Index.State(p.hCopy, p.tCopy, p.sCopy));
                } catch (IOException e) {
                    memo.dropMine(p.it.path());
                    ko.put(p.it.copy(), Files.exists(p.copy, LinkOption.NOFOLLOW_LINKS) ? MSG_LOCKED : MSG_UNKNOWN_COPY);
                }
            }
            index.save();
            memo.save();
        }
        return ko;
    }

    /**
     * Dossier a ouvrir dans l'explorateur pour cette copie.
     * @return {dossier, null} ou {null, raison}
     */
    static Object[] locate(String rootName, String copy) {
        Root r = rootName == null ? null : ROOTS.get(rootName);
        if (r == null) return new Object[] { null, MSG_UNKNOWN_ROOT };
        if (copy == null || !safeRel(copy) || !isCopy(copy)) return new Object[] { null, MSG_UNKNOWN_COPY };
        for (Entry e : snapshot(r, true)) {
            if (!e.copy().equals(copy)) continue;
            Path parent = r.dir.resolve(copy).normalize().getParent();
            if (parent == null || !parent.startsWith(r.dir) || !Files.isDirectory(parent)) break;
            return new Object[] { parent, null };
        }
        return new Object[] { null, MSG_UNKNOWN_CONFLICT };
    }

    // =======================================================================
    //  Memoire des choix (.cotransfert/resolutions.tsv, propre a chaque poste)
    // =======================================================================

    /**
     * Choix explicites recents, par fichier :
     *   - "mine" : un choix fait SUR CE POSTE (contenu garde, contenu ecarte, base
     *     a annoncer au pair, envoye ou non). Garde jusqu'a l'accuse de reception
     *     du pair, 7 jours au plus. Sert a envoyer le choix a la prochaine session
     *     s'il n'est pas parti, a le marquer comme choix explicite, et a reconnaitre
     *     le cas ou le pair renvoie justement la version ecartee ici (choix croises
     *     sur les deux postes : jamais applique en silence) ;
     *   - "peer" : un choix annonce PAR LE PAIR (cadre de marque), en attente du
     *     cadre de fichier ou de suppression qui le suit dans le flux.
     * Une empreinte BASE_ABSENT veut dire "pas de fichier".
     */
    static final class Memo {
        static final long TTL_MS = 7L * 24 * 3600 * 1000;

        static final class Mine {
            final long kept, discarded, base, time;
            volatile boolean sent;
            Mine(long kept, long discarded, long base, long time, boolean sent) {
                this.kept = kept; this.discarded = discarded; this.base = base; this.time = time; this.sent = sent;
            }
        }

        record Peer(long kept, long discarded) { }

        private final Path file;
        private final Map<String, Mine> mine = new LinkedHashMap<>();
        private final Map<String, Peer> peer = new LinkedHashMap<>();
        private boolean dirty;

        Memo(Path dir) { this.file = dir.resolve(SyncIgnore.META).resolve("resolutions.tsv"); }

        synchronized void load() {
            mine.clear();
            peer.clear();
            dirty = false;
            if (!Files.isRegularFile(file)) return;
            long now = System.currentTimeMillis();
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String[] f = line.split("\t", 7);
                    if (f.length < 7) continue;
                    long kept = Long.parseLong(f[1]), disc = Long.parseLong(f[2]), base = Long.parseLong(f[3]), time = Long.parseLong(f[4]);
                    if (now - time > TTL_MS) { dirty = true; continue; }
                    if (f[0].equals("M")) mine.put(f[6], new Mine(kept, disc, base, time, f[5].equals("1")));
                    else if (f[0].equals("P")) peer.put(f[6], new Peer(kept, disc));
                }
            } catch (IOException | RuntimeException e) {
                Agent.log("conflits: memoire des choix illisible (%s), ignoree", e.getMessage());
            }
        }

        /** Ecrit le fichier s'il y a eu un changement (remplacement atomique). */
        synchronized void save() {
            if (!dirty) return;
            dirty = false;
            try {
                if (mine.isEmpty() && peer.isEmpty()) { Files.deleteIfExists(file); return; }
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling("resolutions.tsv.tmp");
                long now = System.currentTimeMillis();
                try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                    for (Map.Entry<String, Mine> e : mine.entrySet()) {
                        Mine m = e.getValue();
                        w.write("M\t" + m.kept + "\t" + m.discarded + "\t" + m.base + "\t" + m.time + "\t" + (m.sent ? "1" : "0") + "\t" + e.getKey());
                        w.newLine();
                    }
                    for (Map.Entry<String, Peer> e : peer.entrySet()) {
                        w.write("P\t" + e.getValue().kept() + "\t" + e.getValue().discarded() + "\t0\t" + now + "\t0\t" + e.getKey());
                        w.newLine();
                    }
                }
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | RuntimeException e) {
                dirty = true;
                Agent.log("conflits: memoire des choix non enregistree (%s)", e.getMessage());
            }
        }

        synchronized Mine mine(String rel) { return mine.get(rel); }

        synchronized List<String> minePaths() { return new ArrayList<>(mine.keySet()); }

        synchronized void putMine(String rel, long kept, long discarded, long base) {
            mine.put(rel, new Mine(kept, discarded, base, System.currentTimeMillis(), false));
            dirty = true;
        }

        synchronized void dropMine(String rel) { if (mine.remove(rel) != null) dirty = true; }

        synchronized void sent(String rel) {
            Mine m = mine.get(rel);
            if (m != null && !m.sent) { m.sent = true; dirty = true; }
        }

        /** Accuse de reception du pair pour le choix "contenu garde = kept". */
        synchronized void ack(String rel, long kept) {
            Mine m = mine.get(rel);
            if (m != null && m.kept == kept) { mine.remove(rel); dirty = true; }
        }

        synchronized void putPeer(String rel, long kept, long discarded) {
            peer.put(rel, new Peer(kept, discarded));
            dirty = true;
        }

        /** Rend et retire l'annonce du pair pour ce fichier (consommee par le cadre qui suit). */
        synchronized Peer takePeer(String rel) {
            Peer p = peer.remove(rel);
            if (p != null) dirty = true;
            return p;
        }
    }
}
