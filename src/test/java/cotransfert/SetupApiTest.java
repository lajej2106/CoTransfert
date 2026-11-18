package cotransfert;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Essai de l'API de reglage (SetupApi servie par WebServer), sans dependance :
 * simple main, code de sortie non nul en cas d'echec, aucun service reel.
 *
 * Le serveur web tourne dans ce processus, sur un port libre de la boucle
 * locale, avec un cotransfert.conf temporaire ; l'arret et la relance sont
 * remplaces par une action qui note le code demande. Les requetes partent d'une
 * socket brute, pour choisir librement Host, Origin et les autres en-tetes.
 *
 *   1. Conf vide : configured=false, dans /api/config et dans /api/status.
 *   2. Gardes : X-CoTransfert, Content-Type, Host, Origin, methode, taille et
 *      forme du corps, adresse distante hors boucle locale.
 *   3. Validation : chaque erreur avec son champ, rien d'ecrit.
 *   4. Enregistrement complet, relecture du fichier cle par cle, relance (75).
 *   5. Secrets : jamais renvoyes, vide = inchange, clear = efface.
 *   6. /api/secret/new et /api/secret/reveal.
 *   7. /api/test : disque, WebDAV et Drime (faux serveurs), delai maximal,
 *      /api/status qui repond pendant un essai bloque.
 *   8. /api/channel : chaque cas du choix du canal (inconnu, deselection, non
 *      regle, sans secret, direct non teste, essai KO, force, essai OK, canal
 *      deja choisi re-teste sans relance), rien d'autre que channel= ne change.
 *   9. Mise a jour par le canal et journal : updateAuto dans /api/config,
 *      /api/update/publish, /api/update/check, /api/update/install (reponses
 *      exactes, etat dans /api/status, relance), /api/log, et leurs gardes.
 *  10. /api/browse sans ecran, /api/quit.
 *
 * Usage : java -cp <classes> cotransfert.SetupApiTest <dossier de travail>
 */
public final class SetupApiTest {

    private static final String SECRET = "secret-partage-api-0123456789";
    private static final String USER = "utilisateur";
    private static final String PASSWORD = "mot-de-passe-webdav-api-4417";
    private static final String TOKEN = "jeton-drime-api-882113";
    private static final String FORBIDDEN = "{\"ok\":false,\"message\":\"reserve a ce poste\"}";

    private static int port;
    private static Path work, conf;
    private static final LinkedBlockingQueue<Integer> exits = new LinkedBlockingQueue<>();

    record Resp(int status, String headers, String body) {
        Map<String, Object> json() throws IOException {
            Object o = DrimeRelayStore.parseJson(body);
            if (!(o instanceof Map<?, ?> m)) throw new IOException("objet JSON attendu: " + body);
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
            return out;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) { System.err.println("usage: SetupApiTest <dossier de travail>"); System.exit(2); }
        // Pas de boite de dialogue pendant l'essai : /api/browse doit repondre "indisponible".
        System.setProperty("java.awt.headless", "true");
        Thread dog = new Thread(() -> {
            try { Thread.sleep(120_000); } catch (InterruptedException e) { return; }
            System.err.println("ECHEC: delai global depasse (blocage)");
            Runtime.getRuntime().halt(3);
        }, "watchdog");
        dog.setDaemon(true);
        dog.start();

        work = Paths.get(args[0]).toAbsolutePath().normalize();
        wipe(work);
        Files.createDirectories(work);
        conf = work.resolve("poste").resolve("cotransfert.conf");

        SetupApi api = new SetupApi(conf, "POSTE-DE-TEST", exits::add);
        api.exitDelayMs = 50;
        HttpServer srv = WebServer.start(0, true, api);
        port = srv.getAddress().getPort();
        FakeWebDavServer dav = new FakeWebDavServer(USER, PASSWORD);
        FakeDrimeServer drime = new FakeDrimeServer(TOKEN);
        try {
            emptyConf();
            guards();
            validation();
            fullSave(dav, drime);
            secrets();
            secretRoutes();
            channelTests(api, dav, drime);
            channelChoice(dav, drime);
            updateRoutes();
            conflictRoutes();
            kmRoutes(api);
            browseAndQuit();
            remoteAddress();
        } finally {
            srv.stop(0);
            dav.stop();
            drime.stop();
        }
        System.out.println("SETUP API TEST: OK");
        System.exit(0);
    }

    // --- 1. conf vide -------------------------------------------------------

    private static void emptyConf() throws Exception {
        Resp r = get("/api/config");
        check(r.status == 200, "GET config: " + r.status);
        check(r.headers.toLowerCase().contains("cache-control: no-store"), "Cache-Control absent");
        Map<String, Object> c = r.json();
        check(Boolean.FALSE.equals(c.get("configured")), "conf vide: configured");
        check("".equals(c.get("role")) && Boolean.FALSE.equals(c.get("master")), "conf vide: role/master");
        check(((List<?>) c.get("roots")).isEmpty(), "conf vide: roots");
        check(Boolean.FALSE.equals(c.get("secretSet")), "conf vide: secretSet");
        check("".equals(c.get("channel")) && !c.containsKey("channels"), "conf vide: channel=\"\" attendu, sans channels: " + r.body);
        check("keep-both".equals(c.get("conflict")) && "link".equals(c.get("discovery")) && "system".equals(c.get("proxy"))
                && "java".equals(c.get("truststore")) && "".equals(c.get("relay")) && "".equals(c.get("peer")), "conf vide: defauts");
        Map<?, ?> w = (Map<?, ?>) c.get("webdav"), d = (Map<?, ?>) c.get("drime");
        check("".equals(w.get("url")) && "".equals(w.get("user")) && Boolean.FALSE.equals(w.get("passwordSet")), "conf vide: webdav");
        check(Boolean.FALSE.equals(d.get("tokenSet")) && "CoTransfertRelais".equals(d.get("folder")), "conf vide: drime");
        check(conf.toString().equals(c.get("configPath")) && "POSTE-DE-TEST".equals(c.get("host")), "conf vide: configPath/host");
        check(Boolean.FALSE.equals(c.get("updateAuto")) && r.body.contains("\"updateAuto\":false"), "conf vide: updateAuto doit etre le booleen false: " + r.body);

        Status.get().setConfigured(false);
        Map<?, ?> self = (Map<?, ?>) get("/api/status").json().get("self");
        check(Boolean.FALSE.equals(self.get("configured")), "status: self.configured=false attendu");
        // "update" est toujours la, meme quand la mise a jour est indisponible (la page s'en sert pour montrer le journal).
        check(self.get("update") instanceof Map<?, ?> u0 && Boolean.FALSE.equals(u0.get("supported")) && Boolean.FALSE.equals(u0.get("canPublish"))
                && Boolean.FALSE.equals(u0.get("available")) && "".equals(u0.get("current")) && "".equals(u0.get("version")) && "".equals(u0.get("state"))
                && u0.get("message") instanceof String m0 && !m0.isEmpty(), "status: self.update par defaut: " + self.get("update"));
        check(self.get("boot") instanceof Long b && b > 0 && self.get("boot").equals(((Map<?, ?>) get("/api/status").json().get("self")).get("boot")),
                "status: self.boot attendu, stable dans un meme processus");
        Status.get().setConfigured(true);
        self = (Map<?, ?>) get("/api/status").json().get("self");
        check(Boolean.TRUE.equals(self.get("configured")), "status: self.configured=true attendu");
        Status.get().setConfigured(false);
        System.out.println("  ok  conf vide");
    }

    // --- 2. gardes ----------------------------------------------------------

    private static void guards() throws Exception {
        String ok = "{\"role\":\"zzz\"}";   // jamais enregistre : le role est refuse
        Resp r = post("/api/config", ok, "X-CoTransfert", null);
        check(r.status == 403 && r.body.equals(FORBIDDEN), "sans X-CoTransfert: " + r.status + " " + r.body);
        r = post("/api/config", ok, "X-CoTransfert", "0");
        check(r.status == 403, "X-CoTransfert: 0 -> " + r.status);
        r = post("/api/config", ok, "Content-Type", "text/plain");
        check(r.status == 403 && r.body.equals(FORBIDDEN), "Content-Type text/plain: " + r.status);
        r = post("/api/config", ok, "Content-Type", "application/x-www-form-urlencoded");
        check(r.status == 403, "Content-Type formulaire: " + r.status);
        r = post("/api/config", ok, "Content-Type", "application/json; charset=utf-8");
        check(r.status == 200 && r.body.contains("\"errors\""), "Content-Type avec charset: " + r.status + " " + r.body);

        // Host : seul ce poste, par ses noms de boucle locale.
        for (String h : new String[] { "evil.example", "evil.example:" + port, "localhost.evil.example:" + port, "192.0.2.7:" + port,
                "localhost:" + port + "@evil.example", "localhost:abc" }) {
            r = request("GET", "/api/config", null, "Host", h);
            check(r.status == 403 && r.body.equals(FORBIDDEN), "GET avec Host " + h + ": " + r.status);
            r = post("/api/secret/reveal", "{}", "Host", h);
            check(r.status == 403, "POST avec Host " + h + ": " + r.status);
        }
        r = request("GET", "/api/config", null, "Host", null);
        check(r.status == 403 || r.status == 400, "GET sans Host: " + r.status);
        for (String h : new String[] { "localhost", "LOCALHOST:" + port, "127.0.0.1:" + port, "127.0.0.1", "[::1]:" + port, "[::1]" }) {
            r = request("GET", "/api/config", null, "Host", h);
            check(r.status == 200, "GET avec Host " + h + ": " + r.status);
        }

        // Origin : s'il est present, le meme hote que Host.
        for (String o : new String[] { "http://evil.example", "https://localhost:" + port, "http://localhost:1", "null",
                "http://127.0.0.1:" + port }) {
            r = request("GET", "/api/config", null, "Origin", o);
            check(r.status == 403 && r.body.equals(FORBIDDEN), "GET avec Origin " + o + ": " + r.status);
            r = post("/api/secret/new", "{}", "Origin", o);
            check(r.status == 403, "POST avec Origin " + o + ": " + r.status);
        }
        r = post("/api/secret/new", "{}", "Origin", "http://localhost:" + port);
        check(r.status == 200, "POST avec Origin de la page: " + r.status);
        r = request("GET", "/api/config", null, "Host", "127.0.0.1:" + port, "Origin", "http://127.0.0.1:" + port);
        check(r.status == 200, "GET avec Origin 127.0.0.1: " + r.status);

        // /api/status reste lisible quel que soit Host.
        r = request("GET", "/api/status", null, "Host", "192.0.2.7:" + port);
        check(r.status == 200 && r.body.contains("\"self\""), "status avec Host etranger: " + r.status);

        // Methodes.
        r = request("PUT", "/api/config", "{}", "Content-Type", "application/json", "X-CoTransfert", "1");
        check(r.status == 405 && r.headers.contains("Allow: GET, POST") && r.body.contains("\"ok\":false"), "PUT config: " + r.status);
        r = request("DELETE", "/api/config", null);
        check(r.status == 405, "DELETE config: " + r.status);
        r = request("OPTIONS", "/api/config", null);
        check(r.status == 405 && !r.headers.toLowerCase().contains("access-control"), "OPTIONS config: " + r.status);
        for (String p : new String[] { "/api/secret/new", "/api/secret/reveal", "/api/test", "/api/channel", "/api/browse", "/api/quit" }) {
            r = get(p);
            check(r.status == 405 && r.headers.contains("Allow: POST"), "GET " + p + ": " + r.status);
        }
        check(exits.isEmpty(), "GET /api/quit a declenche un arret");
        r = post("/api/channel", "{\"channel\":\"direct\"}", "X-CoTransfert", null);
        check(r.status == 403 && r.body.equals(FORBIDDEN), "channel sans X-CoTransfert: " + r.status + " " + r.body);
        r = post("/api/channel", "{\"channel\":\"direct\"}", "Content-Type", "text/plain");
        check(r.status == 403, "channel en text/plain: " + r.status);
        r = post("/api/channel", "{\"channel\":\"direct\"}", "Host", "evil.example:" + port);
        check(r.status == 403, "channel avec Host etranger: " + r.status);
        r = post("/api/channel", "{\"channel\":\"direct\"}", "Origin", "http://evil.example");
        check(r.status == 403, "channel avec Origin etranger: " + r.status);

        // Corps : taille et forme.
        r = post("/api/config", "{\"role\":\"hub\",\"x\":\"" + "a".repeat(70 * 1024) + "\"}");
        check(r.status == 413 && r.body.contains("\"ok\":false"), "corps de 70 Kio: " + r.status);
        r = post("/api/config", "{\"role\":\"zzz\",\"x\":\"" + "a".repeat(60 * 1024) + "\"}");
        check(r.status == 200, "corps de 60 Kio: " + r.status);
        for (String bad : new String[] { "{", "{\"role\":}", "[]", "\"hub\"", "42", "{\"role\":\"hub\"} x", "role=hub" }) {
            r = post("/api/config", bad);
            check(r.status == 400 && r.body.contains("\"ok\":false"), "corps invalide " + bad + ": " + r.status);
        }

        // Routes inconnues : du JSON, pas la page.
        r = get("/api/inconnue");
        check(r.status == 404 && r.body.contains("\"ok\":false"), "route inconnue: " + r.status);
        r = get("/api/config/x");
        check(r.status == 404, "/api/config/x: " + r.status);
        r = get("/");
        check(r.status == 200 && r.headers.toLowerCase().contains("text/html"), "page: " + r.status);
        check(!Files.exists(conf) && exits.isEmpty(), "une requete refusee a ecrit la conf ou demande un arret");
        System.out.println("  ok  gardes");
    }

    // --- 3. validation ------------------------------------------------------

    private static void validation() throws Exception {
        Path dir = Files.createDirectories(work.resolve("valide"));
        Path file = Files.write(work.resolve("fichier.txt"), new byte[] { 1 });
        String d = q(dir.toString());
        String okRoot = "\"roots\":[{\"name\":\"A\",\"path\":" + d + "}]";
        String webdav = "\"secret\":\"" + SECRET + "\",\"webdav\":{\"url\":\"https://cloud.example.org/dav/x\",\"user\":\"u\",\"password\":\"p\"}";

        expect("{}", "role");
        expect("{\"role\":\"chef\"}", "role");
        expect("{\"role\":7}", "role");
        expect("{\"role\":\"hub\",\"master\":\"oui\"}", "master");
        expect("{\"role\":\"hub\",\"conflict\":\"hasard\"}", "conflict");
        expect("{\"role\":\"hub\",\"roots\":{}}", "roots");
        expect("{\"role\":\"hub\",\"roots\":[" + okRoot.substring(9, okRoot.length() - 1) + ",7]}", "roots[1]");
        expect("{\"role\":\"hub\",\"roots\":[{\"name\":\"\",\"path\":" + d + "}]}", "roots[0].name");
        expect("{\"role\":\"hub\",\"roots\":[{\"path\":" + d + "}]}", "roots[0].name");
        expect("{\"role\":\"hub\",\"roots\":[{\"name\":\"a=b\",\"path\":" + d + "}]}", "roots[0].name");
        expect("{\"role\":\"hub\",\"roots\":[{\"name\":\"a\\nb\",\"path\":" + d + "}]}", "roots[0].name");
        expect("{\"role\":\"hub\",\"roots\":[{\"name\":\"a/b\",\"path\":" + d + "}]}", "roots[0].name");
        expect("{\"role\":\"hub\",\"roots\":[{\"name\":\"A\",\"path\":" + d + "},{\"name\":\"a\",\"path\":" + q(dir.resolve("x").toString()) + "}]}",
                "roots[1].name");
        expect("{\"role\":\"hub\",\"roots\":[{\"name\":\"A\",\"path\":\"\"}]}", "roots[0].path");
        expect("{\"role\":\"hub\",\"roots\":[{\"name\":\"A\"}]}", "roots[0].path");
        expect("{\"role\":\"hub\",\"roots\":[{\"name\":\"A\",\"path\":\"relatif\\\\dossier\"}]}", "roots[0].path");
        expect("{\"role\":\"hub\",\"roots\":[{\"name\":\"A\",\"path\":" + q(file.toString()) + "}]}", "roots[0].path");
        expect("{\"role\":\"hub\",\"roots\":[{\"name\":\"A\",\"path\":" + q(file.resolve("dessous").toString()) + "}]}", "roots[0].path");
        expect("{\"role\":\"hub\",\"roots\":[{\"name\":\"A\",\"path\":" + d + "},{\"name\":\"B\",\"path\":" + d + "}]}", "roots[1].path");
        expect("{\"role\":\"hub\"," + okRoot + ",\"secret\":\"trop-court\"}", "secret");
        expect("{\"role\":\"hub\"," + okRoot + ",\"secret\":12345678901234567}", "secret");
        expect("{\"role\":\"hub\"," + okRoot + ",\"clear\":[\"role\"]}", "clear");
        // channels (ancienne bascule) et channel ne sont plus des champs de /api/config : ignores, jamais une erreur.
        Resp ign = post("/api/config", "{\"role\":\"chef\",\"channels\":[\"direct\",\"ftp\"],\"channel\":\"ftp\"}");
        check(fields(ign).equals(List.of("role")), "channels/channel doivent etre ignores par /api/config: " + ign.body);
        ign = post("/api/config", "{\"role\":\"chef\",\"channels\":7,\"channel\":7}");
        check(fields(ign).equals(List.of("role")), "channels/channel non textuels doivent etre ignores: " + ign.body);
        expect("{\"role\":\"hub\",\"secret\":\"" + SECRET + "\",\"webdav\":{\"url\":\"http://cloud.example.org/dav/x\",\"user\":\"u\",\"password\":\"p\"}}", "webdav.url");
        expect("{\"role\":\"hub\",\"secret\":\"" + SECRET + "\",\"webdav\":{\"url\":\"https://u:p@cloud.example.org/dav\",\"user\":\"u\",\"password\":\"p\"}}", "webdav.url");
        expect("{\"role\":\"hub\",\"secret\":\"" + SECRET + "\",\"webdav\":{\"url\":\"cloud\",\"user\":\"u\",\"password\":\"p\"}}", "webdav.url");
        expect("{\"role\":\"hub\",\"secret\":\"" + SECRET + "\",\"webdav\":{\"url\":\"https://cloud.example.org/dav/x\",\"password\":\"p\"}}", "webdav.user");
        expect("{\"role\":\"hub\",\"secret\":\"" + SECRET + "\",\"webdav\":{\"url\":\"https://cloud.example.org/dav/x\",\"user\":\"u\"}}", "webdav.password");
        expect("{\"role\":\"hub\",\"webdav\":7}", "webdav");
        expect("{\"role\":\"hub\",\"webdav\":{\"url\":\"https://cloud.example.org/dav/x\",\"user\":\"u\",\"password\":\"p\"}}", "secret");
        expect("{\"role\":\"hub\",\"drime\":{\"token\":\"jeton-sans-secret-partage\"}}", "secret");
        expect("{\"role\":\"hub\",\"secret\":\"" + SECRET + "\",\"drime\":{\"token\":\"jeton avec espace\"}}", "drime.token");
        expect("{\"role\":\"hub\",\"drime\":{\"folder\":\"../ailleurs\"}}", "drime.folder");
        expect("{\"role\":\"hub\",\"relay\":\"relatif\"}", "relay");
        expect("{\"role\":\"hub\",\"relay\":\"*:\\\\\"}", "relay");
        expect("{\"role\":\"client\",\"peer\":\"un hote\"}", "peer");
        expect("{\"role\":\"client\",\"discovery\":\"wan\"}", "discovery");
        expect("{\"role\":\"client\",\"proxy\":\"hote-sans-port\"}", "proxy");
        expect("{\"role\":\"client\",\"proxy\":\"hote:99999\"}", "proxy");
        expect("{\"role\":\"client\",\"truststore\":\"mac\"}", "truststore");

        // Plusieurs erreurs a la fois : toutes rendues, chacune avec son champ et un message.
        Resp r = post("/api/config", "{\"role\":\"chef\",\"conflict\":\"x\",\"roots\":[{\"name\":\"\",\"path\":\"rel\"}],\"" + "relay\":\"rel\"," + webdav.replace("https:", "ftp:") + "}");
        List<String> fields = fields(r);
        check(fields.equals(List.of("role", "conflict", "roots[0].name", "roots[0].path", "relay", "webdav.url")), "erreurs multiples: " + fields);
        for (Object e : (List<?>) r.json().get("errors"))
            check(((Map<?, ?>) e).get("message") instanceof String m && !m.isBlank(), "erreur sans message");
        check(!r.body.contains(SECRET), "le secret saisi revient dans les erreurs");

        check(!Files.exists(conf), "une saisie refusee a ete enregistree");
        check(!Files.exists(dir.resolve("x")), "une saisie refusee a cree un dossier");
        check(exits.poll(300, TimeUnit.MILLISECONDS) == null, "une saisie refusee a demande une relance");
        System.out.println("  ok  validation");
    }

    private static void expect(String body, String field) throws Exception {
        Resp r = post("/api/config", body);
        check(r.status == 200, "validation " + field + ": statut " + r.status + " " + r.body);
        check(Boolean.FALSE.equals(r.json().get("ok")), "validation " + field + ": ok attendu a false: " + r.body);
        check(fields(r).contains(field), "validation: champ " + field + " attendu, recu " + r.body + " pour " + body);
    }

    private static List<String> fields(Resp r) throws IOException {
        List<String> out = new ArrayList<>();
        if (r.json().get("errors") instanceof List<?> l)
            for (Object e : l) out.add(String.valueOf(((Map<?, ?>) e).get("field")));
        return out;
    }

    // --- 4. enregistrement complet -----------------------------------------

    private static Path rootA, rootB, relayDir;

    private static void fullSave(FakeWebDavServer dav, FakeDrimeServer drime) throws Exception {
        rootA = Files.createDirectories(work.resolve("Projet A"));
        rootB = work.resolve("nouveau").resolve("Projet B");   // absent : cree a l'enregistrement
        relayDir = Files.createDirectories(work.resolve("relais"));
        // Cles que la page ne regle pas : elles doivent survivre a un enregistrement.
        Files.createDirectories(conf.getParent());
        Files.write(conf, List.of("drime.url=" + drime.apiUrl(), "drime.workspace=7", "relay.Projet A=" + relayDir));

        String body = "{\"configured\":false,\"role\":\"Client\",\"master\":true,\"conflict\":\"newer-wins\","
                + "\"roots\":[{\"name\":\" Projet A \",\"path\":" + q(rootA.toString()) + ",\"exists\":true},"
                + "{\"name\":\"Projet B\",\"path\":" + q(rootB.toString()) + "}],"
                + "\"secret\":\"" + SECRET + "\",\"secretSet\":false,"
                + "\"channels\":[\"disque\",\"direct\",\"webdav\",\"drime\"],\"channel\":\"drime\","
                + "\"webdav\":{\"url\":" + q(dav.baseUrl()) + ",\"user\":\"" + USER + "\",\"password\":\"" + PASSWORD + "\",\"passwordSet\":false},"
                + "\"drime\":{\"token\":\"" + TOKEN + "\",\"folder\":\"Transit/Essai\"},"
                + "\"relay\":" + q(relayDir.toString()) + ",\"peer\":\"192.0.2.1\",\"discovery\":\"lan\","
                + "\"proxy\":\"none\",\"truststore\":\"windows\",\"configPath\":\"ignore\",\"host\":\"ignore\"}";
        Resp r = post("/api/config", body);
        check(r.status == 200 && r.body.equals("{\"ok\":true,\"restart\":true}"), "enregistrement: " + r.status + " " + r.body);
        Integer code = exits.poll(3, TimeUnit.SECONDS);
        check(code != null && code == 75, "relance attendue avec le code 75, recu " + code);
        check(Files.isDirectory(rootB), "dossier absent non cree");
        check(!Files.exists(conf.resolveSibling("cotransfert.conf.tmp")), "fichier temporaire laisse a cote de la conf");

        Config c = Config.load(conf);
        check("client".equals(c.role), "role: " + c.role);
        check(Boolean.TRUE.equals(c.master), "master: " + c.master);
        check("newer-wins".equals(c.conflict), "conflict: " + c.conflict);
        check(new ArrayList<>(c.roots.keySet()).equals(List.of("Projet A", "Projet B")), "roots: " + c.roots.keySet());
        check(rootA.toString().equals(c.roots.get("Projet A")) && rootB.toString().equals(c.roots.get("Projet B")), "chemins: " + c.roots);
        check(SECRET.equals(c.secret), "secret");
        check(c.channel.isEmpty(), "/api/config ne doit pas choisir de canal: " + c.channel);
        for (String line : Files.readAllLines(conf))
            check(!line.trim().startsWith("channels") && !line.trim().startsWith("channel="), "ligne de canal ecrite par /api/config: " + line);
        check(dav.baseUrl().equals(c.webdavUrl) && USER.equals(c.webdavUser) && PASSWORD.equals(c.webdavPassword), "webdav");
        check(TOKEN.equals(c.drimeToken) && "Transit/Essai".equals(c.drimeFolder), "drime: " + c.drimeFolder);
        check(drime.apiUrl().equals(c.drimeUrl) && c.drimeWorkspace == 7, "drime.url / drime.workspace perdus");
        check(relayDir.toString().equals(c.relay) && relayDir.toString().equals(c.relays.get("Projet A")), "relay: " + c.relay + " " + c.relays);
        check("192.0.2.1".equals(c.peer) && "lan".equals(c.discovery), "peer/discovery");
        check("none".equals(c.proxy) && "windows".equals(c.tlsTruststore), "proxy/truststore");
        check(c.configured(), "configured");

        // master : la valeur par defaut du role n'est pas figee dans le fichier.
        check(ok(post("/api/config", "{\"master\":false}")) && Config.load(conf).master == null, "master par defaut (client) non remis a vide");
        check(ok(post("/api/config", "{\"master\":true}")) && Boolean.TRUE.equals(Config.load(conf).master), "master explicite perdu");
        // Champs absents = inchanges ; roots remplace la liste entiere.
        check(ok(post("/api/config", "{\"conflict\":\"keep-both\"}")), "enregistrement partiel");
        c = Config.load(conf);
        check("keep-both".equals(c.conflict) && "client".equals(c.role) && c.roots.size() == 2 && SECRET.equals(c.secret)
                && "lan".equals(c.discovery) && c.channel.isEmpty(), "un champ absent a change");
        check(ok(post("/api/config", "{\"roots\":[{\"name\":\"Projet A\",\"path\":" + q(rootA.toString()) + "}]}")), "remplacement de roots");
        check(Config.load(conf).roots.keySet().equals(java.util.Set.of("Projet A")), "roots non remplace");
        // Formulaire complet tel que la page l'envoie : sans master, sans secret ; une chaine vide retire la valeur.
        check(ok(post("/api/config", "{\"master\":true,\"peer\":\"192.0.2.1\"}")), "preparation du formulaire complet");
        String form = "{\"role\":\"client\",\"conflict\":\"keep-both\",\"roots\":[{\"name\":\"Projet A\",\"path\":" + q(rootA.toString()) + "}],"
                + "\"channels\":[\"direct\",\"webdav\",\"drime\"],\"webdav\":{\"url\":" + q(dav.baseUrl()) + ",\"user\":\"" + USER + "\"},"
                + "\"drime\":{\"folder\":\"\"},\"relay\":\"\",\"peer\":\"\",\"discovery\":\"link\",\"proxy\":\"proxy.example.org:8080\",\"truststore\":\"java\"}";
        check(ok(post("/api/config", form)), "formulaire complet");
        c = Config.load(conf);
        check(Boolean.TRUE.equals(c.master), "master absent : la valeur enregistree doit rester");
        check(c.relay.isEmpty() && c.peer.isEmpty(), "une chaine vide doit retirer relay et peer");
        check("CoTransfertRelais".equals(c.drimeFolder) && drime.apiUrl().equals(c.drimeUrl) && c.drimeWorkspace == 7, "drime: dossier par defaut, url et espace gardes");
        check(SECRET.equals(c.secret) && PASSWORD.equals(c.webdavPassword) && TOKEN.equals(c.drimeToken), "secrets absents du formulaire : inchanges");
        check(c.channel.isEmpty() && "proxy.example.org:8080".equals(c.proxy) && "link".equals(c.discovery), "canal/proxy/decouverte");
        Resp g = get("/api/config");
        check("".equals(g.json().get("channel")) && !g.json().containsKey("channels"), "GET: channel attendu a la place de channels: " + g.body);
        check(ok(post("/api/config", "{\"channel\":\"disque\",\"relay\":" + q(relayDir.toString()) + ",\"proxy\":\"none\"}")), "retour aux reglages de l'essai");
        check(Config.load(conf).channel.isEmpty(), "channel pose par /api/config");
        check(exits.poll(300, TimeUnit.MILLISECONDS) == null, "une seule relance attendue par instance");
        System.out.println("  ok  enregistrement complet");
    }

    private static boolean ok(Resp r) throws IOException {
        return r.status == 200 && Boolean.TRUE.equals(r.json().get("ok"));
    }

    // --- 5. secrets ---------------------------------------------------------

    private static void secrets() throws Exception {
        Resp r = get("/api/config");
        check(noSecret(r.body), "GET config contient un secret: " + r.body);
        Map<String, Object> c = r.json();
        check(Boolean.TRUE.equals(c.get("configured")) && Boolean.TRUE.equals(c.get("secretSet")), "configured/secretSet");
        check(Boolean.TRUE.equals(((Map<?, ?>) c.get("webdav")).get("passwordSet")) && Boolean.TRUE.equals(((Map<?, ?>) c.get("drime")).get("tokenSet")), "passwordSet/tokenSet");
        check(!c.containsKey("secret") && !((Map<?, ?>) c.get("webdav")).containsKey("password") && !((Map<?, ?>) c.get("drime")).containsKey("token"), "cle secrete presente");
        Map<?, ?> root = (Map<?, ?>) ((List<?>) c.get("roots")).get(0);
        check("Projet A".equals(root.get("name")) && rootA.toString().equals(root.get("path")) && Boolean.TRUE.equals(root.get("exists")), "roots: " + root);
        check(Boolean.TRUE.equals(c.get("master")) && "client".equals(c.get("role")), "role/master effectifs");
        check(noSecret(get("/api/status").body), "/api/status contient un secret");

        // Vide ou absent = inchange.
        check(ok(post("/api/config", "{\"secret\":\"\",\"webdav\":{\"password\":\"\"},\"drime\":{\"token\":\"  \"}}")), "secrets vides refuses");
        Config f = Config.load(conf);
        check(SECRET.equals(f.secret) && PASSWORD.equals(f.webdavPassword) && TOKEN.equals(f.drimeToken), "un secret vide a efface le secret enregistre");
        check(ok(post("/api/config", "{\"webdav\":{\"user\":\"" + USER + "\"},\"drime\":{}}")), "secrets absents refuses");
        f = Config.load(conf);
        check(SECRET.equals(f.secret) && PASSWORD.equals(f.webdavPassword) && TOKEN.equals(f.drimeToken), "un secret absent a efface le secret enregistre");

        // clear = efface ; le secret partage reste exige tant qu'un canal en ligne est regle.
        r = post("/api/config", "{\"clear\":[\"secret\"]}");
        check(fields(r).equals(List.of("secret")) && SECRET.equals(Config.load(conf).secret), "secret efface malgre un canal en ligne: " + r.body);
        r = post("/api/config", "{\"clear\":[\"webdav.password\"]}");
        check(fields(r).equals(List.of("webdav.password")), "mot de passe efface en laissant le canal regle: " + r.body);
        check(ok(post("/api/config", "{\"clear\":[\"drime.token\"]}")), "clear drime.token");
        f = Config.load(conf);
        check(f.drimeToken.isEmpty() && SECRET.equals(f.secret) && PASSWORD.equals(f.webdavPassword), "clear drime.token: mauvais effet");
        check(ok(post("/api/config", "{\"clear\":[\"webdav.password\",\"secret\"],\"webdav\":{\"url\":\"\"}}")), "clear webdav.password + secret");
        f = Config.load(conf);
        check(f.secret.isEmpty() && f.webdavPassword.isEmpty() && f.webdavUrl.isEmpty() && USER.equals(f.webdavUser), "clear: mauvais effet");
        r = get("/api/config");
        check(Boolean.FALSE.equals(r.json().get("secretSet")), "secretSet apres clear");
        // Une nouvelle valeur l'emporte sur clear dans la meme requete.
        check(ok(post("/api/config", "{\"clear\":[\"secret\"],\"secret\":\"  " + SECRET + "  \"}")) && SECRET.equals(Config.load(conf).secret), "clear + nouvelle valeur");
        System.out.println("  ok  secrets");
    }

    private static boolean noSecret(String s) {
        return !s.contains(SECRET) && !s.contains(PASSWORD) && !s.contains(TOKEN);
    }

    // --- 6. /api/secret/new et /api/secret/reveal ---------------------------

    private static void secretRoutes() throws Exception {
        byte[] before = Files.readAllBytes(conf);
        String a = (String) post("/api/secret/new", "{}").json().get("secret");
        String b = (String) post("/api/secret/new", "").json().get("secret");
        check(a != null && a.length() >= 16 && b != null && b.length() >= 16 && !a.equals(b), "secret genere: " + a + " / " + b);
        check(java.util.Arrays.equals(before, Files.readAllBytes(conf)), "/api/secret/new a modifie la conf");
        check(SECRET.equals(post("/api/secret/reveal", "{}").json().get("secret")), "reveal");
        // Le secret genere est acceptable tel quel.
        check(ok(post("/api/config", "{\"secret\":\"" + a + "\"}")) && a.equals(post("/api/secret/reveal", "{}").json().get("secret")), "secret genere refuse");
        check(ok(post("/api/config", "{\"clear\":[\"secret\"]}")) && "".equals(post("/api/secret/reveal", "{}").json().get("secret")), "reveal sans secret");
        check(ok(post("/api/config", "{\"secret\":\"" + SECRET + "\"}")), "remise du secret");
        System.out.println("  ok  secret/new, secret/reveal");
    }

    // --- 7. /api/test -------------------------------------------------------

    private static void channelTests(SetupApi api, FakeWebDavServer dav, FakeDrimeServer drime) throws Exception {
        byte[] before = Files.readAllBytes(conf);
        String net = ",\"proxy\":\"none\",\"truststore\":\"java\"";

        // Disque.
        Resp r = post("/api/test", "{\"channel\":\"disque\",\"relay\":" + q(relayDir.toString()) + "}");
        check(r.status == 200 && r.body.equals("{\"ok\":true,\"message\":\"joignable\"}"), "disque present: " + r.body);
        r = post("/api/test", "{\"channel\":\"disque\"}");
        check(testOk(r), "disque, valeur enregistree: " + r.body);
        r = post("/api/test", "{\"channel\":\"disque\",\"relay\":" + q(work.resolve("absent").toString()) + "}");
        check(testKo(r, "support absent"), "disque absent: " + r.body);
        r = post("/api/test", "{\"channel\":\"disque\",\"relay\":\"\"}");
        check(testKo(r, "requis"), "disque non regle: " + r.body);

        // WebDAV : la conf n'a plus ni adresse ni mot de passe, tout vient du formulaire.
        String w = "\"webdav\":{\"url\":" + q(dav.baseUrl()) + ",\"user\":\"" + USER + "\"";
        r = post("/api/test", "{\"channel\":\"webdav\"," + w + ",\"password\":\"" + PASSWORD + "\"}" + net + "}");
        check(testOk(r), "webdav joignable: " + r.body);
        r = post("/api/test", "{\"channel\":\"webdav\"," + w + ",\"password\":\"mauvais-mot-de-passe-9911\"}" + net + "}");
        check(testKo(r, "identifiants refuses") && !r.body.contains("9911"), "webdav, mauvais mot de passe: " + r.body);
        r = post("/api/test", "{\"channel\":\"webdav\"," + w + "}" + net + "}");
        check(testKo(r, "requis"), "webdav sans mot de passe nulle part: " + r.body);
        r = post("/api/test", "{\"channel\":\"webdav\",\"webdav\":{\"url\":\"http://cloud.example.org/dav\",\"user\":\"u\",\"password\":\"p\"}" + net + "}");
        check(testKo(r, "https"), "webdav en http: " + r.body);
        // Mot de passe enregistre : un champ absent ou vide le reprend.
        check(ok(post("/api/config", "{" + w + ",\"password\":\"" + PASSWORD + "\"}}")), "enregistrement webdav");
        before = Files.readAllBytes(conf);
        r = post("/api/test", "{\"channel\":\"webdav\"," + w + "}" + net + "}");
        check(testOk(r), "webdav, mot de passe enregistre (absent): " + r.body);
        r = post("/api/test", "{\"channel\":\"webdav\"," + w + ",\"password\":\"\"}" + net + "}");
        check(testOk(r), "webdav, mot de passe enregistre (vide): " + r.body);
        r = post("/api/test", "{\"channel\":\"webdav\"" + net + "}");
        check(testOk(r), "webdav, tout enregistre: " + r.body);

        // Drime : l'adresse de l'API (drime.url) vient de la conf.
        r = post("/api/test", "{\"channel\":\"drime\",\"drime\":{\"folder\":\"Transit\"}" + net + "}");
        check(testKo(r, "jeton requis"), "drime sans jeton: " + r.body);
        r = post("/api/test", "{\"channel\":\"drime\",\"drime\":{\"token\":\"" + TOKEN + "\",\"folder\":\"Transit\"}" + net + "}");
        check(testOk(r), "drime joignable: " + r.body);
        r = post("/api/test", "{\"channel\":\"drime\",\"drime\":{\"token\":\"mauvais-jeton-7733\"}" + net + "}");
        check(testKo(r, "jeton refuse") && !r.body.contains("7733"), "drime, mauvais jeton: " + r.body);

        r = post("/api/test", "{\"channel\":\"direct\"}");
        check(testKo(r, "canal inconnu"), "canal inconnu: " + r.body);
        r = post("/api/test", "{}");
        check(testKo(r, "canal inconnu"), "canal absent: " + r.body);

        // Serveur muet : l'essai est borne, et /api/status repond pendant ce temps.
        api.testTimeoutMs = 1500;
        try (ServerSocket mute = new ServerSocket(0, 5, InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 }))) {
            AtomicReference<Object> out = new AtomicReference<>();
            String body = "{\"channel\":\"webdav\",\"webdav\":{\"url\":\"http://127.0.0.1:" + mute.getLocalPort() + "/dav\",\"user\":\"u\",\"password\":\"p\"}" + net + "}";
            long t0 = System.nanoTime();
            Thread t = new Thread(() -> {
                try { out.set(post("/api/test", body)); } catch (Exception e) { out.set(e); }
            });
            t.start();
            Thread.sleep(300);
            long s0 = System.nanoTime();
            for (int i = 0; i < 5; i++) check(get("/api/status").status == 200 && get("/api/config").status == 200, "IHM bloquee pendant un essai");
            long statusMs = (System.nanoTime() - s0) / 1_000_000;
            check(t.isAlive(), "l'essai du serveur muet a deja rendu la main");
            check(statusMs < 1000, "/api/status lent pendant un essai: " + statusMs + " ms");
            t.join(10_000);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            check(out.get() instanceof Resp, "essai du serveur muet: " + out.get());
            check(testKo((Resp) out.get(), "delai depasse"), "serveur muet: " + ((Resp) out.get()).body);
            check(ms >= 1400 && ms < 6000, "delai maximal non respecte: " + ms + " ms");
        }
        api.testTimeoutMs = 20_000;

        check(java.util.Arrays.equals(before, Files.readAllBytes(conf)), "/api/test a modifie la conf");
        System.out.println("  ok  essais de canal");
    }

    private static boolean testOk(Resp r) throws IOException {
        return r.status == 200 && Boolean.TRUE.equals(r.json().get("ok")) && "joignable".equals(r.json().get("message"));
    }

    private static boolean testKo(Resp r, String reason) throws IOException {
        return r.status == 200 && Boolean.FALSE.equals(r.json().get("ok"))
                && r.json().get("message") instanceof String m && m.contains(reason) && noSecret(r.body);
    }

    // --- 8. /api/channel ----------------------------------------------------

    /** Serveur neuf sur la meme conf : une instance ne demande qu'un arret, et chaque cas doit dire s'il relance. */
    private static final class Fresh implements AutoCloseable {
        final LinkedBlockingQueue<Integer> codes = new LinkedBlockingQueue<>();
        final HttpServer srv;
        final int main = port;
        final SetupApi api;

        Fresh() throws IOException {
            api = new SetupApi(conf, "POSTE-DE-TEST", codes::add);
            api.exitDelayMs = 50;
            srv = WebServer.start(0, true, api);
            port = srv.getAddress().getPort();
        }

        /** Vrai si la relance (code 75) a ete demandee. */
        boolean restarted() throws InterruptedException {
            Integer code = codes.poll(1500, TimeUnit.MILLISECONDS);
            check(code == null || code == 75, "code d'arret inattendu: " + code);
            return code != null;
        }

        /** Vrai si aucun arret n'a ete demande. */
        boolean quiet() throws InterruptedException { return codes.poll(400, TimeUnit.MILLISECONDS) == null; }

        @Override public void close() { port = main; srv.stop(0); }
    }

    /** POST /api/channel sur une instance neuve ; relance attendue ou non. */
    private static Resp choose(String body, boolean restart) throws Exception {
        try (Fresh f = new Fresh()) {
            Resp r = post("/api/channel", body);
            check(r.status == 200 && noSecret(r.body), "channel " + body + ": " + r.status + " " + r.body);
            if (restart) check(f.restarted(), "relance (75) attendue apres " + body + ": " + r.body);
            else check(f.quiet(), "aucune relance attendue apres " + body + ": " + r.body);
            return r;
        }
    }

    private static void setConf(java.util.function.Consumer<Config> change) throws IOException {
        Config c = Config.load(conf);
        change.accept(c);
        c.write(conf);
    }

    private static void channelChoice(FakeWebDavServer dav, FakeDrimeServer drime) throws Exception {
        // Depart : WebDAV et disque regles, Drime sans jeton, secret pose, aucun canal choisi.
        Config c0 = Config.load(conf);
        check(c0.channel.isEmpty() && c0.webdavConfigured() && !c0.drimeConfigured() && !c0.relay.isEmpty() && SECRET.equals(c0.secret),
                "etat de depart inattendu");
        check("".equals(get("/api/config").json().get("channel")), "GET config: aucun canal attendu");
        byte[] before = Files.readAllBytes(conf);

        // 1. Nom inconnu (ou absent, ou pas un texte).
        for (String body : new String[] { "{\"channel\":\"ftp\"}", "{}", "{\"channel\":7}", "{\"channel\":\"channels\",\"force\":true}" }) {
            Resp r = choose(body, false);
            check(r.body.equals("{\"ok\":false,\"message\":\"canal inconnu\"}"), "canal inconnu " + body + ": " + r.body);
        }
        // 3. Canal non regle : a regler d'abord, force ou non.
        for (String body : new String[] { "{\"channel\":\"drime\"}", "{\"channel\":\"drime\",\"force\":true}", "{\"channel\":\" Drime \",\"force\":false}" }) {
            Resp r = choose(body, false);
            check(r.body.equals("{\"ok\":false,\"needsConfig\":true,\"message\":\"canal non regle\"}"), "canal non regle " + body + ": " + r.body);
        }
        setConf(c -> { c.relay = ""; c.relays.clear(); });
        Resp r = choose("{\"channel\":\"disque\"}", false);
        check(r.body.equals("{\"ok\":false,\"needsConfig\":true,\"message\":\"canal non regle\"}"), "disque non regle: " + r.body);
        // Relais propre a un dossier seulement : le canal disque est regle, et s'essaie sur ce relais.
        setConf(c -> c.relays.put("Projet A", relayDir.toString()));
        r = choose("{\"channel\":\"disque\"}", true);
        check(r.body.equals("{\"ok\":true,\"restart\":true,\"tested\":true,\"message\":\"joignable\"}"), "disque, relais par dossier: " + r.body);
        setConf(c -> { c.relay = relayDir.toString(); c.channel = ""; });
        // 4. Canal en ligne sans secret partage.
        setConf(c -> c.secret = "");
        for (String body : new String[] { "{\"channel\":\"webdav\"}", "{\"channel\":\"webdav\",\"force\":true}" }) {
            r = choose(body, false);
            check(r.body.equals("{\"ok\":false,\"needsConfig\":true,\"message\":\"secret partage manquant\"}"), "sans secret " + body + ": " + r.body);
        }
        setConf(c -> c.secret = SECRET);
        check(java.util.Arrays.equals(before, Files.readAllBytes(conf)), "un choix refuse a modifie la conf");

        // 5. direct : pas d'essai possible, accepte, enregistre, relance.
        r = choose("{\"channel\":\"direct\"}", true);
        check(r.body.startsWith("{\"ok\":true,\"restart\":true,\"tested\":false,\"message\":\""), "direct: " + r.body);
        check("direct".equals(Config.load(conf).channel) && "direct".equals(get("/api/config").json().get("channel")), "direct non enregistre");
        r = choose("{\"channel\":\"direct\"}", false);
        check(r.body.startsWith("{\"ok\":true,\"restart\":false,\"tested\":false,"), "direct deja choisi: " + r.body);

        // 6. Essai KO sans force : rien n'est enregistre, le choix precedent reste.
        setConf(c -> c.relay = work.resolve("support-absent").toString());
        byte[] ko = Files.readAllBytes(conf);
        r = choose("{\"channel\":\"disque\",\"force\":false}", false);
        check(r.body.equals("{\"ok\":false,\"canForce\":true,\"message\":\"disque absent\"}"), "disque absent: " + r.body);
        check(java.util.Arrays.equals(ko, Files.readAllBytes(conf)) && "direct".equals(Config.load(conf).channel), "un essai KO a modifie la conf");
        // 7a. force : choisi quand meme, sans essai.
        r = choose("{\"channel\":\"disque\",\"force\":true}", true);
        check(r.body.startsWith("{\"ok\":true,\"restart\":true,\"tested\":false,\"message\":\""), "disque force: " + r.body);
        check("disque".equals(Config.load(conf).channel), "disque force non enregistre");
        // Deja choisi et toujours KO : simple re-test, rien ne change ; force : rien a relancer.
        r = choose("{\"channel\":\"disque\"}", false);
        check(r.body.equals("{\"ok\":false,\"canForce\":true,\"message\":\"disque absent\"}") && "disque".equals(Config.load(conf).channel),
                "re-test KO du canal choisi: " + r.body);
        r = choose("{\"channel\":\"disque\",\"force\":true}", false);
        check(r.body.startsWith("{\"ok\":true,\"restart\":false,\"tested\":false,"), "force sur le canal deja choisi: " + r.body);
        setConf(c -> c.relay = relayDir.toString());

        // 7b. Essai OK : enregistre channel= et rien d'autre, relance.
        Config b4 = Config.load(conf);
        r = choose("{\"channel\":\"webdav\"}", true);
        check(r.body.equals("{\"ok\":true,\"restart\":true,\"tested\":true,\"message\":\"joignable\"}"), "webdav joignable: " + r.body);
        Config af = Config.load(conf);
        check("webdav".equals(af.channel), "webdav non enregistre: " + af.channel);
        af.channel = b4.channel;
        Path cmp = conf.resolveSibling("compare.conf"), cmp2 = conf.resolveSibling("compare2.conf");
        af.write(cmp);
        b4.write(cmp2);
        check(Files.readAllLines(cmp).equals(Files.readAllLines(cmp2)), "le choix du canal a change autre chose que channel=");
        Files.delete(cmp);
        Files.delete(cmp2);
        check(SECRET.equals(af.secret) && PASSWORD.equals(af.webdavPassword) && af.roots.size() == 1, "reglages perdus par le choix du canal");
        // 7c. Canal deja choisi : simple re-test, ni ecriture ni relance.
        byte[] chosen = Files.readAllBytes(conf);
        r = choose("{\"channel\":\"webdav\"}", false);
        check(r.body.equals("{\"ok\":true,\"restart\":false,\"tested\":true,\"message\":\"joignable\"}"), "re-test du canal choisi: " + r.body);
        check(java.util.Arrays.equals(chosen, Files.readAllBytes(conf)), "un re-test a modifie la conf");
        // Le canal choisi tombe : le re-test dit KO, le choix reste (aucune bascule).
        dav.setDown(true);
        r = choose("{\"channel\":\"webdav\"}", false);
        Map<String, Object> j = r.json();
        check(Boolean.FALSE.equals(j.get("ok")) && Boolean.TRUE.equals(j.get("canForce")) && j.get("message") instanceof String m && !m.isBlank()
                && !j.containsKey("restart"), "re-test KO: " + r.body);
        check(java.util.Arrays.equals(chosen, Files.readAllBytes(conf)), "un re-test KO a modifie la conf");
        dav.setDown(false);
        // Mauvais identifiants enregistres : KO avec la raison, sans le mot de passe.
        setConf(c -> c.webdavPassword = "mauvais-mot-de-passe-5521");
        r = choose("{\"channel\":\"webdav\"}", false);
        check(r.body.equals("{\"ok\":false,\"canForce\":true,\"message\":\"identifiants refuses\"}") && !r.body.contains("5521"), "webdav refuse: " + r.body);
        setConf(c -> c.webdavPassword = PASSWORD);

        // Drime, une fois regle : meme essai que /api/test, avec les valeurs enregistrees.
        setConf(c -> c.drimeToken = "mauvais-jeton-6634");
        r = choose("{\"channel\":\"drime\"}", false);
        check(Boolean.TRUE.equals(r.json().get("canForce")) && String.valueOf(r.json().get("message")).contains("jeton refuse")
                && !r.body.contains("6634"), "drime, mauvais jeton: " + r.body);
        setConf(c -> c.drimeToken = TOKEN);
        r = choose("{\"channel\":\"DRIME\"}", true);
        check(r.body.equals("{\"ok\":true,\"restart\":true,\"tested\":true,\"message\":\"joignable\"}"), "drime joignable: " + r.body);
        check("drime".equals(Config.load(conf).channel) && "drime".equals(get("/api/config").json().get("channel")), "drime non enregistre");

        // /api/config ne touche pas au canal choisi.
        try (Fresh f = new Fresh()) {
            check(ok(post("/api/config", "{\"channel\":\"direct\",\"channels\":[\"disque\"],\"conflict\":\"keep-both\"}")) && f.restarted(), "enregistrement des reglages");
            check("drime".equals(Config.load(conf).channel), "/api/config a change le canal choisi");
        }

        // 2. "" : deselection, enregistree, relance.
        r = choose("{\"channel\":\"\"}", true);
        check(r.body.equals("{\"ok\":true,\"restart\":true}"), "deselection: " + r.body);
        check(Config.load(conf).channel.isEmpty() && "".equals(get("/api/config").json().get("channel")), "deselection non enregistree");
        for (String line : Files.readAllLines(conf)) check(!line.startsWith("channel"), "ligne de canal apres deselection: " + line);
        setConf(c -> c.drimeToken = "");
        System.out.println("  ok  choix du canal (/api/channel)");
    }

    // --- 9. mise a jour par le canal, journal ---------------------------------

    private static void updateRoutes() throws Exception {
        // updateAuto : booleen dans GET, accepte dans POST, absent = inchange, autre chose qu'un booleen = refuse.
        byte[] confBefore = Files.readAllBytes(conf);
        try (Fresh f = new Fresh()) {
            Resp r = post("/api/config", "{\"updateAuto\":\"oui\"}");
            check(fields(r).equals(List.of("updateAuto")) && f.quiet(), "updateAuto non booleen: " + r.body);
            check(Arrays.equals(confBefore, Files.readAllBytes(conf)), "conf modifiee par un updateAuto invalide");
            r = post("/api/config", "{\"updateAuto\":true}");
            check(r.body.equals("{\"ok\":true,\"restart\":true}") && f.restarted(), "updateAuto=true: " + r.body);
        }
        check(Config.load(conf).updateAuto && Files.readAllLines(conf).contains("update.auto=true"), "update.auto=true non ecrit");
        Resp g = get("/api/config");
        check(Boolean.TRUE.equals(g.json().get("updateAuto")) && g.body.contains("\"updateAuto\":true"), "GET config: updateAuto=true attendu: " + g.body);
        try (Fresh f = new Fresh()) {
            check(ok(post("/api/config", "{\"conflict\":\"keep-both\"}")) && Config.load(conf).updateAuto, "updateAuto absent : doit rester vrai");
            check(f.restarted(), "relance attendue apres un enregistrement");
        }
        try (Fresh f = new Fresh()) {
            check(ok(post("/api/config", "{\"updateAuto\":false}")) && !Config.load(conf).updateAuto, "updateAuto=false non enregistre");
            check(f.restarted(), "relance attendue apres un enregistrement");
        }
        for (String line : Files.readAllLines(conf)) check(!line.startsWith("update.auto="), "update.auto=false doit rester en commentaire: " + line);
        check(Boolean.FALSE.equals(get("/api/config").json().get("updateAuto")), "GET config: updateAuto=false attendu");

        // Sans mise a jour (essais, modes sans agent complet) : chaque route repond, rien ne redemarre.
        try (Fresh f = new Fresh()) {
            Resp r = post("/api/update/publish", "{}");
            check(r.status == 200 && Boolean.FALSE.equals(r.json().get("ok")) && r.json().get("message") instanceof String, "publish sans mise a jour: " + r.body);
            r = post("/api/update/check", "");
            check(r.status == 200 && r.json().keySet().equals(new java.util.LinkedHashSet<>(List.of("ok", "available", "version", "message")))
                    && Boolean.FALSE.equals(r.json().get("ok")) && Boolean.FALSE.equals(r.json().get("available")), "check sans mise a jour: " + r.body);
            r = post("/api/update/install", "{}");
            check(r.status == 200 && Boolean.FALSE.equals(r.json().get("ok")) && !r.body.contains("restart") && f.quiet(), "install sans mise a jour: " + r.body);

            // Gardes : comme les autres routes de reglage.
            for (String path : new String[] { "/api/update/publish", "/api/update/check", "/api/update/install" }) {
                r = post(path, "{}", "X-CoTransfert", null);
                check(r.status == 403 && r.body.equals(FORBIDDEN), path + " sans X-CoTransfert: " + r.status);
                r = post(path, "{}", "Content-Type", "text/plain");
                check(r.status == 403, path + " en text/plain: " + r.status);
                r = post(path, "{}", "Host", "cotransfert.example.org:" + port);
                check(r.status == 403, path + " avec un Host etranger: " + r.status);
                r = post(path, "{}", "Origin", "http://autre.example.org");
                check(r.status == 403, path + " avec une Origin etrangere: " + r.status);
                r = get(path);
                check(r.status == 405, path + " en GET: " + r.status);
                r = post(path + "/x", "{}");
                check(r.status == 404, path + "/x: " + r.status);
            }
            check(f.quiet(), "une route refusee a demande un arret");
        }

        // Deux postes sur un meme disque relais : le hub publie, le client verifie puis installe.
        Path relay = Files.createDirectories(work.resolve("relais-maj"));
        Path hubDir = Files.createDirectories(work.resolve("maj-hub")), clientDir = Files.createDirectories(work.resolve("maj-client"));
        java.time.LocalDateTime t1 = java.time.LocalDateTime.of(2026, 3, 1, 10, 0, 0), t2 = java.time.LocalDateTime.of(2026, 3, 2, 11, 30, 0);
        Path hubJar = UpdateTest.jar(hubDir.resolve("cotransfert.jar"), t2, Updater.MAIN_CLASS, "v2");
        Path clientJar = UpdateTest.jar(clientDir.resolve("cotransfert.jar"), t1, Updater.MAIN_CLASS, "v1");
        Crypto.Key key = Crypto.derive(SECRET);
        Status st = Status.get();

        try (Fresh f = new Fresh()) {
            f.api.updater = new Updater(hubJar, Updater.versionOf(hubJar), true, key, () -> new DirRelayStore(relay.toString()), "", st::setUpdate, 0);
            Map<?, ?> u = update();
            check("2026-03-02 11:30".equals(u.get("current")) && Boolean.TRUE.equals(u.get("supported")) && Boolean.TRUE.equals(u.get("canPublish"))
                    && Boolean.FALSE.equals(u.get("available")) && "".equals(u.get("version")) && "".equals(u.get("state")) && "".equals(u.get("message")),
                    "status du hub avant publication: " + u);
            Resp r = post("/api/update/install", "{}");
            check(Boolean.FALSE.equals(r.json().get("ok")) && f.quiet() && !Files.exists(hubDir.resolve("cotransfert.jar.nouveau")), "le hub a installe: " + r.body);
            r = post("/api/update/publish", "{}");
            check(r.status == 200 && r.body.equals("{\"ok\":true,\"message\":\"version du 2026-03-02 11:30 publiee\"}"), "publish: " + r.status + " " + r.body);
            check(Files.isRegularFile(relay.resolve("_maj").resolve("version.scelle")) && Files.isRegularFile(relay.resolve("_maj").resolve("cotransfert.jar.scelle")),
                    "fichiers publies absents du relais");
            r = post("/api/update/check", "{}");
            check(Boolean.TRUE.equals(r.json().get("ok")) && Boolean.FALSE.equals(r.json().get("available")) && "2026-03-02 11:30".equals(r.json().get("version")),
                    "check cote hub: " + r.body);
            check(f.quiet() && noSecret(get("/api/status").body), "publier ou verifier ne redemarre rien");
        }

        try (Fresh f = new Fresh()) {
            f.api.updater = new Updater(clientJar, Updater.versionOf(clientJar), false, key, () -> new DirRelayStore(relay.toString()), "", st::setUpdate, 0);
            Map<?, ?> u = update();
            check("2026-03-01 10:00".equals(u.get("current")) && Boolean.TRUE.equals(u.get("supported")) && Boolean.FALSE.equals(u.get("canPublish"))
                    && Boolean.FALSE.equals(u.get("available")) && "".equals(u.get("state")), "status du client avant verification: " + u);
            Resp r = post("/api/update/publish", "{}");
            check(Boolean.FALSE.equals(r.json().get("ok")), "le client a publie: " + r.body);
            r = post("/api/update/check", "{}");
            check(r.status == 200 && r.body.equals("{\"ok\":true,\"available\":true,\"version\":\"2026-03-02 11:30\","
                    + "\"message\":\"mise a jour disponible : version du 2026-03-02 11:30\"}"), "check: " + r.status + " " + r.body);
            u = update();
            check(Boolean.TRUE.equals(u.get("available")) && "2026-03-02 11:30".equals(u.get("version")) && "".equals(u.get("state"))
                    && "2026-03-01 10:00".equals(u.get("current")), "status apres verification: " + u);
            check(f.quiet(), "verifier ne redemarre rien");
            r = post("/api/update/install", "{}");
            check(r.status == 200 && r.body.equals("{\"ok\":true,\"restart\":true}"), "install: " + r.status + " " + r.body);
            check(Arrays.equals(Files.readAllBytes(clientDir.resolve("cotransfert.jar.nouveau")), Files.readAllBytes(hubJar)), "jar installe different du jar publie");
            u = update();
            check("ready".equals(u.get("state")) && Boolean.FALSE.equals(u.get("available")) && "2026-03-02 11:30".equals(u.get("version")), "status apres installation: " + u);
            check(f.restarted(), "relance (75) attendue apres l'installation");
        }

        // Version deja a jour, puis plus rien de publie : les messages les distinguent ; un echec ne redemarre rien.
        try (Fresh f = new Fresh()) {
            f.api.updater = new Updater(hubJar, Updater.versionOf(hubJar), false, key, () -> new DirRelayStore(relay.toString()), "", st::setUpdate, 0);
            Resp r = post("/api/update/check", "{}");
            check(Boolean.TRUE.equals(r.json().get("ok")) && Boolean.FALSE.equals(r.json().get("available"))
                    && String.valueOf(r.json().get("message")).contains("deja a jour"), "check a jour: " + r.body);
            r = post("/api/update/install", "{}");
            check(Boolean.FALSE.equals(r.json().get("ok")) && String.valueOf(r.json().get("message")).contains("deja a jour") && f.quiet(), "install a jour: " + r.body);
            Files.delete(relay.resolve("_maj").resolve("version.scelle"));
            r = post("/api/update/check", "{}");
            check(Boolean.TRUE.equals(r.json().get("ok")) && Boolean.FALSE.equals(r.json().get("available")) && "".equals(r.json().get("version"))
                    && String.valueOf(r.json().get("message")).contains("aucune version publiee"), "check sans publication: " + r.body);
        }
        // Indisponible (lien direct, pas de secret...) : la raison dans la reponse et dans l'etat.
        try (Fresh f = new Fresh()) {
            f.api.updater = new Updater(clientJar, Updater.versionOf(clientJar), false, key, null, Agent.NO_SECRET_DIRECT, st::setUpdate, 0);
            Map<?, ?> u = update();
            check(Boolean.FALSE.equals(u.get("supported")) && Agent.NO_SECRET_DIRECT.equals(u.get("message"))
                    && "2026-03-01 10:00".equals(u.get("current")), "status indisponible: " + u);
            for (String path : new String[] { "/api/update/publish", "/api/update/check", "/api/update/install" }) {
                Resp r = post(path, "{}");
                check(r.status == 200 && Boolean.FALSE.equals(r.json().get("ok")) && String.valueOf(r.json().get("message")).contains("lien direct"), path + " indisponible: " + r.body);
            }
            check(f.quiet(), "une action indisponible a demande un arret");
        }

        // Journal : chemin et dernieres lignes, en lecture seule, sur la boucle locale uniquement.
        Path log = work.resolve("journal").resolve("cotransfert.log");
        Files.createDirectories(log.getParent());
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= 1100; i++) lines.add("12:00:00 ligne " + i + (i == 1100 ? " \"guillemets\" et \\ barre" : ""));
        Files.write(log, lines);
        try (Fresh f = new Fresh()) {
            Resp r = get("/api/log");
            check(r.status == 200 && r.json().get("path").equals("") && ((List<?>) r.json().get("lines")).isEmpty(), "journal non demarre: " + r.body);
            f.api.logFile = log;
            r = get("/api/log?lines=2");
            check(r.status == 200 && r.body.equals("{\"path\":" + q(log.toString()) + ",\"lines\":[\"12:00:00 ligne 1099\","
                    + "\"12:00:00 ligne 1100 \\\"guillemets\\\" et \\\\ barre\"]}"), "log?lines=2: " + r.body);
            check(r.headers.toLowerCase().contains("cache-control: no-store"), "log: Cache-Control absent");
            check(((List<?>) get("/api/log").json().get("lines")).size() == 200, "log sans parametre : 200 lignes attendues");
            check(((List<?>) get("/api/log?lines=5000").json().get("lines")).size() == 1000, "log : 1000 lignes au plus");
            check(((List<?>) get("/api/log?lines=abc").json().get("lines")).size() == 200, "log?lines=abc : valeur par defaut attendue");
            check(((List<?>) get("/api/log?lines=-3").json().get("lines")).size() == 1, "log?lines=-3 : une ligne attendue");
            r = post("/api/log", "{}");
            check(r.status == 405, "POST log: " + r.status);
            r = request("GET", "/api/log", null, "Host", "cotransfert.example.org:" + port);
            check(r.status == 403 && r.body.equals(FORBIDDEN), "log avec un Host etranger: " + r.status);
            r = request("GET", "/api/log", null, "Origin", "http://autre.example.org");
            check(r.status == 403, "log avec une Origin etrangere: " + r.status);
            r = get("/api/log/x");
            check(r.status == 404, "/api/log/x: " + r.status);
            check(f.quiet(), "lire le journal ne redemarre rien");
        }
        System.out.println("  ok  mise a jour par le canal (updateAuto, publish, check, install, etat, gardes) et journal (/api/log)");
    }

    /** L'objet "update" de "self" dans /api/status. */
    private static Map<?, ?> update() throws IOException {
        Map<?, ?> self = (Map<?, ?>) get("/api/status").json().get("self");
        check(self.get("update") instanceof Map, "status: self.update absent");
        return (Map<?, ?>) self.get("update");
    }

    // --- 9bis. conflits en attente : liste, resolution, ouverture ------------

    private static void conflictRoutes() throws Exception {
        // Aucun dossier connu : la route existe quand meme et rend une liste vide.
        Resp r = get("/api/conflicts");
        check(r.status == 200 && r.body.equals("{\"count\":0,\"conflicts\":[]}"), "conflits sans dossier: " + r.status + " " + r.body);
        check(r.headers.toLowerCase().contains("cache-control: no-store"), "conflits: Cache-Control absent");

        // Gardes : les memes que les autres routes de reglage.
        r = request("GET", "/api/conflicts", null, "Host", "evil.example:" + port);
        check(r.status == 403 && r.body.equals(FORBIDDEN), "GET conflits avec Host etranger: " + r.status);
        r = request("GET", "/api/conflicts", null, "Origin", "http://evil.example");
        check(r.status == 403 && r.body.equals(FORBIDDEN), "GET conflits avec Origin etranger: " + r.status);
        r = post("/api/conflicts", "{}");
        check(r.status == 405 && r.headers.contains("Allow: GET"), "POST conflits: " + r.status);
        r = get("/api/conflicts/x");
        check(r.status == 404, "/api/conflicts/x: " + r.status);
        for (String p : new String[] { "/api/conflicts/resolve", "/api/conflicts/open" }) {
            r = get(p);
            check(r.status == 405 && r.headers.contains("Allow: POST"), "GET " + p + ": " + r.status);
            r = post(p, "{\"all\":true,\"keep\":\"mine\"}", "X-CoTransfert", null);
            check(r.status == 403 && r.body.equals(FORBIDDEN), p + " sans X-CoTransfert: " + r.status);
            r = post(p, "{\"all\":true,\"keep\":\"mine\"}", "Content-Type", "text/plain");
            check(r.status == 403, p + " en text/plain: " + r.status);
            r = post(p, "{\"all\":true,\"keep\":\"mine\"}", "Host", "evil.example:" + port);
            check(r.status == 403, p + " avec Host etranger: " + r.status);
            r = post(p, "{\"all\":true,\"keep\":\"mine\"}", "Origin", "http://evil.example");
            check(r.status == 403, p + " avec Origin etranger: " + r.status);
            r = post(p, "{");
            check(r.status == 400, p + " avec un corps invalide: " + r.status);
        }

        // Un dossier avec deux conflits en attente, tels que le moteur les laisse.
        String root = "Classeur";
        Path dir = Files.createDirectories(work.resolve("conflits").resolve("classeur"));
        Files.createDirectories(dir.resolve("dossier"));
        Files.createDirectories(dir.resolve(".cotransfert"));
        String ca = "a.txt.conflict-peer-20261116-173000", cb = "dossier/b.txt.conflict-peer-20261116-173001";
        Files.writeString(dir.resolve("a.txt"), "ma version de a\n");
        Files.writeString(dir.resolve(ca), "version de l'autre poste pour a\n");
        Files.writeString(dir.resolve("dossier/b.txt"), "ma version de b\n");
        Files.writeString(dir.resolve(cb), "version de l'autre poste pour b\n");
        Files.setLastModifiedTime(dir.resolve(ca), java.nio.file.attribute.FileTime.fromMillis(1_794_839_000_000L));
        Files.writeString(dir.resolve(".cotransfert").resolve("conflicts.log"),
                "20261116-173000\ta.txt\t-> " + ca + "\n"
                + "20261116-173000\tc.txt\t(suppression pair ignoree)\n"
                + "20261116-173001\tdossier/b.txt\t-> " + cb + "\n"
                + "20261116-173002\told.txt\t-> old.txt.conflict-peer-20261116-173002\n");
        Conflicts.register(root, dir);
        Status.get().setPath(root, dir.toString());
        try {
            r = get("/api/conflicts");
            Map<String, Object> j = r.json();
            List<?> list = (List<?>) j.get("conflicts");
            check(r.status == 200 && Long.valueOf(2).equals(j.get("count")) && list.size() == 2, "liste des conflits: " + r.body);
            Map<?, ?> first = (Map<?, ?>) list.get(0);
            check(root.equals(first.get("root")) && "a.txt".equals(first.get("path")) && ca.equals(first.get("copy"))
                    && "2026-11-16 17:30:00".equals(first.get("when")) && Boolean.TRUE.equals(first.get("mineExists"))
                    && Long.valueOf(Files.size(dir.resolve("a.txt"))).equals(first.get("mineSize"))
                    && Long.valueOf(Files.getLastModifiedTime(dir.resolve("a.txt")).toMillis()).equals(first.get("mineTime"))
                    && Long.valueOf(Files.size(dir.resolve(ca))).equals(first.get("theirsSize"))
                    && Long.valueOf(1_794_839_000_000L).equals(first.get("theirsTime")), "champs d'un conflit: " + r.body);
            check("dossier/b.txt".equals(((Map<?, ?>) list.get(1)).get("path")) && cb.equals(((Map<?, ?>) list.get(1)).get("copy")), "second conflit: " + r.body);
            check(statusConflicts(root) == 2, "compteur de /api/status: " + statusConflicts(root));

            // Refus : rien n'est touche.
            r = post("/api/conflicts/resolve", "{\"root\":" + q(root) + ",\"copy\":" + q(ca) + ",\"keep\":\"les deux\"}");
            check(r.status == 200 && Boolean.FALSE.equals(r.json().get("ok")) && Long.valueOf(0).equals(r.json().get("resolved"))
                    && ((List<?>) r.json().get("failed")).isEmpty() && r.json().get("message") instanceof String, "keep invalide: " + r.body);
            r = post("/api/conflicts/resolve", "{\"root\":" + q(root) + ",\"copy\":" + q(ca) + "}");
            check(Boolean.FALSE.equals(r.json().get("ok")), "keep absent: " + r.body);
            String[][] bad = {
                { "Inconnu", ca, "dossier inconnu" },
                { root, "../" + ca, "copie inconnue" },
                { root, dir.resolve(ca).toString(), "copie inconnue" },
                { root, "a.txt", "copie inconnue" },
                { root, ".cotransfert/conflicts.log", "copie inconnue" },
                { root, "old.txt.conflict-peer-20261116-173002", "conflit inconnu" },
                { root, "a.txt.conflict-peer-20261116-173009", "conflit inconnu" },
            };
            for (String[] b : bad) {
                for (String keep : new String[] { "mine", "theirs" }) {
                    r = post("/api/conflicts/resolve", "{\"root\":" + q(b[0]) + ",\"copy\":" + q(b[1]) + ",\"keep\":" + q(keep) + "}");
                    check(r.status == 200 && r.body.equals("{\"ok\":false,\"resolved\":0,\"failed\":[{\"root\":" + q(b[0]) + ",\"copy\":" + q(b[1])
                            + ",\"message\":" + q(b[2]) + "}]}"), "refus attendu pour " + b[1] + ": " + r.body);
                }
                r = post("/api/conflicts/open", "{\"root\":" + q(b[0]) + ",\"copy\":" + q(b[1]) + "}");
                check(r.status == 200 && r.body.equals("{\"ok\":false,\"message\":" + q(b[2]) + "}"), "ouvrir: refus attendu pour " + b[1] + ": " + r.body);
            }
            r = post("/api/conflicts/resolve", "{\"copy\":" + q(ca) + ",\"keep\":\"mine\"}");
            check(r.body.contains("\"message\":\"dossier inconnu\"") && Boolean.FALSE.equals(r.json().get("ok")), "sans dossier: " + r.body);
            r = post("/api/conflicts/resolve", "{\"all\":true,\"root\":\"Inconnu\",\"keep\":\"mine\"}");
            check(r.body.equals("{\"ok\":false,\"resolved\":0,\"failed\":[{\"root\":\"Inconnu\",\"copy\":\"\",\"message\":\"dossier inconnu\"}]}"), "groupe, dossier inconnu: " + r.body);
            check(Files.readString(dir.resolve("a.txt")).equals("ma version de a\n") && Files.exists(dir.resolve(ca)) && Files.exists(dir.resolve(cb))
                    && statusConflicts(root) == 2, "un refus a touche un fichier");

            // Ouvrir l'emplacement : facultatif, indisponible sans ecran.
            r = post("/api/conflicts/open", "{\"root\":" + q(root) + ",\"copy\":" + q(cb) + "}");
            check(r.status == 200 && r.body.equals("{\"ok\":false,\"message\":\"indisponible\"}"), "ouvrir sans ecran: " + r.body);

            // Un conflit : garder ma version. Sans moteur : le fichier ne bouge pas, la copie part,
            // l'index retient l'empreinte de la copie (la base du prochain envoi).
            long copyHash = Index.hashFile(dir.resolve(ca));
            r = post("/api/conflicts/resolve", "{\"root\":" + q(root) + ",\"copy\":" + q(ca) + ",\"keep\":\"mine\"}");
            check(r.status == 200 && r.body.equals("{\"ok\":true,\"resolved\":1,\"failed\":[]}"), "garder ma version: " + r.body);
            check(Files.readString(dir.resolve("a.txt")).equals("ma version de a\n") && !Files.exists(dir.resolve(ca)), "garder ma version: fichiers");
            Index idx = new Index(dir);
            idx.load();
            check(idx.get("a.txt") != null && idx.get("a.txt").hash == copyHash, "garder ma version: l'index doit porter l'empreinte de la copie");
            check(statusConflicts(root) == 1 && Long.valueOf(1).equals(get("/api/conflicts").json().get("count")), "compteur apres une resolution");

            // Tous les conflits : garder la version de l'autre poste.
            r = post("/api/conflicts/resolve", "{\"all\":true,\"keep\":\"theirs\"}");
            check(r.status == 200 && r.body.equals("{\"ok\":true,\"resolved\":1,\"failed\":[]}"), "groupe: " + r.body);
            check(Files.readString(dir.resolve("dossier/b.txt")).equals("version de l'autre poste pour b\n") && !Files.exists(dir.resolve(cb)), "groupe: fichiers");
            r = get("/api/conflicts");
            check(r.body.equals("{\"count\":0,\"conflicts\":[]}") && statusConflicts(root) == 0, "plus aucun conflit attendu: " + r.body);
            r = post("/api/conflicts/resolve", "{\"all\":true,\"root\":" + q(root) + ",\"keep\":\"mine\"}");
            check(r.body.equals("{\"ok\":true,\"resolved\":0,\"failed\":[]}"), "groupe sans conflit: " + r.body);
        } finally {
            Conflicts.forget(root);
            Status.get().drop(root);
        }
        check(exits.isEmpty(), "une route de conflit a demande un arret");
        System.out.println("  ok  conflits (liste, resolution, ouverture, gardes)");
    }

    /** self.roots[].conflicts de /api/status pour ce dossier, -1 s'il est absent. */
    private static int statusConflicts(String root) throws IOException {
        Map<?, ?> self = (Map<?, ?>) get("/api/status").json().get("self");
        for (Object o : (List<?>) self.get("roots")) {
            Map<?, ?> m = (Map<?, ?>) o;
            if (root.equals(m.get("name"))) return ((Long) m.get("conflicts")).intValue();
        }
        return -1;
    }

    // --- 10. /api/browse sans ecran, /api/quit, adresse distante -------------

    private static void browseAndQuit() throws Exception {
        Resp r = post("/api/browse", "{\"start\":" + q(work.toString()) + "}");
        check(r.status == 200 && r.body.equals("{\"error\":\"indisponible\"}"), "browse sans ecran: " + r.status + " " + r.body);
        r = post("/api/browse", "");
        check(r.status == 200 && r.body.equals("{\"error\":\"indisponible\"}"), "browse sans corps: " + r.body);

        // Instance neuve : celle de l'essai a deja demande sa relance.
        LinkedBlockingQueue<Integer> codes = new LinkedBlockingQueue<>();
        SetupApi api = new SetupApi(conf, "POSTE-DE-TEST", codes::add);
        HttpServer srv = WebServer.start(0, true, api);
        int main = port;
        port = srv.getAddress().getPort();
        try {
            r = post("/api/quit", "{}", "X-CoTransfert", null);
            check(r.status == 403 && codes.poll(500, TimeUnit.MILLISECONDS) == null, "quit sans en-tete: " + r.status);
            r = post("/api/quit", "");
            check(r.status == 200 && r.body.equals("{\"ok\":true}"), "quit: " + r.status + " " + r.body);
            Integer code = codes.poll(3, TimeUnit.SECONDS);
            check(code != null && code == 0, "arret attendu avec le code 0, recu " + code);
        } finally {
            port = main;
            srv.stop(0);
        }
        System.out.println("  ok  browse (sans ecran), quit");
    }

    // --- partage clavier-souris : bascule, raccourci Windows, reglages "km" -----

    /** Doublure du poste : ni fenetre, ni Robot. */
    private static final class KmEnv implements Km.Env {
        final java.util.concurrent.atomic.AtomicInteger captures = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger sinks = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1_000_000);
        @Override public boolean headless() { return false; }
        @Override public KmInject.Sink sink() {
            sinks.incrementAndGet();
            return new KmInject.Sink() {
                @Override public void enter(KmSettings.Edge edge, int pos) { }
                @Override public void move(int dx, int dy) { }
                @Override public void button(int button, boolean down) { }
                @Override public void wheel(int notches) { }
                @Override public void key(int vk, int location, boolean down) { }
                @Override public void releaseAll() { }
                @Override public int ignored() { return 0; }
            };
        }
        @Override public KmCapture.Grabber grabber() {
            return new KmCapture.Grabber() {
                @Override public void start(KmSettings settings, KmCapture.Listener listener) { captures.incrementAndGet(); }
                @Override public void stop(java.awt.Point park) { }
            };
        }
        @Override public java.awt.Point pointer() { return new java.awt.Point(500, 500); }
        @Override public java.awt.Rectangle[] screens() { return new java.awt.Rectangle[] { new java.awt.Rectangle(0, 0, 1920, 1080) }; }
        @Override public long nowMs() { return now.get(); }
    }

    private static void kmRoutes(SetupApi api) throws Exception {
        // Doublures posees AVANT toute requete : jamais de PowerShell, de .lnk, de fenetre ni de Robot.
        List<String> calls = Collections.synchronizedList(new ArrayList<>());
        api.shortcuts = new SetupApi.Shortcuts() {
            @Override public KmShortcut.Result create(int webPort, KmSettings.Hotkey hotkey) {
                calls.add("create " + webPort + " " + hotkey.text());
                return new KmShortcut.Result(true, "raccourci cree");
            }
            @Override public KmShortcut.Result remove() {
                calls.add("remove");
                return new KmShortcut.Result(true, "raccourci retire");
            }
        };
        api.webPort = 4242;
        KmEnv env = new KmEnv();
        Km km = new Km(env);
        api.km = km;

        // Gardes : les memes que pour les reglages.
        for (String path : new String[] { "/api/km/toggle", "/api/km/shortcut" }) {
            String body = "{\"create\":true}";
            Resp r = post(path, body, "X-CoTransfert", null);
            check(r.status == 403 && r.body.equals(FORBIDDEN), path + " sans X-CoTransfert: " + r.status);
            r = post(path, body, "Content-Type", "text/plain");
            check(r.status == 403, path + " en text/plain: " + r.status);
            r = post(path, body, "Content-Type", "application/x-www-form-urlencoded");
            check(r.status == 403, path + " en formulaire: " + r.status);
            r = post(path, body, "Host", "evil.example:" + port);
            check(r.status == 403, path + " avec un Host etranger: " + r.status);
            r = post(path, body, "Origin", "http://evil.example");
            check(r.status == 403, path + " avec une Origin etrangere: " + r.status);
            r = get(path);
            check(r.status == 405, path + " en GET: " + r.status);
        }
        check(calls.isEmpty() && env.captures.get() == 0, "une requete refusee a agi: " + calls);

        // Bascule : desactivee par defaut, refus propre.
        Resp r = post("/api/km/toggle", "{}");
        check(r.status == 200 && Boolean.FALSE.equals(r.json().get("ok")) && "idle".equals(r.json().get("state"))
                && String.valueOf(r.json().get("message")).contains("desactivee"), "bascule desactivee: " + r.status + " " + r.body);
        km.configure(new KmSettings(true, KmSettings.Edge.RIGHT, 250, KmSettings.Hotkey.parse("Ctrl+Alt+F12"), 0), false, "direct", true);
        r = post("/api/km/toggle", "{}");
        check(r.status == 200 && Boolean.FALSE.equals(r.json().get("ok")) && String.valueOf(r.json().get("message")).contains("pas connecte"),
                "bascule sans lien: " + r.body);
        check(env.captures.get() == 0, "capture ouverte sans lien");

        // Lien etabli avec un second controleur : la requete du raccourci Windows (celle de curl.exe :
        // Host 127.0.0.1, pas d'Origin, X-CoTransfert, JSON) prend le controle, la suivante le rend.
        KmEnv peerEnv = new KmEnv();
        Km peer = new Km(peerEnv);
        peer.configure(new KmSettings(true, KmSettings.Edge.LEFT, 250, null, 0), true, "direct", true);
        try (java.net.ServerSocket ss = new java.net.ServerSocket(0, 1, InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 }));
             Socket a = new Socket(ss.getInetAddress(), ss.getLocalPort()); Socket b = ss.accept()) {
            KmLink la = new KmLink(a.getInputStream(), a.getOutputStream(), a, "hub");
            KmLink lb = new KmLink(b.getInputStream(), b.getOutputStream(), b, "client");
            Thread ta = new Thread(() -> km.serve(la), "km-essai-a");
            Thread tb = new Thread(() -> peer.serve(lb), "km-essai-b");
            ta.setDaemon(true); tb.setDaemon(true);
            ta.start(); tb.start();
            long end = System.currentTimeMillis() + 5000;
            while (!(km.ready() && peer.ready()) && System.currentTimeMillis() < end) Thread.sleep(10);
            check(km.ready() && peer.ready(), "lien clavier-souris d'essai non etabli");

            r = raw(InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 }), port, "POST", "/api/km/toggle", "{}",
                    "Host", "127.0.0.1:" + port, "User-Agent", "curl/8.0", "Accept", "*/*", "X-CoTransfert", "1", "Content-Type", "application/json");
            check(r.status == 200 && Boolean.TRUE.equals(r.json().get("ok")) && "driving".equals(r.json().get("state")),
                    "bascule par la requete du raccourci: " + r.status + " " + r.body);
            check(env.captures.get() == 1 && km.state() == Km.State.DRIVING, "capture non ouverte par la bascule");
            end = System.currentTimeMillis() + 3000;
            while (peer.state() != Km.State.DRIVEN && System.currentTimeMillis() < end) Thread.sleep(10);
            check(peer.state() == Km.State.DRIVEN && peerEnv.sinks.get() == 1, "l'autre poste n'est pas pilote");
            r = post("/api/km/toggle", "");
            check(r.status == 200 && Boolean.TRUE.equals(r.json().get("ok")) && "idle".equals(r.json().get("state")), "seconde bascule: " + r.body);
            end = System.currentTimeMillis() + 3000;
            while (peer.state() != Km.State.IDLE && System.currentTimeMillis() < end) Thread.sleep(10);
            check(km.state() == Km.State.IDLE && peer.state() == Km.State.IDLE && env.captures.get() == 1, "controle non rendu");
            la.close();
            lb.close();
        }
        check(noSecret(km.json()) && km.json().contains("\"state\":\"idle\""), "etat clavier-souris: " + km.json());
        r = get("/api/status");
        check(r.status == 200 && r.body.contains("\"km\":{\"enabled\":false") && noSecret(r.body), "status sans bloc km desactive: " + r.body);

        // Reglages "km" : valeurs par defaut, aller-retour, valeurs invalides refusees sans rien ecrire.
        Object def = get("/api/config").json().get("km");
        check(def instanceof Map<?, ?> m && Boolean.FALSE.equals(m.get("enabled")) && "aucun".equals(m.get("edge"))
                && "Ctrl+Alt+F12".equals(m.get("hotkey")) && String.valueOf(m.get("delay")).startsWith("250")
                && String.valueOf(m.get("returnButton")).startsWith("0"), "km par defaut: " + def);
        check(ok(post("/api/config", "{\"km\":{\"enabled\":true,\"edge\":\"Droite\",\"delay\":400,\"hotkey\":\"ctrl+shift+k\",\"returnButton\":5}}")),
                "enregistrement de km");
        Config c = Config.load(conf);
        check("true".equals(c.km.getProperty("km.enabled")) && "droite".equals(c.km.getProperty("km.edge"))
                && "400".equals(c.km.getProperty("km.edge.delay")) && "Ctrl+Maj+K".equals(c.km.getProperty("km.hotkey"))
                && "5".equals(c.km.getProperty("km.return.button")), "km enregistre: " + c.km);
        List<String> lines = Files.readAllLines(conf);
        check(lines.contains("km.enabled=true") && lines.contains("km.edge=droite") && lines.contains("km.hotkey=Ctrl+Maj+K"), "lignes km.* de la conf: " + lines);
        check(SECRET.equals(c.secret) && !c.roots.isEmpty() && "client".equals(c.role), "enregistrer km a change autre chose");
        Object got = get("/api/config").json().get("km");
        check(got instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("enabled")) && "droite".equals(m.get("edge"))
                && "Ctrl+Maj+K".equals(m.get("hotkey")) && String.valueOf(m.get("delay")).startsWith("400")
                && String.valueOf(m.get("returnButton")).startsWith("5"), "km relu: " + got);
        // Champ absent = inchange.
        check(ok(post("/api/config", "{\"km\":{\"delay\":0}}")), "km partiel");
        c = Config.load(conf);
        check("0".equals(c.km.getProperty("km.edge.delay")) && "droite".equals(c.km.getProperty("km.edge"))
                && "true".equals(c.km.getProperty("km.enabled")), "km partiel: " + c.km);
        String before = new String(Files.readAllBytes(conf), StandardCharsets.UTF_8);
        String[][] bad = {
                { "{\"km\":{\"enabled\":\"oui\"}}", "km.enabled" }, { "{\"km\":{\"edge\":\"diagonale\"}}", "km.edge" },
                { "{\"km\":{\"delay\":99999}}", "km.edge.delay" }, { "{\"km\":{\"delay\":-1}}", "km.edge.delay" },
                { "{\"km\":{\"delay\":\"vite\"}}", "km.edge.delay" }, { "{\"km\":{\"delay\":1.5}}", "km.edge.delay" },
                { "{\"km\":{\"hotkey\":\"F12\"}}", "km.hotkey" }, { "{\"km\":{\"hotkey\":\"Ctrl+Alt+Suppr\"}}", "km.hotkey" },
                { "{\"km\":{\"hotkey\":\"Alt+F4\"}}", "km.hotkey" }, { "{\"km\":{\"hotkey\":\"Ctrl+K\\nrole=hub\"}}", "km.hotkey" },
                { "{\"km\":{\"returnButton\":3}}", "km.return.button" }, { "{\"km\":{\"returnButton\":\"4\"}}", "km.return.button" },
                { "{\"km\":\"oui\"}", "km" } };
        for (String[] t : bad) {
            r = post("/api/config", t[0]);
            check(r.status == 200 && fields(r).contains(t[1]), "km invalide " + t[0] + " -> " + r.body);
        }
        check(before.equals(new String(Files.readAllBytes(conf), StandardCharsets.UTF_8)), "une valeur km invalide a ete ecrite");

        // Raccourci Windows : la doublure recoit le port de la page et la touche ENREGISTREE.
        r = post("/api/km/shortcut", "{\"create\":true}");
        check(r.status == 200 && Boolean.TRUE.equals(r.json().get("ok")) && calls.equals(List.of("create 4242 Ctrl+Maj+K")),
                "creation du raccourci: " + r.body + " " + calls);
        r = post("/api/km/shortcut", "{\"create\":false}");
        check(r.status == 200 && Boolean.TRUE.equals(r.json().get("ok")) && calls.equals(List.of("create 4242 Ctrl+Maj+K", "remove")),
                "retrait du raccourci: " + r.body + " " + calls);
        for (String body : new String[] { "{}", "", "{\"create\":\"oui\"}", "{\"create\":1}" }) {
            r = post("/api/km/shortcut", body);
            check(r.status == 200 && Boolean.FALSE.equals(r.json().get("ok")), "raccourci sans create booleen (" + body + "): " + r.body);
        }
        check(ok(post("/api/config", "{\"km\":{\"hotkey\":\"\"}}")) && "aucun".equals(Config.load(conf).km.getProperty("km.hotkey")), "raccourci retire des reglages");
        r = post("/api/km/shortcut", "{\"create\":true}");
        check(r.status == 200 && Boolean.FALSE.equals(r.json().get("ok")) && String.valueOf(r.json().get("message")).contains("aucun raccourci"),
                "creation sans raccourci regle: " + r.body);
        check(calls.size() == 2, "la doublure a ete appelee a tort: " + calls);
        // Retour a l'etat par defaut : la suite de l'essai ne depend pas de km.
        check(ok(post("/api/config", "{\"km\":{\"enabled\":false,\"edge\":\"aucun\",\"delay\":250,\"hotkey\":\"Ctrl+Alt+F12\",\"returnButton\":0}}")), "km remis par defaut");
        check(Files.readAllLines(conf).contains("# km.enabled=false"), "km par defaut : en commentaire dans la conf");
        System.out.println("  ok  clavier-souris (bascule, raccourci, reglages km)");
    }

    /** Sur toutes les interfaces (comme un hub) : l'etat est lisible du reseau, les reglages non. */
    private static void remoteAddress() throws Exception {
        InetAddress lan = null;
        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!ni.isUp() || ni.isLoopback()) continue;
            for (InetAddress a : Collections.list(ni.getInetAddresses()))
                if (a instanceof Inet4Address && !a.isLoopbackAddress() && lan == null) lan = a;
        }
        if (lan == null) { System.out.println("  --  adresse distante : aucune interface reseau, essai ignore"); return; }
        HttpServer srv = WebServer.start(0, false, new SetupApi(conf, "POSTE-DE-TEST", c -> { }));
        int p = srv.getAddress().getPort();
        try {
            Resp r;
            try {
                r = raw(lan, p, "GET", "/api/status", null, "Host", "localhost:" + p);
            } catch (IOException e) {
                System.out.println("  --  adresse distante : interface injoignable depuis ce poste, essai ignore");
                return;
            }
            check(r.status == 200 && r.body.contains("\"self\"") && noSecret(r.body), "status depuis le reseau: " + r.status);
            r = raw(lan, p, "GET", "/api/config", null, "Host", "localhost:" + p);
            check(r.status == 403 && r.body.equals(FORBIDDEN), "GET config depuis le reseau: " + r.status + " " + r.body);
            r = raw(lan, p, "GET", "/api/config", null, "Host", lan.getHostAddress() + ":" + p);
            check(r.status == 403, "GET config depuis le reseau (Host = adresse): " + r.status);
            r = raw(lan, p, "GET", "/api/log?lines=5", null, "Host", "localhost:" + p);
            check(r.status == 403 && r.body.equals(FORBIDDEN), "GET log depuis le reseau: " + r.status + " " + r.body);
            r = raw(lan, p, "GET", "/api/conflicts", null, "Host", "localhost:" + p);
            check(r.status == 403 && r.body.equals(FORBIDDEN), "GET conflits depuis le reseau: " + r.status + " " + r.body);
            for (String path : new String[] { "/api/config", "/api/secret/reveal", "/api/secret/new", "/api/test", "/api/channel", "/api/browse", "/api/quit",
                    "/api/update/publish", "/api/update/check", "/api/update/install", "/api/conflicts/resolve", "/api/conflicts/open",
                    "/api/km/toggle", "/api/km/shortcut" }) {
                r = raw(lan, p, "POST", path, "{}", "Host", "localhost:" + p, "Content-Type", "application/json", "X-CoTransfert", "1");
                check(r.status == 403 && noSecret(r.body), "POST " + path + " depuis le reseau: " + r.status);
            }
            r = raw(InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 }), p, "GET", "/api/config", null, "Host", "localhost:" + p);
            check(r.status == 200, "GET config local sur un serveur toutes interfaces: " + r.status);
        } finally {
            srv.stop(0);
        }
        System.out.println("  ok  adresse distante (" + "reglages refuses hors boucle locale)");
    }

    // --- outils -------------------------------------------------------------

    private static Resp get(String path) throws IOException { return request("GET", path, null); }

    /** POST JSON avec les en-tetes attendus ; hdrs les remplace (valeur null = en-tete retire). */
    private static Resp post(String path, String body, String... hdrs) throws IOException {
        List<String> h = new ArrayList<>(List.of("Content-Type", "application/json", "X-CoTransfert", "1"));
        for (int i = 0; i + 1 < hdrs.length; i += 2) {
            int at = h.indexOf(hdrs[i]);
            if (at >= 0) { h.remove(at); h.remove(at); }
        }
        for (String s : hdrs) h.add(s);
        return request("POST", path, body, h.toArray(new String[0]));
    }

    private static Resp request(String method, String path, String body, String... hdrs) throws IOException {
        List<String> h = new ArrayList<>();
        boolean host = false;
        for (int i = 0; i + 1 < hdrs.length; i += 2) if ("Host".equals(hdrs[i])) host = true;
        if (!host) { h.add("Host"); h.add("localhost:" + port); }
        for (String s : hdrs) h.add(s);
        return raw(InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 }), port, method, path, body, h.toArray(new String[0]));
    }

    /** Requete HTTP/1.1 sur une socket brute : en-tetes libres (paires nom, valeur ; valeur null = en-tete absent). */
    private static Resp raw(InetAddress addr, int p, String method, String path, String body, String... hdrs) throws IOException {
        byte[] b = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        for (int i = 0; i + 1 < hdrs.length; i += 2)
            if (hdrs[i + 1] != null) sb.append(hdrs[i]).append(": ").append(hdrs[i + 1]).append("\r\n");
        if (body != null) sb.append("Content-Length: ").append(b.length).append("\r\n");
        sb.append("Connection: close\r\n\r\n");
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(addr, p), 3000);
            s.setSoTimeout(40_000);
            OutputStream o = s.getOutputStream();
            o.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
            o.write(b);
            o.flush();
            InputStream in = s.getInputStream();
            String all = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int sep = all.indexOf("\r\n\r\n");
            if (sep < 0 || all.length() < 12) throw new IOException("reponse illisible: " + all);
            return new Resp(Integer.parseInt(all.substring(9, 12)), all.substring(0, sep), all.substring(sep + 4));
        }
    }

    private static String q(String s) { return Status.q(s); }

    private static void check(boolean ok, String what) {
        if (ok) return;
        System.err.println("ECHEC: " + what);
        System.exit(1);
    }

    private static void wipe(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException { Files.delete(f); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException { Files.delete(d); return FileVisitResult.CONTINUE; }
        });
    }
}
