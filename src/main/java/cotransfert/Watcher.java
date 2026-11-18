package cotransfert;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * BRIQUE 2 - Surveillance du systeme de fichiers.
 *
 * S'appuie sur {@link java.nio.file.WatchService}, qui sous Windows est adosse a
 * l'API native ReadDirectoryChangesW. L'enregistrement est recursif : chaque
 * sous-dossier est enregistre, et tout nouveau dossier cree est enregistre a la
 * volee.
 *
 * Les evenements bruts (CREATE / MODIFY / DELETE) sont coalesces par chemin sur
 * une fenetre de debounce : un compilateur peut reecrire un .class plusieurs
 * fois en quelques millisecondes, on ne veut emettre qu'un seul evenement. Au
 * moment du flush, le type final est deduit de l'existence du fichier
 * (existe -> UPSERT, sinon DELETE), ce qui absorbe aussi les sequences
 * create+delete rapides.
 */
public final class Watcher {

    public enum Kind { UPSERT, DELETE }   // CREATE+MODIFY fusionnes en UPSERT

    public static final class Event {
        public final Kind kind;
        public final String rel;   // chemin relatif a la racine, separateurs '/'
        public final Path abs;     // chemin absolu
        public Event(Kind kind, String rel, Path abs) { this.kind = kind; this.rel = rel; this.abs = abs; }
    }

    public interface Listener { void onEvent(Event e); }

    private final Path root;
    private final Predicate<String> excludeRel;
    private final long debounceMs;
    private final Listener listener;

    private WatchService ws;
    private Thread pump;
    // cle de surveillance -> dossier surveille (pour reconstruire le chemin absolu)
    private final Map<WatchKey, Path> keys = new HashMap<>();
    // flush en attente par chemin absolu (pour le debounce)
    private final Map<Path, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
    private ScheduledExecutorService debouncer;
    private volatile boolean running;

    public Watcher(Path root, Predicate<String> excludeRel, long debounceMs, Listener listener) {
        this.root = root.toAbsolutePath().normalize();
        this.excludeRel = excludeRel;
        this.debounceMs = debounceMs;
        this.listener = listener;
    }

    public void start() throws IOException {
        this.ws = FileSystems.getDefault().newWatchService();
        this.debouncer = Executors.newSingleThreadScheduledExecutor(daemon("cotp-debounce"));
        running = true;
        registerTree(root);

        pump = new Thread(this::pumpLoop, "cotp-watcher");
        pump.setDaemon(true);
        pump.start();
        Agent.log("watcher: surveillance de %s (%d dossiers)", root, keys.size());
    }

    public void close() {
        running = false;
        try { if (ws != null) ws.close(); } catch (IOException ignore) { }
        if (debouncer != null) debouncer.shutdownNow();
        if (pump != null) pump.interrupt();
    }

    // --- Enregistrement recursif -------------------------------------------

    private void registerTree(Path start) {
        if (!Files.isDirectory(start)) return;
        try {
            Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String rel = toRel(dir);
                    // On ne descend pas dans les dossiers exclus (.cotransfert, .git, target, ...).
                    if (!rel.isEmpty() && isExcluded(rel)) return FileVisitResult.SKIP_SUBTREE;
                    registerDir(dir);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE; // un fichier/dossier disparu en cours de route : on ignore
                }
            });
        } catch (IOException e) {
            Agent.log("watcher: parcours de %s impossible (%s)", start, e.getMessage());
        }
    }

    private void registerDir(Path dir) {
        try {
            WatchKey key = dir.register(ws,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            keys.put(key, dir);
        } catch (IOException e) {
            Agent.log("watcher: enregistrement de %s impossible (%s)", dir, e.getMessage());
        }
    }

    // --- Boucle d'ecoute ----------------------------------------------------

    private void pumpLoop() {
        while (running) {
            WatchKey key;
            try {
                key = ws.take();
            } catch (ClosedWatchServiceException | InterruptedException e) {
                return; // arret demande
            }
            Path dir = keys.get(key);
            if (dir != null) {
                for (WatchEvent<?> ev : key.pollEvents()) {
                    WatchEvent.Kind<?> k = ev.kind();
                    if (k == StandardWatchEventKinds.OVERFLOW) {
                        // Trop d'evenements perdus : on re-scanne le sous-arbre pour ne rien manquer.
                        Agent.log("watcher: OVERFLOW sous %s, re-scan", dir);
                        registerTree(dir);
                        continue;
                    }
                    Path child = dir.resolve((Path) ev.context());
                    onRaw(child, k == StandardWatchEventKinds.ENTRY_CREATE);
                }
            }
            boolean valid = key.reset();
            if (!valid) keys.remove(key); // dossier supprime : la cle devient invalide
        }
    }

    /**
     * Traite un evenement brut sur un chemin enfant.
     * @param created vrai si l'evenement est un ENTRY_CREATE (pour l'enregistrement dynamique de dossier)
     */
    private void onRaw(Path child, boolean created) {
        String rel = toRel(child);
        if (rel.isEmpty() || isExcluded(rel)) return;

        // Un dossier nouvellement cree doit etre enregistre (et son contenu balaye,
        // car une arborescence entiere peut apparaitre avant qu'on l'enregistre).
        if (Files.isDirectory(child)) {
            if (created) {
                registerTree(child);
                // Les fichiers deja presents a l'interieur ne generent pas forcement
                // d'evenement : on les emet nous-memes comme UPSERT.
                emitExistingFiles(child);
            }
            return; // on n'emet jamais d'evenement pour un dossier
        }
        scheduleFlush(child, rel);
    }

    private void emitExistingFiles(Path dir) {
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                    String rel = toRel(d);
                    return (!rel.isEmpty() && isExcluded(rel)) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String rel = toRel(file);
                    if (!rel.isEmpty() && !isExcluded(rel)) scheduleFlush(file, rel);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignore) { }
    }

    // --- Debounce -----------------------------------------------------------

    private void scheduleFlush(Path abs, String rel) {
        if (!running) return;
        try {
            // Reprogramme : annule le flush precedent pour ce chemin s'il n'a pas encore eu lieu.
            ScheduledFuture<?> prev = pending.put(abs, debouncer.schedule(
                    () -> flush(abs, rel), debounceMs, TimeUnit.MILLISECONDS));
            if (prev != null) prev.cancel(false);
        } catch (java.util.concurrent.RejectedExecutionException ignore) {
            // debouncer en cours d'arret
        }
    }

    private void flush(Path abs, String rel) {
        pending.remove(abs);
        if (!running) return;
        try {
            Kind kind = Files.exists(abs) ? Kind.UPSERT : Kind.DELETE;
            // Un dossier qui aurait atterri ici (cas de course) ne doit pas etre emis.
            if (kind == Kind.UPSERT && Files.isDirectory(abs)) return;
            listener.onEvent(new Event(kind, rel, abs));
        } catch (Exception e) {
            // Robustesse : toute I/O qui echoue est traitee comme une suppression.
            try { listener.onEvent(new Event(Kind.DELETE, rel, abs)); } catch (Exception ignore) { }
        }
    }

    // --- Helpers ------------------------------------------------------------

    private String toRel(Path abs) {
        Path r = root.relativize(abs.toAbsolutePath().normalize());
        return r.toString().replace('\\', '/');
    }

    private boolean isExcluded(String rel) {
        // Toujours exclure le dossier interne de l'agent.
        if (rel.equals(".cotransfert") || rel.startsWith(".cotransfert/")) return true;
        return excludeRel != null && excludeRel.test(rel);
    }

    private static ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }
}
