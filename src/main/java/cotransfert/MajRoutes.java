package cotransfert;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mise a jour par le lien direct, cote HUB : le hub range la publication dans
 * un dossier local (cotransfert.maj, a cote de cotransfert.conf) et la sert au
 * client par le serveur HTTP de la page. Le client ne fait que des requetes
 * sortantes vers le hub ({@link HubHttpRelayStore}).
 *
 * Ces routes ne sont PAS reservees a la boucle locale (le client doit les
 * atteindre par le cable) ; elles ne donnent acces qu'aux deux fichiers de la
 * publication, deja scelles avec le secret partage :
 *
 *   GET /maj/liste
 *       -> text/plain : une premiere ligne "CTMAJ-LISTE 1", puis une ligne
 *          "<taille> <nom>" par fichier publie.
 *   GET /maj/fichier?nom=<nom>[&max=<n>]
 *       -> le fichier (au plus n octets depuis le debut), ou 404 s'il est absent.
 *   PUT /maj/fichier?nom=<nom>
 *       -> depose le fichier (publication par le client). Le jar d'abord, la
 *          fiche ensuite.
 *
 * Garde-fous :
 *   - nom : exactement "version.scelle" ou "cotransfert.jar.scelle" (comparaison
 *     stricte avec ces deux noms : aucun chemin n'est construit a partir de la
 *     requete, donc aucun parcours de dossier possible) ; sinon 400 ;
 *   - taille : fiche au plus {@link Updater#FICHE_MAX_BYTES} octets, jar au plus
 *     {@link Updater#MAX_JAR_BYTES} + l'enveloppe du scellement ; au-dela 413,
 *     rien n'est ecrit ;
 *   - PUT : le contenu doit s'ouvrir avec le secret partage de ce poste, sinon
 *     403 et rien n'est ecrit. Le jar recu est mis de cote (nom ".recu") sans
 *     toucher a la publication ; c'est la fiche qui valide l'ensemble : elle doit
 *     decrire ce jar (taille, empreinte) et ne pas etre plus ancienne que la
 *     version deja publiee (une ancienne publication capturee sur le reseau ne
 *     peut pas etre rejouee par-dessus une plus recente), sinon 409. Alors
 *     seulement le jar puis la fiche remplacent la publication ;
 *   - aucune suppression par le reseau (pas de DELETE) ;
 *   - sans secret partage, les routes ne sont pas servies du tout (404).
 * Un poste qui ne connait pas le secret peut donc lire des fichiers scelles,
 * rien d'autre : ni remplacer, ni effacer la publication, ni remplir le disque.
 */
final class MajRoutes {

    /** Dossier du hub qui porte la publication sur le lien direct, a cote de cotransfert.conf. */
    static final String DIR_NAME = "cotransfert.maj";
    static final String LIST_PATH = "/maj/liste", FILE_PATH = "/maj/fichier";
    static final String LIST_MAGIC = "CTMAJ-LISTE 1";
    static final String FICHE_NAME = Updater.FICHE_PATH.substring(Updater.DIR.length() + 1);
    static final String JAR_NAME = Updater.JAR_PATH.substring(Updater.DIR.length() + 1);
    /** Taille maximale d'un jar scelle. */
    static final long JAR_MAX_BYTES = Updater.MAX_JAR_BYTES + Crypto.SEAL_OVERHEAD;
    private static final String STAGED = JAR_NAME + ".recu";

    private final Path dir;
    private final Crypto.Key key;
    private final Object writing = new Object();

    /**
     * @param root dossier de la publication (cotransfert.maj) ; les fichiers sont dans root/_maj
     * @param key  cle derivee du secret partage (jamais null : sans secret, pas de routes)
     */
    MajRoutes(Path root, Crypto.Key key) {
        if (key == null) throw new IllegalArgumentException("secret partage manquant");
        this.dir = root.resolve(Updater.DIR);
        this.key = key;
    }

    /** Dossier de la publication pour une configuration donnee. */
    static Path rootFor(Path conf) {
        return conf.toAbsolutePath().resolveSibling(DIR_NAME);
    }

    /**
     * Magasin local du hub sur ce dossier (cree s'il manque). S'il ne peut pas
     * etre cree, le magasin se dit indisponible.
     */
    static RelayStore localStore(Path root) {
        try {
            Files.createDirectories(root);
        } catch (IOException | RuntimeException e) {
            // le magasin dira "support absent"
        }
        return new DirRelayStore(root.toString());
    }

    void register(HttpServer srv) {
        srv.createContext(LIST_PATH, ex -> guard(ex, LIST_PATH, this::list));
        srv.createContext(FILE_PATH, ex -> guard(ex, FILE_PATH, this::file));
    }

    private interface Handler { void handle(HttpExchange ex) throws IOException; }

    private static void guard(HttpExchange ex, String path, Handler h) throws IOException {
        try {
            if (!path.equals(ex.getRequestURI().getPath())) text(ex, 404, "route inconnue");
            else h.handle(ex);
        } catch (IOException | RuntimeException e) {
            // Jamais le detail d'une erreur locale (chemin...) vers le reseau.
            try { text(ex, 500, "erreur du hub"); } catch (IOException | RuntimeException ignore) { /* reponse deja partie */ }
        } finally {
            ex.close();
        }
    }

    private void list(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) { text(ex, 405, "methode refusee"); return; }
        StringBuilder sb = new StringBuilder(LIST_MAGIC).append('\n');
        for (String name : new String[] { FICHE_NAME, JAR_NAME }) {
            try {
                Path f = dir.resolve(name);
                if (Files.isRegularFile(f)) sb.append(Files.size(f)).append(' ').append(name).append('\n');
            } catch (IOException gone) {
                // disparu entre les deux appels
            }
        }
        text(ex, 200, sb.toString());
    }

    private void file(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex.getRequestURI().getRawQuery());
        String name = q.get("nom");
        boolean fiche = FICHE_NAME.equals(name);
        if (q == BAD_QUERY || (!fiche && !JAR_NAME.equals(name))) { text(ex, 400, "nom refuse"); return; }
        long limit = fiche ? Updater.FICHE_MAX_BYTES : JAR_MAX_BYTES;
        String method = ex.getRequestMethod();
        if ("GET".equals(method)) get(ex, name, limit, q.get("max"));
        else if ("PUT".equals(method)) put(ex, fiche, limit);
        else text(ex, 405, "methode refusee");
    }

    private void get(HttpExchange ex, String name, long limit, String max) throws IOException {
        long n = limit;
        if (max != null) {
            try {
                long m = Long.parseLong(max);
                if (m > 0) n = Math.min(n, m);
            } catch (NumberFormatException e) {
                text(ex, 400, "max refuse");
                return;
            }
        }
        byte[] b;
        try (InputStream in = Files.newInputStream(dir.resolve(name))) {
            b = in.readNBytes((int) n);
        } catch (NoSuchFileException e) {
            text(ex, 404, "absent");
            return;
        }
        reply(ex, 200, "application/octet-stream", b);
    }

    private void put(HttpExchange ex, boolean fiche, long limit) throws IOException {
        String declared = ex.getRequestHeaders().getFirst("Content-Length");
        if (declared != null) {
            try {
                if (Long.parseLong(declared.trim()) > limit) { text(ex, 413, "trop volumineux"); return; }
            } catch (NumberFormatException e) {
                text(ex, 400, "longueur refusee");
                return;
            }
        }
        byte[] body;
        try (InputStream in = ex.getRequestBody()) {
            body = in.readNBytes((int) limit + 1);
        }
        if (body.length > limit) { text(ex, 413, "trop volumineux"); return; }
        synchronized (writing) {
            if (fiche) putFiche(ex, body);
            else putJar(ex, body);
        }
    }

    /** Jar recu : mis de cote s'il s'ouvre avec le secret ; la publication en place n'est pas touchee. */
    private void putJar(HttpExchange ex, byte[] sealed) throws IOException {
        try {
            if (Updater.openJar(key, sealed).length > Updater.MAX_JAR_BYTES) throw new IOException("trop volumineux");
        } catch (IOException e) {
            text(ex, 403, "refuse : ne s'ouvre pas avec le secret partage du hub");
            return;
        }
        write(STAGED, sealed);
        text(ex, 200, "recu");
    }

    /** Fiche recue : si elle s'ouvre, decrit le jar mis de cote et n'est pas plus ancienne, les deux sont publies. */
    private void putFiche(HttpExchange ex, byte[] sealed) throws IOException {
        Updater.Version v;
        try {
            v = Updater.openFiche(key, sealed);
        } catch (IOException e) {
            text(ex, 403, "refuse : ne s'ouvre pas avec le secret partage du hub");
            return;
        }
        Path staged = dir.resolve(STAGED);
        try {
            byte[] jar = Updater.openJar(key, Files.readAllBytes(staged));
            if (jar.length != v.size() || !Updater.sha256(jar).equals(v.sha256())) throw new IOException("jar different de la fiche");
        } catch (IOException e) {
            text(ex, 409, "refuse : la fiche ne decrit pas le jar recu");
            return;
        }
        try {
            Updater.Version cur = Updater.openFiche(key, Files.readAllBytes(dir.resolve(FICHE_NAME)));
            if (cur.dateMs() > v.dateMs()) {
                Files.deleteIfExists(staged);
                text(ex, 409, "refuse : version plus ancienne que celle deja publiee");
                return;
            }
        } catch (IOException e) {
            // rien de publie, ou fiche en place illisible : la nouvelle la remplace
        }
        MailboxSession.moveAtomic(staged, dir.resolve(JAR_NAME));
        write(FICHE_NAME, sealed);
        Agent.log("maj: version du %s publiee par le client (lien direct)", v.label());
        text(ex, 200, "publie");
    }

    private void write(String name, byte[] data) throws IOException {
        Files.createDirectories(dir);
        Path tmp = dir.resolve(name + ".part");
        try {
            Files.write(tmp, data);
            MailboxSession.moveAtomic(tmp, dir.resolve(name));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static final Map<String, String> BAD_QUERY = new LinkedHashMap<>();

    /** Parametres de la requete ; {@link #BAD_QUERY} si elle est illisible ou repete un parametre. */
    private static Map<String, String> query(String raw) {
        Map<String, String> m = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return m;
        try {
            for (String part : raw.split("&")) {
                int eq = part.indexOf('=');
                String k = URLDecoder.decode(eq < 0 ? part : part.substring(0, eq), StandardCharsets.UTF_8);
                String v = eq < 0 ? "" : URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
                if (m.put(k, v) != null) return BAD_QUERY;
            }
        } catch (IllegalArgumentException e) {
            return BAD_QUERY;
        }
        return m;
    }

    private static void text(HttpExchange ex, int status, String s) throws IOException {
        reply(ex, status, "text/plain; charset=utf-8", s.getBytes(StandardCharsets.UTF_8));
    }

    private static void reply(HttpExchange ex, int status, String type, byte[] b) throws IOException {
        ex.getResponseHeaders().add("Content-Type", type);
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) {
            try (OutputStream o = ex.getResponseBody()) { o.write(b); }
        }
    }
}
