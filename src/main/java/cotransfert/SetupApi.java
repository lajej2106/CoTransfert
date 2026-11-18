package cotransfert;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

/**
 * API de reglage de l'IHM web : tout ce que l'utilisateur regle dans la page
 * (role, dossiers, secret, canaux) passe par ici et finit dans cotransfert.conf.
 *
 * Reservee a ce poste. Chaque route exige :
 *   - une adresse distante de boucle locale ;
 *   - un en-tete Host valant localhost, 127.0.0.1 ou [::1] (avec ou sans port) :
 *     un nom etranger resolu vers ce poste est refuse ;
 *   - s'il est present, un en-tete Origin sur ce meme hote : une autre page
 *     ouverte dans le navigateur ne peut pas piloter l'agent ;
 *   - pour un POST : Content-Type application/json et X-CoTransfert: 1 (un
 *     formulaire ou une requete "simple" d'un autre site ne peut pas les poser).
 * Sinon 403. Corps limite a 64 Kio. Aucune valeur secrete n'est journalisee ni
 * renvoyee, sauf par /api/secret/reveal dont c'est l'objet.
 *
 *   GET  /api/config          reglages actuels, sans secret
 *   POST /api/config          valide, ecrit cotransfert.conf (atomique), puis demande la relance (code 75)
 *   POST /api/secret/new      genere un secret, sans l'enregistrer
 *   POST /api/secret/reveal   secret enregistre
 *   POST /api/test            essaie un canal avec les valeurs du formulaire, sans rien enregistrer
 *   POST /api/channel         choisit LE canal de transfert : l'essaie avec les valeurs enregistrees,
 *                             l'enregistre (channel=) puis demande la relance ; "" = ne plus en utiliser
 *   POST /api/browse          selecteur de dossier du poste
 *   POST /api/quit            arret de l'agent (code 0)
 *   POST /api/update/publish  hub : publie la version de ce poste dans le canal par lots choisi
 *   POST /api/update/check    lit la version publiee ; client : dit si c'est une mise a jour
 *   POST /api/update/install  client : telecharge, verifie, puis demande la relance (qui fait l'echange)
 *   GET  /api/log?lines=200   dernieres lignes du journal (1000 au plus) et son chemin
 *   GET  /api/conflicts          conflits en attente de ce poste (toujours 200, liste vide comprise)
 *   POST /api/conflicts/resolve  garde ma version ou celle de l'autre poste, pour un conflit ou pour tous
 *   POST /api/conflicts/open     ouvre l'explorateur de fichiers sur le dossier d'un conflit
 *   POST /api/km/toggle       partage clavier-souris : prend le controle de l'autre poste, ou le rend
 *                             (bouton de la page, et cible du raccourci Windows : voir {@link KmShortcut})
 *   POST /api/km/shortcut     {"create":true|false} : cree ou retire le raccourci Windows de bascule
 *
 * L'arret et la relance passent par une action injectee (dans l'agent : arret,
 * ou redemarrage par {@link Relance}), pour que l'API se teste sans arreter la
 * JVM. Le code 75 transmis a cette action veut dire "redemarrer".
 */
final class SetupApi {

    /** Code de sortie qui demande au lanceur de relancer l'agent (reglages modifies). */
    static final int EXIT_RESTART = 75;
    static final int MAX_BODY = 64 * 1024;
    private static final String FORBIDDEN = "{\"ok\":false,\"message\":\"reserve a ce poste\"}";
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");
    private static final List<String> CONFLICTS = List.of("keep-both", "master-wins", "newer-wins");
    private static final List<String> CLEARABLE = List.of("secret", "webdav.password", "drime.token");
    private static final String NAME_FORBIDDEN = "=/\\:*?\"<>|";

    private final Path conf;
    private final String host;
    private final IntConsumer exit;
    /** Delai entre la reponse et l'arret, en ms (raccourci par les tests). */
    volatile long exitDelayMs = 1000;
    /** Duree maximale d'un essai de canal, en ms. */
    volatile long testTimeoutMs = 20_000;

    /** Mise a jour par le canal ; null = indisponible (essais, modes sans agent complet). */
    volatile Updater updater;
    /** Journal lu par /api/log ; null = celui de l'agent ({@link Journal#path}). */
    volatile Path logFile;

    /** Port de la page de ce poste : c'est lui que vise le raccourci Windows du partage clavier-souris. */
    volatile int webPort = 47790;
    /** Partage clavier-souris ; null = celui de l'agent ({@link Km#get}). */
    volatile Km km;

    /** Creation et retrait du raccourci Windows de bascule (doublure dans les essais). */
    interface Shortcuts {
        KmShortcut.Result create(int webPort, KmSettings.Hotkey hotkey);
        KmShortcut.Result remove();
    }

    volatile Shortcuts shortcuts = new Shortcuts() {
        @Override public KmShortcut.Result create(int port, KmSettings.Hotkey hotkey) { return KmShortcut.create(port, hotkey); }
        @Override public KmShortcut.Result remove() { return KmShortcut.remove(); }
    };
    private final AtomicBoolean shortcutBusy = new AtomicBoolean();

    private final Object saveLock = new Object();
    private final AtomicBoolean exiting = new AtomicBoolean();
    /** Essais de canal simultanes : bornes, pour ne jamais epuiser les fils du serveur web. */
    private final Semaphore tests = new Semaphore(3);
    private final ExecutorService worker = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "cotp-essai");
        t.setDaemon(true);
        return t;
    });

    /**
     * @param conf fichier cotransfert.conf de ce poste
     * @param host nom du poste (affiche par la page)
     * @param exit action d'arret : recoit 75 (relance) ou 0 (quitter)
     */
    SetupApi(Path conf, String host, IntConsumer exit) {
        this.conf = conf.toAbsolutePath();
        this.host = host == null ? "" : host;
        this.exit = exit;
    }

    private interface Route { String apply(Map<String, Object> body, boolean get) throws IOException; }

    private static final String NO_UPDATE = "mise a jour indisponible dans ce mode de lancement";

    void register(HttpServer srv) {
        route(srv, "/api/config", true, (b, get) -> get ? configJson(Config.load(conf)) : save(b));
        route(srv, "/api/secret/new", false, (b, get) -> "{\"secret\":" + Status.q(Crypto.generateSecret()) + "}");
        route(srv, "/api/secret/reveal", false, (b, get) -> "{\"secret\":" + Status.q(Config.load(conf).secret) + "}");
        route(srv, "/api/test", false, (b, get) -> test(b));
        route(srv, "/api/channel", false, (b, get) -> channel(b));
        route(srv, "/api/browse", false, (b, get) -> browse(b));
        route(srv, "/api/quit", false, (b, get) -> { later(0, Math.min(exitDelayMs, 300)); return "{\"ok\":true}"; });
        route(srv, "/api/update/publish", false, (b, get) -> updatePublish());
        route(srv, "/api/update/check", false, (b, get) -> updateCheck());
        route(srv, "/api/update/install", false, (b, get) -> updateInstall());
        srv.createContext("/api/log", this::log);
        route(srv, "/api/conflicts", true, false, (b, get) -> Conflicts.json());
        route(srv, "/api/conflicts/resolve", false, true, (b, get) -> conflictsResolve(b));
        route(srv, "/api/conflicts/open", false, true, (b, get) -> conflictsOpen(b));
        route(srv, "/api/km/toggle", false, (b, get) -> kmToggle());
        route(srv, "/api/km/shortcut", false, (b, get) -> kmShortcut(b));
    }

    // =======================================================================
    //  Partage clavier-souris (voir Km, KmShortcut)
    // =======================================================================

    private static final String KM_UNAVAILABLE = "partage clavier-souris indisponible sur ce poste";

    /** POST /api/km/toggle : {"ok","message","state"}. Refus propre si la fonction est desactivee ou inutilisable. */
    private String kmToggle() {
        try {
            Km k = km != null ? km : Km.get();
            return k.toggle().json();
        } catch (RuntimeException | LinkageError e) {
            return result(false, KM_UNAVAILABLE);
        }
    }

    /**
     * POST /api/km/shortcut : {"create":true} cree (ou remplace) le raccourci Windows qui porte
     * la touche de raccourci enregistree ; {"create":false} le retire. Rien n'est cree sans demande.
     */
    private String kmShortcut(Map<String, Object> body) {
        if (!(body.get("create") instanceof Boolean create)) return msg("create : vrai ou faux attendu");
        if (!shortcutBusy.compareAndSet(false, true)) return msg("une demande est deja en cours");
        try {
            if (!create) return shortcuts.remove().json();
            KmSettings.Hotkey hotkey = KmSettings.from(Config.load(conf).km, null).hotkey;
            if (hotkey == null) return msg("aucun raccourci clavier n'est regle");
            return shortcuts.create(webPort, hotkey).json();
        } catch (RuntimeException | LinkageError e) {
            return msg("raccourci Windows indisponible sur ce poste");
        } finally {
            shortcutBusy.set(false);
        }
    }

    // =======================================================================
    //  Conflits en attente (voir Conflicts)
    // =======================================================================

    /**
     * POST /api/conflicts/resolve : {"root","copy","keep"} pour un conflit,
     * {"all":true,"keep"} pour tous, {"all":true,"root","keep"} pour ceux d'un
     * dossier. keep vaut "mine" ou "theirs". Chaque echec est rendu avec son
     * dossier, sa copie et sa raison ; ok est faux des qu'il y en a un.
     */
    private static String conflictsResolve(Map<String, Object> body) {
        Object keep = body.get("keep");
        boolean all = Boolean.TRUE.equals(body.get("all"));
        String root = body.get("root") instanceof String s ? s : null;
        String copy = body.get("copy") instanceof String s ? s : null;
        if (!"mine".equals(keep) && !"theirs".equals(keep))
            return "{\"ok\":false,\"resolved\":0,\"failed\":[],\"message\":\"keep attendu : mine ou theirs\"}";
        if (body.get("all") != null && !(body.get("all") instanceof Boolean))
            return "{\"ok\":false,\"resolved\":0,\"failed\":[],\"message\":\"all : vrai ou faux attendu\"}";
        if (body.get("root") != null && root == null)
            return new Conflicts.Outcome(0, List.of(new Conflicts.Failure("", copy == null ? "" : copy, Conflicts.MSG_UNKNOWN_ROOT))).json();
        boolean mine = "mine".equals(keep);
        if (all) return Conflicts.resolveAll(root, mine).json();
        if (root == null) return new Conflicts.Outcome(0, List.of(new Conflicts.Failure("", copy == null ? "" : copy, Conflicts.MSG_UNKNOWN_ROOT))).json();
        if (copy == null) return new Conflicts.Outcome(0, List.of(new Conflicts.Failure(root, "", Conflicts.MSG_UNKNOWN_COPY))).json();
        return Conflicts.resolve(root, copy, mine).json();
    }

    /** POST /api/conflicts/open : ouvre l'explorateur de fichiers sur le dossier de la copie. */
    private static String conflictsOpen(Map<String, Object> body) {
        String root = body.get("root") instanceof String s ? s : null;
        String copy = body.get("copy") instanceof String s ? s : null;
        Object[] where = Conflicts.locate(root, copy);
        if (where[0] == null) return result(false, (String) where[1]);
        boolean ok;
        try {
            ok = DesktopSupport.openFolder((Path) where[0]);
        } catch (Throwable t) {
            ok = false;   // JRE sans module graphique
        }
        return ok ? "{\"ok\":true}" : result(false, "indisponible");
    }

    // =======================================================================
    //  Mise a jour par le canal, journal
    // =======================================================================

    private String updatePublish() {
        Updater u = updater;
        Updater.Result r = u == null ? new Updater.Result(false, NO_UPDATE) : u.publish();
        return result(r.ok(), r.message());
    }

    private String updateCheck() {
        Updater u = updater;
        Updater.Check c = u == null ? new Updater.Check(false, false, "", NO_UPDATE) : u.check();
        return "{\"ok\":" + c.ok() + ",\"available\":" + c.available() + ",\"version\":" + Status.q(c.version())
                + ",\"message\":" + Status.q(c.message()) + "}";
    }

    /** Telecharge et verifie ; en cas de succes l'agent redemarre et la relance met la nouvelle version en place. */
    private String updateInstall() {
        Updater u = updater;
        Updater.Result r = u == null ? new Updater.Result(false, NO_UPDATE) : u.install();
        if (!r.ok()) return result(false, r.message());
        later(EXIT_RESTART, exitDelayMs);
        return "{\"ok\":true,\"restart\":true}";
    }

    /** GET /api/log?lines=200 : memes gardes que les reglages (le journal cite des chemins de ce poste). */
    private void log(HttpExchange ex) {
        try {
            if (!local(ex)) { send(ex, 403, FORBIDDEN); return; }
            if (!"/api/log".equals(ex.getRequestURI().getPath())) { send(ex, 404, msg("route inconnue")); return; }
            if (!"GET".equals(ex.getRequestMethod())) {
                ex.getResponseHeaders().set("Allow", "GET");
                send(ex, 405, msg("methode non permise"));
                return;
            }
            int lines = 200;
            String query = ex.getRequestURI().getRawQuery();
            if (query != null) for (String kv : query.split("&")) {
                if (!kv.startsWith("lines=")) continue;
                try { lines = Integer.parseInt(kv.substring(6)); } catch (NumberFormatException e) { /* valeur par defaut */ }
            }
            lines = Math.max(1, Math.min(lines, 1000));
            Path f = logFile != null ? logFile : Journal.path();
            StringBuilder sb = new StringBuilder("{\"path\":").append(Status.q(f == null ? "" : f.toString())).append(",\"lines\":[");
            boolean first = true;
            for (String l : Journal.tail(f, lines)) {
                if (!first) sb.append(',');
                first = false;
                sb.append(Status.q(l));
            }
            send(ex, 200, sb.append("]}").toString());
        } catch (IOException | RuntimeException e) {
            try { send(ex, 500, msg("erreur interne")); } catch (IOException | RuntimeException ignore) { }
        } finally {
            ex.close();
        }
    }

    // =======================================================================
    //  Gardes communes
    // =======================================================================

    private void route(HttpServer srv, String path, boolean allowGet, Route fn) {
        route(srv, path, allowGet, true, fn);
    }

    private void route(HttpServer srv, String path, boolean allowGet, boolean allowPost, Route fn) {
        srv.createContext(path, ex -> {
            try {
                if (!local(ex)) { send(ex, 403, FORBIDDEN); return; }
                if (!path.equals(ex.getRequestURI().getPath())) { send(ex, 404, msg("route inconnue")); return; }
                String m = ex.getRequestMethod();
                boolean get = "GET".equals(m);
                if (!("POST".equals(m) && allowPost) && !(get && allowGet)) {
                    ex.getResponseHeaders().set("Allow", !allowPost ? "GET" : allowGet ? "GET, POST" : "POST");
                    send(ex, 405, msg("methode non permise"));
                    return;
                }
                Map<String, Object> body = Map.of();
                if (!get) {
                    String ct = ex.getRequestHeaders().getFirst("Content-Type");
                    String mime = ct == null ? "" : ct.split(";", 2)[0].trim();
                    if (!"1".equals(trim(ex.getRequestHeaders().getFirst("X-CoTransfert")))
                            || !"application/json".equalsIgnoreCase(mime)) { send(ex, 403, FORBIDDEN); return; }
                    byte[] raw = body(ex);
                    if (raw == null) { send(ex, 413, msg("corps trop volumineux (64 Kio au plus)")); return; }
                    body = object(raw);
                    if (body == null) { send(ex, 400, msg("JSON invalide (objet attendu)")); return; }
                }
                send(ex, 200, fn.apply(body, get));
            } catch (IOException | RuntimeException e) {
                // Jamais le detail : il pourrait citer une valeur saisie.
                try { send(ex, 500, msg("erreur interne")); } catch (IOException | RuntimeException ignore) { }
            } finally {
                ex.close();
            }
        });
    }

    /** Vrai si la requete vient de ce poste, pour ce poste, depuis la page de ce poste. */
    private static boolean local(HttpExchange ex) {
        InetAddress from = ex.getRemoteAddress() == null ? null : ex.getRemoteAddress().getAddress();
        if (from == null || !from.isLoopbackAddress()) return false;
        List<String> hosts = ex.getRequestHeaders().get("Host");
        if (hosts == null || hosts.size() != 1) return false;
        String h = hosts.get(0).trim().toLowerCase(Locale.ROOT);
        if (!LOCAL_HOSTS.contains(hostOnly(h))) return false;
        List<String> origins = ex.getRequestHeaders().get("Origin");
        if (origins == null) return true;
        return origins.size() == 1 && origins.get(0).trim().toLowerCase(Locale.ROOT).equals("http://" + h);
    }

    /** "localhost:47790" devient "localhost", "[::1]:47790" devient "[::1]" ; "" si le port n'est pas un nombre. */
    private static String hostOnly(String h) {
        int end = h.startsWith("[") ? h.indexOf(']') + 1 : (h.indexOf(':') < 0 ? h.length() : h.indexOf(':'));
        if (end <= 0) return "";
        String port = h.substring(end);
        if (!port.isEmpty() && !port.matches(":[0-9]{1,5}")) return "";
        return h.substring(0, end);
    }

    /** Corps de la requete, ou null au-dela de 64 Kio. */
    private static byte[] body(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] b = in.readNBytes(MAX_BODY + 1);
            if (b.length <= MAX_BODY) return b;
            // Trop gros : on absorbe encore un peu (1 Mio au plus) pour que l'appelant lise le refus proprement.
            byte[] sink = new byte[8192];
            long left = 1 << 20;
            int n;
            while (left > 0 && (n = in.read(sink, 0, (int) Math.min(sink.length, left))) > 0) left -= n;
            return null;
        }
    }

    /** Objet JSON du corps ({} si le corps est vide), ou null s'il est illisible. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(byte[] raw) {
        String s = new String(raw, StandardCharsets.UTF_8);
        if (s.isBlank()) return Map.of();
        try {
            return DrimeRelayStore.parseJson(s) instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static void send(HttpExchange ex, int status, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(b); }
    }

    private static String msg(String m) { return "{\"ok\":false,\"message\":" + Status.q(m) + "}"; }

    private static String result(boolean ok, String m) { return "{\"ok\":" + ok + ",\"message\":" + Status.q(m) + "}"; }

    private void later(int code, long delayMs) {
        if (!exiting.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            try { Thread.sleep(Math.max(0, delayMs)); } catch (InterruptedException e) { /* on sort quand meme */ }
            exit.accept(code);
        }, "cotp-arret");
        t.setDaemon(true);
        t.start();
    }

    // =======================================================================
    //  GET /api/config
    // =======================================================================

    private String configJson(Config c) {
        boolean master = c.master != null ? c.master : "hub".equals(c.role);
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"configured\":").append(c.configured())
          .append(",\"role\":").append(Status.q(c.role))
          .append(",\"master\":").append(master)
          .append(",\"conflict\":").append(Status.q(c.conflict))
          .append(",\"roots\":[");
        boolean first = true;
        for (Map.Entry<String, String> e : c.roots.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"name\":").append(Status.q(e.getKey())).append(",\"path\":").append(Status.q(e.getValue()))
              .append(",\"exists\":").append(isDir(e.getValue())).append('}');
        }
        sb.append("],\"secretSet\":").append(!c.secret.isBlank())
          .append(",\"channel\":").append(Status.q(Config.channelId(c.channel)))
          .append(",\"webdav\":{\"url\":").append(Status.q(c.webdavUrl)).append(",\"user\":").append(Status.q(c.webdavUser))
          .append(",\"passwordSet\":").append(!c.webdavPassword.isBlank())
          .append("},\"drime\":{\"tokenSet\":").append(!c.drimeToken.isBlank())
          .append(",\"folder\":").append(Status.q(c.drimeFolder))
          .append("},\"relay\":").append(Status.q(c.relay))
          .append(",\"peer\":").append(Status.q(c.peer))
          .append(",\"discovery\":").append(Status.q(c.discovery))
          .append(",\"proxy\":").append(Status.q(c.proxy))
          .append(",\"truststore\":").append(Status.q(c.tlsTruststore))
          .append(",\"updateAuto\":").append(c.updateAuto)
          .append(",\"km\":").append(KmSettings.from(c.km, null).json())
          .append(",\"configPath\":").append(Status.q(conf.toString()))
          .append(",\"host\":").append(Status.q(host)).append('}');
        return sb.toString();
    }

    private static boolean isDir(String path) {
        try { return Files.isDirectory(Paths.get(path)); } catch (InvalidPathException | SecurityException e) { return false; }
    }

    // =======================================================================
    //  POST /api/config
    // =======================================================================

    /** Erreurs de validation : une par champ, dans l'ordre de detection. */
    private static final class Errors {
        final LinkedHashMap<String, String> list = new LinkedHashMap<>();
        void add(String field, String message) { list.putIfAbsent(field, message); }
        boolean none() { return list.isEmpty(); }
        String json() {
            StringBuilder sb = new StringBuilder("{\"ok\":false,\"errors\":[");
            boolean first = true;
            for (Map.Entry<String, String> e : list.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"field\":").append(Status.q(e.getKey())).append(",\"message\":").append(Status.q(e.getValue())).append('}');
            }
            return sb.append("]}").toString();
        }
    }

    private String save(Map<String, Object> body) {
        synchronized (saveLock) {
            Config c = Config.load(conf);
            Errors err = new Errors();
            List<Path> toCreate = new ArrayList<>();
            List<String> fields = new ArrayList<>();
            apply(c, body, err, toCreate, fields);
            if (!err.none()) return err.json();
            // Dossiers absents : crees seulement quand tout le reste est valide.
            for (int i = 0; i < toCreate.size(); i++) {
                try {
                    if (toCreate.get(i) != null) Files.createDirectories(toCreate.get(i));
                } catch (IOException | RuntimeException e) {
                    err.add(fields.get(i), "dossier introuvable et impossible a creer");
                }
            }
            if (!err.none()) return err.json();
            try {
                c.write(conf);
            } catch (IOException | RuntimeException e) {
                return msg("ecriture de cotransfert.conf impossible (dossier protege en ecriture ?)");
            }
            later(EXIT_RESTART, exitDelayMs);
            return "{\"ok\":true,\"restart\":true}";
        }
    }

    /** Reporte dans c les champs presents de body ; les champs absents restent inchanges. */
    private static void apply(Config c, Map<String, Object> body, Errors err, List<Path> toCreate, List<String> createFields) {
        String role = text(body, "role", "role", err);
        if (role != null) {
            role = role.toLowerCase(Locale.ROOT);
            if (role.equals("hub") || role.equals("client")) c.role = role;
            else err.add("role", "role attendu : hub ou client");
        } else if (!c.role.equals("hub") && !c.role.equals("client")) {
            err.add("role", "role requis : hub ou client");
        }

        if (body.containsKey("master")) {
            Object m = body.get("master");
            // La valeur par defaut du role (hub maitre, client non) n'est pas figee dans le fichier.
            if (m == null) c.master = null;
            else if (m instanceof Boolean b) c.master = b == "hub".equals(c.role) ? null : b;
            else err.add("master", "vrai ou faux attendu");
        }

        String conflict = text(body, "conflict", "conflict", err);
        if (conflict != null) {
            conflict = conflict.toLowerCase(Locale.ROOT);
            if (CONFLICTS.contains(conflict)) c.conflict = conflict;
            else err.add("conflict", "politique attendue : keep-both, master-wins ou newer-wins");
        }

        if (body.get("roots") != null) roots(c, body.get("roots"), err, toCreate, createFields);

        // Secrets : "clear" efface, une valeur non vide remplace, absent ou vide = inchange.
        Object clear = body.get("clear");
        if (clear != null) {
            if (!(clear instanceof List<?> l)) err.add("clear", "liste attendue");
            else for (Object o : l) {
                if (!(o instanceof String s) || !CLEARABLE.contains(s)) { err.add("clear", "valeurs possibles : secret, webdav.password, drime.token"); continue; }
                switch (s) {
                    case "secret" -> c.secret = "";
                    case "webdav.password" -> c.webdavPassword = "";
                    default -> c.drimeToken = "";
                }
            }
        }
        String secret = text(body, "secret", "secret", err);
        if (secret != null && !secret.isEmpty()) {
            if (secret.length() < 16) err.add("secret", "secret trop court (16 caracteres minimum)");
            else c.secret = secret;
        }

        // "channels" (ancienne bascule automatique) et "channel" sont ignores ici : le canal
        // se choisit par POST /api/channel, qui l'essaie avant de l'enregistrer.

        Map<?, ?> w = sub(body, "webdav", err);
        if (w != null) {
            String url = text(w, "url", "webdav.url", err), user = text(w, "user", "webdav.user", err),
                    pw = text(w, "password", "webdav.password", err);
            if (url != null) c.webdavUrl = url;
            if (user != null) c.webdavUser = user;
            if (pw != null && !pw.isEmpty()) c.webdavPassword = pw;
        }
        Map<?, ?> d = sub(body, "drime", err);
        if (d != null) {
            String token = text(d, "token", "drime.token", err), folder = text(d, "folder", "drime.folder", err);
            if (token != null && !token.isEmpty()) {
                if (!printable(token)) err.add("drime.token", "jeton invalide (ni espace ni accent)");
                else c.drimeToken = token;
            }
            if (folder != null) {
                String f = folder.replace('\\', '/');
                while (f.startsWith("/")) f = f.substring(1);
                while (f.endsWith("/")) f = f.substring(0, f.length() - 1);
                boolean bad = false;
                for (String seg : f.split("/")) if (seg.equals(".") || seg.equals("..") || (seg.isEmpty() && !f.isEmpty())) bad = true;
                if (bad) err.add("drime.folder", "dossier de transit invalide");
                else c.drimeFolder = f.isEmpty() ? Config.DEFAULT_DRIME_FOLDER : f;
            }
        }

        String relay = text(body, "relay", "relay", err);
        if (relay != null) {
            String why = relayProblem(relay);
            if (why != null) err.add("relay", why);
            else c.relay = relay;
        }

        String peer = text(body, "peer", "peer", err);
        if (peer != null) {
            if (peer.length() > 255 || !peer.matches("[A-Za-z0-9._:\\-\\[\\]%]*")) err.add("peer", "nom ou adresse du hub invalide");
            else c.peer = peer;
        }
        String discovery = text(body, "discovery", "discovery", err);
        if (discovery != null) {
            discovery = discovery.toLowerCase(Locale.ROOT);
            if (discovery.equals("link") || discovery.equals("lan")) c.discovery = discovery;
            else err.add("discovery", "decouverte attendue : link ou lan");
        }
        String proxy = text(body, "proxy", "proxy", err);
        if (proxy != null) {
            String p = proxy.isEmpty() ? "system" : proxy;
            if (p.equalsIgnoreCase("system") || p.equalsIgnoreCase("none")) c.proxy = p.toLowerCase(Locale.ROOT);
            else if (proxyOk(p)) c.proxy = p;
            else err.add("proxy", "proxy attendu : system, none ou hote:port");
        }
        String trust = text(body, "truststore", "truststore", err);
        if (trust != null) {
            String t = trust.isEmpty() ? "java" : trust.toLowerCase(Locale.ROOT);
            if (t.equals("java") || t.equals("windows")) c.tlsTruststore = t;
            else err.add("truststore", "certificats attendus : java ou windows");
        }

        if (body.get("updateAuto") != null) {
            if (body.get("updateAuto") instanceof Boolean b) c.updateAuto = b;
            else err.add("updateAuto", "vrai ou faux attendu");
        }

        Map<?, ?> k = sub(body, "km", err);
        if (k != null) km(c, k, err);

        // Coherence de l'ensemble, sur les valeurs finales (posees ou deja enregistrees),
        // que le canal soit choisi ou non : on peut regler plusieurs canaux, un seul sert.
        if (!c.webdavUrl.isEmpty()) {
            String why = webdavUrlProblem(c.webdavUrl);
            if (why != null) err.add("webdav.url", why);
            if (c.webdavUser.isEmpty()) err.add("webdav.user", "identifiant requis");
            if (c.webdavPassword.isEmpty()) err.add("webdav.password", "mot de passe d'application requis");
        }
        if ((c.webdavConfigured() || c.drimeConfigured()) && c.secret.isBlank())
            err.add("secret", "secret partage obligatoire pour les canaux en ligne (WebDAV, Drime)");
    }

    /**
     * Sous-objet "km" : {"enabled","edge","delay","hotkey","returnButton"}, chaque champ
     * facultatif. Les erreurs portent le nom de la cle de cotransfert.conf (km.edge, ...).
     */
    private static void km(Config c, Map<?, ?> k, Errors err) {
        java.util.Properties p = KmSettings.from(c.km, null).toProperties();
        Object enabled = k.get("enabled");
        if (enabled instanceof Boolean b) p.setProperty(KmSettings.K_ENABLED, String.valueOf(b));
        else if (enabled != null) err.add(KmSettings.K_ENABLED, "vrai ou faux attendu");
        String edge = text(k, "edge", KmSettings.K_EDGE, err);
        if (edge != null) p.setProperty(KmSettings.K_EDGE, edge.isEmpty() ? "aucun" : edge);
        String hotkey = text(k, "hotkey", KmSettings.K_HOTKEY, err);
        if (hotkey != null) p.setProperty(KmSettings.K_HOTKEY, hotkey.isEmpty() ? "aucun" : hotkey);
        kmNumber(k, "delay", KmSettings.K_DELAY, p, err);
        kmNumber(k, "returnButton", KmSettings.K_RETURN, p, err);
        Object clipboard = k.get("clipboard");
        if (clipboard instanceof Boolean b) p.setProperty(KmSettings.K_CLIPBOARD, String.valueOf(b));
        else if (clipboard != null) err.add(KmSettings.K_CLIPBOARD, "vrai ou faux attendu");
        List<String> problems = new ArrayList<>();
        KmSettings s = KmSettings.from(p, problems);
        for (String pb : problems) {
            int at = pb.indexOf(" : ");
            err.add(at > 0 ? pb.substring(0, at) : "km", at > 0 ? pb.substring(at + 3) : pb);
        }
        if (!problems.isEmpty()) return;
        c.km.clear();
        c.km.putAll(s.toProperties());
    }

    private static void kmNumber(Map<?, ?> k, String key, String field, java.util.Properties p, Errors err) {
        Object o = k.get(key);
        if (o == null) return;
        if (o instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue()) && Math.abs(n.doubleValue()) < 1e9) {
            p.setProperty(field, String.valueOf(n.longValue()));
        } else {
            err.add(field, "nombre entier attendu");
        }
    }

    private static void roots(Config c, Object value, Errors err, List<Path> toCreate, List<String> createFields) {
        if (!(value instanceof List<?> l)) { err.add("roots", "liste attendue"); return; }
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        Set<String> names = new HashSet<>(), paths = new HashSet<>();
        for (int i = 0; i < l.size(); i++) {
            String fn = "roots[" + i + "].name", fp = "roots[" + i + "].path";
            if (!(l.get(i) instanceof Map<?, ?> r)) { err.add("roots[" + i + "]", "nom et chemin attendus"); continue; }
            String name = text(r, "name", fn, err), path = text(r, "path", fp, err);
            boolean nameOk = false;
            if (name == null || name.isEmpty()) { if (!err.list.containsKey(fn)) err.add(fn, "nom requis"); }
            else if (name.length() > 100) err.add(fn, "nom trop long (100 caracteres au plus)");
            else if (!nameAllowed(name)) err.add(fn, "caracteres interdits dans le nom : = / \\ : * ? \" < > |");
            else if (!names.add(name.toLowerCase(Locale.ROOT))) err.add(fn, "nom deja utilise");
            else nameOk = true;

            Path p = null;
            if (path == null || path.isEmpty()) { if (!err.list.containsKey(fp)) err.add(fp, "chemin requis"); }
            else {
                try { p = Paths.get(path); } catch (InvalidPathException e) { err.add(fp, "chemin invalide"); }
            }
            if (p != null && !p.isAbsolute()) { err.add(fp, "chemin absolu requis (ex. C:\\work\\MonProjet)"); p = null; }
            if (p != null) {
                p = p.normalize();
                try {
                    if (!paths.add(p.toString().toLowerCase(Locale.ROOT))) { err.add(fp, "dossier deja utilise sous un autre nom"); p = null; }
                    else if (Files.isDirectory(p)) { /* present */ }
                    else if (Files.exists(p)) { err.add(fp, "ce chemin designe un fichier, pas un dossier"); p = null; }
                    else {
                        // Absent : on pourra le creer si son premier parent existant est un dossier.
                        Path a = p.getParent();
                        while (a != null && !Files.exists(a)) a = a.getParent();
                        if (a == null || !Files.isDirectory(a)) { err.add(fp, "dossier introuvable"); p = null; }
                        else { toCreate.add(p); createFields.add(fp); }
                    }
                } catch (SecurityException e) {
                    err.add(fp, "dossier introuvable");
                    p = null;
                }
            }
            if (nameOk && p != null) out.put(name, p.toString());
        }
        c.roots.clear();
        c.roots.putAll(out);
    }

    private static boolean nameAllowed(String name) {
        for (int i = 0; i < name.length(); i++) if (NAME_FORBIDDEN.indexOf(name.charAt(i)) >= 0) return false;
        return !name.equals(".") && !name.equals("..") && !name.endsWith(".") && !name.startsWith("#");
    }

    /** Sous-objet (webdav, drime), ou null s'il est absent. */
    private static Map<?, ?> sub(Map<String, Object> body, String key, Errors err) {
        Object o = body.get(key);
        if (o == null) return null;
        if (o instanceof Map<?, ?> m) return m;
        err.add(key, "objet attendu");
        return null;
    }

    /**
     * Champ texte, sans espaces autour ; null s'il est absent. Une valeur qui n'est
     * pas un texte d'une ligne est refusee : le fichier de configuration est en
     * "cle=valeur", une ligne par cle.
     */
    private static String text(Map<?, ?> m, String key, String field, Errors err) {
        Object o = m.get(key);
        if (o == null) return null;
        if (!(o instanceof String s)) { err.add(field, "texte attendu"); return null; }
        s = s.strip();
        if (s.length() > 2048) { err.add(field, "valeur trop longue"); return null; }
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < 0x20 || ch == 0x7f || ch == 0x85 || ch == 0x2028 || ch == 0x2029) { err.add(field, "caractere interdit (saut de ligne ou controle)"); return null; }
        }
        return s;
    }

    private static boolean printable(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) <= ' ' || s.charAt(i) > '~') return false;
        return true;
    }

    private static boolean proxyOk(String p) {
        int i = p.lastIndexOf(':');
        if (i <= 0 || !p.substring(0, i).matches("[A-Za-z0-9._\\-\\[\\]:]+")) return false;
        try {
            int port = Integer.parseInt(p.substring(i + 1));
            return port > 0 && port < 65536;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Dossier relais : vide (canal non configure), "*:\Dossier" ou chemin absolu. Le support peut etre absent. */
    private static String relayProblem(String relay) {
        if (relay.isEmpty()) return null;
        if (relay.startsWith("*:")) {
            String rest = relay.substring(2);
            while (rest.startsWith("\\") || rest.startsWith("/")) rest = rest.substring(1);
            return rest.isEmpty() ? "dossier relais attendu apres *:\\ (jamais la racine d'un lecteur)" : null;
        }
        try {
            return Paths.get(relay).isAbsolute() ? null : "chemin absolu ou *:\\Dossier attendu";
        } catch (InvalidPathException e) {
            return "chemin invalide";
        }
    }

    /** Meme regle que le magasin WebDAV : https obligatoire (http seulement vers la boucle locale), pas d'identifiants. */
    private static String webdavUrlProblem(String url) {
        java.net.http.HttpClient http = HttpSupport.client("none");
        try {
            new WebDavRelayStore(url, "u", "p", http).close();
            return null;
        } catch (IllegalArgumentException e) {
            String m = e.getMessage() == null ? "" : e.getMessage();
            if (m.contains("identifiants")) return "l'adresse ne doit pas contenir d'identifiants";
            if (m.contains("https://")) return "l'adresse doit commencer par https://";
            return "adresse invalide (attendu : https://serveur/chemin/du/dossier)";
        } finally {
            http.shutdownNow();
        }
    }

    // =======================================================================
    //  POST /api/test
    // =======================================================================

    private String test(Map<String, Object> body) {
        String ch = body.get("channel") instanceof String s ? s.trim().toLowerCase(Locale.ROOT) : "";
        if (!ch.equals("webdav") && !ch.equals("drime") && !ch.equals("disque"))
            return result(false, "canal inconnu (webdav, drime ou disque)");
        // Valeurs du formulaire par-dessus la configuration enregistree ; un secret absent ou vide = celui enregistre.
        Config c = Config.load(conf);
        Errors err = new Errors();
        Map<?, ?> w = sub(body, "webdav", err), d = sub(body, "drime", err);
        if (w != null) {
            String url = text(w, "url", "webdav.url", err), user = text(w, "user", "webdav.user", err),
                    pw = text(w, "password", "webdav.password", err);
            if (url != null) c.webdavUrl = url;
            if (user != null) c.webdavUser = user;
            if (pw != null && !pw.isEmpty()) c.webdavPassword = pw;
        }
        if (d != null) {
            String token = text(d, "token", "drime.token", err), folder = text(d, "folder", "drime.folder", err);
            if (token != null && !token.isEmpty()) c.drimeToken = token;
            if (folder != null) c.drimeFolder = folder.isEmpty() ? Config.DEFAULT_DRIME_FOLDER : folder;
        }
        String relay = text(body, "relay", "relay", err), proxy = text(body, "proxy", "proxy", err),
                trust = text(body, "truststore", "truststore", err);
        if (relay != null) c.relay = relay;
        if (proxy != null && !proxy.isEmpty()) c.proxy = proxy;
        if (trust != null && !trust.isEmpty()) c.tlsTruststore = trust.toLowerCase(Locale.ROOT);
        if (!err.none()) return result(false, "valeur invalide : " + err.list.keySet().iterator().next());
        c.relays.clear();
        String[] r = runProbe(c, ch);
        return result("ok".equals(r[0]), r[1]);
    }

    /**
     * Essaie un canal par lots avec cette configuration : au plus testTimeoutMs,
     * au plus trois essais a la fois. Chemin commun a /api/test (valeurs du
     * formulaire) et a /api/channel (valeurs enregistrees).
     * @return {"ok" ou "ko", message sans aucune valeur sensible}
     */
    private String[] runProbe(Config c, String ch) {
        if (!tests.tryAcquire()) return new String[] { "ko", "d'autres essais sont en cours, reessayer dans un instant" };
        Future<String[]> f;
        try {
            f = worker.submit(() -> {
                try { return probe(c, ch); } finally { tests.release(); }
            });
        } catch (RuntimeException e) {
            tests.release();
            return new String[] { "ko", "essai impossible" };
        }
        String[] r;
        try {
            r = f.get(testTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            r = new String[] { "ko", "pas de reponse (delai depasse)" };
        } catch (InterruptedException | ExecutionException e) {
            r = new String[] { "ko", "essai impossible" };
        }
        String m = r[1] == null || r[1].isBlank() ? "indisponible" : r[1];
        // Ceinture et bretelles : aucune valeur sensible dans le message.
        for (String v : new String[] { c.secret, c.webdavPassword, c.drimeToken })
            if (v != null && v.trim().length() >= 4) m = m.replace(v.trim(), "***");
        return new String[] { r[0], m };
    }

    /** Construit le magasin comme en production (Agent.Channels) et teste sa joignabilite. */
    private static String[] probe(Config c, String ch) {
        Agent.Channels chs = null;
        try {
            if (!ch.equals("disque")) HttpSupport.init(c.tlsTruststore, c.proxy);
            chs = new Agent.Channels(c, true);
            String why = chs.why(ch, "");
            if (!why.isEmpty()) {
                if (why.equals("non configure")) {
                    why = switch (ch) {
                        case "webdav" -> "adresse, identifiant et mot de passe requis";
                        case "drime" -> "jeton requis";
                        default -> "dossier relais requis";
                    };
                }
                return new String[] { "ko", why };
            }
            try (RelayStore s = chs.store(ch, "")) {
                return s.available() ? new String[] { "ok", "joignable" } : new String[] { "ko", s.lastError() };
            }
        } catch (RuntimeException e) {
            return new String[] { "ko", "essai impossible" };
        } finally {
            if (chs != null) chs.shutdown();
        }
    }

    // =======================================================================
    //  POST /api/channel
    // =======================================================================

    /**
     * Choix du canal de transfert, {"channel":"drime","force":false}.
     *   - nom inconnu : refuse ; "" : deselection, enregistree, relance ;
     *   - canal non regle, ou canal en ligne sans secret partage : needsConfig, rien n'est enregistre ;
     *   - "direct" ne s'essaie pas (le cable peut etre branche plus tard) : accepte, "tested":false ;
     *   - les autres sont essayes comme par /api/test, avec les valeurs enregistrees ;
     *     essai KO : rien n'est enregistre, "canForce" ; "force":true choisit sans essai ;
     *   - canal deja choisi : simple essai, ni ecriture ni relance ("restart":false).
     */
    private String channel(Map<String, Object> body) {
        if (!(body.get("channel") instanceof String raw)) return msg("canal inconnu");
        String ch = raw.trim().toLowerCase(Locale.ROOT);
        if (!ch.isEmpty() && !Config.KNOWN_CHANNELS.contains(ch)) return msg("canal inconnu");
        boolean force = Boolean.TRUE.equals(body.get("force"));

        Config c = Config.load(conf);
        if (ch.isEmpty()) return select("", true, null, "");

        // Canal non regle, ou en ligne sans secret : a regler d'abord (forcer n'y change rien).
        String relay = c.relay;
        if (ch.equals("disque") && relay.isBlank()) {
            // Pas de relais par defaut : celui du premier dossier qui en a un en propre.
            for (String n : c.roots.keySet()) if (relay.isBlank() && c.diskConfigured(n)) relay = c.relayFor(n);
        }
        boolean set = switch (ch) {
            case "webdav" -> c.webdavConfigured();
            case "drime" -> c.drimeConfigured();
            case "disque" -> !relay.isBlank();
            default -> true;
        };
        if (!set) return "{\"ok\":false,\"needsConfig\":true,\"message\":\"canal non regle\"}";
        if ((ch.equals("webdav") || ch.equals("drime")) && c.secret.isBlank())
            return "{\"ok\":false,\"needsConfig\":true,\"message\":\"secret partage manquant\"}";

        boolean already = ch.equals(Config.channelId(c.channel));
        if (ch.equals("direct")) return select(ch, !already, false, "non teste : le lien direct se branche quand on veut");
        if (force) return select(ch, !already, false, "choisi sans essai");

        // Meme essai que /api/test, avec les valeurs enregistrees. Hors verrou : il peut durer.
        c.relay = relay;
        c.relays.clear();
        String[] r = runProbe(c, ch);
        if (!"ok".equals(r[0])) {
            String why = ch.equals("disque") && "support absent".equals(r[1]) ? "disque absent" : r[1];
            return "{\"ok\":false,\"canForce\":true,\"message\":" + Status.q(why) + "}";
        }
        return select(ch, !already, true, r[1]);
    }

    /**
     * Enregistre channel= (sauf si write est faux : canal deja choisi) et repond.
     * La configuration est relue sous verrou : seule la cle channel change.
     */
    private String select(String ch, boolean write, Boolean tested, String message) {
        if (write) {
            synchronized (saveLock) {
                Config c = Config.load(conf);
                c.channel = ch;
                try {
                    c.write(conf);
                } catch (IOException | RuntimeException e) {
                    return msg("ecriture de cotransfert.conf impossible (dossier protege en ecriture ?)");
                }
                later(EXIT_RESTART, exitDelayMs);
            }
        }
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"restart\":").append(write);
        if (tested != null) sb.append(",\"tested\":").append(tested).append(",\"message\":").append(Status.q(message));
        return sb.append('}').toString();
    }

    // =======================================================================
    //  POST /api/browse
    // =======================================================================

    private static String browse(Map<String, Object> body) {
        String start = body.get("start") instanceof String s ? s : "";
        String p;
        try {
            p = DesktopSupport.chooseFolder(start);
        } catch (Throwable t) {
            p = null;   // JRE sans module graphique
        }
        return p == null ? "{\"error\":\"indisponible\"}" : "{\"path\":" + Status.q(p) + "}";
    }

    private static String trim(String s) { return s == null ? "" : s.trim(); }
}
