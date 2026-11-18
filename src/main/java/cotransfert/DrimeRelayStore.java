package cotransfert;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Magasin "drime" : stockage en ligne Drime par son API REST (pas son WebDAV).
 * Uniquement du HTTPS sortant, jeton personnel en en-tete Authorization: Bearer.
 *
 * L'API adresse les fichiers par identifiant et non par chemin : le magasin
 * resout les dossiers niveau par niveau (identifiants memorises) et tient un
 * cache par dossier nom -> (id, hash, taille), alimente par les listes et
 * invalide apres chaque ecriture ou suppression dans ce dossier.
 *
 * Deux entrees de meme nom peuvent coexister dans un dossier : en lecture on
 * garde la plus recente (plus grand identifiant) ; avant d'ecrire un chemin on
 * supprime definitivement les entrees de ce nom. Deux dossiers de meme nom
 * (creation simultanee par les deux postes) : le plus ancien (plus petit
 * identifiant) fait foi des deux cotes, l'autre est supprime par son createur.
 *
 * Ecriture : depot sous "nom.part" puis renommage (PUT /file-entries/{id}) ; si
 * le renommage est refuse, depot direct sous le nom final (le lecteur verifie
 * taille et CRC). Moins de 5 Mio : POST /uploads ; au-dela : depot en parts de
 * 5 Mio par URL signees. Toute suppression est definitive (deleteForever) : le
 * transit ne remplit jamais la corbeille.
 *
 * Le jeton n'est envoye qu'a l'origine de l'API (jamais a l'hote de stockage
 * d'une URL signee ou d'une redirection) et n'apparait dans aucun message.
 */
public final class DrimeRelayStore implements RelayStore {

    static final String DEFAULT_API = "https://app.drime.cloud/api/v1";
    static final String DEFAULT_FOLDER = "CoTransfertRelais";
    /** Seuil documente : en dessous depot simple, a partir de la depot en plusieurs parts. */
    static final long MULTIPART_MIN = 5L << 20;
    private static final int PART = 5 << 20;
    private static final int PER_PAGE = 50, MAX_PAGES = 2000, MAX_REDIRECTS = 5;
    private static final int JSON_MAX = 16 << 20;
    private static final long FOLDER_TTL_MS = 600_000, BLOCK_MS = 600_000, BACKOFF_MIN = 2000, BACKOFF_MAX = 300_000;
    private static final String BUSY = "serveur occupe", QUOTA = "quota plein";
    private static final String NO_RIGHT = "jeton sans le droit necessaire", BAD_TOKEN = "jeton refuse";

    /** Erreur HTTP de l'API (le code sert aux reprises : 404 = identifiant perime). */
    static final class ApiException extends IOException {
        private static final long serialVersionUID = 1L;
        final int status;
        ApiException(int status, String msg) { super(msg); this.status = status; }
    }

    private record Ent(long id, String name, String hash, long size, boolean folder, Long parent, boolean deleted) { }
    private record Dir(long folderId, long at, List<Ent> files) { }
    private interface Src { long size(); byte[] read(long off, int len) throws IOException; }

    private final String api, token, folder, host, cfgError;
    private final URI apiUri;
    private final long workspace;
    private final HttpClient http;
    private final boolean ownClient;

    /** Durees de cache, modifiables par les tests uniquement. */
    volatile long probeTtlMs = 10_000, cacheTtlMs = 4_000;

    private final Object mu = new Object(), probeLock = new Object();
    private final ReentrantLock folderLock = new ReentrantLock();
    private final Map<String, Dir> dirs = new HashMap<>();           // garde par mu
    private final Map<String, long[]> folders = new HashMap<>();     // garde par mu : chemin -> {id, date}
    private long epoch;                                              // garde par mu

    private volatile boolean probeOk, renameOk = true;
    private volatile long probeAt = Long.MIN_VALUE, busyUntil = Long.MIN_VALUE, blockUntil = Long.MIN_VALUE;
    private volatile long backoff = BACKOFF_MIN;
    private volatile String err = "", blockWhy = "";

    /**
     * @param apiUrl      base de l'API ; vide : {@value #DEFAULT_API}
     * @param token       jeton personnel
     * @param folder      dossier de transit a la racine du stockage ; vide : {@value #DEFAULT_FOLDER}
     * @param workspaceId espace de travail (0 = espace personnel)
     * @param http        client HTTP (proxy et certificats deja configures)
     */
    public DrimeRelayStore(String apiUrl, String token, String folder, long workspaceId, HttpClient http) {
        String a = apiUrl == null || apiUrl.isBlank() ? DEFAULT_API : apiUrl.trim();
        while (a.endsWith("/")) a = a.substring(0, a.length() - 1);
        String f = folder == null ? "" : folder.trim().replace('\\', '/');
        while (f.startsWith("/")) f = f.substring(1);
        while (f.endsWith("/")) f = f.substring(0, f.length() - 1);
        this.folder = f.isEmpty() ? DEFAULT_FOLDER : f;
        this.token = token == null ? "" : token.trim();
        this.workspace = workspaceId;
        String bad = null, h = "?";
        URI u = null;
        try {
            u = URI.create(a);
            if (u.getHost() == null || u.getScheme() == null || u.getRawQuery() != null || u.getRawUserInfo() != null) bad = "URL de l'API invalide";
            else { h = u.getHost(); if (!safe(u)) bad = "URL de l'API refusee (https obligatoire)"; }
        } catch (IllegalArgumentException e) {
            bad = "URL de l'API invalide";
        }
        if (bad == null && this.token.isEmpty()) bad = "jeton manquant";
        if (bad == null) {
            for (int i = 0; i < this.token.length(); i++) {
                char c = this.token.charAt(i);
                if (c <= ' ' || c > '~') { bad = "jeton invalide"; break; }
            }
        }
        this.api = a; this.apiUri = u; this.host = h; this.cfgError = bad;
        // Redirections suivies a la main : le jeton ne doit jamais partir vers un autre hote.
        if (http.followRedirects() == HttpClient.Redirect.NEVER) { this.http = http; this.ownClient = false; }
        else { this.http = withoutRedirects(http); this.ownClient = true; }
    }

    /** Meme client (proxy, certificats, delais), sans suivi automatique des redirections. */
    private static HttpClient withoutRedirects(HttpClient src) {
        HttpClient.Builder b = HttpClient.newBuilder().version(src.version()).followRedirects(HttpClient.Redirect.NEVER)
                .sslContext(src.sslContext()).sslParameters(src.sslParameters());
        src.connectTimeout().ifPresent(b::connectTimeout);
        src.proxy().ifPresent(b::proxy);
        src.authenticator().ifPresent(b::authenticator);
        src.cookieHandler().ifPresent(b::cookieHandler);
        src.executor().ifPresent(b::executor);
        return b.build();
    }

    // --- contrat RelayStore -------------------------------------------------

    @Override public String id() { return "drime"; }

    @Override public String describe() {
        return "Drime " + host + ", dossier " + folder + (workspace != 0 ? ", espace " + workspace : "");
    }

    @Override public long pollMs() { return 10_000; }

    @Override public long maxLotBytes() { return 16L << 20; }

    @Override public String lastError() {
        if (cfgError != null) return cfgError;
        long n = now();
        if (n < blockUntil) return blockWhy;
        if (n < busyUntil) return BUSY;
        return err;
    }

    @Override public boolean available() {
        if (cfgError != null) return false;
        try {
            synchronized (probeLock) {
                long n = now();
                if (n < busyUntil || n < blockUntil) return false;
                if (probeAt != Long.MIN_VALUE && n - probeAt < probeTtlMs) return probeOk;
                try {
                    Map<String, Object> m = api("GET", "/users/me", null);
                    if (!(m.get("user") instanceof Map)) down("reponse inattendue du serveur");
                } catch (IOException e) {
                    String m = e.getMessage() == null ? "" : e.getMessage();
                    if (err.isEmpty()) err = m.startsWith("drime : ") ? m.substring(8) : "erreur reseau";
                    probeOk = false;
                }
                probeAt = now();
                n = now();
                return probeOk && n >= busyUntil && n >= blockUntil;
            }
        } catch (RuntimeException e) {
            probeOk = false;
            err = "erreur interne";
            return false;
        }
    }

    @Override public List<Entry> list(String dir) throws IOException {
        Dir d = dir(norm(dir), false, 0);
        if (d == null) return new ArrayList<>();
        Map<String, Ent> best = new TreeMap<>();
        for (Ent e : d.files) {
            if (e.name.endsWith(".part")) continue;
            Ent o = best.get(e.name);
            if (o == null || e.id > o.id) best.put(e.name, e);
        }
        List<Entry> out = new ArrayList<>();
        for (Ent e : best.values()) out.add(new Entry(e.name, e.size));
        return out;
    }

    @Override public byte[] read(String path, int maxBytes) throws IOException {
        String[] dn = split(path);
        Dir d = dir(dn[0], false, cacheTtlMs);
        Ent e = d == null ? null : newest(d, dn[1]);
        if (e == null) return null;
        byte[] b = download(e, maxBytes);
        if (b != null) return b;
        // 404 : entree remplacee depuis la derniere liste ; on relit le dossier une fois
        invalidate(dn[0]);
        d = dir(dn[0], false, 0);
        Ent e2 = d == null ? null : newest(d, dn[1]);
        if (e2 == null || e2.id == e.id) return null;
        return download(e2, maxBytes);
    }

    @Override public void write(String path, Path src) throws IOException {
        try (FileChannel ch = FileChannel.open(src, StandardOpenOption.READ)) {
            final long size = ch.size();
            put(path, new Src() {
                @Override public long size() { return size; }
                @Override public byte[] read(long off, int len) throws IOException {
                    ByteBuffer bb = ByteBuffer.allocate(len);
                    long p = off;
                    while (bb.hasRemaining()) {
                        int k = ch.read(bb, p);
                        if (k < 0) throw new IOException("drime : fichier source raccourci pendant le depot");
                        p += k;
                    }
                    return bb.array();
                }
            });
        }
    }

    @Override public void write(String path, byte[] data) throws IOException {
        final byte[] d = data == null ? new byte[0] : data;
        put(path, new Src() {
            @Override public long size() { return d.length; }
            @Override public byte[] read(long off, int len) { return java.util.Arrays.copyOfRange(d, (int) off, (int) off + len); }
        });
    }

    @Override public void delete(String path) throws IOException {
        String[] dn = split(path);
        Dir d = dir(dn[0], false, cacheTtlMs);
        if (d == null) return;
        List<Long> ids = new ArrayList<>();
        for (Ent e : d.files) if (e.name.equals(dn[1])) ids.add(e.id);
        if (ids.isEmpty()) {
            // cache peut-etre perime : une liste fraiche avant de conclure a l'absence
            d = dir(dn[0], false, 0);
            if (d == null) return;
            for (Ent e : d.files) if (e.name.equals(dn[1])) ids.add(e.id);
            if (ids.isEmpty()) return;
        }
        try {
            deleteIds(ids);
        } catch (IOException e) {
            invalidate(dn[0]);
            throw e;
        }
        // le cache du dossier est mis a jour sur place (equivaut a l'invalider pour ce nom)
        synchronized (mu) {
            Dir cur = dirs.get(dn[0]);
            if (cur != null) {
                List<Ent> keep = new ArrayList<>();
                for (Ent e : cur.files) if (!ids.contains(e.id)) keep.add(e);
                dirs.put(dn[0], new Dir(cur.folderId, cur.at, keep));
            }
            epoch++;
        }
    }

    @Override public void close() {
        if (ownClient) {
            try { http.shutdownNow(); } catch (RuntimeException ignore) { }
        }
    }

    // --- ecriture -----------------------------------------------------------

    private void put(String path, Src src) throws IOException {
        String[] dn = split(path);
        try {
            for (int attempt = 0; ; attempt++) {
                try {
                    put0(dn[0], dn[1], src);
                    return;
                } catch (ApiException e) {
                    // dossier supprime par ailleurs : identifiants perimes, on resout a nouveau une fois
                    boolean quota = e.getMessage() != null && e.getMessage().endsWith(QUOTA);
                    if ((e.status != 404 && e.status != 422) || quota || attempt > 0) throw e;
                    forgetFolders();
                }
            }
        } finally {
            invalidate(dn[0]);
        }
    }

    private void put0(String dir, String name, Src src) throws IOException {
        long fid = folder(dir, true);
        List<Ent> cur = listAll(fid, false);
        boolean viaPart = renameOk;
        String tmp = name + ".part";
        List<Long> stale = new ArrayList<>(), old = new ArrayList<>();
        for (Ent e : cur) {
            if (e.name.equals(tmp)) stale.add(e.id);
            else if (e.name.equals(name)) old.add(e.id);
        }
        if (!stale.isEmpty()) deleteIds(stale);
        if (viaPart) {
            Ent up = upload(fid, tmp, src);
            if (!old.isEmpty()) deleteIds(old);
            try {
                rename(up.id, name);
                return;
            } catch (ApiException e) {
                if (e.status != 400 && e.status != 404 && e.status != 405 && e.status != 422 && e.status != 501) throw e;
                renameOk = false;                       // renommage indisponible : depot direct desormais
                deleteIds(List.of(up.id));
            }
        } else if (!old.isEmpty()) {
            deleteIds(old);
        }
        Ent up = upload(fid, name, src);
        if (up.name != null && !up.name.equals(name)) {
            // le serveur a choisi un autre nom : on tente de le corriger, sinon on ne laisse rien
            try { rename(up.id, name); }
            catch (ApiException e) { deleteIds(List.of(up.id)); throw new IOException("drime : nom de fichier modifie par le serveur"); }
        }
    }

    private Ent upload(long fid, String name, Src src) throws IOException {
        long size = src.size();
        Map<String, Object> m = size < MULTIPART_MIN ? uploadSimple(fid, name, src) : uploadParts(fid, name, src);
        Ent e = m.get("fileEntry") instanceof Map<?, ?> fe ? ent(fe) : null;
        if (e == null) {
            // reponse sans entree exploitable : on la retrouve par son nom
            for (Ent x : listAll(fid, false)) if (x.name.equals(name) && (e == null || x.id > e.id)) e = x;
            if (e == null) throw new IOException("drime : depot non confirme par le serveur");
        }
        Object sz = m.get("fileEntry") instanceof Map<?, ?> fe2 ? fe2.get("file_size") : null;
        if (sz instanceof Number n && n.longValue() != size) {
            deleteIds(List.of(e.id));
            throw new IOException("drime : depot incomplet (taille differente sur le serveur)");
        }
        return e;
    }

    /** POST /uploads en multipart/form-data (champs parentId, workspaceId, file). */
    private Map<String, Object> uploadSimple(long fid, String name, Src src) throws IOException {
        String bnd = "----cotransfert" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream bos = new ByteArrayOutputStream((int) src.size() + 600);
        StringBuilder h = new StringBuilder();
        h.append("--").append(bnd).append("\r\nContent-Disposition: form-data; name=\"parentId\"\r\n\r\n").append(fid).append("\r\n");
        h.append("--").append(bnd).append("\r\nContent-Disposition: form-data; name=\"workspaceId\"\r\n\r\n").append(workspace).append("\r\n");
        h.append("--").append(bnd).append("\r\nContent-Disposition: form-data; name=\"file\"; filename=\"")
                .append(name.replace("%", "%25").replace("\"", "%22").replace("\r", "%0D").replace("\n", "%0A"))
                .append("\"\r\nContent-Type: application/octet-stream\r\n\r\n");
        bos.writeBytes(h.toString().getBytes(StandardCharsets.UTF_8));
        bos.writeBytes(src.read(0, (int) src.size()));
        bos.writeBytes(("\r\n--" + bnd + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpResponse<InputStream> r = send("POST", url("/uploads"), HttpRequest.BodyPublishers.ofByteArray(bos.toByteArray()),
                "multipart/form-data; boundary=" + bnd, null, true, 300);
        ok(r, false);
        return json(r);
    }

    /** Depot en parts : create -> URL signees -> PUT des parts -> complete -> entree. */
    private Map<String, Object> uploadParts(long fid, String name, Src src) throws IOException {
        long size = src.size();
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 && dot < name.length() - 1 ? name.substring(dot + 1) : "bin";
        String mime = "application/octet-stream";
        Map<String, Object> c = api("POST", "/s3/multipart/create", "{\"filename\":" + q(name) + ",\"mime\":" + q(mime) + ",\"size\":" + size
                + ",\"extension\":" + q(ext) + ",\"relativePath\":\"\",\"workspaceId\":" + workspace + ",\"parentId\":" + fid + "}");
        String key = str(c, "key"), uploadId = str(c, "uploadId");
        if (key == null || uploadId == null) throw new IOException("drime : reponse inattendue du serveur (depot en parts)");
        String ku = "\"key\":" + q(key) + ",\"uploadId\":" + q(uploadId);
        try {
            int n = (int) ((size + PART - 1) / PART);
            StringBuilder nums = new StringBuilder();
            for (int i = 1; i <= n; i++) nums.append(i == 1 ? "" : ",").append(i);
            Map<Integer, String> urls = signed(api("POST", "/s3/multipart/batch-sign-part-urls", "{" + ku + ",\"partNumbers\":[" + nums + "]}"));
            StringBuilder parts = new StringBuilder();
            for (int i = 1; i <= n; i++) {
                String url = urls.get(i);
                if (url == null) url = signed(api("POST", "/s3/multipart/batch-sign-part-urls", "{" + ku + ",\"partNumbers\":[" + i + "]}")).get(i);
                if (url == null) throw new IOException("drime : URL de depot manquante pour une part");
                long off = (long) (i - 1) * PART;
                byte[] chunk = src.read(off, (int) Math.min(PART, size - off));
                URI pu;
                try { pu = URI.create(url); } catch (IllegalArgumentException e) { throw new IOException("drime : URL de depot invalide"); }
                HttpResponse<InputStream> r = send("PUT", pu, HttpRequest.BodyPublishers.ofByteArray(chunk), null, null, false, 300);
                ok(r, true);
                r.body().close();
                String etag = r.headers().firstValue("ETag").orElse(null);
                if (etag == null || etag.isBlank()) throw new IOException("drime : part deposee sans ETag");
                etag = etag.trim();
                if (!etag.startsWith("\"") && !etag.startsWith("W/")) etag = "\"" + etag + "\"";
                parts.append(i == 1 ? "" : ",").append("{\"PartNumber\":").append(i).append(",\"ETag\":").append(q(etag)).append('}');
            }
            api("POST", "/s3/multipart/complete", "{" + ku + ",\"parts\":[" + parts + "]}");
        } catch (IOException | RuntimeException e) {
            try { api("POST", "/s3/multipart/abort", "{" + ku + "}"); } catch (IOException | RuntimeException ignore) { }
            throw e;
        }
        return api("POST", "/s3/entries", "{\"workspaceId\":" + workspace + ",\"parentId\":" + fid + ",\"clientMime\":" + q(mime)
                + ",\"clientName\":" + q(name) + ",\"filename\":" + q(key.substring(key.lastIndexOf('/') + 1)) + ",\"size\":" + size
                + ",\"clientExtension\":" + q(ext) + ",\"relativePath\":\"\"}");
    }

    private static Map<Integer, String> signed(Map<String, Object> m) {
        Map<Integer, String> out = new HashMap<>();
        if (m.get("urls") instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> u && u.get("partNumber") instanceof Number pn && u.get("url") instanceof String s) out.put(pn.intValue(), s);
            }
        }
        return out;
    }

    private void rename(long id, String name) throws IOException {
        api("PUT", "/file-entries/" + id + "?workspaceId=" + workspace, "{\"name\":" + q(name) + "}");
    }

    /** Suppression definitive (jamais la corbeille). Identifiants deja absents : sans effet. */
    private void deleteIds(List<Long> ids) throws IOException {
        if (ids.isEmpty()) return;
        StringBuilder sb = new StringBuilder("{\"entryIds\":[");
        for (int i = 0; i < ids.size(); i++) sb.append(i == 0 ? "" : ",").append(ids.get(i));
        sb.append("],\"deleteForever\":true}");
        try {
            api("POST", "/file-entries/delete", sb.toString());
        } catch (ApiException e) {
            if (e.status != 404) throw e;
        }
    }

    // --- lecture ------------------------------------------------------------

    /** Contenu de l'entree (prefixe si maxBytes > 0), ou null si le serveur ne la connait plus. */
    private byte[] download(Ent e, int maxBytes) throws IOException {
        if (e.hash == null || e.hash.isEmpty()) throw new IOException("drime : entree sans identifiant de telechargement");
        HttpResponse<InputStream> r = send("GET", url("/file-entries/download/" + HttpSupport.encodePath(e.hash)), null, null,
                maxBytes > 0 ? "bytes=0-" + (maxBytes - 1) : null, true, 300);
        int s = r.statusCode();
        if (s == 404) { r.body().close(); return null; }
        if (s == 416) { r.body().close(); return new byte[0]; }      // prefixe demande sur un fichier vide
        ok(r, false);
        long cap = maxBytes > 0 ? maxBytes : maxLotBytes() + (1L << 20);
        try (InputStream in = r.body()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            long total = 0;
            while (total < cap) {
                int k = in.read(buf, 0, (int) Math.min(buf.length, cap - total));
                if (k < 0) return bos.toByteArray();
                bos.write(buf, 0, k);
                total += k;
            }
            // une reponse 200 complete a une demande de prefixe est tronquee ici (on arrete la lecture)
            if (maxBytes > 0 || in.read() < 0) return bos.toByteArray();
            throw new IOException("drime : fichier trop gros pour un lot");
        } catch (IOException x) {
            if (x.getMessage() != null && x.getMessage().startsWith("drime : ")) throw x;
            String why = clean(HttpSupport.explain(x));
            down(why);
            throw new IOException("drime : " + why, x);
        }
    }

    private static Ent newest(Dir d, String name) {
        Ent best = null;
        for (Ent e : d.files) if (e.name.equals(name) && (best == null || e.id > best.id)) best = e;
        return best;
    }

    // --- dossiers et listes -------------------------------------------------

    /** Liste du dossier (toutes les entrees fichier, doublons et .part compris), ou null si le dossier n'existe pas. */
    private Dir dir(String dirPath, boolean create, long maxAgeMs) throws IOException {
        for (int attempt = 0; ; attempt++) {
            long ep;
            synchronized (mu) {
                Dir d = dirs.get(dirPath);
                if (d != null && maxAgeMs > 0 && now() - d.at < maxAgeMs) return d;
                ep = epoch;
            }
            Long fid = folder(dirPath, create);
            if (fid == null) { invalidate(dirPath); return null; }
            try {
                Dir d = new Dir(fid, now(), listAll(fid, false));
                synchronized (mu) { if (epoch == ep) dirs.put(dirPath, d); }
                return d;
            } catch (ApiException e) {
                if (e.status != 404) throw e;
                forgetFolders();
                if (attempt > 0) return null;
            }
        }
    }

    private void invalidate(String dirPath) {
        synchronized (mu) { dirs.remove(dirPath); epoch++; }
    }

    private void forgetFolders() {
        synchronized (mu) { folders.clear(); dirs.clear(); epoch++; }
    }

    /** Identifiant du dossier "transit/dirPath", resolu (et cree si demande) niveau par niveau ; null s'il n'existe pas. */
    private Long folder(String dirPath, boolean create) throws IOException {
        List<String> segs = new ArrayList<>();
        for (String s : (folder + "/" + dirPath).split("/")) if (!s.isEmpty()) segs.add(s);
        folderLock.lock();
        try {
            Long parent = null;
            StringBuilder key = new StringBuilder();
            for (String seg : segs) {
                key.append('/').append(seg);
                String k = key.toString();
                Long id = null;
                synchronized (mu) {
                    long[] c = folders.get(k);
                    if (c != null && now() - c[1] < FOLDER_TTL_MS) id = c[0];
                }
                if (id == null) {
                    id = findFolder(parent, seg);
                    if (id == null) {
                        if (!create) return null;
                        id = createFolder(parent, seg);
                    }
                    synchronized (mu) { folders.put(k, new long[] { id, now() }); }
                }
                parent = id;
            }
            return parent;
        } finally {
            folderLock.unlock();
        }
    }

    /** Plus ancien dossier de ce nom sous parent (null = racine) : le meme choix des deux cotes. */
    private Long findFolder(Long parent, String name) throws IOException {
        Long best = null;
        for (Ent e : listAll(parent, true)) if (e.folder && e.name.equals(nfc(name)) && (best == null || e.id < best)) best = e.id;
        return best;
    }

    private long createFolder(Long parent, String name) throws IOException {
        Long mine = null;
        ApiException refused = null;
        try {
            Map<String, Object> m = api("POST", "/folders?workspaceId=" + workspace,
                    "{\"name\":" + q(name) + ",\"parentId\":" + (parent == null ? "null" : parent.toString()) + "}");
            Ent e = m.get("folder") instanceof Map<?, ?> f ? ent(f) : null;
            if (e != null) mine = e.id;
        } catch (ApiException e) {
            if (e.status != 409 && e.status != 422) throw e;
            refused = e;                                // peut-etre cree entre-temps par le pair
        }
        // creation simultanee par les deux postes : le plus ancien fait foi, on retire le notre
        Long best = findFolder(parent, name);
        if (best == null) {
            if (mine != null) return mine;
            throw refused != null ? refused : new IOException("drime : creation du dossier non confirmee par le serveur");
        }
        if (mine != null && !mine.equals(best)) {
            try { deleteIds(List.of(mine)); } catch (IOException ignore) { }
        }
        return best;
    }

    /** Toutes les pages d'un dossier (parent null = racine) ; foldersOnly : seulement les sous-dossiers, sinon seulement les fichiers. */
    private List<Ent> listAll(Long parent, boolean foldersOnly) throws IOException {
        Map<Long, Ent> out = new LinkedHashMap<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            Map<String, Object> m = api("GET", "/drive/file-entries?perPage=" + PER_PAGE + "&page=" + page + "&workspaceId=" + workspace
                    + "&orderBy=name&orderDir=asc" + (parent != null ? "&parentIds=" + parent : "") + (foldersOnly ? "&type=folder" : ""), null);
            if (!(m.get("data") instanceof List) && m.get("pagination") instanceof Map<?, ?> p) m = cast(p);
            if (!(m.get("data") instanceof List<?> data)) throw new IOException("drime : reponse inattendue du serveur (liste)");
            for (Object o : data) {
                Ent e = o instanceof Map<?, ?> em ? ent(em) : null;
                if (e == null || e.deleted || e.folder != foldersOnly) continue;
                // filtre de precaution : uniquement les enfants directs du dossier demande
                if (parent == null ? e.parent != null && e.parent != 0 : e.parent != null && !e.parent.equals(parent)) continue;
                out.put(e.id, e);
            }
            Long last = lng(m.get("last_page")), per = lng(m.get("per_page"));
            if (data.isEmpty()) break;
            if (last != null ? page >= last : data.size() < (per != null && per > 0 ? per : PER_PAGE)) break;
        }
        return new ArrayList<>(out.values());
    }

    private static Ent ent(Map<?, ?> m) {
        Long id = lng(m.get("id"));
        if (id == null || !(m.get("name") instanceof String name)) return null;
        Long size = lng(m.get("file_size"));
        Object p = m.get("parent_id");
        return new Ent(id, nfc(name), m.get("hash") instanceof String h ? h : null, size == null ? 0 : size,
                "folder".equals(m.get("type")), lng(p), m.get("deleted_at") instanceof String);
    }

    // --- HTTP ---------------------------------------------------------------

    private Map<String, Object> api(String method, String pathQuery, String jsonBody) throws IOException {
        HttpResponse<InputStream> r = send(method, url(pathQuery),
                jsonBody == null ? null : HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8),
                jsonBody == null ? null : "application/json", null, true, 60);
        ok(r, false);
        return json(r);
    }

    private URI url(String pathQuery) throws IOException {
        if (cfgError != null) throw new IOException("drime : " + cfgError);
        try { return URI.create(api + pathQuery); } catch (IllegalArgumentException e) { throw new IOException("drime : adresse invalide"); }
    }

    /**
     * Envoie la requete. auth : jeton joint, uniquement vers l'origine de l'API.
     * Les redirections d'un GET sont suivies ici ; vers une autre origine, sans le jeton.
     */
    private HttpResponse<InputStream> send(String method, URI uri, HttpRequest.BodyPublisher body, String ctype, String range,
                                           boolean auth, int timeoutSec) throws IOException {
        if (cfgError != null) throw new IOException("drime : " + cfgError);
        if (now() < busyUntil) throw new IOException("drime : " + BUSY);
        URI cur = uri;
        for (int hop = 0; ; hop++) {
            if (cur.getHost() == null || !safe(cur)) throw new IOException("drime : adresse refusee (https obligatoire)");
            HttpRequest.Builder b = HttpRequest.newBuilder(cur).timeout(Duration.ofSeconds(timeoutSec));
            boolean toApi = sameOrigin(cur);
            if (auth && toApi) b.header("Authorization", "Bearer " + token);
            if (toApi) b.header("Accept", "application/json");
            if (ctype != null) b.header("Content-Type", ctype);
            if (range != null) b.header("Range", range);
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : body);
            HttpResponse<InputStream> r;
            try {
                r = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("drime : interrompu");
            } catch (IOException e) {
                String why = clean(HttpSupport.explain(e));
                down(why);
                throw new IOException("drime : " + why, e);
            }
            int s = r.statusCode();
            if (!"GET".equals(method) || (s != 301 && s != 302 && s != 303 && s != 307 && s != 308)) return r;
            String loc = r.headers().firstValue("Location").orElse(null);
            r.body().close();
            if (loc == null || hop >= MAX_REDIRECTS) { down("redirection invalide"); throw new IOException("drime : redirection invalide"); }
            try { cur = cur.resolve(loc); } catch (IllegalArgumentException e) { throw new IOException("drime : redirection invalide"); }
        }
    }

    /** Laisse passer un 2xx ; sinon ferme la reponse, classe l'erreur et leve. storage : reponse de l'hote de stockage. */
    private void ok(HttpResponse<InputStream> r, boolean storage) throws IOException {
        int s = r.statusCode();
        if (s >= 200 && s < 300) {
            backoff = BACKOFF_MIN;
            err = "";
            if (!storage) { probeOk = true; probeAt = now(); }
            return;
        }
        String msg = "";
        try (InputStream in = r.body()) {
            Object o = parseJson(new String(slurp(in, 65536), StandardCharsets.UTF_8));
            if (o instanceof Map<?, ?> m && m.get("message") instanceof String t) msg = t;
        } catch (IOException | RuntimeException ignore) { }
        if (s == 429 || s == 503) {
            long wait = HttpSupport.retryAfterMs(r.headers().firstValue("Retry-After"), backoff);
            backoff = Math.min(BACKOFF_MAX, backoff * 2);
            busyUntil = now() + wait;
            err = BUSY;
            throw new ApiException(s, "drime : " + BUSY);
        }
        if (storage) {
            err = "depot refuse par le stockage (" + s + ")";
            throw new ApiException(s, "drime : " + err);
        }
        if (s == 401) { down(BAD_TOKEN); throw new ApiException(s, "drime : " + BAD_TOKEN); }
        if (s == 402 || s == 507 || (s >= 400 && s < 500 && quotaLike(msg))) { block(QUOTA); throw new ApiException(s, "drime : " + QUOTA); }
        if (s == 403) { block(NO_RIGHT); throw new ApiException(s, "drime : " + NO_RIGHT); }
        if (s == 404) throw new ApiException(s, "drime : introuvable sur le serveur");
        if (s >= 500) { down("erreur du serveur (" + s + ")"); throw new ApiException(s, "drime : erreur du serveur (" + s + ")"); }
        String m = clean(msg.length() > 120 ? msg.substring(0, 120) : msg);
        err = "refus du serveur (" + s + ")";
        throw new ApiException(s, "drime : " + err + (m.isBlank() ? "" : " : " + m));
    }

    private Map<String, Object> json(HttpResponse<InputStream> r) throws IOException {
        byte[] b;
        try (InputStream in = r.body()) {
            b = slurp(in, JSON_MAX);
        } catch (IOException e) {
            String why = clean(HttpSupport.explain(e));
            down(why);
            throw new IOException("drime : " + why, e);
        }
        try {
            if (parseJson(new String(b, StandardCharsets.UTF_8)) instanceof Map<?, ?> m) return cast(m);
        } catch (IOException ignore) { }
        // typiquement une page HTML d'un proxy ou d'un portail a la place de l'API
        down("reponse illisible du serveur");
        throw new IOException("drime : reponse illisible du serveur");
    }

    private static byte[] slurp(InputStream in, int max) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int k;
        while ((k = in.read(buf)) >= 0) {
            if (bos.size() + k > max) throw new IOException("reponse trop longue");
            bos.write(buf, 0, k);
        }
        return bos.toByteArray();
    }

    /** Message de refus evoquant l'espace de stockage (formulation non documentee : heuristique). */
    private static boolean quotaLike(String msg) {
        String m = msg.toLowerCase(Locale.ROOT);
        return m.contains("quota") || m.contains("exhausted") || m.contains("allowed space") || m.contains("storage space")
                || m.contains("not enough space") || m.contains("insufficient space") || m.contains("espace de stockage");
    }

    private void down(String why) { err = why; probeOk = false; probeAt = now(); }

    private void block(String why) { blockWhy = why; err = why; blockUntil = now() + BLOCK_MS; }

    private boolean sameOrigin(URI u) {
        return u.getScheme() != null && u.getScheme().equalsIgnoreCase(apiUri.getScheme())
                && u.getHost() != null && u.getHost().equalsIgnoreCase(apiUri.getHost()) && port(u) == port(apiUri);
    }

    private static int port(URI u) { return u.getPort() >= 0 ? u.getPort() : "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80; }

    /** https partout ; http seulement vers la boucle locale (tests). */
    private static boolean safe(URI u) {
        if ("https".equalsIgnoreCase(u.getScheme())) return true;
        String h = u.getHost();
        return "http".equalsIgnoreCase(u.getScheme()) && h != null && (h.equalsIgnoreCase("localhost") || h.equals("127.0.0.1"));
    }

    /** Ceinture et bretelles : le jeton ne sort dans aucun message. */
    private String clean(String s) {
        if (s == null) return "";
        return token.isEmpty() ? s : s.replace(token, "***");
    }

    private static long now() { return System.nanoTime() / 1_000_000; }

    private static String nfc(String s) { return Normalizer.normalize(s, Normalizer.Form.NFC); }

    private static String norm(String dir) {
        StringBuilder sb = new StringBuilder();
        for (String s : (dir == null ? "" : dir).replace('\\', '/').split("/")) {
            if (s.isEmpty()) continue;
            if (sb.length() > 0) sb.append('/');
            sb.append(nfc(s));
        }
        return sb.toString();
    }

    /** "a/b/c.lot" -> {"a/b", "c.lot"}. */
    private static String[] split(String path) throws IOException {
        String p = norm(path);
        if (p.isEmpty()) throw new IOException("drime : chemin vide");
        int c = p.lastIndexOf('/');
        return c < 0 ? new String[] { "", p } : new String[] { p.substring(0, c), p.substring(c + 1) };
    }

    private static Long lng(Object o) {
        if (o instanceof Long l) return l;
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s && !s.isEmpty() && s.length() <= 18) {
            try { return Long.parseLong(s); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private static String str(Map<String, Object> m, String k) { return m.get(k) instanceof String s ? s : null; }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) { return (Map<String, Object>) m; }

    // --- JSON minimal -------------------------------------------------------

    /** Chaine JSON entre guillemets. */
    static String q(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        return sb.append('"').toString();
    }

    /**
     * Analyse un texte JSON complet. Objets : LinkedHashMap ; tableaux : ArrayList ;
     * nombres : Long (entier sans exposant qui tient sur 64 bits) ou Double ;
     * chaines, Boolean, null. Leve IOException sur tout texte invalide.
     */
    static Object parseJson(String text) throws IOException {
        String s = text != null && text.startsWith("\ufeff") ? text.substring(1) : text;
        if (s == null) throw new IOException("JSON invalide (vide)");
        Json j = new Json(s);
        j.ws();
        Object v = j.value(0);
        j.ws();
        if (j.i != s.length()) throw j.bad();
        return v;
    }

    private static final class Json {
        private final String s;
        private int i;
        Json(String s) { this.s = s; }

        IOException bad() { return new IOException("JSON invalide (position " + i + ")"); }

        void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') break;
                i++;
            }
        }

        Object value(int depth) throws IOException {
            if (depth > 64 || i >= s.length()) throw bad();
            char c = s.charAt(i);
            if (c == '{') return object(depth);
            if (c == '[') return array(depth);
            if (c == '"') return string();
            if (c == '-' || (c >= '0' && c <= '9')) return number();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            throw bad();
        }

        private Map<String, Object> object(int depth) throws IOException {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                if (i >= s.length() || s.charAt(i) != '"') throw bad();
                String k = string();
                ws();
                if (i >= s.length() || s.charAt(i) != ':') throw bad();
                i++;
                ws();
                m.put(k, value(depth + 1));
                ws();
                if (i >= s.length()) throw bad();
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') { i--; throw bad(); }
            }
        }

        private List<Object> array(int depth) throws IOException {
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
            while (true) {
                ws();
                l.add(value(depth + 1));
                ws();
                if (i >= s.length()) throw bad();
                char c = s.charAt(i++);
                if (c == ']') return l;
                if (c != ',') { i--; throw bad(); }
            }
        }

        private String string() throws IOException {
            StringBuilder sb = new StringBuilder();
            i++;
            while (true) {
                if (i >= s.length()) throw bad();
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c < 0x20) { i--; throw bad(); }
                if (c != '\\') { sb.append(c); continue; }
                if (i >= s.length()) throw bad();
                char e = s.charAt(i++);
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (i + 4 > s.length()) throw bad();
                        int v = 0;
                        for (int k = 0; k < 4; k++) {
                            int d = Character.digit(s.charAt(i + k), 16);
                            if (d < 0 || s.charAt(i + k) > 'f') { i += k; throw bad(); }
                            v = v * 16 + d;
                        }
                        i += 4;
                        sb.append((char) v);
                    }
                    default -> { i--; throw bad(); }
                }
            }
        }

        private Object number() throws IOException {
            int start = i;
            boolean integer = true;
            if (s.charAt(i) == '-') i++;
            if (i >= s.length()) throw bad();
            if (s.charAt(i) == '0') i++;
            else if (digits() == 0) throw bad();
            if (i < s.length() && s.charAt(i) == '.') {
                integer = false;
                i++;
                if (digits() == 0) throw bad();
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                integer = false;
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                if (digits() == 0) throw bad();
            }
            String t = s.substring(start, i);
            if (integer) {
                try { return Long.parseLong(t); } catch (NumberFormatException tooBig) { /* hors 64 bits : Double */ }
            }
            return Double.parseDouble(t);
        }

        private int digits() {
            int n = 0;
            while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') { i++; n++; }
            return n;
        }
    }
}
