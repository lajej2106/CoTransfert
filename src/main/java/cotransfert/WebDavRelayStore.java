package cotransfert;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Magasin de relais sur un serveur WebDAV (canal "webdav") : protocole standard,
 * HTTPS sortant uniquement, authentification Basic envoyee d'emblee.
 *
 * Ecriture : PUT vers "nom.part" puis MOVE (Overwrite: T), dossiers parents
 * crees par MKCOL a la demande. Le mot de passe n'apparait jamais dans un
 * message, une description ou une exception.
 *
 * Quand le serveur demande d'attendre (429/503, Retry-After), le magasin se
 * declare indisponible jusqu'a l'echeance et n'emet plus de requete d'ici la,
 * au lieu de dormir.
 */
public final class WebDavRelayStore implements RelayStore {

    private static final long POLL_MS = 10_000;
    private static final long SMALL_MS = 30_000;            // petites requetes
    private static final long UPLOAD_MS = 600_000;          // depot d'un lot, plus une marge selon la taille
    private static final long DOWNLOAD_MS = 1_200_000;      // lecture complete d'un lot (taille inconnue d'avance)
    private static final long BUSY_MS = 30_000;             // 429/503 sans Retry-After
    private static final long FULL_MS = 60_000;             // quota plein : indisponible ce temps-la
    private static final String DAV = "DAV:";
    private static final byte[] PROPFIND = ("<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>")
            .getBytes(StandardCharsets.UTF_8);

    private final String root;       // URL du dossier de base, encodee, sans '/' final
    private final String label;      // hote + chemin, pour les journaux
    private final String auth;       // en-tete Authorization : ne sort jamais de cette classe
    private final HttpClient http;
    /** Dossiers (relatifs a la base, "" = la base) dont l'existence a ete constatee. */
    private final Set<String> known = ConcurrentHashMap.newKeySet();

    private final Object probeLock = new Object();   // une seule sonde a la fois
    private final Object state = new Object();
    private boolean checked, up;
    private long checkedAt;
    private long holdUntil;          // indisponible jusqu'a cette echeance (Retry-After, quota)
    private boolean holdAll;         // vrai : plus aucune requete d'ici l'echeance
    private String holdWhy = "";
    private volatile String lastError = "";

    /**
     * @param baseUrl  URL du dossier de transit (https obligatoire, http accepte vers la boucle locale)
     * @param user     identifiant
     * @param password mot de passe d'application
     * @param http     client HTTP partage (voir {@link HttpSupport#client})
     */
    public WebDavRelayStore(String baseUrl, String user, String password, HttpClient http) {
        if (http == null) throw new IllegalArgumentException("webdav : client HTTP manquant");
        URI u = parse(baseUrl == null ? "" : baseUrl.trim());
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        String host = u.getHost();
        if (host == null || host.isEmpty() || !(scheme.equals("https") || scheme.equals("http"))) throw invalid();
        // Jamais d'identifiants dans l'URL : ils finiraient dans les journaux.
        if (u.getRawUserInfo() != null)
            throw new IllegalArgumentException("webdav.url ne doit pas contenir d'identifiants (utiliser webdav.user et webdav.password)");
        if (scheme.equals("http") && !isLoopback(host))
            throw new IllegalArgumentException("webdav.url doit commencer par https:// (http n'est accepte que vers la boucle locale)");
        String origin = scheme + "://" + host + (u.getPort() >= 0 ? ":" + u.getPort() : "");
        this.root = origin + stripSlash(u.getRawPath() == null ? "" : u.getRawPath());
        this.label = host + (u.getPort() >= 0 ? ":" + u.getPort() : "") + stripSlash(u.getPath() == null ? "" : u.getPath());
        this.auth = HttpSupport.basic(user, password);
        this.http = http;
    }

    private static URI parse(String s) {
        try {
            return new URI(new URI(s).toASCIIString());   // accents bruts : encodes en UTF-8
        } catch (URISyntaxException e) {
            // URL saisie avec des espaces ou des accents non encodes : on encode le chemin.
            int i = s.indexOf("://"), j = i < 0 ? -1 : s.indexOf('/', i + 3);
            if (j < 0) throw invalid();
            try {
                return new URI(s.substring(0, j) + "/" + HttpSupport.encodePath(s.substring(j)));
            } catch (URISyntaxException e2) {
                throw invalid();
            }
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("webdav.url invalide (attendu : https://serveur/chemin/du/dossier)");
    }

    private static boolean isLoopback(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("[::1]");
    }

    // =======================================================================
    //  RelayStore
    // =======================================================================

    @Override public String id() { return "webdav"; }

    @Override public String describe() { return label; }

    @Override public long pollMs() { return POLL_MS; }

    @Override public long maxLotBytes() { return 64L << 20; }

    @Override public String lastError() { return lastError; }

    @Override public boolean available() {
        synchronized (probeLock) {
            synchronized (state) {
                long t = now();
                if (t < holdUntil) { lastError = holdWhy; return false; }
                if (checked && t - checkedAt < POLL_MS) return up;
            }
            boolean ok;
            try {
                ok = probe();
            } catch (IOException | RuntimeException e) {
                ok = false;
                if (lastError.isEmpty()) lastError = HttpSupport.explain(e);
            }
            synchronized (state) {
                up = ok;
                checkedAt = now();
                checked = ok || checkedAt >= holdUntil;   // attente demandee par le serveur : nouvelle sonde a l'echeance
            }
            if (ok) lastError = "";
            return ok;
        }
    }

    /** PROPFIND profondeur 0 sur le dossier de base ; absent : on le cree. */
    private boolean probe() throws IOException {
        String what = "webdav, verification";
        HttpResponse<Void> r = send(what, "PROPFIND", root + "/", HttpRequest.BodyPublishers.ofByteArray(PROPFIND), SMALL_MS,
                HttpResponse.BodyHandlers.discarding(), "Depth", "0", "Content-Type", "application/xml; charset=utf-8");
        int c = r.statusCode();
        if (c == 207 || c == 200) { known.add(""); return true; }
        if (c != 404) throw refused(what, r);
        r = send(what, "MKCOL", root + "/", HttpRequest.BodyPublishers.noBody(), SMALL_MS, HttpResponse.BodyHandlers.discarding());
        c = r.statusCode();
        if (created(c)) { known.add(""); return true; }
        if (c == 404 || c == 409) {
            lastError = "dossier de transit introuvable et impossible a creer";
            return false;
        }
        throw refused(what, r);
    }

    @Override public List<Entry> list(String dir) throws IOException {
        String what = "webdav, liste de " + dir;
        String url = url(dir) + "/";
        HttpResponse<byte[]> r = send(what, "PROPFIND", url, HttpRequest.BodyPublishers.ofByteArray(PROPFIND), SMALL_MS,
                info -> info.statusCode() == 207 ? HttpResponse.BodySubscribers.ofByteArray()
                        : HttpResponse.BodySubscribers.replacing(new byte[0]),
                "Depth", "1", "Content-Type", "application/xml; charset=utf-8");
        int c = r.statusCode();
        if (c == 404) { ok(); return new ArrayList<>(); }
        if (c != 207) throw refused(what, r);
        List<Entry> out;
        try {
            out = parseMultistatus(r.body(), decode(stripSlash(pathOf(url))));
        } catch (XMLStreamException | RuntimeException e) {
            lastError = "reponse du serveur illisible";
            throw new IOException(what + " : " + lastError);
        }
        ok();
        return out;
    }

    @Override public byte[] read(String path, int maxBytes) throws IOException {
        String what = "webdav, lecture de " + path;
        boolean prefix = maxBytes > 0;
        HttpResponse.BodyHandler<byte[]> h = info -> {
            int c = info.statusCode();
            if (c != 200 && c != 206) return HttpResponse.BodySubscribers.replacing(new byte[0]);
            // Un serveur qui ignore Range repond 200 avec tout le fichier : on s'arrete au prefixe.
            return prefix ? new Prefix(maxBytes) : HttpResponse.BodySubscribers.ofByteArray();
        };
        HttpResponse<byte[]> r = prefix
                ? send(what, "GET", url(path), HttpRequest.BodyPublishers.noBody(), SMALL_MS, h, "Range", "bytes=0-" + (maxBytes - 1))
                : send(what, "GET", url(path), HttpRequest.BodyPublishers.noBody(), DOWNLOAD_MS, h);
        int c = r.statusCode();
        if (c == 404) { ok(); return null; }
        if (c == 416 && prefix) { ok(); return new byte[0]; }   // fichier vide
        if (c != 200 && c != 206) throw refused(what, r);
        byte[] b = r.body() == null ? new byte[0] : r.body();
        ok();
        return prefix && b.length > maxBytes ? Arrays.copyOf(b, maxBytes) : b;
    }

    @Override public void write(String path, Path src) throws IOException {
        if (!Files.isRegularFile(src)) throw new IOException("webdav, depot de " + path + " : fichier local introuvable");
        put(path, Files.size(src), src, null);
    }

    @Override public void write(String path, byte[] data) throws IOException {
        put(path, data.length, null, data);
    }

    @Override public void delete(String path) throws IOException {
        String what = "webdav, suppression de " + path;
        HttpResponse<Void> r = send(what, "DELETE", url(path), HttpRequest.BodyPublishers.noBody(), SMALL_MS,
                HttpResponse.BodyHandlers.discarding());
        int c = r.statusCode();
        if (c != 200 && c != 202 && c != 204 && c != 404) throw refused(what, r);
        ok();
    }

    /** Tests : oublie la disponibilite en cache et toute attente demandee par le serveur. */
    void forgetProbe() {
        synchronized (state) { checked = false; holdUntil = 0; holdAll = false; }
    }

    // =======================================================================
    //  Ecriture : PUT vers .part, MKCOL a la demande, MOVE
    // =======================================================================

    private void put(String path, long size, Path file, byte[] data) throws IOException {
        String what = "webdav, depot de " + path;
        String tmp = url(path + ".part");
        long timeout = UPLOAD_MS + size / 64;   // marge : 64 Kio/s au pire
        HttpResponse<Void> r = sendPut(what, tmp, timeout, file, data);
        if (r.statusCode() == 409 || r.statusCode() == 404) {
            makeParents(what, path);
            r = sendPut(what, tmp, timeout, file, data);
        }
        int c = r.statusCode();
        if (c != 200 && c != 201 && c != 204) {
            IOException e = refused(what, r);
            if (c == 507) cleanup(tmp);   // un depot refuse en cours de route peut laisser un morceau
            throw e;
        }
        r = send(what, "MOVE", tmp, HttpRequest.BodyPublishers.noBody(), SMALL_MS, HttpResponse.BodyHandlers.discarding(),
                "Destination", url(path), "Overwrite", "T");
        c = r.statusCode();
        if (c != 200 && c != 201 && c != 204) {
            IOException e = refused(what, r);
            cleanup(tmp);
            throw e;
        }
        int cut = path.lastIndexOf('/');
        known.add(cut < 0 ? "" : HttpSupport.encodePath(path.substring(0, cut)));
        ok();
    }

    private HttpResponse<Void> sendPut(String what, String url, long timeout, Path file, byte[] data) throws IOException {
        HttpRequest.BodyPublisher body = file != null ? HttpRequest.BodyPublishers.ofFile(file)
                : HttpRequest.BodyPublishers.ofByteArray(data);
        return send(what, "PUT", url, body, timeout, HttpResponse.BodyHandlers.discarding(),
                "Content-Type", "application/octet-stream");
    }

    /** Retire un ".part" orphelin, sans masquer l'erreur d'origine. */
    private void cleanup(String tmpUrl) {
        String keep = lastError;
        try {
            send("webdav, menage", "DELETE", tmpUrl, HttpRequest.BodyPublishers.noBody(), SMALL_MS, HttpResponse.BodyHandlers.discarding());
        } catch (IOException | RuntimeException ignore) {
            // il sera retire par le menage de la session
        }
        lastError = keep;
    }

    /**
     * Cree les dossiers parents de path, du plus haut au plus profond. Les niveaux
     * deja constates sont sautes, sauf le parent direct ; si un niveau memorise a
     * disparu du serveur (409), on oublie tout et on repart de la base, une fois.
     */
    private void makeParents(String what, String path) throws IOException {
        List<String> levels = new ArrayList<>();
        levels.add("");
        String[] seg = HttpSupport.encodePath(path).split("/");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < seg.length - 1; i++) {
            if (sb.length() > 0) sb.append('/');
            levels.add(sb.append(seg[i]).toString());
        }
        for (int pass = 0; pass < 2; pass++) {
            boolean redo = false;
            for (int i = 0; i < levels.size(); i++) {
                String l = levels.get(i);
                if (i < levels.size() - 1 && known.contains(l)) continue;
                HttpResponse<Void> r = send(what, "MKCOL", (l.isEmpty() ? root : root + "/" + l) + "/",
                        HttpRequest.BodyPublishers.noBody(), SMALL_MS, HttpResponse.BodyHandlers.discarding());
                int c = r.statusCode();
                if (created(c)) { known.add(l); continue; }
                if ((c == 409 || c == 404) && pass == 0) { known.clear(); redo = true; break; }
                throw refused(what, r);
            }
            if (!redo) return;
        }
    }

    /** MKCOL reussi, ou dossier deja present (405). */
    private static boolean created(int c) { return c == 201 || c == 200 || c == 204 || c == 405; }

    // =======================================================================
    //  Requetes et erreurs
    // =======================================================================

    private String url(String relPath) {
        String e = HttpSupport.encodePath(relPath == null ? "" : relPath);
        return e.isEmpty() ? root : root + "/" + e;
    }

    private static long now() { return System.nanoTime() / 1_000_000; }

    private <T> HttpResponse<T> send(String what, String method, String url, HttpRequest.BodyPublisher body, long timeoutMs,
                                     HttpResponse.BodyHandler<T> handler, String... headers) throws IOException {
        synchronized (state) {
            if (holdAll && now() < holdUntil) throw new IOException(what + " : " + holdWhy);
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(timeoutMs))
                .header("Authorization", auth).method(method, body);
        for (int i = 0; i + 1 < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        // Envoi asynchrone + attente bornee : le delai couvre aussi la lecture du corps,
        // et un thread interrompu (fermeture de la session) est libere tout de suite.
        CompletableFuture<HttpResponse<T>> f = http.sendAsync(b.build(), handler);
        try {
            return f.get(timeoutMs + 5000, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw down(what, e.getCause() == null ? e : e.getCause());
        } catch (TimeoutException e) {
            f.cancel(true);
            throw down(what, new HttpTimeoutException("delai depasse"));
        } catch (InterruptedException e) {
            f.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException(what + " : attente interrompue");
        }
    }

    /** Echec reseau ou TLS : le magasin est tenu pour injoignable jusqu'a la prochaine sonde. */
    private IOException down(String what, Throwable cause) {
        lastError = HttpSupport.explain(cause);
        markDown();
        return HttpSupport.fail(what, cause);
    }

    private void markDown() {
        synchronized (state) { checked = true; up = false; checkedAt = now(); }
    }

    private void hold(long ms, boolean all, String why) {
        synchronized (state) {
            long until = now() + ms;
            if (until > holdUntil || all) { holdUntil = Math.max(holdUntil, until); holdWhy = why; }
            if (all) holdAll = true;
            checked = false;   // nouvelle sonde des l'echeance
        }
    }

    /** Traduit un code HTTP d'echec en message court ; met a jour lastError et la disponibilite. */
    private IOException refused(String what, HttpResponse<?> r) {
        int c = r.statusCode();
        String why;
        switch (c) {
            case 401, 403 -> { why = "identifiants refuses"; markDown(); }
            case 407 -> { why = "le proxy exige une authentification"; markDown(); }
            case 423 -> why = "fichier verrouille";
            case 429, 503 -> {
                why = "serveur occupe";
                hold(HttpSupport.retryAfterMs(r.headers().firstValue("Retry-After"), BUSY_MS), true, why);
            }
            case 507 -> { why = "quota plein"; hold(FULL_MS, false, why); }
            case 405, 501 -> why = "cette adresse n'accepte pas WebDAV (HTTP " + c + ")";
            default -> why = "reponse inattendue du serveur (HTTP " + c + ")";
        }
        lastError = why;
        return new IOException(what + " : " + why);
    }

    /** Une operation a reussi : l'erreur precedente n'est plus d'actualite, sauf indisponibilite en cours. */
    private void ok() {
        synchronized (state) {
            if (now() >= holdUntil && !(checked && !up)) lastError = "";
        }
    }

    // =======================================================================
    //  Reponse PROPFIND (multistatus)
    // =======================================================================

    /**
     * Fichiers directs listes par une reponse multistatus. self est le chemin
     * decode du dossier interroge : le serveur le liste lui-meme, on le saute.
     */
    private static List<Entry> parseMultistatus(byte[] xml, String self) throws XMLStreamException {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, Boolean.TRUE);
        f.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        f.setProperty(XMLInputFactory.IS_COALESCING, Boolean.TRUE);
        try {
            f.setProperty("http://javax.xml.XMLConstants/property/accessExternalDTD", "");
        } catch (IllegalArgumentException ignore) {
            // propriete inconnue de cet analyseur : la DTD est deja desactivee
        }
        List<Entry> out = new ArrayList<>();
        XMLStreamReader x = f.createXMLStreamReader(new ByteArrayInputStream(xml));
        try {
            String href = null, status = null;
            boolean col = false, pCol = false, inProp = false, inStat = false, inType = false;
            long len = -1, pLen = -1;
            StringBuilder text = null;
            while (x.hasNext()) {
                int ev = x.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    text = null;
                    if (!DAV.equals(x.getNamespaceURI())) continue;
                    switch (x.getLocalName()) {
                        case "response" -> { href = null; col = false; len = -1; inProp = false; inStat = false; inType = false; }
                        case "propstat" -> { inStat = true; pCol = false; pLen = -1; status = null; }
                        case "prop" -> inProp = true;
                        case "resourcetype" -> inType = true;
                        case "collection" -> { if (inType) pCol = true; }
                        case "href", "getcontentlength", "status" -> text = new StringBuilder();
                        default -> { }
                    }
                } else if (ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA) {
                    if (text != null) text.append(x.getText());
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    String t = text == null ? null : text.toString().trim();
                    text = null;
                    if (!DAV.equals(x.getNamespaceURI())) continue;
                    switch (x.getLocalName()) {
                        case "href" -> { if (!inProp && href == null && t != null) href = t; }
                        case "status" -> { if (inStat && !inProp) status = t; }
                        case "getcontentlength" -> {
                            if (inProp && t != null) {
                                try { pLen = Long.parseLong(t); } catch (NumberFormatException ignore) { }
                            }
                        }
                        case "resourcetype" -> inType = false;
                        case "prop" -> inProp = false;
                        case "propstat" -> {
                            // Les proprietes absentes arrivent dans un propstat 404 : on ne garde que le 200.
                            if (status == null || status.matches("(?s).*\\s2\\d\\d(\\s.*)?")) {
                                col |= pCol;
                                if (pLen >= 0) len = pLen;
                            }
                            inStat = false;
                        }
                        case "response" -> {
                            if (href == null || col) break;
                            String p = stripSlash(pathOf(href));
                            if (decode(p).equals(self)) break;
                            String name = decode(p.substring(p.lastIndexOf('/') + 1));
                            if (name.isEmpty() || name.endsWith(".part")) break;
                            out.add(new Entry(name, len));
                        }
                        default -> { }
                    }
                }
            }
        } finally {
            try { x.close(); } catch (XMLStreamException ignore) { }
        }
        return out;
    }

    /** Chemin d'un href, qu'il soit absolu ("https://hote/a/b") ou relatif a l'hote ("/a/b"). */
    private static String pathOf(String href) {
        int i = href.indexOf("://");
        if (i > 0 && href.indexOf('/') > i) {
            int j = href.indexOf('/', i + 3);
            return j < 0 ? "" : href.substring(j);
        }
        return href;
    }

    private static String stripSlash(String s) {
        int n = s.length();
        while (n > 0 && s.charAt(n - 1) == '/') n--;
        return s.substring(0, n);
    }

    /** Decode les %XX (UTF-8) ; '+' reste '+', une sequence invalide est gardee telle quelle. */
    private static String decode(String s) {
        if (s.indexOf('%') < 0) return s;
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

    /** Ne garde que les max premiers octets du corps, puis abandonne la reponse sans la lire en entier. */
    private static final class Prefix implements HttpResponse.BodySubscriber<byte[]> {
        private final int max;
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private Flow.Subscription sub;
        private boolean done;

        Prefix(int max) { this.max = max; }

        @Override public CompletionStage<byte[]> getBody() { return body; }

        @Override public void onSubscribe(Flow.Subscription s) {
            if (sub != null) { s.cancel(); return; }
            sub = s;
            s.request(Long.MAX_VALUE);
        }

        @Override public void onNext(List<ByteBuffer> items) {
            if (done) return;
            for (ByteBuffer b : items) {
                int n = Math.min(b.remaining(), max - buf.size());
                if (n <= 0) break;
                byte[] t = new byte[n];
                b.get(t);
                buf.write(t, 0, n);
            }
            if (buf.size() >= max) {
                done = true;
                sub.cancel();
                body.complete(buf.toByteArray());
            }
        }

        @Override public void onError(Throwable t) { body.completeExceptionally(t); }

        @Override public void onComplete() { body.complete(buf.toByteArray()); }
    }
}
