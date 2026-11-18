package cotransfert;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests du magasin WebDAV (WebDavRelayStore) contre un faux serveur local
 * (FakeWebDavServer). Simple main, sans dependance : code de sortie non nul en
 * cas d'echec. Aucun service reel n'est contacte.
 *
 * Usage : java -cp <classes> cotransfert.WebDavStoreTest <dossier de travail>
 */
public final class WebDavStoreTest {

    private static final String USER = "utilisateur", PASS = "mot-de-passe-de-test-7391";
    private static final Random RND = new Random(7);
    private static Path work;
    private static HttpClient http;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) { System.err.println("usage: WebDavStoreTest <dossier de travail>"); System.exit(2); }
        work = Paths.get(args[0]).toAbsolutePath().normalize();
        Thread dog = new Thread(() -> {
            try { Thread.sleep(180_000); } catch (InterruptedException e) { return; }
            System.err.println("ECHEC: delai global depasse (blocage)");
            Runtime.getRuntime().halt(3);
        }, "watchdog");
        dog.setDaemon(true);
        dog.start();
        try {
            wipe(work);
            Files.createDirectories(work);
            http = HttpSupport.client("none");
            testConstruction();
            testOperations();
            testNoms();
            testGrosFichier();
            testHrefs();
            testDossiersDisparus();
            testIdentifiants();
            testErreursServeur();
            testRetryAfter();
            testPanne();
            testConcurrence();
            wipe(work);
            System.out.println("WEBDAV STORE TEST: OK");
            System.exit(0);
        } catch (Throwable t) {
            System.err.println("WEBDAV STORE TEST: ECHEC");
            t.printStackTrace();
            System.exit(1);
        }
    }

    // =======================================================================
    //  1. Construction : https obligatoire, aucun secret dans la description
    // =======================================================================

    private static void testConstruction() throws Exception {
        step("1. construction");
        for (String bad : new String[] { "http://cloud.example.org/dav/transit", "ftp://cloud.example.org/x", "", "cloud.example.org/dav",
                "https://" + USER + ":" + PASS + "@cloud.example.org/dav", null }) {
            try {
                new WebDavRelayStore(bad, USER, PASS, http);
                fail("URL acceptee a tort: " + bad);
            } catch (IllegalArgumentException ok) {
                check(!ok.getMessage().contains(PASS), "mot de passe dans le message de refus");
            }
        }
        // Aucune requete a la construction : ces hotes n'existent pas et ne sont jamais contactes.
        WebDavRelayStore s = new WebDavRelayStore("https://cloud.example.org/remote.php/dav/files/u/Transit/", USER, PASS, http);
        check("webdav".equals(s.id()), "id");
        check("cloud.example.org/remote.php/dav/files/u/Transit".equals(s.describe()), "describe: " + s.describe());
        check(!s.describe().contains(PASS) && !s.toString().contains(PASS), "mot de passe dans describe/toString");
        check(s.pollMs() == 10_000 && s.maxLotBytes() == (64L << 20), "pollMs/maxLotBytes");
        check(s.generation() == 0 && s.inboundReady() && s.lastError().isEmpty(), "valeurs par defaut");
        s.close();
        s = new WebDavRelayStore("https://cloud.example.org:8443/dav/Mon dossier", USER, PASS, http);
        check("cloud.example.org:8443/dav/Mon dossier".equals(s.describe()), "describe (espace): " + s.describe());
        new WebDavRelayStore("http://localhost:1/dav", USER, PASS, http);
        new WebDavRelayStore("http://127.0.0.1:1/dav", USER, PASS, http);
    }

    // =======================================================================
    //  2. Toutes les operations du contrat
    // =======================================================================

    private static void testOperations() throws Exception {
        step("2. operations du contrat");
        FakeWebDavServer srv = new FakeWebDavServer(USER, PASS);
        try {
            WebDavRelayStore s = store(srv, PASS);
            check(s.available(), "disponible: " + s.lastError());
            check(srv.requests() == 1 && srv.requests("PROPFIND") == 1, "une sonde attendue: " + srv.requests());
            for (int i = 0; i < 5; i++) check(s.available(), "disponible (cache)");
            check(srv.requests() == 1, "available() doit etre mis en cache: " + srv.requests());
            check(s.lastError().isEmpty(), "lastError: " + s.lastError());

            // absent : null, liste vide, suppression sans effet
            check(s.read("doc/hub.ack", 0) == null && s.read("doc/hub.ack", 16) == null, "lecture d'un absent");
            check(s.list("doc/hub-vers-client").isEmpty() && s.list("nulle/part/du/tout").isEmpty(), "liste d'un dossier absent");
            s.delete("doc/hub-vers-client/000001.lot");
            s.delete("doc/absent.ack");
            check(s.lastError().isEmpty(), "lastError apres des 404: " + s.lastError());

            // ecriture avec creation des dossiers parents
            byte[] a = random(3000), b = random(10);
            s.write("doc/hub-vers-client/000001.lot", a);
            check(Arrays.equals(a, srv.file("doc/hub-vers-client/000001.lot")), "contenu depose");
            check(srv.hasCollection("doc") && srv.hasCollection("doc/hub-vers-client"), "dossiers parents");
            check(noPart(srv), ".part oublie: " + srv.files());
            int mk = srv.requests("MKCOL"), put = srv.requests("PUT"), mv = srv.requests("MOVE");
            check(put == 2 && mv == 1, "1er depot: PUT refuse, MKCOL, PUT, MOVE (PUT=" + put + ", MOVE=" + mv + ")");
            s.write("doc/hub-vers-client/000002.lot", b);
            check(srv.requests("MKCOL") == mk && srv.requests("PUT") == put + 1 && srv.requests("MOVE") == mv + 1,
                    "2e depot dans le meme dossier: un PUT et un MOVE, pas de MKCOL");
            s.write("doc/hub.ack", "CTACK1 1 2 3 4\n".getBytes(StandardCharsets.US_ASCII));
            s.write("doc/a/b/c/d/profond.lot", b);
            check(Arrays.equals(b, srv.file("doc/a/b/c/d/profond.lot")), "dossiers imbriques");
            s.write("racine.txt", b);
            check(Arrays.equals(b, srv.file("racine.txt")), "fichier a la racine");

            // depuis un fichier local
            Path local = work.resolve("local.lot");
            Files.write(local, a);
            s.write("doc/client-vers-hub/000001.lot", local);
            check(Arrays.equals(a, srv.file("doc/client-vers-hub/000001.lot")), "depot depuis un fichier");
            try { s.write("doc/x.lot", work.resolve("absent.lot")); fail("fichier local absent accepte"); } catch (IOException ok) { }

            // liste : fichiers directs seulement, ni sous-dossier ni .part
            srv.put("doc/hub-vers-client/000003.lot.part", random(5));
            srv.put("doc/hub-vers-client/sous/000009.lot", random(5));
            Map<String, Long> l = names(s.list("doc/hub-vers-client"));
            check(l.equals(Map.of("000001.lot", 3000L, "000002.lot", 10L)), "liste: " + l);
            check(names(s.list("doc/hub-vers-client/")).equals(l), "liste avec '/' final");
            l = names(s.list("doc"));
            check(l.equals(Map.of("hub.ack", 15L)), "liste de doc: " + l);
            l = names(s.list(""));
            check(l.equals(Map.of("racine.txt", 10L)), "liste de la base: " + l);

            // lecture complete et prefixe (Range honore : 206)
            check(Arrays.equals(a, s.read("doc/hub-vers-client/000001.lot", 0)), "lecture complete");
            check(Arrays.equals(a, s.read("doc/hub-vers-client/000001.lot", -1)), "lecture complete (maxBytes < 0)");
            check(Arrays.equals(Arrays.copyOf(a, 38), s.read("doc/hub-vers-client/000001.lot", 38)), "prefixe");
            check(Arrays.equals(a, s.read("doc/hub-vers-client/000001.lot", 1 << 20)), "prefixe plus long que le fichier");
            check(Arrays.equals(b, s.read("doc/hub-vers-client/000002.lot", 10)), "prefixe de la taille du fichier");
            // serveur qui ignore Range : 200 complet, tronque
            srv.setIgnoreRange(true);
            check(Arrays.equals(Arrays.copyOf(a, 38), s.read("doc/hub-vers-client/000001.lot", 38)), "prefixe sans Range");
            check(Arrays.equals(a, s.read("doc/hub-vers-client/000001.lot", 5000)), "prefixe sans Range, fichier court");
            srv.setIgnoreRange(false);
            // fichier vide
            s.write("doc/vide.bin", new byte[0]);
            check(s.read("doc/vide.bin", 0).length == 0 && s.read("doc/vide.bin", 8).length == 0, "fichier vide");
            check(names(s.list("doc")).get("vide.bin") == 0L, "taille du fichier vide");

            // remplacement
            byte[] c = random(777);
            s.write("doc/hub-vers-client/000001.lot", c);
            check(Arrays.equals(c, s.read("doc/hub-vers-client/000001.lot", 0)), "remplacement");
            check(names(s.list("doc/hub-vers-client")).get("000001.lot") == 777L, "taille apres remplacement");
            check(parts(srv) == 1, ".part oublie apres remplacement: " + srv.files());   // seul celui pose par le test

            // suppression
            s.delete("doc/hub-vers-client/000001.lot");
            check(srv.file("doc/hub-vers-client/000001.lot") == null, "suppression");
            check(s.read("doc/hub-vers-client/000001.lot", 0) == null, "lecture apres suppression");
            s.delete("doc/hub-vers-client/000001.lot");
            // le menage d'un .part d'un autre depot reste possible par son nom
            s.delete("doc/hub-vers-client/000003.lot.part");
            check(srv.file("doc/hub-vers-client/000003.lot.part") == null, "suppression d'un .part");
            check(s.lastError().isEmpty(), "lastError en fin de test: " + s.lastError());
            s.close();
        } finally {
            srv.stop();
        }
    }

    // =======================================================================
    //  3. Noms avec espaces et accents (dossier de base compris)
    // =======================================================================

    private static void testNoms() throws Exception {
        step("3. espaces et accents");
        FakeWebDavServer srv = new FakeWebDavServer("/dav/Dossier de transit été", USER, PASS);
        try {
            check(srv.baseUrl().contains("%20") && !srv.baseUrl().contains(" "), "URL de base encodee: " + srv.baseUrl());
            WebDavRelayStore s = store(srv, PASS);
            check(s.available(), "disponible: " + s.lastError());
            String dir = "Mon Projet été à Noël/hub-vers-client";
            String[] names = { "000001.lot", "fichier avec espaces.lot", "éèàüç 中文.ack",
                    "a+b %41 100%&c#d;e=f?g.txt", "~tilde (1) [x] {y} 'z' @ $ ! ,.bin" };
            Map<String, Long> want = new TreeMap<>();
            int n = 0;
            for (String name : names) {
                byte[] data = random(100 + n++);
                s.write(dir + "/" + name, data);
                check(Arrays.equals(data, srv.file(dir + "/" + name)), "nom altere au depot: " + name + " -> " + srv.files());
                check(Arrays.equals(data, s.read(dir + "/" + name, 0)), "lecture: " + name);
                check(Arrays.equals(Arrays.copyOf(data, 7), s.read(dir + "/" + name, 7)), "prefixe: " + name);
                want.put(name, (long) data.length);
            }
            check(noPart(srv), ".part oublie: " + srv.files());
            for (boolean abs : new boolean[] { false, true }) {
                srv.setAbsoluteHrefs(abs);
                check(names(s.list(dir)).equals(want), "liste (href " + (abs ? "absolus" : "relatifs") + "): " + names(s.list(dir)));
            }
            for (String name : names) s.delete(dir + "/" + name);
            check(s.list(dir).isEmpty() && srv.files().isEmpty(), "suppression: " + srv.files());

            // meme chose en donnant l'URL de base non encodee, avec un '/' final
            WebDavRelayStore s2 = new WebDavRelayStore(
                    srv.baseUrl().substring(0, srv.baseUrl().indexOf("/dav")) + "/dav/Dossier de transit été/", USER, PASS, http);
            check(s2.available(), "URL de base non encodee: " + s2.lastError());
            s2.write("x y/z.txt", new byte[] { 1, 2, 3 });
            check(Arrays.equals(new byte[] { 1, 2, 3 }, srv.file("x y/z.txt")), "depot via l'URL non encodee");
            // URL a moitie encodee : espaces en %20, accents bruts
            WebDavRelayStore s3 = new WebDavRelayStore(
                    srv.baseUrl().substring(0, srv.baseUrl().indexOf("/dav")) + "/dav/Dossier%20de%20transit%20été", USER, PASS, http);
            check(s3.available(), "URL de base a moitie encodee: " + s3.lastError());
            check(Arrays.equals(new byte[] { 1, 2, 3 }, s3.read("x y/z.txt", 0)), "lecture via l'URL a moitie encodee");
            check(names(s3.list("x y")).equals(Map.of("z.txt", 3L)), "liste via l'URL a moitie encodee");
        } finally {
            srv.stop();
        }
    }

    // =======================================================================
    //  4. Fichier de plusieurs Mio, aller-retour exact
    // =======================================================================

    private static void testGrosFichier() throws Exception {
        step("4. fichier de plusieurs Mio");
        FakeWebDavServer srv = new FakeWebDavServer(USER, PASS);
        try {
            WebDavRelayStore s = store(srv, PASS);
            byte[] big = random(5 * 1024 * 1024 + 123);
            Path local = work.resolve("gros.lot");
            Files.write(local, big);
            s.write("doc/hub-vers-client/000042.lot", local);
            check(Arrays.equals(big, srv.file("doc/hub-vers-client/000042.lot")), "depot du gros fichier");
            check(Arrays.equals(big, s.read("doc/hub-vers-client/000042.lot", 0)), "lecture du gros fichier");
            check(names(s.list("doc/hub-vers-client")).equals(Map.of("000042.lot", (long) big.length)), "taille listee");
            check(Arrays.equals(Arrays.copyOf(big, 38), s.read("doc/hub-vers-client/000042.lot", 38)), "prefixe (206)");
            // Serveur sans Range : le prefixe ne doit pas attendre ni garder tout le corps, et le magasin reste utilisable.
            srv.setIgnoreRange(true);
            for (int i = 0; i < 5; i++)
                check(Arrays.equals(Arrays.copyOf(big, 38), s.read("doc/hub-vers-client/000042.lot", 38)), "prefixe (200 tronque)");
            check(Arrays.equals(Arrays.copyOf(big, 100_000), s.read("doc/hub-vers-client/000042.lot", 100_000)), "prefixe de 100 ko");
            srv.setIgnoreRange(false);
            check(Arrays.equals(big, s.read("doc/hub-vers-client/000042.lot", 0)), "lecture complete apres des prefixes");
            s.write("doc/hub-vers-client/000042.lot", big);   // meme contenu, depuis la memoire
            check(Arrays.equals(big, srv.file("doc/hub-vers-client/000042.lot")) && noPart(srv), "remplacement du gros fichier");
        } finally {
            srv.stop();
        }
    }

    // =======================================================================
    //  5. href absolus ou relatifs, dossier liste en premier
    // =======================================================================

    private static void testHrefs() throws Exception {
        step("5. href absolus et relatifs");
        FakeWebDavServer srv = new FakeWebDavServer(USER, PASS);
        try {
            WebDavRelayStore s = store(srv, PASS);
            srv.put("doc/hub-vers-client/000001.lot", random(11));
            srv.put("doc/hub-vers-client/000002.lot", random(22));
            srv.put("doc/hub-vers-client/hub-vers-client", random(33));   // meme nom que son dossier
            srv.put("doc/hub-vers-client/sous/x.lot", random(44));
            Map<String, Long> want = Map.of("000001.lot", 11L, "000002.lot", 22L, "hub-vers-client", 33L);
            for (boolean abs : new boolean[] { false, true, false }) {
                srv.setAbsoluteHrefs(abs);
                check(names(s.list("doc/hub-vers-client")).equals(want), "liste (absolu=" + abs + "): " + names(s.list("doc/hub-vers-client")));
                check(s.list("doc/hub-vers-client").size() == 3, "doublon dans la liste");
                check(s.list("doc").isEmpty(), "doc ne contient que des dossiers: " + names(s.list("doc")));
                check(s.available(), "disponible");
            }
        } finally {
            srv.stop();
        }
    }

    // =======================================================================
    //  6. Dossier de base absent (MKCOL), dossiers disparus cote serveur
    // =======================================================================

    private static void testDossiersDisparus() throws Exception {
        step("6. dossier de base absent, dossiers disparus");
        FakeWebDavServer srv = new FakeWebDavServer(USER, PASS);
        try {
            srv.remove("");
            check(!srv.hasCollection(""), "base retiree");
            WebDavRelayStore s = store(srv, PASS);
            check(s.available(), "la base absente doit etre creee: " + s.lastError());
            check(srv.hasCollection("") && srv.requests("MKCOL") == 1, "MKCOL de la base");
            s.write("doc/hub-vers-client/000001.lot", random(50));

            // un dossier memorise disparait du serveur : le depot suivant le recree
            srv.remove("doc");
            check(s.list("doc/hub-vers-client").isEmpty(), "liste apres disparition");
            byte[] d = random(60);
            s.write("doc/hub-vers-client/000002.lot", d);
            check(Arrays.equals(d, srv.file("doc/hub-vers-client/000002.lot")), "depot apres disparition des dossiers");

            // tout disparait, base comprise, sans nouvelle sonde
            srv.remove("");
            s.write("doc/client-vers-hub/000001.lot", d);
            check(Arrays.equals(d, srv.file("doc/client-vers-hub/000001.lot")), "depot apres disparition de la base");
            check(noPart(srv), ".part oublie: " + srv.files());
        } finally {
            srv.stop();
        }
    }

    // =======================================================================
    //  7. Identifiants refuses
    // =======================================================================

    private static void testIdentifiants() throws Exception {
        step("7. identifiants refuses (401)");
        FakeWebDavServer srv = new FakeWebDavServer(USER, PASS);
        try {
            String wrong = "mauvais-secret-5512";
            WebDavRelayStore bad = store(srv, wrong);
            check(!bad.available(), "disponible avec un mauvais mot de passe");
            check("identifiants refuses".equals(bad.lastError()), "lastError: " + bad.lastError());
            int n = srv.requests();
            check(!bad.available() && srv.requests() == n, "le refus doit etre mis en cache");
            List<IOException> errs = new ArrayList<>();
            try { bad.list("doc"); fail("liste acceptee"); } catch (IOException e) { errs.add(e); }
            try { bad.read("doc/hub.ack", 0); fail("lecture acceptee"); } catch (IOException e) { errs.add(e); }
            try { bad.write("doc/hub.ack", new byte[3]); fail("depot accepte"); } catch (IOException e) { errs.add(e); }
            try { bad.delete("doc/hub.ack"); fail("suppression acceptee"); } catch (IOException e) { errs.add(e); }
            for (IOException e : errs) {
                check(e.getMessage().contains("identifiants refuses"), "message: " + e.getMessage());
                for (Throwable t = e; t != null; t = t.getCause()) {
                    String m = String.valueOf(t);
                    check(!m.contains(wrong) && !m.contains(PASS) && !m.contains("Basic ") && !m.contains(HttpSupport.basic(USER, wrong)),
                            "secret dans une exception: " + m);
                }
            }
            check(srv.files().isEmpty(), "rien ne doit avoir ete ecrit: " + srv.files());
            check(!bad.describe().contains(wrong) && !bad.lastError().contains(wrong), "secret dans describe/lastError");

            // 401 puis 403 en cours de route sur un magasin sain
            WebDavRelayStore s = store(srv, PASS);
            check(s.available(), "disponible");
            for (int code : new int[] { 401, 403 }) {
                srv.failNext(null, 1, code, null);
                try { s.list("doc"); fail("liste acceptee malgre " + code); } catch (IOException e) {
                    check(e.getMessage().contains("identifiants refuses"), "message " + code + ": " + e.getMessage());
                }
                n = srv.requests();
                check(!s.available() && srv.requests() == n, "indisponible sans nouvelle requete apres " + code);
                check("identifiants refuses".equals(s.lastError()), "lastError apres " + code + ": " + s.lastError());
                s.forgetProbe();
                check(s.available() && s.lastError().isEmpty(), "retour a la normale apres " + code + ": " + s.lastError());
            }
        } finally {
            srv.stop();
        }
    }

    // =======================================================================
    //  8. Quota plein (507), fichier verrouille (423), autres codes
    // =======================================================================

    private static void testErreursServeur() throws Exception {
        step("8. 507, 423, erreurs diverses");
        FakeWebDavServer srv = new FakeWebDavServer(USER, PASS);
        try {
            WebDavRelayStore s = store(srv, PASS);
            check(s.available(), "disponible");
            byte[] old = random(40);
            s.write("doc/hub-vers-client/000001.lot", old);

            srv.failNext("PUT", 1, 507, null);
            try { s.write("doc/hub-vers-client/000001.lot", random(99)); fail("depot accepte malgre 507"); } catch (IOException e) {
                check(e.getMessage().contains("quota plein"), "message 507: " + e.getMessage());
            }
            check("quota plein".equals(s.lastError()), "lastError 507: " + s.lastError());
            check(Arrays.equals(old, srv.file("doc/hub-vers-client/000001.lot")), "l'ancien contenu doit rester intact");
            int n = srv.requests();
            check(!s.available() && srv.requests() == n, "quota plein: indisponible, sans requete");
            check("quota plein".equals(s.lastError()), "lastError 507 apres available: " + s.lastError());
            // la lecture et la purge restent possibles pendant ce temps (c'est ce qui libere de la place)
            check(Arrays.equals(old, s.read("doc/hub-vers-client/000001.lot", 0)), "lecture pendant le quota plein");
            check("quota plein".equals(s.lastError()), "lastError efface trop tot: " + s.lastError());
            s.forgetProbe();
            check(s.available() && s.lastError().isEmpty(), "retour apres 507: " + s.lastError());

            // 507 a la creation d'un dossier
            srv.failNext("MKCOL", 1, 507, null);
            try { s.write("autre/dossier/f.lot", random(9)); fail("depot accepte malgre 507 au MKCOL"); } catch (IOException e) {
                check(e.getMessage().contains("quota plein"), "message 507 MKCOL: " + e.getMessage());
            }
            s.forgetProbe();
            s.write("autre/dossier/f.lot", old);
            check(Arrays.equals(old, srv.file("autre/dossier/f.lot")), "depot apres 507 au MKCOL");

            // 423 au renommage : erreur, ancien contenu intact, .part retire
            srv.failNext("MOVE", 1, 423, null);
            try { s.write("doc/hub-vers-client/000001.lot", random(99)); fail("depot accepte malgre 423"); } catch (IOException e) {
                check(e.getMessage().contains("fichier verrouille"), "message 423: " + e.getMessage());
            }
            check("fichier verrouille".equals(s.lastError()), "lastError 423: " + s.lastError());
            check(Arrays.equals(old, srv.file("doc/hub-vers-client/000001.lot")) && noPart(srv), "apres 423: " + srv.files());
            check(names(s.list("doc/hub-vers-client")).equals(Map.of("000001.lot", 40L)), "liste apres 423");
            check(s.lastError().isEmpty(), "lastError doit s'effacer au succes suivant: " + s.lastError());
            srv.failNext("DELETE", 1, 423, null);
            try { s.delete("doc/hub-vers-client/000001.lot"); fail("suppression acceptee malgre 423"); } catch (IOException e) {
                check(e.getMessage().contains("fichier verrouille"), "message 423 DELETE: " + e.getMessage());
            }
            check(s.available(), "un fichier verrouille ne rend pas le magasin indisponible");

            // code inattendu : IOException courte, pas d'indisponibilite durable
            srv.failNext(null, 1, 500, null);
            try { s.read("doc/hub-vers-client/000001.lot", 0); fail("lecture acceptee malgre 500"); } catch (IOException e) {
                check(e.getMessage().contains("HTTP 500") && e.getMessage().length() < 200, "message 500: " + e.getMessage());
            }
            check(Arrays.equals(old, s.read("doc/hub-vers-client/000001.lot", 0)), "lecture apres 500");
        } finally {
            srv.stop();
        }
    }

    // =======================================================================
    //  9. Serveur occupe : 429 / 503 et Retry-After
    // =======================================================================

    private static void testRetryAfter() throws Exception {
        step("9. 429 / 503 et Retry-After");
        FakeWebDavServer srv = new FakeWebDavServer(USER, PASS);
        try {
            WebDavRelayStore s = store(srv, PASS);
            check(s.available(), "disponible");
            s.write("doc/hub.ack", new byte[] { 9 });

            srv.failNext(null, 1, 429, "2");
            long t0 = System.nanoTime();
            try { s.list("doc"); fail("liste acceptee malgre 429"); } catch (IOException e) {
                check(e.getMessage().contains("serveur occupe"), "message 429: " + e.getMessage());
            }
            check((System.nanoTime() - t0) / 1_000_000 < 1500, "le magasin ne doit pas dormir sur un 429");
            int n = srv.requests();
            check(!s.available(), "indisponible pendant Retry-After");
            check("serveur occupe".equals(s.lastError()), "lastError 429: " + s.lastError());
            // plus aucune requete d'ici l'echeance
            try { s.read("doc/hub.ack", 0); fail("lecture pendant Retry-After"); } catch (IOException e) {
                check(e.getMessage().contains("serveur occupe"), "message pendant l'attente: " + e.getMessage());
            }
            try { s.write("doc/hub.ack", new byte[1]); fail("depot pendant Retry-After"); } catch (IOException ok) { }
            try { s.delete("doc/hub.ack"); fail("suppression pendant Retry-After"); } catch (IOException ok) { }
            check(!s.available() && srv.requests() == n, "aucune requete pendant Retry-After: " + (srv.requests() - n));
            Thread.sleep(1000);
            check(!s.available() && srv.requests() == n, "Retry-After de 2 s ecourte");
            Thread.sleep(1300);
            check(s.available(), "disponible apres Retry-After: " + s.lastError());
            check(s.lastError().isEmpty() && srv.requests() == n + 1, "une sonde apres l'echeance");
            check(Arrays.equals(new byte[] { 9 }, s.read("doc/hub.ack", 0)), "contenu intact apres l'attente");

            // 429 pendant la sonde elle-meme
            s.forgetProbe();
            srv.failNext("PROPFIND", 1, 429, "1");
            check(!s.available() && "serveur occupe".equals(s.lastError()), "sonde 429: " + s.lastError());
            n = srv.requests();
            check(!s.available() && srv.requests() == n, "pas de nouvelle sonde pendant Retry-After");
            Thread.sleep(1200);
            check(s.available(), "disponible apres la sonde 429");

            // 503 sans Retry-After : attente par defaut, pas de requete
            srv.failNext(null, 1, 503, null);
            try { s.write("doc/hub.ack", new byte[2]); fail("depot accepte malgre 503"); } catch (IOException e) {
                check(e.getMessage().contains("serveur occupe"), "message 503: " + e.getMessage());
            }
            n = srv.requests();
            Thread.sleep(300);
            check(!s.available() && srv.requests() == n, "503: indisponible sans requete");
            check(Arrays.equals(new byte[] { 9 }, srv.file("doc/hub.ack")), "contenu intact apres 503");
            s.forgetProbe();
            check(s.available(), "retour apres 503");
        } finally {
            srv.stop();
        }
    }

    // =======================================================================
    //  10. Serveur en panne puis de retour
    // =======================================================================

    private static void testPanne() throws Exception {
        step("10. serveur en panne puis de retour");
        FakeWebDavServer srv = new FakeWebDavServer(USER, PASS);
        try {
            WebDavRelayStore s = store(srv, PASS);
            check(s.available(), "disponible");
            byte[] a = random(500);
            s.write("doc/hub-vers-client/000001.lot", a);

            srv.setDown(true);
            // la disponibilite en cache reste vraie jusqu'au premier echec constate
            try { s.read("doc/hub-vers-client/000001.lot", 0); fail("lecture sur un serveur arrete"); } catch (IOException e) {
                check(e.getMessage() != null && !e.getMessage().isBlank() && !e.getMessage().contains(PASS), "message de panne: " + e.getMessage());
            }
            check(!s.available(), "un echec reseau rend le magasin indisponible sans attendre la sonde");
            check(!s.lastError().isEmpty(), "lastError en panne");
            s.forgetProbe();
            long t0 = System.nanoTime();
            check(!s.available(), "disponible alors que le serveur est arrete");
            check((System.nanoTime() - t0) / 1_000_000 < 20_000, "sonde trop lente sur un serveur arrete");
            check(!s.lastError().isEmpty() && !s.lastError().contains(PASS), "lastError en panne: " + s.lastError());
            try { s.write("doc/hub-vers-client/000002.lot", a); fail("depot sur un serveur arrete"); } catch (IOException ok) { }
            try { s.list("doc"); fail("liste sur un serveur arrete"); } catch (IOException ok) { }
            try { s.delete("doc/hub.ack"); fail("suppression sur un serveur arrete"); } catch (IOException ok) { }
            Path local = work.resolve("panne.lot");
            Files.write(local, a);
            try { s.write("doc/hub-vers-client/000002.lot", local); fail("depot de fichier sur un serveur arrete"); } catch (IOException ok) { }
            Files.delete(local);   // le fichier local ne doit pas rester ouvert apres l'echec

            srv.setDown(false);
            check(!s.available(), "le resultat de la sonde doit rester en cache");
            s.forgetProbe();
            check(s.available() && s.lastError().isEmpty(), "retour du serveur: " + s.lastError());
            check(Arrays.equals(a, s.read("doc/hub-vers-client/000001.lot", 0)), "contenu intact apres la panne");
            s.write("doc/hub-vers-client/000002.lot", a);
            check(names(s.list("doc/hub-vers-client")).size() == 2 && noPart(srv), "depot apres la panne");
        } finally {
            srv.stop();
        }
    }

    // =======================================================================
    //  11. Plusieurs threads sur le meme magasin
    // =======================================================================

    private static void testConcurrence() throws Exception {
        step("11. acces concurrents");
        FakeWebDavServer srv = new FakeWebDavServer(USER, PASS);
        try {
            WebDavRelayStore s = store(srv, PASS);
            AtomicReference<Throwable> err = new AtomicReference<>();
            List<Thread> ts = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                final int id = t;
                Thread th = new Thread(() -> {
                    try {
                        Random r = new Random(id);
                        String dir = "doc" + (id % 3) + "/" + (id % 2 == 0 ? "hub-vers-client" : "client-vers-hub");
                        for (int i = 0; i < 15; i++) {
                            String p = dir + "/t" + id + "-" + i + ".lot";
                            byte[] data = new byte[200 + r.nextInt(3000)];
                            r.nextBytes(data);
                            s.write(p, data);
                            check(s.available(), "disponible");
                            check(Arrays.equals(data, s.read(p, 0)), "lecture concurrente de " + p);
                            check(Arrays.equals(Arrays.copyOf(data, 38), s.read(p, 38)), "prefixe concurrent de " + p);
                            Long size = names(s.list(dir)).get("t" + id + "-" + i + ".lot");
                            check(size != null && size == data.length, "liste concurrente de " + p);
                            if (i % 2 == 0) s.delete(p);
                        }
                    } catch (Throwable e) {
                        err.compareAndSet(null, e);
                    }
                }, "concurrent-" + t);
                th.start();
                ts.add(th);
            }
            for (Thread th : ts) th.join(60_000);
            for (Thread th : ts) check(!th.isAlive(), "thread bloque");
            if (err.get() != null) throw new AssertionError("echec concurrent", err.get());
            check(srv.files().size() == 8 * 7 && noPart(srv), "fichiers restants: " + srv.files().size());
            check(srv.requests("PROPFIND") > 0 && s.lastError().isEmpty(), "lastError: " + s.lastError());
        } finally {
            srv.stop();
        }
    }

    // =======================================================================
    //  Outils
    // =======================================================================

    private static WebDavRelayStore store(FakeWebDavServer srv, String password) {
        return new WebDavRelayStore(srv.baseUrl(), USER, password, http);
    }

    private static Map<String, Long> names(List<RelayStore.Entry> l) {
        Map<String, Long> m = new TreeMap<>();
        for (RelayStore.Entry e : l) check(m.put(e.name(), e.size()) == null, "nom liste deux fois: " + e.name());
        return m;
    }

    private static boolean noPart(FakeWebDavServer srv) {
        for (String f : srv.files()) if (f.endsWith(".part")) return false;
        return true;
    }

    /** Nombre de fichiers ".part" presents sur le serveur. */
    private static int parts(FakeWebDavServer srv) {
        int n = 0;
        for (String f : srv.files()) if (f.endsWith(".part")) n++;
        return n;
    }

    private static byte[] random(int n) {
        byte[] b = new byte[n];
        RND.nextBytes(b);
        return b;
    }

    private static void wipe(Path root) throws Exception {
        if (!Files.exists(root)) return;
        for (int i = 0; i < 20; i++) {
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                    @Override public FileVisitResult visitFile(Path p, BasicFileAttributes a) throws IOException {
                        Files.delete(p); return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult postVisitDirectory(Path p, IOException e) throws IOException {
                        Files.delete(p); return FileVisitResult.CONTINUE;
                    }
                });
                return;
            } catch (IOException e) {
                Thread.sleep(200);
            }
        }
        throw new IOException("nettoyage impossible: " + root);
    }

    private static void step(String s) { System.out.println("=== " + s); }

    private static void check(boolean ok, String msg) { if (!ok) throw new AssertionError(msg); }

    private static void fail(String msg) { throw new AssertionError(msg); }
}
