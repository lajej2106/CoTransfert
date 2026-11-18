package cotransfert;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Faux serveur Drime pour les tests, en memoire, sur 127.0.0.1 (ports ephemeres).
 * Reproduit les formes de requete et de reponse documentees des points d'API
 * utilises par {@link DrimeRelayStore} :
 *
 *   GET  /users/me                          POST /folders
 *   GET  /drive/file-entries (pagine)       POST /uploads (multipart/form-data)
 *   GET  /file-entries/download/{hash}      PUT  /file-entries/{id} (renommage)
 *   POST /file-entries/delete               POST /s3/multipart/create | batch-sign-part-urls | complete | abort
 *   POST /s3/entries
 *
 * Deux serveurs : l'API ({@link #apiUrl()}, jeton Bearer exige) et le "stockage"
 * ({@link #storageUrl()}, autre origine) qui sert les URL signees des parts et
 * les telechargements rediriges. Le stockage refuse toute requete portant un
 * en-tete Authorization et la compte dans {@link #authLeaks()}.
 *
 * Fidele a l'API : identifiants numeriques croissants, hash = base64url("id|"),
 * noms en double admis pour les fichiers, corbeille distincte de la suppression
 * definitive. Usage :
 *
 *   FakeDrimeServer srv = new FakeDrimeServer("jeton-de-test");
 *   RelayStore s = new DrimeRelayStore(srv.apiUrl(), "jeton-de-test", "CoTransfertRelais", 0, HttpSupport.client("none"));
 *   ... srv.entries(), srv.trash(), srv.count("POST /uploads") ...
 *   srv.stop();
 */
public final class FakeDrimeServer {

    /** Vue d'une entree (fichier ou dossier) du faux stockage. */
    public record Item(long id, String name, boolean folder, Long parentId, long size, String hash, boolean trashed, long workspaceId) { }

    private static final class E {
        long id, ws, created, updated;
        String name;
        boolean folder, trashed;
        Long parent;
        byte[] data = new byte[0];
    }

    private static final class Up {
        String key;
        final Map<Integer, byte[]> parts = new TreeMap<>();
    }

    private static final long PART_MIN = 5L << 20;

    private final HttpServer apiSrv, stoSrv;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "faux-drime");
        t.setDaemon(true);
        return t;
    });
    private final String sig = UUID.randomUUID().toString().replace("-", "");

    // etat, garde par this
    private final Map<Long, E> all = new LinkedHashMap<>();
    private final Map<String, Up> uploads = new HashMap<>();
    private final Map<String, byte[]> objects = new HashMap<>();
    private final Map<String, Integer> counters = new TreeMap<>();
    private long nextId = 488_163_000L, clock = 1;
    private String token;
    private boolean down, rangeSupported = true, redirectDownloads = true, renameSupported = true, duplicateFolders = false;
    private int failStatus, failCount, failRetryAfter, mutationStatus, requests, authLeaks;
    private long quota = -1;

    /** Demarre le faux serveur ; seul ce jeton est accepte. */
    public FakeDrimeServer(String token) throws IOException {
        this.token = token;
        InetAddress lo = InetAddress.getByName("127.0.0.1");
        apiSrv = HttpServer.create(new InetSocketAddress(lo, 0), 64);
        stoSrv = HttpServer.create(new InetSocketAddress(lo, 0), 64);
        apiSrv.createContext("/", x -> guard(x, true));
        stoSrv.createContext("/", x -> guard(x, false));
        apiSrv.setExecutor(pool);
        stoSrv.setExecutor(pool);
        apiSrv.start();
        stoSrv.start();
    }

    // --- API publique -------------------------------------------------------

    /** Base de l'API, a passer a DrimeRelayStore : http://127.0.0.1:port/api/v1 */
    public String apiUrl() { return "http://127.0.0.1:" + apiSrv.getAddress().getPort() + "/api/v1"; }

    /** Origine du faux stockage (URL signees, telechargements rediriges). */
    public String storageUrl() { return "http://127.0.0.1:" + stoSrv.getAddress().getPort(); }

    public void stop() {
        apiSrv.stop(0);
        stoSrv.stop(0);
        pool.shutdownNow();
    }

    /** Change le jeton accepte (les requetes avec l'ancien recoivent 401). */
    public synchronized void setToken(String t) { token = t; }

    /** true : les deux serveurs coupent la connexion sans repondre (panne reseau). */
    public synchronized void setDown(boolean d) { down = d; }

    /** Les count prochaines requetes d'API (count < 0 : toutes) repondent status ; retryAfterSec > 0 ajoute Retry-After. */
    public synchronized void failAll(int status, int count, int retryAfterSec) { failStatus = status; failCount = count; failRetryAfter = retryAfterSec; }

    /** Toute requete d'API autre que GET repond status (403 : jeton en lecture seule) jusqu'a clearFailures(). */
    public synchronized void failMutations(int status) { mutationStatus = status; }

    public synchronized void clearFailures() { failStatus = 0; failCount = 0; failRetryAfter = 0; mutationStatus = 0; }

    /** Espace total autorise en octets, corbeille comprise (&lt; 0 : illimite). Depassement : 422 a la creation de l'entree. */
    public synchronized void setQuotaBytes(long q) { quota = q; }

    /** false : l'en-tete Range est ignore (reponse 200 complete). Defaut : true. */
    public synchronized void setRangeSupported(boolean b) { rangeSupported = b; }

    /** true (defaut) : le telechargement redirige (302) vers le stockage ; false : contenu servi par l'API. */
    public synchronized void setRedirectDownloads(boolean b) { redirectDownloads = b; }

    /** false : PUT /file-entries/{id} repond 405. Defaut : true. */
    public synchronized void setRenameSupported(boolean b) { renameSupported = b; }

    /** true : POST /folders accepte un nom deja pris (deux dossiers homonymes) ; false (defaut) : 422. */
    public synchronized void setDuplicateFoldersAllowed(boolean b) { duplicateFolders = b; }

    /** Entrees vivantes (hors corbeille), par identifiant croissant. */
    public synchronized List<Item> entries() { return items(false); }

    /** Contenu de la corbeille. */
    public synchronized List<Item> trash() { return items(true); }

    /** Entree vivante la plus recente a ce chemin depuis la racine ("Dossier/Sous/f.lot"), ou null. */
    public synchronized Item find(String path) {
        Long parent = null;
        E cur = null;
        for (String seg : path.split("/")) {
            if (seg.isEmpty()) continue;
            cur = null;
            for (E e : all.values()) {
                if (!e.trashed && e.name.equals(seg) && java.util.Objects.equals(e.parent, parent) && (cur == null || e.id > cur.id)) cur = e;
            }
            if (cur == null) return null;
            parent = cur.id;
        }
        return cur == null ? null : item(cur);
    }

    /** Contenu d'un fichier (vivant ou en corbeille), ou null. */
    public synchronized byte[] content(long id) {
        E e = all.get(id);
        return e == null || e.folder ? null : e.data.clone();
    }

    /** Cree un dossier directement (parentId null : racine), noms en double admis. Renvoie son identifiant. */
    public synchronized long addFolder(Long parentId, String name) { return create(parentId, name, true, new byte[0], 0).id; }

    /** Cree un fichier directement, noms en double admis. Renvoie son identifiant. */
    public synchronized long addFile(Long parentId, String name, byte[] data) { return create(parentId, name, false, data.clone(), 0).id; }

    /** Retire definitivement une entree et son contenu (comme un tiers le ferait). */
    public synchronized void remove(long id) { purge(id); }

    /** Nombre de requetes recues pour une route : "GET /users/me", "GET /drive/file-entries", "POST /uploads",
     *  "POST /folders", "GET /file-entries/download/{hash}", "PUT /file-entries/{id}", "POST /file-entries/delete",
     *  "POST /s3/multipart/create", "POST /s3/entries", "PUT stockage", "GET stockage"... */
    public synchronized int count(String route) { return counters.getOrDefault(route, 0); }

    /** Nombre total de requetes recues par l'API (pannes comprises). */
    public synchronized int requests() { return requests; }

    /** Requetes arrivees au stockage avec un en-tete Authorization (doit rester 0). */
    public synchronized int authLeaks() { return authLeaks; }

    public synchronized void resetCounters() { counters.clear(); requests = 0; }

    // --- aiguillage ---------------------------------------------------------

    private void guard(HttpExchange x, boolean isApi) {
        try {
            boolean cut;
            synchronized (this) { cut = down; }
            if (cut) { x.close(); return; }
            byte[] body = readAll(x.getRequestBody());
            if (isApi) api(x, body); else storage(x, body);
        } catch (Throwable t) {
            try { send(x, 500, "{\"message\":\"Server Error\"}"); } catch (IOException ignore) { }
        } finally {
            x.close();
        }
    }

    private void api(HttpExchange x, byte[] body) throws IOException {
        String method = x.getRequestMethod(), path = x.getRequestURI().getPath();
        Map<String, String> qs = query(x.getRequestURI().getRawQuery());
        if (!path.startsWith("/api/v1/")) { send(x, 404, "{\"message\":\"Not Found\"}"); return; }
        String p = path.substring("/api/v1".length());
        String route = method + " " + (p.startsWith("/file-entries/download/") ? "/file-entries/download/{hash}"
                : p.matches("/file-entries/\\d+") ? "/file-entries/{id}" : p);
        int inj, retry;
        String auth = x.getRequestHeaders().getFirst("Authorization");
        boolean authOk;
        synchronized (this) {
            requests++;
            counters.merge(route, 1, Integer::sum);
            inj = 0;
            retry = failRetryAfter;
            if (failCount != 0) { inj = failStatus; if (failCount > 0) failCount--; }
            else if (mutationStatus != 0 && !method.equals("GET")) inj = mutationStatus;
            authOk = auth != null && auth.equals("Bearer " + token);
        }
        if (inj != 0) {
            if (retry > 0 && (inj == 429 || inj == 503)) x.getResponseHeaders().set("Retry-After", String.valueOf(retry));
            send(x, inj, inj == 429 ? "{\"message\":\"Too Many Attempts.\"}"
                    : inj == 403 ? "{\"message\":\"This API key is missing the required scope: files.write\"}"
                    : inj == 401 ? "{\"message\":\"Unauthenticated.\"}" : "{\"message\":\"Server Error\"}");
            return;
        }
        if (!authOk) { send(x, 401, "{\"message\":\"Unauthenticated.\"}"); return; }
        Map<String, Object> in = new HashMap<>();
        String ctype = x.getRequestHeaders().getFirst("Content-Type");
        if (body.length > 0 && ctype != null && ctype.startsWith("application/json")) {
            Object o;
            try { o = DrimeRelayStore.parseJson(new String(body, StandardCharsets.UTF_8)); }
            catch (IOException e) { send(x, 400, "{\"message\":\"Malformed JSON\"}"); return; }
            if (o instanceof Map<?, ?> m) for (Map.Entry<?, ?> en : m.entrySet()) in.put(String.valueOf(en.getKey()), en.getValue());
        }
        synchronized (this) {
            switch (route) {
                case "GET /users/me" -> send(x, 200, "{\"user\":{\"id\":15843,\"email\":\"user@example.com\",\"display_name\":\"Test\"},\"status\":\"success\"}");
                case "GET /drive/file-entries" -> listEntries(x, qs);
                case "POST /folders" -> createFolder(x, in, qs);
                case "POST /uploads" -> upload(x, body, ctype);
                case "GET /file-entries/download/{hash}" -> download(x, p.substring("/file-entries/download/".length()));
                case "PUT /file-entries/{id}" -> rename(x, Long.parseLong(p.substring("/file-entries/".length())), in);
                case "POST /file-entries/delete" -> delete(x, in);
                case "POST /s3/multipart/create" -> mpCreate(x, in);
                case "POST /s3/multipart/batch-sign-part-urls" -> mpSign(x, in);
                case "POST /s3/multipart/complete" -> mpComplete(x, in);
                case "POST /s3/multipart/abort" -> { uploads.remove(String.valueOf(in.get("uploadId"))); send(x, 200, "{\"status\":\"success\"}"); }
                case "POST /s3/entries" -> s3Entry(x, in);
                default -> send(x, 404, "{\"message\":\"Not Found\"}");
            }
        }
    }

    // --- points d'API -------------------------------------------------------

    private void listEntries(HttpExchange x, Map<String, String> qs) throws IOException {
        int per = Math.max(1, Math.min(100, (int) num(qs.get("perPage"), 50)));
        int page = Math.max(1, (int) num(qs.get("page"), 1));
        long ws = num(qs.get("workspaceId"), 0);
        boolean trashOnly = "true".equals(qs.get("deletedOnly")) || "1".equals(qs.get("deletedOnly"));
        String type = qs.get("type");
        List<Long> parents = new ArrayList<>();
        if (qs.get("parentIds") != null) for (String s : qs.get("parentIds").split(",")) if (!s.isBlank()) parents.add(Long.parseLong(s.trim()));
        String fh = qs.get("folderId") != null ? qs.get("folderId") : qs.get("pageId");
        if (parents.isEmpty() && fh != null && !fh.isEmpty() && !fh.equals("0")) { Long id = unhash(fh); parents.add(id == null ? -1L : id); }
        List<E> sel = new ArrayList<>();
        for (E e : all.values()) {
            if (e.ws != ws || e.trashed != trashOnly) continue;
            if (!trashOnly && (parents.isEmpty() ? e.parent != null : !parents.contains(e.parent))) continue;
            if (type != null && !type.equals(e.folder ? "folder" : "file")) continue;
            sel.add(e);
        }
        boolean asc = "asc".equals(qs.get("orderDir"));
        Comparator<E> c = "name".equals(qs.get("orderBy")) ? Comparator.comparing((E e) -> e.name).thenComparingLong(e -> e.id)
                : Comparator.comparingLong((E e) -> e.updated).thenComparingLong(e -> e.id);
        sel.sort(asc ? c : c.reversed());
        int total = sel.size(), last = Math.max(1, (total + per - 1) / per), from = (page - 1) * per;
        StringBuilder sb = new StringBuilder("{\"current_page\":").append(page).append(",\"data\":[");
        for (int i = from; i < Math.min(total, from + per); i++) sb.append(i == from ? "" : ",").append(json(sel.get(i)));
        E folder = parents.size() == 1 ? all.get(parents.get(0)) : null;
        sb.append("],\"from\":").append(from < total ? String.valueOf(from + 1) : "null").append(",\"last_page\":").append(last)
                .append(",\"per_page\":").append(per).append(",\"to\":").append(from < total ? String.valueOf(Math.min(total, from + per)) : "null")
                .append(",\"total\":").append(total).append(",\"folder\":").append(folder == null ? "null" : json(folder)).append('}');
        send(x, 200, sb.toString());
    }

    private void createFolder(HttpExchange x, Map<String, Object> in, Map<String, String> qs) throws IOException {
        if (!(in.get("name") instanceof String name) || name.isEmpty()) { invalid(x, "name", "The name field is required."); return; }
        Long parent = in.get("parentId") instanceof Number n ? n.longValue() : null;
        if (parent != null && !liveFolder(parent)) { send(x, 404, "{\"message\":\"No query results for model [App\\\\FileEntry] " + parent + "\"}"); return; }
        long ws = num(qs.get("workspaceId"), 0);
        if (!duplicateFolders) {
            for (E e : all.values()) {
                if (e.folder && !e.trashed && e.ws == ws && e.name.equals(name) && java.util.Objects.equals(e.parent, parent)) {
                    invalid(x, "name", "A folder with the same name already exists.");
                    return;
                }
            }
        }
        send(x, 200, "{\"status\":\"success\",\"folder\":" + json(create(parent, name, true, new byte[0], ws)) + "}");
    }

    private void upload(HttpExchange x, byte[] body, String ctype) throws IOException {
        int bi = ctype == null ? -1 : ctype.indexOf("boundary=");
        if (ctype == null || !ctype.startsWith("multipart/form-data") || bi < 0) { invalid(x, "file", "The file field is required."); return; }
        String bnd = ctype.substring(bi + 9).trim();
        if (bnd.startsWith("\"") && bnd.endsWith("\"")) bnd = bnd.substring(1, bnd.length() - 1);
        String raw = new String(body, StandardCharsets.ISO_8859_1), delim = "--" + bnd;
        Map<String, String> fields = new HashMap<>();
        String fileName = null;
        byte[] file = null;
        int pos = raw.indexOf(delim);
        while (pos >= 0) {
            int start = pos + delim.length();
            if (raw.startsWith("--", start)) break;
            int hEnd = raw.indexOf("\r\n\r\n", start), next = raw.indexOf("\r\n" + delim, start);
            if (hEnd < 0 || next < 0 || hEnd > next) { send(x, 400, "{\"message\":\"Malformed multipart body\"}"); return; }
            String head = new String(raw.substring(start, hEnd).getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
            String name = param(head, "name"), fn = param(head, "filename");
            byte[] content = java.util.Arrays.copyOfRange(body, hEnd + 4, next);
            if ("file".equals(name) && fn != null) { fileName = fn; file = content; }
            else if (name != null) fields.put(name, new String(content, StandardCharsets.UTF_8));
            pos = next + 2;
        }
        if (file == null || fileName == null || fileName.isEmpty()) { invalid(x, "file", "The file field is required."); return; }
        String ps = fields.getOrDefault("parentId", "");
        Long parent = ps.isEmpty() || ps.equals("null") ? null : Long.valueOf(ps);
        if (parent != null && !liveFolder(parent)) { send(x, 404, "{\"message\":\"No query results for model [App\\\\FileEntry] " + parent + "\"}"); return; }
        if (overQuota(file.length)) { quotaError(x); return; }
        E e = create(parent, fileName, false, file, num(fields.get("workspaceId"), 0));
        send(x, 201, "{\"status\":\"success\",\"fileEntry\":" + json(e) + "}");
    }

    private void download(HttpExchange x, String hashes) throws IOException {
        Long id = unhash(hashes);
        E e = id == null ? null : all.get(id);
        if (e == null || e.folder) { send(x, 404, "{\"message\":\"No query results for model [App\\\\FileEntry]\"}"); return; }
        if (redirectDownloads) {
            x.getResponseHeaders().set("Location", storageUrl() + "/get/" + e.id + "?X-Amz-Signature=" + sig);
            x.sendResponseHeaders(302, -1);
            return;
        }
        serve(x, e);
    }

    private void serve(HttpExchange x, E e) throws IOException {
        String range = x.getRequestHeaders().getFirst("Range");
        byte[] d = e.data;
        x.getResponseHeaders().set("Content-Type", "application/octet-stream");
        x.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"download\"");
        if (rangeSupported && range != null && range.matches("bytes=\\d+-\\d*")) {
            String[] ab = range.substring(6).split("-");
            long a = Long.parseLong(ab[0]), b = ab.length > 1 ? Long.parseLong(ab[1]) : d.length - 1;
            if (a >= d.length) { x.getResponseHeaders().set("Content-Range", "bytes */" + d.length); x.sendResponseHeaders(416, -1); return; }
            b = Math.min(b, d.length - 1);
            x.getResponseHeaders().set("Content-Range", "bytes " + a + "-" + b + "/" + d.length);
            bytes(x, 206, java.util.Arrays.copyOfRange(d, (int) a, (int) b + 1));
            return;
        }
        bytes(x, 200, d);
    }

    private void rename(HttpExchange x, long id, Map<String, Object> in) throws IOException {
        if (!renameSupported) { send(x, 405, "{\"message\":\"Method Not Allowed\"}"); return; }
        E e = all.get(id);
        if (e == null || e.trashed) { send(x, 404, "{\"message\":\"No query results for model [App\\\\FileEntry] " + id + "\"}"); return; }
        if (in.get("name") instanceof String n && !n.isEmpty()) { e.name = n; e.updated = clock++; }
        send(x, 200, "{\"status\":\"success\",\"fileEntry\":" + json(e) + "}");
    }

    private void delete(HttpExchange x, Map<String, Object> in) throws IOException {
        if (!(in.get("entryIds") instanceof List<?> ids)) { invalid(x, "entryIds", "The entry ids field is required."); return; }
        boolean forever = Boolean.TRUE.equals(in.get("deleteForever"));
        if (ids.isEmpty() && Boolean.TRUE.equals(in.get("emptyTrash"))) {
            for (E e : new ArrayList<>(all.values())) if (e.trashed) purge(e.id);
        }
        for (Object o : ids) {
            long id = o instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(o));
            if (forever) purge(id); else trashTree(id);
        }
        send(x, 200, "{\"status\":\"success\"}");
    }

    private void mpCreate(HttpExchange x, Map<String, Object> in) throws IOException {
        for (String k : new String[] { "filename", "mime", "size", "extension" }) {
            if (in.get(k) == null) { invalid(x, k, "The " + k + " field is required."); return; }
        }
        String uuid = UUID.randomUUID().toString(), uploadId = "UP" + UUID.randomUUID().toString().replace("-", "") + "_-x";
        Up u = new Up();
        u.key = "uploads/" + uuid + "/" + uuid;
        uploads.put(uploadId, u);
        send(x, 200, "{\"key\":" + js(u.key) + ",\"uploadId\":" + js(uploadId) + ",\"acl\":\"private\",\"status\":\"success\"}");
    }

    private void mpSign(HttpExchange x, Map<String, Object> in) throws IOException {
        Up u = uploads.get(String.valueOf(in.get("uploadId")));
        if (u == null || !u.key.equals(in.get("key")) || !(in.get("partNumbers") instanceof List<?> nums)) { invalid(x, "uploadId", "Invalid upload."); return; }
        StringBuilder sb = new StringBuilder("{\"urls\":[");
        int i = 0;
        for (Object o : nums) {
            int n = ((Number) o).intValue();
            sb.append(i++ == 0 ? "" : ",").append("{\"partNumber\":").append(n).append(",\"url\":")
                    .append(js(storageUrl() + "/" + u.key + "?partNumber=" + n + "&uploadId=" + in.get("uploadId") + "&X-Amz-Signature=" + sig)).append('}');
        }
        send(x, 200, sb.append("],\"status\":\"success\"}").toString());
    }

    private void mpComplete(HttpExchange x, Map<String, Object> in) throws IOException {
        Up u = uploads.get(String.valueOf(in.get("uploadId")));
        if (u == null || !u.key.equals(in.get("key")) || !(in.get("parts") instanceof List<?> parts) || parts.isEmpty()) { invalid(x, "parts", "Invalid upload."); return; }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int expect = 1;
        for (Object o : parts) {
            if (!(o instanceof Map<?, ?> pm) || !(pm.get("PartNumber") instanceof Number pn) || !(pm.get("ETag") instanceof String etag)) { invalid(x, "parts", "Invalid part."); return; }
            byte[] d = u.parts.get(pn.intValue());
            // comme S3 : parts dans l'ordre, ETag exact avec ses guillemets, 5 Mio minimum sauf la derniere
            if (pn.intValue() != expect++ || d == null || !etag.equals(etag(d))) { invalid(x, "parts", "InvalidPart"); return; }
            if (expect <= parts.size() && d.length < PART_MIN) { invalid(x, "parts", "EntityTooSmall"); return; }
            bos.writeBytes(d);
        }
        if (expect - 1 != u.parts.size()) { invalid(x, "parts", "InvalidPart"); return; }
        objects.put(u.key, bos.toByteArray());
        uploads.remove(String.valueOf(in.get("uploadId")));
        send(x, 200, "{\"location\":" + js(storageUrl() + "/" + u.key) + ",\"status\":\"success\"}");
    }

    private void s3Entry(HttpExchange x, Map<String, Object> in) throws IOException {
        if (!(in.get("filename") instanceof String fn) || !(in.get("clientName") instanceof String name) || !(in.get("size") instanceof Number size)) {
            invalid(x, "filename", "The filename field is required.");
            return;
        }
        byte[] d = objects.get("uploads/" + fn + "/" + fn);
        if (d == null || d.length != size.longValue()) { invalid(x, "filename", "Uploaded file not found."); return; }
        Long parent = in.get("parentId") instanceof Number n ? n.longValue() : null;
        if (parent != null && !liveFolder(parent)) { send(x, 404, "{\"message\":\"No query results for model [App\\\\FileEntry] " + parent + "\"}"); return; }
        if (overQuota(d.length)) { quotaError(x); return; }
        objects.remove("uploads/" + fn + "/" + fn);
        E e = create(parent, name, false, d, in.get("workspaceId") instanceof Number w ? w.longValue() : 0);
        send(x, 200, "{\"fileEntry\":" + json(e) + ",\"status\":\"success\"}");
    }

    // --- faux stockage (URL signees) ----------------------------------------

    private void storage(HttpExchange x, byte[] body) throws IOException {
        String method = x.getRequestMethod(), path = x.getRequestURI().getPath();
        Map<String, String> qs = query(x.getRequestURI().getRawQuery());
        synchronized (this) {
            counters.merge(method + " stockage", 1, Integer::sum);
            if (x.getRequestHeaders().getFirst("Authorization") != null) {
                authLeaks++;
                bytes(x, 400, "<Error><Code>InvalidArgument</Code></Error>".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (!sig.equals(qs.get("X-Amz-Signature"))) { bytes(x, 403, "<Error><Code>SignatureDoesNotMatch</Code></Error>".getBytes(StandardCharsets.UTF_8)); return; }
            if (method.equals("PUT") && path.startsWith("/uploads/")) {
                Up u = uploads.get(String.valueOf(qs.get("uploadId")));
                if (u == null || !path.equals("/" + u.key)) { bytes(x, 404, "<Error><Code>NoSuchUpload</Code></Error>".getBytes(StandardCharsets.UTF_8)); return; }
                u.parts.put((int) num(qs.get("partNumber"), 0), body);
                x.getResponseHeaders().set("ETag", etag(body));
                x.sendResponseHeaders(200, -1);
                return;
            }
            if (method.equals("GET") && path.startsWith("/get/")) {
                E e = all.get(num(path.substring(5), -1));
                if (e == null || e.folder) { bytes(x, 404, "<Error><Code>NoSuchKey</Code></Error>".getBytes(StandardCharsets.UTF_8)); return; }
                serve(x, e);
                return;
            }
            bytes(x, 404, "<Error><Code>NotFound</Code></Error>".getBytes(StandardCharsets.UTF_8));
        }
    }

    // --- etat ---------------------------------------------------------------

    private E create(Long parent, String name, boolean folder, byte[] data, long ws) {
        E e = new E();
        e.id = nextId++; e.name = name; e.folder = folder; e.parent = parent; e.data = data; e.ws = ws;
        e.created = e.updated = clock++;
        all.put(e.id, e);
        return e;
    }

    private boolean liveFolder(long id) {
        E e = all.get(id);
        return e != null && e.folder && !e.trashed;
    }

    private void purge(long id) {
        if (all.remove(id) == null) return;
        for (E c : new ArrayList<>(all.values())) if (c.parent != null && c.parent == id) purge(c.id);
    }

    private void trashTree(long id) {
        E e = all.get(id);
        if (e == null) return;
        e.trashed = true;
        for (E c : all.values()) if (c.parent != null && c.parent == id && !c.trashed) trashTree(c.id);
    }

    private boolean overQuota(long add) {
        if (quota < 0) return false;
        long used = 0;
        for (E e : all.values()) used += e.data.length;
        return used + add > quota;
    }

    private List<Item> items(boolean trashed) {
        List<Item> out = new ArrayList<>();
        for (E e : all.values()) if (e.trashed == trashed) out.add(item(e));
        return out;
    }

    private static Item item(E e) { return new Item(e.id, e.name, e.folder, e.parent, e.data.length, hash(e.id), e.trashed, e.ws); }

    private static String hash(long id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((id + "|").getBytes(StandardCharsets.US_ASCII));
    }

    private static Long unhash(String h) {
        try {
            String s = new String(Base64.getUrlDecoder().decode(h), StandardCharsets.US_ASCII);
            return s.endsWith("|") ? Long.valueOf(s.substring(0, s.length() - 1)) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String stamp(long tick) {
        return String.format("2024-01-%02dT%02d:%02d:%02d.000000Z", 1 + (tick / 86400) % 28, (tick / 3600) % 24, (tick / 60) % 60, tick % 60);
    }

    private String json(E e) {
        int dot = e.name.lastIndexOf('.');
        return "{\"id\":" + e.id + ",\"name\":" + js(e.name) + ",\"description\":null,\"file_name\":" + js("obj-" + e.id)
                + ",\"mime\":" + (e.folder ? "null" : "\"application/octet-stream\"") + ",\"file_size\":" + e.data.length
                + ",\"parent_id\":" + (e.parent == null ? "null" : e.parent.toString()) + ",\"type\":\"" + (e.folder ? "folder" : "file") + "\""
                + ",\"extension\":" + (e.folder || dot < 0 ? "null" : js(e.name.substring(dot + 1))) + ",\"public\":false,\"workspace_id\":" + e.ws
                + ",\"deleted_at\":" + (e.trashed ? js(stamp(e.updated)) : "null") + ",\"created_at\":" + js(stamp(e.created))
                + ",\"updated_at\":" + js(stamp(e.updated)) + ",\"path\":" + js(e.parent == null ? "" + e.id : e.parent + "/" + e.id)
                + ",\"hash\":" + js(hash(e.id)) + ",\"url\":" + js("api/v1/file-entries/" + e.id) + ",\"tags\":[],\"permissions\":{\"files.update\":true}"
                + ",\"users\":[{\"email\":\"user@example.com\",\"id\":15843,\"owns_entry\":true,\"entry_permissions\":[]}]}";
    }

    /** Chaine JSON a la maniere du serveur reel : non-ASCII en \\uXXXX, barre oblique echappee. */
    private static String js(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') sb.append("\\\"");
            else if (c == '\\') sb.append("\\\\");
            else if (c == '/') sb.append("\\/");
            else if (c < 0x20 || c > 0x7e) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    private static String etag(byte[] d) {
        try {
            StringBuilder sb = new StringBuilder("\"");
            for (byte b : MessageDigest.getInstance("MD5").digest(d)) sb.append(String.format("%02x", b));
            return sb.append('"').toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String param(String head, String name) {
        int i = head.indexOf("; " + name + "=\"");
        if (i < 0) return null;
        int a = i + name.length() + 4, b = head.indexOf('"', a);
        return b < 0 ? null : head.substring(a, b);
    }

    private static long num(String s, long def) {
        try { return s == null || s.isBlank() ? def : Long.parseLong(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String kv : raw.split("&")) {
            int eq = kv.indexOf('=');
            String k = eq < 0 ? kv : kv.substring(0, eq), v = eq < 0 ? "" : kv.substring(eq + 1);
            m.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return m;
    }

    private void quotaError(HttpExchange x) throws IOException {
        send(x, 422, "{\"status\":\"error\",\"message\":\"You have exhausted your allowed space. Delete some files or upgrade your plan.\",\"errors\":{}}");
    }

    private static void invalid(HttpExchange x, String field, String msg) throws IOException {
        send(x, 422, "{\"status\":\"error\",\"message\":\"The given data was invalid.\",\"errors\":{" + js(field) + ":[" + js(msg) + "]}}");
    }

    private static void send(HttpExchange x, int status, String json) throws IOException {
        x.getResponseHeaders().set("Content-Type", "application/json");
        bytes(x, status, json.getBytes(StandardCharsets.UTF_8));
    }

    private static void bytes(HttpExchange x, int status, byte[] b) throws IOException {
        if (b.length == 0) { x.sendResponseHeaders(status, -1); return; }
        x.sendResponseHeaders(status, b.length);
        try (OutputStream os = x.getResponseBody()) { os.write(b); }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (in) { return in.readAllBytes(); }
    }
}
