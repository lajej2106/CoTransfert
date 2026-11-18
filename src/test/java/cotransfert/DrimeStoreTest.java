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
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tests du magasin Drime (DrimeRelayStore) contre un faux serveur local
 * (FakeDrimeServer). Simple main, sans dependance, aucun appel au service reel :
 * code de sortie non nul en cas d'echec.
 *
 * Usage : java -cp <classes> cotransfert.DrimeStoreTest <dossier de travail>
 */
public final class DrimeStoreTest {

    private static final String TOKEN = "jeton-TEST-9f3b7c21d4e84a06";
    private static final String TRANSIT = "Transit Test";
    /** "Mon Projet ete" avec ses accents, et un nom de fichier accentue. */
    private static final String PROJ = "Mon Projet \u00e9t\u00e9", NOTE = "note \u00e0 lire, n\u00b01.txt";
    private static final Random RND = new Random(7);
    /** Tout texte produit par le magasin : aucun ne doit contenir le jeton. */
    private static final List<String> SEEN = new CopyOnWriteArrayList<>();

    private static Path work;
    private static FakeDrimeServer srv;
    private static HttpClient hc;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) { System.err.println("usage: DrimeStoreTest <dossier de travail>"); System.exit(2); }
        work = Paths.get(args[0]).toAbsolutePath().normalize();
        Thread dog = new Thread(() -> {
            try { Thread.sleep(240_000); } catch (InterruptedException e) { return; }
            System.err.println("ECHEC: delai global depasse (blocage)");
            Runtime.getRuntime().halt(3);
        }, "watchdog");
        dog.setDaemon(true);
        dog.start();
        try {
            wipe(work);
            Files.createDirectories(work);
            hc = HttpSupport.client("none");
            testJson();
            testConfiguration();
            srv = new FakeDrimeServer(TOKEN);
            testOperations();
            testRemplacementEtDoublons();
            testPagination();
            testGrosDepot();
            testVariantesServeur();
            testCachePartage();
            testJetonRefuse();
            testDroitManquant();
            testServeurOccupe();
            testPanne();
            testQuota();
            testDossierDisparu();
            testConcurrence();
            check(srv.authLeaks() == 0, "le jeton a ete envoye a l'hote de stockage");
            check(srv.trash().isEmpty(), "la corbeille doit rester vide: " + srv.trash());
            for (String s : SEEN) check(!s.contains(TOKEN), "jeton present dans un message: " + s.replace(TOKEN, "<jeton>"));
            srv.stop();
            wipe(work);
            System.out.println("DRIME STORE TEST: OK");
            System.exit(0);
        } catch (Throwable t) {
            System.err.println("DRIME STORE TEST: ECHEC");
            t.printStackTrace();
            System.exit(1);
        }
    }

    // =======================================================================
    //  1. Analyseur JSON
    // =======================================================================

    private static void testJson() throws Exception {
        step("1. analyseur JSON");
        Object o = DrimeRelayStore.parseJson("\ufeff { \"a\" : [ 1 , -2.5e3 , true , false , null , \"x\\ny\\u00e9\\\"\\\\\\/\\t\" , {} , [] ] ,\r\n"
                + "\"id\":9007199254740993, \"big\":123456789012345678901234567890, \"z\":-0, \"s\":\"\\ud83d\\ude00\", \"a\\u0062\":{\"k\":{\"k\":[[]]}} }");
        Map<?, ?> m = (Map<?, ?>) o;
        List<?> a = (List<?>) m.get("a");
        check(a.size() == 8 && a.get(0).equals(1L) && a.get(1).equals(-2500.0) && a.get(2) == Boolean.TRUE && a.get(3) == Boolean.FALSE
                && a.get(4) == null, "valeurs simples: " + a);
        check("x\ny\u00e9\"\\/\t".equals(a.get(5)), "echappements: " + a.get(5));
        check(a.get(6) instanceof Map<?, ?> em && em.isEmpty() && a.get(7) instanceof List<?> el && el.isEmpty(), "conteneurs vides");
        check(m.get("id").equals(9007199254740993L), "entier 64 bits exact: " + m.get("id"));
        check(m.get("big") instanceof Double, "entier hors 64 bits");
        check(m.get("z").equals(0L) && "\ud83d\ude00".equals(m.get("s")) && m.containsKey("ab"), "divers");
        check(DrimeRelayStore.parseJson(" \"seul\" ").equals("seul") && DrimeRelayStore.parseJson("42").equals(42L)
                && DrimeRelayStore.parseJson("null") == null, "valeur racine simple");
        check(((Map<?, ?>) DrimeRelayStore.parseJson("{\"a\":1,\"a\":2}")).get("a").equals(2L), "cle en double");
        String deep = "[".repeat(40) + "]".repeat(40), tooDeep = "[".repeat(200) + "]".repeat(200);
        check(DrimeRelayStore.parseJson(deep) instanceof List, "imbrication raisonnable");
        for (String bad : new String[] { "", " ", "{", "[1,]", "{\"a\":1,}", "{a:1}", "{\"a\" 1}", "[1 2]", "01", "1.", ".5", "-", "1e", "+1", "tru", "nul",
                "\"abc", "\"a\\x\"", "\"a\\u12g4\"", "\"a\\u12\"", "\"tab\there\"", "{} x", "[]]", "'a'", "{\"a\":}", "[,1]", "NaN", "\"\\", tooDeep,
                "<html><body>portail</body></html>" }) {
            try { DrimeRelayStore.parseJson(bad); fail("JSON invalide accepte: " + (bad.length() > 30 ? bad.substring(0, 30) : bad)); }
            catch (IOException ok) { }
        }
        try { DrimeRelayStore.parseJson(null); fail("null accepte"); } catch (IOException ok) { }
        String tricky = "a\"b\\c\nd\u0001\u00e9/";
        check(tricky.equals(DrimeRelayStore.parseJson(DrimeRelayStore.q(tricky))), "ecriture puis relecture d'une chaine");
    }

    // =======================================================================
    //  2. Configuration (aucun appel reseau)
    // =======================================================================

    private static void testConfiguration() throws Exception {
        step("2. configuration");
        DrimeRelayStore def = new DrimeRelayStore("", TOKEN, " ", 0, hc);
        check("drime".equals(def.id()) && def.pollMs() == 10_000 && def.maxLotBytes() == 16L << 20, "identite et limites");
        check(def.describe().contains("app.drime.cloud") && def.describe().contains("CoTransfertRelais"), "valeurs par defaut: " + def.describe());
        check(def.generation() == 0 && def.inboundReady(), "generation / inboundReady");
        note(def.describe());
        def.close();
        for (String[] c : new String[][] { { "http://stockage.example.org/api/v1", TOKEN }, { "pas une url", TOKEN }, { "ftp://127.0.0.1/x", TOKEN },
                { "http://127.0.0.1:9/api/v1", "" }, { "http://127.0.0.1:9/api/v1", "jeton avec espace" }, { "http://127.0.0.1:9/api/v1", "jeton\r\nX: y" } }) {
            DrimeRelayStore s = new DrimeRelayStore(c[0], c[1], "x", 0, hc);
            check(!s.available() && !s.lastError().isEmpty(), "configuration invalide acceptee: " + c[0]);
            note(s.lastError()); note(s.describe());
            try { s.list("a"); fail("liste avec une configuration invalide"); } catch (IOException e) { note(e); }
            try { s.write("a/b", new byte[1]); fail("ecriture avec une configuration invalide"); } catch (IOException e) { note(e); }
            s.close();
        }
    }

    // =======================================================================
    //  3. Toutes les operations du contrat, dossiers imbriques, espaces et accents
    // =======================================================================

    private static void testOperations() throws Exception {
        step("3. operations, dossiers imbriques, noms avec espaces et accents");
        DrimeRelayStore s = store();
        check(s.available() && s.lastError().isEmpty(), "disponible: " + s.lastError());
        check(s.available() && srv.count("GET /users/me") == 1, "available() doit etre mis en cache: " + srv.count("GET /users/me"));
        note(s.describe());
        check(!s.describe().contains(TOKEN) && s.describe().contains(TRANSIT), "describe: " + s.describe());

        // rien n'existe : lectures sans effet, aucun dossier cree
        check(s.list(PROJ + "/hub-vers-client").isEmpty() && s.read(PROJ + "/hub.ack", 0) == null, "magasin vide");
        s.delete(PROJ + "/hub.ack");
        check(srv.entries().isEmpty(), "une lecture ne doit rien creer: " + srv.entries());

        byte[] lot1 = random(3000), lot2 = random(70_000), ack = "CTACK1 12 abcdef".getBytes(StandardCharsets.US_ASCII);
        String dir = PROJ + "/hub-vers-client";
        s.write(dir + "/000001.lot", lot1);
        Path f2 = work.resolve("lot2.bin");
        Files.write(f2, lot2);
        s.write(dir + "/000002.lot", f2);
        s.write(dir + "/" + NOTE, new byte[0]);
        s.write(PROJ + "/hub.ack", ack);
        s.write("racine.txt", "r".getBytes(StandardCharsets.US_ASCII));

        FakeDrimeServer.Item it = srv.find(TRANSIT + "/" + dir + "/000001.lot");
        check(it != null && !it.folder() && it.size() == 3000 && Arrays.equals(lot1, srv.content(it.id())), "depot simple sur le serveur");
        check(srv.find(TRANSIT + "/" + dir + "/" + NOTE) != null, "nom accentue sur le serveur");
        check(srv.find(TRANSIT + "/racine.txt") != null && srv.find(TRANSIT).parentId() == null, "dossier de transit a la racine");
        check(countNamed(TRANSIT) == 1 && countNamed(PROJ) == 1 && countNamed("hub-vers-client") == 1, "dossiers crees une seule fois");
        check(srv.count("POST /uploads") == 5 && srv.count("POST /s3/multipart/create") == 0, "petits fichiers : depot simple");
        check(srv.count("PUT /file-entries/{id}") == 5, "depot sous .part puis renommage");
        noPart();

        check(sizes(s.list(dir)).equals(Map.of("000001.lot", 3000L, "000002.lot", 70_000L, NOTE, 0L)), "liste: " + s.list(dir));
        check(sizes(s.list(PROJ)).equals(Map.of("hub.ack", (long) ack.length)), "les sous-dossiers ne sont pas listes: " + s.list(PROJ));
        check(sizes(s.list("")).equals(Map.of("racine.txt", 1L)), "liste du dossier de transit: " + s.list(""));
        check(s.list(PROJ + "/absent").isEmpty() && s.list("Autre/absent").isEmpty(), "dossier absent");

        check(Arrays.equals(lot1, s.read(dir + "/000001.lot", 0)) && Arrays.equals(lot2, s.read(dir + "/000002.lot", -1)), "lecture complete");
        check(Arrays.equals(ack, s.read(PROJ + "/hub.ack", 0)), "lecture de l'acquittement");
        check(Arrays.equals(Arrays.copyOf(lot2, 38), s.read(dir + "/000002.lot", 38)), "lecture d'un prefixe (Range)");
        check(Arrays.equals(lot1, s.read(dir + "/000001.lot", 1_000_000)), "prefixe plus long que le fichier");
        check(s.read(dir + "/" + NOTE, 0).length == 0 && s.read(dir + "/" + NOTE, 38).length == 0, "fichier vide");
        check(s.read(dir + "/000003.lot", 0) == null && s.read(PROJ + "/absent/x", 10) == null, "lecture d'un absent");
        srv.setRangeSupported(false);
        check(Arrays.equals(Arrays.copyOf(lot2, 38), s.read(dir + "/000002.lot", 38)), "prefixe quand le serveur ignore Range (200 tronque)");
        srv.setRangeSupported(true);

        s.delete(dir + "/000001.lot");
        s.delete(dir + "/000001.lot");                       // absent : sans effet
        s.delete(PROJ + "/absent/x");
        check(s.read(dir + "/000001.lot", 0) == null && s.list(dir).size() == 2, "suppression");
        check(srv.find(TRANSIT + "/" + dir + "/000001.lot") == null && srv.trash().isEmpty(), "suppression definitive, corbeille vide");
        s.delete(dir + "/" + NOTE);
        s.delete(dir + "/000002.lot");
        s.delete(PROJ + "/hub.ack");
        s.delete("racine.txt");
        check(s.list(dir).isEmpty() && files() == 0, "tout supprime: " + srv.entries());
        check(srv.authLeaks() == 0, "jeton envoye au stockage lors d'un telechargement redirige");
        check(srv.count("GET stockage") > 0, "les telechargements devaient passer par la redirection");
        s.close();
    }

    // =======================================================================
    //  4. Remplacement et doublons de nom
    // =======================================================================

    private static void testRemplacementEtDoublons() throws Exception {
        step("4. remplacement, doublons de nom, restes .part");
        DrimeRelayStore s = store();
        String p = PROJ + "/client.ack";
        for (int i = 1; i <= 4; i++) s.write(p, ("version " + i).getBytes(StandardCharsets.US_ASCII));
        check(countNamed("client.ack") == 1 && srv.trash().isEmpty(), "un remplacement laisse une seule entree et une corbeille vide");
        check("version 4".equals(new String(s.read(p, 0), StandardCharsets.US_ASCII)), "derniere version lue");
        noPart();

        // doublons deja presents (deposes par un autre outil) + reste d'un depot interrompu
        long projId = srv.find(TRANSIT + "/" + PROJ).id();
        srv.addFile(projId, "double.lot", "ancien".getBytes(StandardCharsets.US_ASCII));
        srv.addFile(projId, "double.lot", "moyen!!".getBytes(StandardCharsets.US_ASCII));
        srv.addFile(projId, "double.lot", "le plus recent".getBytes(StandardCharsets.US_ASCII));
        srv.addFile(projId, "double.lot.part", new byte[5]);
        srv.addFile(projId, "seul.lot.part", new byte[5]);
        s.cacheTtlMs = 0;
        Map<String, Long> l = sizes(s.list(PROJ));
        check(l.equals(Map.of("client.ack", 9L, "double.lot", 14L)), "liste : le plus recent des doublons, sans .part: " + l);
        check("le plus recent".equals(new String(s.read(PROJ + "/double.lot", 0), StandardCharsets.US_ASCII)), "lecture du plus recent des doublons");
        check(s.read(PROJ + "/seul.lot", 0) == null, "un .part n'est pas le fichier");
        s.write(PROJ + "/double.lot", "neuf".getBytes(StandardCharsets.US_ASCII));
        check(countNamed("double.lot") == 1 && countNamed("double.lot.part") == 0, "ecriture : doublons et reste .part supprimes");
        check("neuf".equals(new String(s.read(PROJ + "/double.lot", 0), StandardCharsets.US_ASCII)), "contenu apres remplacement des doublons");
        srv.addFile(projId, "double.lot", "encore".getBytes(StandardCharsets.US_ASCII));
        s.delete(PROJ + "/double.lot");
        check(countNamed("double.lot") == 0 && srv.trash().isEmpty(), "suppression de tous les doublons, definitivement");
        s.delete(PROJ + "/client.ack");

        // deux dossiers homonymes (creation simultanee par les deux postes) : le plus ancien fait foi
        long first = srv.addFolder(projId, "jumeau"), second = srv.addFolder(projId, "jumeau");
        srv.addFile(second, "perdu.lot", new byte[3]);
        DrimeRelayStore a = store(), b = store();
        a.write(PROJ + "/jumeau/a.lot", new byte[10]);
        b.write(PROJ + "/jumeau/b.lot", new byte[20]);
        check(sizes(a.list(PROJ + "/jumeau")).equals(Map.of("a.lot", 10L, "b.lot", 20L)) && sizes(b.list(PROJ + "/jumeau")).size() == 2,
                "dossiers homonymes : les deux cotes doivent choisir le meme");
        for (FakeDrimeServer.Item it : srv.entries()) if (it.name().equals("a.lot") || it.name().equals("b.lot")) check(it.parentId() == first, "plus ancien dossier attendu");
        srv.remove(first); srv.remove(second);

        // creation concurrente reelle : le serveur accepte les homonymes, chaque createur verifie apres coup
        srv.setDuplicateFoldersAllowed(true);
        DrimeRelayStore c = store(), d = store();
        Thread t1 = thread(() -> c.write(PROJ + "/course/x/c.lot", new byte[1]));
        Thread t2 = thread(() -> d.write(PROJ + "/course/x/d.lot", new byte[2]));
        t1.join(30_000); t2.join(30_000);
        srv.setDuplicateFoldersAllowed(false);
        check(ERR.isEmpty(), "ecritures concurrentes: " + ERR);
        check(countNamed("course") == 1 && countNamed("x") == 1, "dossier cree en double: " + countNamed("course") + "/" + countNamed("x"));
        c.cacheTtlMs = 0;
        check(c.list(PROJ + "/course/x").size() == 2, "les deux fichiers dans le meme dossier");
        srv.remove(srv.find(TRANSIT + "/" + PROJ + "/course").id());
        srv.remove(projId);
        for (FakeDrimeServer.Item it : new ArrayList<>(srv.entries())) if (!it.folder()) srv.remove(it.id());
        s.close(); a.close(); b.close(); c.close(); d.close();
    }

    // =======================================================================
    //  5. Pagination
    // =======================================================================

    private static void testPagination() throws Exception {
        step("5. pagination");
        DrimeRelayStore s = store();
        s.write("Pages/in/000000.lot", new byte[1]);
        long dirId = srv.find(TRANSIT + "/Pages/in").id(), rootish = srv.find(TRANSIT + "/Pages").id();
        for (int i = 1; i <= 130; i++) srv.addFile(dirId, String.format("%06d.lot", i), new byte[i]);
        for (int i = 0; i < 120; i++) srv.addFolder(rootish, String.format("autre-%03d", i));     // le dossier cherche est sur la 3e page
        srv.addFolder(rootish, "zz-dernier");
        srv.resetCounters();
        DrimeRelayStore fresh = store();
        fresh.cacheTtlMs = 60_000;
        List<RelayStore.Entry> l = fresh.list("Pages/in");
        check(l.size() == 131, "liste paginee incomplete: " + l.size());
        for (RelayStore.Entry e : l) check(e.size() == Math.max(1, Integer.parseInt(e.name().substring(0, 6))), "taille de " + e.name());
        check(srv.count("GET /drive/file-entries") >= 3 + 1 + 3, "plusieurs pages attendues: " + srv.count("GET /drive/file-entries"));
        check(fresh.list("Pages/zz-dernier").isEmpty() && fresh.read("Pages/zz-dernier/x", 0) == null, "dossier trouve en derniere page");
        check(Arrays.equals(new byte[130], fresh.read("Pages/in/000130.lot", 0)), "lecture d'un fichier de la derniere page");
        // le cache du dossier sert les lectures qui suivent une liste, sans nouvelle liste
        int lists = srv.count("GET /drive/file-entries");
        fresh.read("Pages/in/000001.lot", 0); fresh.read("Pages/in/000077.lot", 0); fresh.read("Pages/in/999999.lot", 0);
        check(srv.count("GET /drive/file-entries") == lists, "les lectures devaient utiliser le cache de la liste");
        // une suppression met le cache a jour
        fresh.delete("Pages/in/000077.lot");
        check(fresh.read("Pages/in/000077.lot", 0) == null && fresh.list("Pages/in").size() == 130, "cache apres suppression");
        srv.remove(srv.find(TRANSIT + "/Pages").id());
        s.close(); fresh.close();
    }

    // =======================================================================
    //  6. Depot en plusieurs parts
    // =======================================================================

    private static void testGrosDepot() throws Exception {
        step("6. depot en plusieurs parts (URL signees)");
        DrimeRelayStore s = store();
        srv.resetCounters();
        int part = 5 << 20;
        byte[] big = random(2 * part + 12_345);
        Path f = work.resolve("gros.lot");
        Files.write(f, big);
        s.write(PROJ + "/hub-vers-client/000009.lot", f);
        check(srv.count("POST /s3/multipart/create") == 1 && srv.count("POST /s3/multipart/batch-sign-part-urls") >= 1
                && srv.count("PUT stockage") == 3 && srv.count("POST /s3/multipart/complete") == 1 && srv.count("POST /s3/entries") == 1
                && srv.count("POST /uploads") == 0, "enchainement du depot en parts");
        check(srv.authLeaks() == 0, "jeton envoye avec une URL signee");
        FakeDrimeServer.Item it = srv.find(TRANSIT + "/" + PROJ + "/hub-vers-client/000009.lot");
        check(it != null && Arrays.equals(big, srv.content(it.id())), "contenu du gros fichier sur le serveur");
        check(Arrays.equals(big, s.read(PROJ + "/hub-vers-client/000009.lot", 0)), "aller-retour du gros fichier");
        check(Arrays.equals(Arrays.copyOf(big, 38), s.read(PROJ + "/hub-vers-client/000009.lot", 38)), "prefixe du gros fichier");
        noPart();

        // remplacement par un gros contenu en memoire, puis seuils : 5 Mio pile = parts, un octet de moins = simple
        byte[] big2 = random(part + 1);
        s.write(PROJ + "/hub-vers-client/000009.lot", big2);
        check(countNamed("000009.lot") == 1 && Arrays.equals(big2, s.read(PROJ + "/hub-vers-client/000009.lot", 0)), "remplacement d'un gros fichier");
        srv.resetCounters();
        s.write(PROJ + "/seuil-a.lot", new byte[part]);
        check(srv.count("POST /s3/multipart/create") == 1 && srv.count("PUT stockage") == 1 && srv.count("POST /uploads") == 0, "5 Mio : depot en parts");
        s.write(PROJ + "/seuil-b.lot", new byte[part - 1]);
        check(srv.count("POST /s3/multipart/create") == 1 && srv.count("POST /uploads") == 1, "moins de 5 Mio : depot simple");
        check(s.read(PROJ + "/seuil-a.lot", 0).length == part && s.read(PROJ + "/seuil-b.lot", 0).length == part - 1, "tailles aux seuils");
        for (String n : new String[] { "/hub-vers-client/000009.lot", "/seuil-a.lot", "/seuil-b.lot" }) s.delete(PROJ + n);
        check(files() == 0 && srv.trash().isEmpty(), "menage apres les gros depots");
        s.close();
    }

    // =======================================================================
    //  7. Variantes du serveur : pas de renommage, pas de redirection, espace de travail
    // =======================================================================

    private static void testVariantesServeur() throws Exception {
        step("7. sans renommage, sans redirection, espace de travail");
        srv.setRenameSupported(false);
        srv.setRedirectDownloads(false);
        DrimeRelayStore s = store();
        srv.resetCounters();
        for (int i = 1; i <= 3; i++) s.write(PROJ + "/direct.ack", ("v" + i).getBytes(StandardCharsets.US_ASCII));
        check(countNamed("direct.ack") == 1 && srv.trash().isEmpty(), "sans renommage : une seule entree, corbeille vide");
        check(srv.count("PUT /file-entries/{id}") == 1, "le renommage refuse ne doit etre tente qu'une fois: " + srv.count("PUT /file-entries/{id}"));
        check("v3".equals(new String(s.read(PROJ + "/direct.ack", 0), StandardCharsets.US_ASCII)), "lecture sans redirection");
        check("v".equals(new String(s.read(PROJ + "/direct.ack", 1), StandardCharsets.US_ASCII)), "prefixe sans redirection");
        noPart();
        s.delete(PROJ + "/direct.ack");
        srv.setRenameSupported(true);
        srv.setRedirectDownloads(true);

        DrimeRelayStore w = new DrimeRelayStore(srv.apiUrl() + "/", TOKEN, "/" + TRANSIT + "/", 7, hc);
        w.write("esp/f.lot", new byte[9]);
        check(sizes(w.list("esp")).equals(Map.of("f.lot", 9L)) && s.list("esp").isEmpty(), "espaces de travail separes");
        for (FakeDrimeServer.Item it : srv.entries()) if (it.name().equals("f.lot") || it.name().equals("esp")) check(it.workspaceId() == 7, "espace de l'entree " + it);
        w.delete("esp/f.lot");
        note(w.describe());
        for (FakeDrimeServer.Item it : new ArrayList<>(srv.entries())) if (it.workspaceId() == 7) srv.remove(it.id());
        s.close(); w.close();
    }

    // =======================================================================
    //  8. Deux magasins (hub et client) : le cache de l'un suit les ecritures de l'autre
    // =======================================================================

    private static void testCachePartage() throws Exception {
        step("8. deux postes sur le meme magasin");
        DrimeRelayStore hub = store(), cli = store();
        hub.cacheTtlMs = 60_000; cli.cacheTtlMs = 60_000;
        hub.write(PROJ + "/hub.ack", "a1".getBytes(StandardCharsets.US_ASCII));
        check(cli.list(PROJ).size() == 1 && "a1".equals(new String(cli.read(PROJ + "/hub.ack", 0), StandardCharsets.US_ASCII)), "lecture par le pair");
        hub.write(PROJ + "/hub.ack", "a2-plus-long".getBytes(StandardCharsets.US_ASCII));
        // le cache du client pointe sur l'entree remplacee : 404 puis nouvelle liste
        check("a2-plus-long".equals(new String(cli.read(PROJ + "/hub.ack", 0), StandardCharsets.US_ASCII)), "entree remplacee par le pair");
        hub.delete(PROJ + "/hub.ack");
        check(cli.read(PROJ + "/hub.ack", 0) == null, "entree supprimee par le pair");
        cli.write(PROJ + "/client-vers-hub/000001.lot", new byte[100]);
        check(sizes(hub.list(PROJ + "/client-vers-hub")).equals(Map.of("000001.lot", 100L)), "list() interroge toujours le serveur");
        hub.cacheTtlMs = 150;
        cli.write(PROJ + "/client.ack", "c1".getBytes(StandardCharsets.US_ASCII));
        Thread.sleep(200);
        check("c1".equals(new String(hub.read(PROJ + "/client.ack", 0), StandardCharsets.US_ASCII)), "le cache expire : fichier cree par le pair visible");
        cli.delete(PROJ + "/client.ack"); cli.delete(PROJ + "/client-vers-hub/000001.lot");
        hub.close(); cli.close();
    }

    // =======================================================================
    //  9. Pannes : 401, 403, 429/503/500, serveur coupe, quota
    // =======================================================================

    private static void testJetonRefuse() throws Exception {
        step("9. jeton refuse (401)");
        DrimeRelayStore bad = new DrimeRelayStore(srv.apiUrl(), TOKEN + "-perime", TRANSIT, 0, hc);
        check(!bad.available() && "jeton refuse".equals(bad.lastError()), "401 a la sonde: " + bad.lastError());
        note(bad.lastError()); note(bad.describe());
        expectFail(() -> bad.list(PROJ), "jeton refuse");
        expectFail(() -> bad.write(PROJ + "/x.lot", new byte[3]), "jeton refuse");
        expectFail(() -> bad.read(PROJ + "/x.lot", 0), "jeton refuse");
        expectFail(() -> bad.delete(PROJ + "/x.lot"), "jeton refuse");
        bad.close();

        // jeton revoque en cours de route, puis retabli
        DrimeRelayStore s = store();
        s.probeTtlMs = 0;
        check(s.available(), "disponible avant revocation");
        srv.setToken("autre-jeton");
        expectFail(() -> s.write(PROJ + "/x.lot", new byte[3]), "jeton refuse");
        check(!s.available() && "jeton refuse".equals(s.lastError()), "revocation: " + s.lastError());
        srv.setToken(TOKEN);
        check(s.available() && s.lastError().isEmpty(), "jeton retabli: " + s.lastError());
        check(files() == 0, "rien ne doit avoir ete depose");
        s.close();
    }

    private static void testDroitManquant() throws Exception {
        step("10. jeton en lecture seule (403)");
        DrimeRelayStore w = store();
        w.write(PROJ + "/lisible.lot", new byte[4]);
        DrimeRelayStore s = store();
        srv.failMutations(403);
        check(s.available(), "la sonde passe avec un jeton en lecture seule");
        expectFail(() -> s.write(PROJ + "/x.lot", new byte[3]), "jeton sans le droit necessaire");
        check(!s.available() && "jeton sans le droit necessaire".equals(s.lastError()), "403: le canal doit se declarer indisponible: " + s.lastError());
        check(s.list(PROJ).size() == 1 && s.read(PROJ + "/lisible.lot", 0).length == 4, "les lectures restent possibles");
        check(!s.available(), "indisponibilite maintenue apres un 403 (pas d'oscillation)");
        srv.clearFailures();
        w.delete(PROJ + "/lisible.lot");
        check(files() == 0, "rien ne doit rester apres un 403: " + srv.entries());
        s.close(); w.close();
    }

    private static void testServeurOccupe() throws Exception {
        step("11. serveur occupe (429 avec Retry-After, 503 sans), erreur 500");
        DrimeRelayStore s = store();
        s.probeTtlMs = 0;
        s.write(PROJ + "/occ.lot", new byte[8]);
        srv.failAll(429, 1, 1);
        expectFail(() -> s.list(PROJ), "serveur occupe");
        int n = srv.requests();
        check(!s.available() && "serveur occupe".equals(s.lastError()), "429: " + s.lastError());
        expectFail(() -> s.read(PROJ + "/occ.lot", 0), "serveur occupe");
        expectFail(() -> s.write(PROJ + "/occ2.lot", new byte[1]), "serveur occupe");
        check(srv.requests() == n, "aucune requete pendant l'attente demandee par le serveur");
        Thread.sleep(1150);
        check(s.available() && s.lastError().isEmpty(), "reprise apres Retry-After: " + s.lastError());
        check(s.list(PROJ).size() == 1 && s.read(PROJ + "/occ.lot", 0).length == 8, "operations apres la reprise");

        srv.failAll(503, 1, 0);                              // sans Retry-After : repli progressif, 2 s au premier refus
        long t0 = System.nanoTime();
        expectFail(() -> s.write(PROJ + "/occ3.lot", new byte[1]), "serveur occupe");
        n = srv.requests();
        Thread.sleep(600);
        check(!s.available() && srv.requests() == n, "repli sans Retry-After");
        while (!s.available()) {
            check((System.nanoTime() - t0) / 1_000_000 < 8000, "pas de reprise apres le repli");
            Thread.sleep(100);
        }
        check((System.nanoTime() - t0) / 1_000_000 >= 1900, "repli trop court");
        s.write(PROJ + "/occ3.lot", new byte[1]);

        srv.failAll(500, 1, 0);
        expectFail(() -> s.list(PROJ), "erreur du serveur");
        check(s.lastError().contains("erreur du serveur"), "500: " + s.lastError());
        check(s.available() && s.list(PROJ).size() == 2, "reprise apres une erreur 500");
        s.delete(PROJ + "/occ.lot"); s.delete(PROJ + "/occ3.lot");
        noPart();
        s.close();
    }

    private static void testPanne() throws Exception {
        step("12. serveur coupe puis de retour");
        DrimeRelayStore s = store();
        s.write(PROJ + "/avant.lot", new byte[6]);
        check(s.available(), "disponible avant la panne");
        srv.setDown(true);
        expectFail(() -> s.write(PROJ + "/pendant.lot", new byte[6]), null);
        check(!s.available() && !s.lastError().isEmpty(), "panne non detectee");
        note(s.lastError());
        expectFail(() -> s.list(PROJ), null);
        expectFail(() -> s.read(PROJ + "/avant.lot", 0), null);
        srv.setDown(false);
        check(sizes(s.list(PROJ)).equals(Map.of("avant.lot", 6L)), "liste apres le retour");
        check(s.available() && s.lastError().isEmpty(), "une operation reussie retablit la disponibilite: " + s.lastError());
        s.write(PROJ + "/pendant.lot", new byte[6]);

        // meme chose vue par la seule sonde
        DrimeRelayStore p = store();
        p.probeTtlMs = 150;
        check(p.available(), "sonde avant la panne");
        srv.setDown(true);
        Thread.sleep(200);
        check(!p.available() && !p.lastError().isEmpty(), "sonde pendant la panne");
        note(p.lastError());
        srv.setDown(false);
        check(!p.available(), "resultat de la sonde en cache");
        Thread.sleep(200);
        check(p.available() && p.lastError().isEmpty(), "sonde apres le retour");

        // serveur arrete pour de bon : connexion refusee, jamais d'exception hors IOException
        FakeDrimeServer gone = new FakeDrimeServer(TOKEN);
        DrimeRelayStore g = new DrimeRelayStore(gone.apiUrl(), TOKEN, TRANSIT, 0, hc);
        gone.stop();
        check(!g.available() && !g.lastError().isEmpty(), "serveur arrete");
        note(g.lastError());
        expectFail(() -> g.list("x"), null);
        // reponse qui n'est pas du JSON (page d'un portail) : indisponible, pas d'exception inattendue
        com.sun.net.httpserver.HttpServer portail = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), 0), 8);
        portail.createContext("/", x -> {
            byte[] page = "<html><body>Connexion requise</body></html>".getBytes(StandardCharsets.US_ASCII);
            x.getRequestBody().readAllBytes();
            x.getResponseHeaders().set("Content-Type", "text/html");
            x.sendResponseHeaders(200, page.length);
            x.getResponseBody().write(page);
            x.close();
        });
        portail.start();
        DrimeRelayStore html = new DrimeRelayStore("http://127.0.0.1:" + portail.getAddress().getPort() + "/api/v1", TOKEN, TRANSIT, 0, hc);
        check(!html.available() && !html.lastError().isEmpty(), "reponse non JSON acceptee");
        note(html.lastError());
        expectFail(() -> html.list("x"), "reponse illisible");
        portail.stop(0);
        s.delete(PROJ + "/avant.lot"); s.delete(PROJ + "/pendant.lot");
        noPart();
        s.close(); p.close(); g.close(); html.close();
    }

    private static void testQuota() throws Exception {
        step("13. quota plein");
        DrimeRelayStore s = store();
        s.write(PROJ + "/petit.lot", new byte[100]);
        srv.setQuotaBytes(1000);
        expectFail(() -> s.write(PROJ + "/trop.lot", new byte[5000]), "quota plein");
        check(!s.available() && "quota plein".equals(s.lastError()), "quota: " + s.lastError());
        srv.setQuotaBytes(-1);
        check(sizes(s.list(PROJ)).equals(Map.of("petit.lot", 100L)), "rien de partiel apres un refus de quota: " + s.list(PROJ));
        s.delete(PROJ + "/petit.lot");
        noPart();
        s.close();
    }

    // =======================================================================
    //  14. Dossier supprime par ailleurs : identifiants perimes
    // =======================================================================

    private static void testDossierDisparu() throws Exception {
        step("14. dossier supprime par ailleurs");
        DrimeRelayStore s = store();
        s.write(PROJ + "/in/a.lot", new byte[5]);
        srv.remove(srv.find(TRANSIT).id());                  // tout le transit disparait
        check(srv.entries().isEmpty(), "transit retire");
        check(s.read(PROJ + "/in/a.lot", 0) == null, "lecture apres disparition");
        s.write(PROJ + "/in/b.lot", new byte[7]);            // identifiants en cache perimes : 404, nouvelle resolution
        check(srv.find(TRANSIT + "/" + PROJ + "/in/b.lot") != null && sizes(s.list(PROJ + "/in")).equals(Map.of("b.lot", 7L)), "ecriture apres disparition");
        srv.remove(srv.find(TRANSIT + "/" + PROJ + "/in").id());
        check(s.list(PROJ + "/in").isEmpty(), "liste d'un dossier disparu");
        s.delete(PROJ + "/in/b.lot");
        s.write(PROJ + "/in/c.lot", new byte[2]);
        check(sizes(s.list(PROJ + "/in")).equals(Map.of("c.lot", 2L)), "dossier recree");
        s.delete(PROJ + "/in/c.lot");
        s.close();
    }

    // =======================================================================
    //  15. Plusieurs threads sur un meme magasin
    // =======================================================================

    private static void testConcurrence() throws Exception {
        step("15. plusieurs threads");
        DrimeRelayStore s = store();
        for (FakeDrimeServer.Item it : new ArrayList<>(srv.entries())) if (!it.folder()) srv.remove(it.id());
        ERR.clear();
        List<Thread> ts = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            final int id = t;
            ts.add(thread(() -> {
                for (int i = 0; i < 8; i++) {
                    String p = "Multi/d" + (id % 2) + "/t" + id + "-" + i + ".lot";
                    byte[] b = new byte[100 + id * 10 + i];
                    Arrays.fill(b, (byte) (id * 16 + i));
                    s.write(p, b);
                    s.write(p, b);
                    if (!Arrays.equals(b, s.read(p, 0))) throw new IOException("relecture differente: " + p);
                    s.list("Multi/d" + (id % 2));
                    s.available();
                    if (i % 4 == 3) s.delete(p);
                }
            }));
        }
        for (Thread t : ts) t.join(60_000);
        for (Thread t : ts) check(!t.isAlive(), "thread bloque");
        check(ERR.isEmpty(), "erreurs en concurrence: " + ERR);
        check(s.list("Multi/d0").size() == 12 && s.list("Multi/d1").size() == 12, "fichiers attendus: " + s.list("Multi/d0").size() + "+" + s.list("Multi/d1").size());
        check(countNamed("Multi") == 1 && countNamed("d0") == 1 && countNamed("d1") == 1, "dossier cree en double par deux threads");
        check(files() == 24, "une seule entree par fichier: " + files());
        noPart();
        srv.remove(srv.find(TRANSIT).id());
        s.close();
    }

    // =======================================================================
    //  Outils
    // =======================================================================

    private interface Op { void run() throws IOException; }

    private static final List<String> ERR = new CopyOnWriteArrayList<>();

    private static DrimeRelayStore store() { return new DrimeRelayStore(srv.apiUrl(), TOKEN, TRANSIT, 0, hc); }

    private static Thread thread(Op op) {
        Thread t = new Thread(() -> {
            try { op.run(); } catch (Throwable e) { ERR.add(String.valueOf(e)); }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** L'operation doit lever une IOException (et rien d'autre) dont le message contient part. */
    private static void expectFail(Op op, String part) {
        try {
            op.run();
        } catch (IOException e) {
            note(e);
            if (part != null && (e.getMessage() == null || !e.getMessage().contains(part)))
                throw new AssertionError("message attendu \"" + part + "\", recu: " + e.getMessage());
            return;
        }
        throw new AssertionError("echec attendu" + (part == null ? "" : " (" + part + ")"));
    }

    private static void note(String s) { if (s != null) SEEN.add(s); }

    private static void note(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            SEEN.add(String.valueOf(t));
            if (t == t.getCause()) break;
        }
    }

    private static Map<String, Long> sizes(List<RelayStore.Entry> l) {
        Map<String, Long> m = new java.util.TreeMap<>();
        for (RelayStore.Entry e : l) check(m.put(e.name(), e.size()) == null, "nom liste deux fois: " + e.name());
        return m;
    }

    private static int countNamed(String name) {
        int n = 0;
        for (FakeDrimeServer.Item it : srv.entries()) if (it.name().equals(name)) n++;
        return n;
    }

    private static int files() {
        int n = 0;
        for (FakeDrimeServer.Item it : srv.entries()) if (!it.folder()) n++;
        return n;
    }

    private static void noPart() {
        for (FakeDrimeServer.Item it : srv.entries()) check(!it.name().endsWith(".part"), "fichier .part oublie: " + it);
        check(srv.trash().isEmpty(), "corbeille non vide: " + srv.trash());
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
