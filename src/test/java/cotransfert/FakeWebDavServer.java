package cotransfert;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Faux serveur WebDAV pour les tests : en memoire, sur 127.0.0.1 et un port
 * ephemere, bati sur le serveur HTTP du JDK. Aucun service reel n'est contacte.
 *
 * Gere PROPFIND (profondeur 0 ou 1), MKCOL, PUT (409 si le dossier parent
 * manque), GET (avec Range), DELETE, MOVE de fichier (Destination, Overwrite)
 * et l'authentification Basic. Des interrupteurs injectent des pannes.
 *
 * Les chemins passes aux accesseurs sont relatifs au dossier de base, separes
 * par '/', non encodes ; "" designe le dossier de base lui-meme.
 *
 * Exemple :
 *   FakeWebDavServer srv = new FakeWebDavServer("utilisateur", "secret-de-test");
 *   RelayStore store = new WebDavRelayStore(srv.baseUrl(), "utilisateur", "secret-de-test", HttpSupport.client("none"));
 *   ...
 *   srv.stop();
 */
public final class FakeWebDavServer {

    private final String basePath;                 // decode, commence par '/', sans '/' final
    private final String expectedAuth;
    private final int port;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "faux-webdav");
        t.setDaemon(true);
        return t;
    });
    private HttpServer server;                     // null quand le serveur est "en panne"

    private final Object lock = new Object();
    private final TreeMap<String, byte[]> files = new TreeMap<>();
    private final TreeSet<String> dirs = new TreeSet<>();
    private final Map<String, Integer> counts = new TreeMap<>();
    private int total;
    private int failLeft, failStatus;
    private String failMethod, failRetryAfter;
    private boolean absoluteHrefs, ignoreRange;

    /** Serveur dont le dossier de base est "/dav/transit" (deja cree). */
    public FakeWebDavServer(String user, String password) throws IOException {
        this("/dav/transit", user, password);
    }

    /** @param basePath chemin du dossier de base, non encode (espaces et accents admis), par exemple "/dav/Mon dossier". */
    public FakeWebDavServer(String basePath, String user, String password) throws IOException {
        String b = basePath.startsWith("/") ? basePath : "/" + basePath;
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        this.basePath = b;
        this.expectedAuth = HttpSupport.basic(user, password);
        dirs.add("");
        this.server = start(0);
        this.port = server.getAddress().getPort();
    }

    private HttpServer start(int onPort) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), onPort), 50);
        s.createContext("/", this::handle);
        s.setExecutor(pool);
        s.start();
        return s;
    }

    // =======================================================================
    //  API publique
    // =======================================================================

    /** URL (encodee) du dossier de base, sans '/' final : "http://127.0.0.1:port/dav/transit". */
    public String baseUrl() {
        return "http://127.0.0.1:" + port + "/" + HttpSupport.encodePath(basePath);
    }

    /** Arrete le serveur pour de bon. */
    public void stop() {
        synchronized (lock) {
            if (server != null) { server.stop(0); server = null; }
        }
        pool.shutdownNow();
    }

    /** true : le serveur n'ecoute plus (connexion refusee) ; false : il revient sur le meme port, contenu intact. */
    public void setDown(boolean down) throws IOException {
        synchronized (lock) {
            if (down && server != null) { server.stop(0); server = null; }
            else if (!down && server == null) {
                IOException last = null;
                for (int i = 0; i < 50 && server == null; i++) {
                    try { server = start(port); } catch (IOException e) {
                        last = e;
                        try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
                    }
                }
                if (server == null) throw last;
            }
        }
    }

    /**
     * Les n prochaines requetes (de la methode donnee, ou toutes si method est null)
     * repondent status sans rien faire. retryAfter : valeur de l'en-tete Retry-After, ou null.
     * Exemple : failNext("PUT", 1, 507, null) ; failNext(null, 2, 429, "1").
     */
    public void failNext(String method, int n, int status, String retryAfter) {
        synchronized (lock) { failMethod = method; failLeft = n; failStatus = status; failRetryAfter = retryAfter; }
    }

    /** true : les href des reponses PROPFIND sont des URL absolues (sinon des chemins absolus). */
    public void setAbsoluteHrefs(boolean on) { synchronized (lock) { absoluteHrefs = on; } }

    /** true : GET ignore l'en-tete Range et renvoie tout le fichier en 200. */
    public void setIgnoreRange(boolean on) { synchronized (lock) { ignoreRange = on; } }

    /** Nombre total de requetes recues (y compris refusees ou en echec injecte). */
    public int requests() { synchronized (lock) { return total; } }

    /** Nombre de requetes recues pour une methode ("PUT", "PROPFIND"...). */
    public int requests(String method) { synchronized (lock) { return counts.getOrDefault(method, 0); } }

    /** Contenu d'un fichier stocke, ou null s'il est absent. */
    public byte[] file(String relPath) {
        synchronized (lock) { byte[] b = files.get(norm(relPath)); return b == null ? null : b.clone(); }
    }

    /** Chemins de tous les fichiers stockes (y compris les ".part"), tries. */
    public List<String> files() { synchronized (lock) { return new ArrayList<>(files.keySet()); } }

    /** Vrai si ce dossier existe ("" = dossier de base). */
    public boolean hasCollection(String relPath) { synchronized (lock) { return dirs.contains(norm(relPath)); } }

    /** Depose directement un fichier, dossiers parents crees. */
    public void put(String relPath, byte[] data) {
        synchronized (lock) {
            String p = norm(relPath);
            for (int i = p.indexOf('/'); i >= 0; i = p.indexOf('/', i + 1)) dirs.add(p.substring(0, i));
            dirs.add("");
            files.put(p, data.clone());
        }
    }

    /** Supprime directement un fichier, ou un dossier et tout son contenu ("" = le dossier de base lui-meme). */
    public void remove(String relPath) {
        synchronized (lock) {
            String p = norm(relPath);
            files.remove(p);
            if (dirs.remove(p)) {
                String pre = p.isEmpty() ? "" : p + "/";
                files.keySet().removeIf(k -> k.startsWith(pre));
                dirs.removeIf(k -> k.startsWith(pre) && !k.equals(p));
            }
        }
    }

    // =======================================================================
    //  Traitement des requetes
    // =======================================================================

    private void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            byte[] body = ex.getRequestBody().readAllBytes();   // toujours lu en entier avant de repondre
            int injected = 0;
            String retry = null;
            synchronized (lock) {
                total++;
                counts.merge(method, 1, Integer::sum);
                if (failLeft > 0 && (failMethod == null || failMethod.equals(method))) {
                    failLeft--;
                    injected = failStatus;
                    retry = failRetryAfter;
                }
            }
            if (injected != 0) {
                if (retry != null) ex.getResponseHeaders().set("Retry-After", retry);
                if (injected == 401) ex.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"test\"");
                reply(ex, injected, null);
                return;
            }
            if (!expectedAuth.equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                ex.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"test\"");
                reply(ex, 401, null);
                return;
            }
            String rel = relative(ex.getRequestURI().getRawPath());
            if (rel == null) { reply(ex, 404, null); return; }
            synchronized (lock) {
                switch (method) {
                    case "PROPFIND" -> propfind(ex, rel);
                    case "MKCOL" -> {
                        if (dirs.contains(rel) || files.containsKey(rel)) reply(ex, 405, null);
                        else if (!rel.isEmpty() && !dirs.contains(parent(rel))) reply(ex, 409, null);
                        else { dirs.add(rel); reply(ex, 201, null); }   // au-dessus du dossier de base, tout existe
                    }
                    case "PUT" -> {
                        if (rel.isEmpty() || dirs.contains(rel)) reply(ex, 405, null);
                        else if (!dirs.contains(parent(rel))) reply(ex, 409, null);
                        else reply(ex, files.put(rel, body) == null ? 201 : 204, null);
                    }
                    case "GET" -> get(ex, rel);
                    case "DELETE" -> {
                        if (!dirs.contains(rel) && !files.containsKey(rel)) reply(ex, 404, null);
                        else { remove(rel); reply(ex, 204, null); }
                    }
                    case "MOVE" -> move(ex, rel);
                    default -> reply(ex, 405, null);
                }
            }
        } catch (IOException | RuntimeException e) {
            // client parti en cours de reponse (lecture d'un prefixe) : sans importance
        } finally {
            ex.close();
        }
    }

    private void propfind(HttpExchange ex, String rel) throws IOException {
        boolean isDir = dirs.contains(rel);
        if (!isDir && !files.containsKey(rel)) { reply(ex, 404, null); return; }
        String depth = ex.getRequestHeaders().getFirst("Depth");
        // Prefixe d'espace de noms different selon le mode, comme entre deux serveurs reels.
        String d = absoluteHrefs ? "D" : "d";
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<" + d + ":multistatus xmlns:" + d + "=\"DAV:\">\n");
        entry(sb, d, rel);
        if (isDir && !"0".equals(depth)) {
            String pre = rel.isEmpty() ? "" : rel + "/";
            for (String k : dirs) if (!k.equals(rel) && k.startsWith(pre) && k.indexOf('/', pre.length()) < 0) entry(sb, d, k);
            for (String k : files.keySet()) if (k.startsWith(pre) && k.indexOf('/', pre.length()) < 0) entry(sb, d, k);
        }
        sb.append("</" + d + ":multistatus>\n");
        ex.getResponseHeaders().set("Content-Type", "application/xml; charset=utf-8");
        reply(ex, 207, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void entry(StringBuilder sb, String d, String rel) {
        boolean isDir = dirs.contains(rel);
        String enc = HttpSupport.encodePath(basePath + "/" + rel);
        String href = (absoluteHrefs ? "http://127.0.0.1:" + port : "") + "/" + enc + (isDir ? "/" : "");
        sb.append("<" + d + ":response><" + d + ":href>").append(href.replace("&", "&amp;").replace("<", "&lt;"))
          .append("</" + d + ":href><" + d + ":propstat><" + d + ":prop>");
        if (isDir) sb.append("<" + d + ":resourcetype><" + d + ":collection/></" + d + ":resourcetype>");
        else sb.append("<" + d + ":resourcetype/><" + d + ":getcontentlength>").append(files.get(rel).length)
               .append("</" + d + ":getcontentlength>");
        sb.append("</" + d + ":prop><" + d + ":status>HTTP/1.1 200 OK</" + d + ":status></" + d + ":propstat>");
        if (isDir) sb.append("<" + d + ":propstat><" + d + ":prop><" + d + ":getcontentlength/></" + d + ":prop><"
                + d + ":status>HTTP/1.1 404 Not Found</" + d + ":status></" + d + ":propstat>");
        sb.append("</" + d + ":response>\n");
    }

    private void get(HttpExchange ex, String rel) throws IOException {
        byte[] b = files.get(rel);
        if (b == null) { reply(ex, dirs.contains(rel) ? 405 : 404, null); return; }
        String range = ex.getRequestHeaders().getFirst("Range");
        if (range != null && !ignoreRange && range.startsWith("bytes=")) {
            String[] p = range.substring(6).split("-", 2);
            long from = p[0].isEmpty() ? 0 : Long.parseLong(p[0]);
            long to = p.length < 2 || p[1].isEmpty() ? b.length - 1 : Math.min(Long.parseLong(p[1]), b.length - 1);
            if (from >= b.length || to < from) {
                ex.getResponseHeaders().set("Content-Range", "bytes */" + b.length);
                reply(ex, 416, null);
                return;
            }
            ex.getResponseHeaders().set("Content-Range", "bytes " + from + "-" + to + "/" + b.length);
            reply(ex, 206, Arrays.copyOfRange(b, (int) from, (int) to + 1));
            return;
        }
        reply(ex, 200, b);
    }

    private void move(HttpExchange ex, String rel) throws IOException {
        String dest = ex.getRequestHeaders().getFirst("Destination");
        String to = dest == null ? null : relative(URI.create(dest).getRawPath());
        byte[] b = files.get(rel);
        if (b == null) { reply(ex, dirs.contains(rel) ? 403 : 404, null); return; }   // deplacement de dossier non gere
        if (to == null || to.isEmpty()) { reply(ex, 400, null); return; }
        if (!dirs.contains(parent(to))) { reply(ex, 409, null); return; }
        if (dirs.contains(to)) { reply(ex, 412, null); return; }
        boolean existed = files.containsKey(to);
        if (existed && "F".equalsIgnoreCase(ex.getRequestHeaders().getFirst("Overwrite"))) { reply(ex, 412, null); return; }
        files.remove(rel);
        files.put(to, b);
        reply(ex, existed ? 204 : 201, null);
    }

    private static void reply(HttpExchange ex, int status, byte[] body) throws IOException {
        if (body == null) { ex.sendResponseHeaders(status, -1); return; }
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = ex.getResponseBody()) { out.write(body); }
        }
    }

    /** Chemin relatif au dossier de base (decode), ou null si la ressource est en dehors. */
    private String relative(String rawPath) {
        if (rawPath == null) return null;
        StringBuilder sb = new StringBuilder();
        for (String seg : rawPath.split("/")) {
            if (seg.isEmpty()) continue;
            sb.append('/').append(decode(seg));
        }
        String p = sb.toString();
        if (p.equals(basePath)) return "";
        return p.startsWith(basePath + "/") ? p.substring(basePath.length() + 1) : null;
    }

    private static String parent(String rel) {
        int i = rel.lastIndexOf('/');
        return i < 0 ? "" : rel.substring(0, i);
    }

    private static String norm(String rel) {
        String p = rel == null ? "" : rel.replace('\\', '/');
        while (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    private static String decode(String s) {
        byte[] in = s.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
        for (int i = 0; i < in.length; i++) {
            if (in[i] == '%' && i + 2 < in.length) {
                int h = Character.digit((char) in[i + 1], 16), l = Character.digit((char) in[i + 2], 16);
                if (h >= 0 && l >= 0) { out.write((h << 4) | l); i += 2; continue; }
            }
            out.write(in[i]);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
