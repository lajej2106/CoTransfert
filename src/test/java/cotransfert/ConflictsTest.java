package cotransfert;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ecran "Conflits", de bout en bout : deux vrais dossiers, deux vrais moteurs,
 * un canal par lots (disque relais) dans un dossier temporaire.
 *
 *   1. un vrai conflit (meme fichier modifie des deux cotes pendant que le canal
 *      est coupe), sa liste, le compteur de /api/status ; "garder ma version" :
 *      les deux postes finissent avec mon contenu, sans copie ni nouveau conflit ;
 *   2. "garder la version de l'autre poste" : idem avec son contenu ;
 *   3. resolution groupee de plusieurs conflits, d'un dossier puis de tous ;
 *   4. conflit detecte d'un seul cote ;
 *   5. resolution sans moteur (compteur lu sur le disque), puis session : convergence ;
 *   6. choix croises "ma version" sur les deux postes : aucun echange silencieux,
 *      le conflit revient des deux cotes, puis se regle ;
 *   7. "ma version" alors que mon fichier a disparu : la suppression est propagee ;
 *   8. une copie ".conflict-peer-" n'est jamais transmise au pair ;
 *   9. refus : chemins arbitraires, "..", chemin absolu, dossier ou conflit inconnu.
 *
 * Usage : java -cp <classes> cotransfert.ConflictsTest <dossier de travail>
 */
public final class ConflictsTest {

    private static final String NAME = "classeur";
    private static Path work;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) { System.err.println("usage: ConflictsTest <dossier de travail>"); System.exit(2); }
        work = Paths.get(args[0]).toAbsolutePath().normalize();
        Thread dog = new Thread(() -> {
            try { Thread.sleep(300_000); } catch (InterruptedException e) { return; }
            System.err.println("ECHEC: delai global depasse (blocage)");
            Runtime.getRuntime().halt(3);
        }, "watchdog");
        dog.setDaemon(true);
        dog.start();
        try {
            wipe(work);
            Files.createDirectories(work);
            testMineAndTheirs();
            testBulk();
            testOneSided();
            testOffline();
            testCrossed();
            testMineGone();
            testCopiesStayLocal();
            testRefusals();
            wipe(work);
            System.out.println("CONFLICTS TEST: OK");
            System.exit(0);
        } catch (Throwable t) {
            System.err.println("CONFLICTS TEST: ECHEC");
            t.printStackTrace();
            System.exit(1);
        }
    }

    // =======================================================================
    //  Deux postes sur un disque relais que l'on peut couper
    // =======================================================================

    /** Magasin reel, scrute vite, que l'on peut rendre injoignable (canal coupe). */
    private static final class Cut implements RelayStore {
        final RelayStore in;
        volatile boolean down;
        Cut(RelayStore in) { this.in = in; }
        private void up() throws IOException { if (down) throw new IOException("canal coupe (essai)"); }
        @Override public String id() { return in.id(); }
        @Override public String describe() { return in.describe(); }
        @Override public boolean available() { return !down && in.available(); }
        @Override public String lastError() { return down ? "canal coupe (essai)" : in.lastError(); }
        @Override public List<Entry> list(String d) throws IOException { up(); return in.list(d); }
        @Override public byte[] read(String p, int max) throws IOException { up(); return in.read(p, max); }
        @Override public void write(String p, Path src) throws IOException { up(); in.write(p, src); }
        @Override public void write(String p, byte[] data) throws IOException { up(); in.write(p, data); }
        @Override public void delete(String p) throws IOException { up(); in.delete(p); }
        @Override public long pollMs() { return 100; }
        @Override public long maxLotBytes() { return in.maxLotBytes(); }
        @Override public void close() { in.close(); }
    }

    private static final class Post {
        final String side, key;
        final Path dir, relay;
        Cut store;
        Agent.BatchRun run;
        Thread engine;
        volatile boolean down;

        Post(String side, String key, Path dir, Path relay) { this.side = side; this.key = key; this.dir = dir; this.relay = relay; }

        void start() throws IOException {
            check(run == null, key + " deja demarre");
            Status.get().setChannelPlan(key, "disque", Map.of());
            store = new Cut(new DirRelayStore(relay.toString()));
            store.down = down;
            MailboxSession.Options o = new MailboxSession.Options();
            o.flushIdleMs = 150;
            run = Agent.BatchRun.open(NAME, key, side, dir, store, null, o);
            final Agent.BatchRun r = run;
            engine = new Thread(() -> r.sync("hub".equals(side), SyncEngine.ConflictPolicy.KEEP_BOTH), "moteur-" + key);
            engine.setDaemon(true);
            engine.start();
        }

        void cut(boolean d) { down = d; if (store != null) store.down = d; }

        void stop() throws InterruptedException {
            if (run == null) return;
            run.close();
            engine.join(30_000);
            check(!engine.isAlive(), "le moteur " + key + " ne s'arrete pas");
            run = null;
        }

        List<Conflicts.Entry> conflicts() { Conflicts.changed(key); return Conflicts.list(key); }

        Conflicts.Entry conflict(String rel) {
            Conflicts.Entry found = null;
            for (Conflicts.Entry e : conflicts()) if (e.path().equals(rel)) { check(found == null, key + ": deux copies pour " + rel); found = e; }
            return found;
        }

        String read(String rel) throws IOException { return new String(Files.readAllBytes(dir.resolve(rel)), StandardCharsets.UTF_8); }

        void write(String rel, String content) throws IOException {
            Path f = dir.resolve(rel);
            Files.createDirectories(f.getParent());
            Files.write(f, content.getBytes(StandardCharsets.UTF_8));
        }

        /** Nombre de conflits jamais detectes ici (lignes "-> copie" du journal). */
        int detected() throws IOException {
            Path log = dir.resolve(".cotransfert").resolve("conflicts.log");
            if (!Files.exists(log)) return 0;
            int n = 0;
            for (String l : Files.readAllLines(log, StandardCharsets.UTF_8)) if (l.contains("\t-> ")) n++;
            return n;
        }
    }

    private static final class World implements AutoCloseable {
        final Post hub, cli;
        private int round = 1;

        World(String tag) throws IOException {
            Path d = work.resolve(tag);
            Path relay = Files.createDirectories(d.resolve("relais"));
            hub = new Post("hub", tag + "-hub", Files.createDirectories(d.resolve("hub")), relay);
            cli = new Post("client", tag + "-client", Files.createDirectories(d.resolve("client")), relay);
            Conflicts.register(hub.key, hub.dir);
            Conflicts.register(cli.key, cli.dir);
            Status.get().setPath(hub.key, hub.dir.toString());
            Status.get().setPath(cli.key, cli.dir.toString());
        }

        void start() throws IOException { hub.start(); cli.start(); }

        void cut(boolean down) { hub.cut(down); cli.cut(down); }

        /** Les deux dossiers ont le meme contenu (copies de conflit comprises : il ne doit donc en rester aucune). */
        void converge(String what) throws InterruptedException {
            for (int pass = 0; pass < 2; pass++) {
                waitFor(() -> {
                    try { return tree(hub.dir).equals(tree(cli.dir)); } catch (IOException e) { return false; }
                }, 60_000, what);
                Thread.sleep(500);
            }
            say("converge : " + what);
        }

        /**
         * Un vrai conflit : le canal est coupe, le meme fichier est modifie des deux
         * cotes, chaque poste met son envoi en attente, puis le canal revient. Chaque
         * poste recoit la version de l'autre alors que la sienne a change : conflit
         * des deux cotes.
         */
        void conflict(String... rels) throws Exception {
            cut(true);
            for (String rel : rels) {
                hub.write(rel, "version du hub pour " + rel + ", tour " + round + "\n");
                cli.write(rel, "version du client pour " + rel + ", tour " + round + ", plus longue\n");
            }
            round++;
            Thread.sleep(1200);   // les deux watchers ont vu le changement et depose leur envoi dans le spool
            cut(false);
            for (String rel : rels) {
                waitFor(() -> hub.conflict(rel) != null && cli.conflict(rel) != null, 60_000, "conflit des deux cotes sur " + rel);
            }
            Thread.sleep(400);
            for (String rel : rels) {
                check(hub.read(rel).startsWith("version du hub"), "hub: son fichier a ete ecrase par le conflit");
                check(cli.read(rel).startsWith("version du client"), "client: son fichier a ete ecrase par le conflit");
            }
        }

        @Override public void close() {
            try {
                hub.stop();
                cli.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            for (Post p : List.of(hub, cli)) { Conflicts.forget(p.key); Status.get().drop(p.key); }
        }
    }

    private static World world(String tag, String... rels) throws Exception {
        World w = new World(tag);
        for (String rel : rels) w.hub.write(rel, "version de depart de " + rel + "\n");
        w.start();
        w.converge(tag + ": depart");
        return w;
    }

    // =======================================================================
    //  1 et 2. Un conflit, "ma version" puis "celle de l'autre poste"
    // =======================================================================

    private static void testMineAndTheirs() throws Exception {
        step("1. un conflit : liste, compteur, garder ma version");
        try (World w = world("t1", "a.txt", "dossier/b.txt")) {
            Post hub = w.hub, cli = w.cli;
            check(Conflicts.json().contains("\"conflicts\":["), "GET /api/conflicts sans tableau");
            check(statusConflicts(hub.key) == 0 && hub.conflicts().isEmpty(), "conflit avant tout conflit");

            w.conflict("dossier/b.txt");
            Conflicts.Entry e = hub.conflict("dossier/b.txt");
            check(e.root().equals(hub.key) && e.path().equals("dossier/b.txt"), "liste: dossier ou chemin");
            check(e.copy().matches("dossier/b\\.txt\\.conflict-peer-\\d{8}-\\d{6}"), "liste: copie " + e.copy());
            check(e.when().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"), "liste: date " + e.when());
            check(e.mineExists() && e.mineSize() == Files.size(hub.dir.resolve("dossier/b.txt"))
                    && e.mineTime() == Files.getLastModifiedTime(hub.dir.resolve("dossier/b.txt")).toMillis(), "liste: ma version");
            check(e.theirsSize() == Files.size(cli.dir.resolve("dossier/b.txt")) && e.theirsTime() > 0, "liste: version de l'autre poste");
            check(hub.read(e.copy()).startsWith("version du client"), "la copie du hub n'est pas la version du client");
            check(cli.read(cli.conflict("dossier/b.txt").copy()).startsWith("version du hub"), "la copie du client n'est pas la version du hub");
            String json = Conflicts.json();
            check(json.contains("\"root\":" + Status.q(hub.key) + ",\"path\":\"dossier/b.txt\",\"copy\":" + Status.q(e.copy())
                    + ",\"when\":" + Status.q(e.when()) + ",\"mineExists\":true,\"mineSize\":" + e.mineSize() + ",\"mineTime\":" + e.mineTime()
                    + ",\"theirsSize\":" + e.theirsSize() + ",\"theirsTime\":" + e.theirsTime() + "}"), "forme de GET /api/conflicts: " + json);
            // Compteur de /api/status = liste ; etat CONFLICT tant qu'il en reste.
            check(statusConflicts(hub.key) == 1 && statusConflicts(cli.key) == 1, "compteur de /api/status");
            waitFor(() -> "CONFLICT".equals(statusState(hub.key)) && "CONFLICT".equals(statusState(cli.key)), 15_000, "etat CONFLICT tant qu'un conflit attend");

            int seenHub = hub.detected(), seenCli = cli.detected();
            Conflicts.Outcome o = Conflicts.resolve(hub.key, e.copy(), true);
            check(o.resolved() == 1 && o.failed().isEmpty() && o.json().equals("{\"ok\":true,\"resolved\":1,\"failed\":[]}"), "garder ma version: " + o.json());
            check(statusConflicts(hub.key) == 0, "compteur du hub apres resolution");
            w.converge("garder ma version");
            check(hub.read("dossier/b.txt").startsWith("version du hub") && cli.read("dossier/b.txt").startsWith("version du hub"), "contenu apres 'ma version'");
            Thread.sleep(1500);
            check(hub.conflicts().isEmpty() && cli.conflicts().isEmpty(), "il reste un conflit");
            check(statusConflicts(hub.key) == 0 && statusConflicts(cli.key) == 0, "compteurs a 0 attendus");
            check(hub.detected() == seenHub && cli.detected() == seenCli, "un nouveau conflit est revenu apres 'ma version'");
            // Deja resolu : le meme appel est refuse, rien ne bouge.
            o = Conflicts.resolve(hub.key, e.copy(), true);
            check(o.resolved() == 0 && o.failed().size() == 1 && o.failed().get(0).message().equals("conflit inconnu")
                    && o.failed().get(0).root().equals(hub.key) && !o.json().contains("\"ok\":true"), "conflit deja resolu: " + o.json());

            step("2. garder la version de l'autre poste");
            w.conflict("a.txt");
            e = hub.conflict("a.txt");
            long theirsTime = e.theirsTime();
            seenHub = hub.detected(); seenCli = cli.detected();
            o = Conflicts.resolve(hub.key, e.copy(), false);
            check(o.resolved() == 1 && o.failed().isEmpty(), "garder l'autre: " + o.json());
            check(hub.read("a.txt").startsWith("version du client"), "hub: la copie n'a pas remplace mon fichier");
            check(Files.getLastModifiedTime(hub.dir.resolve("a.txt")).toMillis() == theirsTime, "hub: date de modification non reprise");
            w.converge("garder la version de l'autre poste");
            check(cli.read("a.txt").startsWith("version du client"), "client: son fichier a change");
            Thread.sleep(1500);
            check(hub.conflicts().isEmpty() && cli.conflicts().isEmpty(), "il reste un conflit (la copie du client devait etre retiree)");
            check(hub.detected() == seenHub && cli.detected() == seenCli, "un nouveau conflit est revenu apres 'garder l'autre'");
            // La suite reste une synchro normale, sans conflit.
            cli.write("a.txt", "suite ecrite sur le client\n");
            w.converge("modification suivante");
            hub.write("a.txt", "suite ecrite sur le hub\n");
            w.converge("modification suivante, autre sens");
            check(cli.read("a.txt").startsWith("suite ecrite sur le hub") && hub.detected() == seenHub && cli.detected() == seenCli, "conflit apres resolution");
        }
    }

    // =======================================================================
    //  3. Resolution groupee
    // =======================================================================

    private static void testBulk() throws Exception {
        step("3. plusieurs conflits, resolution groupee");
        try (World w = world("t3", "a.txt", "b.txt", "dossier/c.txt", "d.txt")) {
            Post hub = w.hub, cli = w.cli;
            w.conflict("a.txt", "b.txt", "dossier/c.txt");
            check(hub.conflicts().size() == 3 && statusConflicts(hub.key) == 3, "trois conflits attendus sur le hub");
            Conflicts.Outcome o = Conflicts.resolveAll(hub.key, true);
            check(o.resolved() == 3 && o.failed().isEmpty(), "groupe, ma version: " + o.json());
            w.converge("groupe : ma version pour tous");
            for (String rel : List.of("a.txt", "b.txt", "dossier/c.txt")) check(cli.read(rel).startsWith("version du hub"), "contenu de " + rel);
            check(hub.conflicts().isEmpty() && cli.conflicts().isEmpty(), "il reste des conflits");

            // Depuis le client, "celle de l'autre poste" pour tous.
            w.conflict("a.txt", "d.txt");
            check(cli.conflicts().size() == 2, "deux conflits attendus sur le client");
            o = Conflicts.resolveAll(cli.key, false);
            check(o.resolved() == 2 && o.failed().isEmpty(), "groupe, depuis le client: " + o.json());
            w.converge("groupe : celle de l'autre poste pour tous");
            for (String rel : List.of("a.txt", "d.txt")) check(hub.read(rel).startsWith("version du hub") && cli.read(rel).startsWith("version du hub"), "contenu de " + rel);
            check(hub.conflicts().isEmpty() && cli.conflicts().isEmpty(), "il reste des conflits");

            // Echec partiel : un dossier a pris le nom d'un des fichiers ; l'autre conflit est regle quand meme.
            w.conflict("a.txt", "b.txt");
            String stuck = hub.conflict("b.txt").copy();
            Files.delete(hub.dir.resolve("b.txt"));
            Files.createDirectory(hub.dir.resolve("b.txt"));
            o = Conflicts.resolveAll(hub.key, false);
            check(o.resolved() == 1 && o.failed().size() == 1 && o.failed().get(0).root().equals(hub.key) && o.failed().get(0).copy().equals(stuck)
                    && !o.failed().get(0).message().isEmpty(), "echec partiel attendu: " + o.json());
            check(o.json().equals("{\"ok\":false,\"resolved\":1,\"failed\":[{\"root\":" + Status.q(hub.key) + ",\"copy\":" + Status.q(stuck)
                    + ",\"message\":" + Status.q(o.failed().get(0).message()) + "}]}"), "forme d'un echec partiel: " + o.json());
            check(hub.conflict("a.txt") == null && hub.conflict("b.txt") != null && Files.exists(hub.dir.resolve(stuck)), "seul le conflit de a.txt devait etre regle");
            Files.delete(hub.dir.resolve("b.txt"));
            Conflicts.Outcome rest = Conflicts.resolveAll(hub.key, false);
            check(rest.resolved() == 1 && rest.failed().isEmpty(), "reste: " + rest.json());
            // Mon b.txt avait ete supprime a la main : la copie que le client en garde reste a trancher chez lui.
            waitFor(() -> cli.conflicts().size() == 1 && cli.conflict("b.txt") != null, 30_000, "seul b.txt attend encore sur le client");
            Thread.sleep(800);
            check(Conflicts.resolveAll(cli.key, true).resolved() == 1, "client: ma version pour b.txt");
            w.converge("apres un echec partiel");
            check(cli.read("a.txt").startsWith("version du client") && hub.read("b.txt").startsWith("version du client"), "contenu apres echec partiel");
            check(hub.conflicts().isEmpty() && cli.conflicts().isEmpty(), "il reste des conflits");
        }
    }

    // =======================================================================
    //  4. Conflit d'un seul cote
    // =======================================================================

    private static void testOneSided() throws Exception {
        step("4. conflit detecte d'un seul cote");
        try (World w = world("t4", "a.txt", "b.txt", "c.txt")) {
            Post hub = w.hub, cli = w.cli;
            // Le client a modifie son fichier et l'a deja "vu" (index a jour) sans l'avoir envoye :
            // c'est l'etat d'un croisement ou la version du hub arrive avant que le watcher n'envoie.
            cli.stop();
            for (String rel : List.of("a.txt", "b.txt", "c.txt")) {
                cli.write(rel, "version du client pour " + rel + ", plus longue\n");
                hub.write(rel, "version du hub pour " + rel + "\n");
            }
            Index idx = new Index(cli.dir);
            idx.load();
            for (String rel : List.of("a.txt", "b.txt", "c.txt")) {
                Path f = cli.dir.resolve(rel);
                idx.put(rel, new Index.State(Index.hashFile(f), Files.getLastModifiedTime(f).toMillis(), Files.size(f)));
            }
            idx.save();
            cli.start();
            waitFor(() -> cli.conflicts().size() == 3, 60_000, "conflits sur le client");
            Thread.sleep(800);
            check(hub.conflicts().isEmpty(), "le hub ne devait pas voir de conflit");
            check(hub.read("a.txt").startsWith("version du hub"), "hub ecrase");

            Conflicts.Outcome o = Conflicts.resolve(cli.key, cli.conflict("a.txt").copy(), true);
            check(o.resolved() == 1, "un seul cote, ma version: " + o.json());
            o = Conflicts.resolve(cli.key, cli.conflict("b.txt").copy(), false);
            check(o.resolved() == 1, "un seul cote, l'autre: " + o.json());
            // Tous les dossiers du poste d'un coup (ici seul le client a encore un conflit).
            o = Conflicts.resolveAll(null, true);
            check(o.resolved() == 1 && o.failed().isEmpty(), "tous les dossiers: " + o.json());
            w.converge("conflit d'un seul cote");
            check(hub.read("a.txt").startsWith("version du client") && hub.read("b.txt").startsWith("version du hub")
                    && hub.read("c.txt").startsWith("version du client"), "contenu apres resolution");
            Thread.sleep(1200);
            check(hub.conflicts().isEmpty() && cli.conflicts().isEmpty() && hub.detected() == 0, "un conflit est revenu");
        }
    }

    // =======================================================================
    //  5. Resolution sans moteur
    // =======================================================================

    private static void testOffline() throws Exception {
        step("5. resolution sans session, puis session");
        try (World w = world("t5", "a.txt", "b.txt")) {
            Post hub = w.hub, cli = w.cli;
            w.conflict("a.txt", "b.txt");
            hub.stop();
            cli.stop();
            // Sans moteur : la liste et le compteur viennent du disque (ils survivent aux redemarrages).
            check(hub.conflicts().size() == 2 && statusConflicts(hub.key) == 2, "compteur sans session");
            int seenHub = hub.detected(), seenCli = cli.detected();
            Conflicts.Outcome o = Conflicts.resolve(hub.key, hub.conflict("a.txt").copy(), true);
            check(o.resolved() == 1 && o.failed().isEmpty(), "sans session, ma version: " + o.json());
            o = Conflicts.resolve(hub.key, hub.conflict("b.txt").copy(), false);
            check(o.resolved() == 1 && o.failed().isEmpty(), "sans session, l'autre: " + o.json());
            check(hub.conflicts().isEmpty() && statusConflicts(hub.key) == 0, "compteur apres resolution sans session");
            check(hub.read("a.txt").startsWith("version du hub") && hub.read("b.txt").startsWith("version du client"), "contenu du hub");
            check(cli.conflicts().size() == 2, "le client attend encore");
            w.start();
            w.converge("resolution sans session");
            check(cli.read("a.txt").startsWith("version du hub") && cli.read("b.txt").startsWith("version du client"), "contenu du client");
            Thread.sleep(1500);
            check(hub.conflicts().isEmpty() && cli.conflicts().isEmpty(), "il reste un conflit");
            check(hub.detected() == seenHub && cli.detected() == seenCli, "un nouveau conflit est revenu");
        }
    }

    // =======================================================================
    //  6. Choix croises
    // =======================================================================

    private static void testCrossed() throws Exception {
        step("6. 'ma version' choisie sur les deux postes en meme temps");
        try (World w = world("t6", "a.txt")) {
            Post hub = w.hub, cli = w.cli;
            w.conflict("a.txt");
            w.cut(true);
            check(Conflicts.resolve(hub.key, hub.conflict("a.txt").copy(), true).resolved() == 1, "hub: ma version");
            check(Conflicts.resolve(cli.key, cli.conflict("a.txt").copy(), true).resolved() == 1, "client: ma version");
            Thread.sleep(600);
            w.cut(false);
            // Aucun echange silencieux : chacun garde son choix et retrouve la version de l'autre a cote.
            waitFor(() -> hub.conflict("a.txt") != null && cli.conflict("a.txt") != null, 60_000, "le conflit revient des deux cotes");
            Thread.sleep(1000);
            check(hub.read("a.txt").startsWith("version du hub") && cli.read("a.txt").startsWith("version du client"), "les contenus se sont echanges");
            check(hub.read(hub.conflict("a.txt").copy()).startsWith("version du client")
                    && cli.read(cli.conflict("a.txt").copy()).startsWith("version du hub"), "copies apres choix croises");
            int seenHub = hub.detected(), seenCli = cli.detected();
            Thread.sleep(1500);
            check(hub.detected() == seenHub && cli.detected() == seenCli, "echange sans fin apres des choix croises");
            // Un seul choix suffit ensuite.
            check(Conflicts.resolve(cli.key, cli.conflict("a.txt").copy(), false).resolved() == 1, "client: garder l'autre");
            w.converge("apres des choix croises");
            check(hub.read("a.txt").startsWith("version du hub") && cli.read("a.txt").startsWith("version du hub"), "contenu final");
            check(hub.conflicts().isEmpty() && cli.conflicts().isEmpty(), "il reste un conflit");

            // Choix croises compatibles (ma version ici, celle de l'autre la-bas) : convergence directe.
            w.conflict("a.txt");
            w.cut(true);
            check(Conflicts.resolve(hub.key, hub.conflict("a.txt").copy(), true).resolved() == 1, "hub: ma version");
            check(Conflicts.resolve(cli.key, cli.conflict("a.txt").copy(), false).resolved() == 1, "client: l'autre");
            w.cut(false);
            w.converge("choix croises compatibles");
            check(hub.conflicts().isEmpty() && cli.conflicts().isEmpty(), "il reste un conflit");
        }
    }

    // =======================================================================
    //  7. "Ma version" alors que mon fichier a disparu
    // =======================================================================

    private static void testMineGone() throws Exception {
        step("7. mon fichier a disparu : 'ma version' propage la suppression, 'l'autre' restaure");
        try (World w = world("t7", "a.txt", "b.txt")) {
            Post hub = w.hub, cli = w.cli;
            w.conflict("a.txt", "b.txt");
            Files.delete(hub.dir.resolve("a.txt"));
            Files.delete(hub.dir.resolve("b.txt"));
            waitFor(() -> !hub.conflict("a.txt").mineExists() && !hub.conflict("b.txt").mineExists(), 10_000, "mineExists=false");
            Thread.sleep(1200);
            check(Files.exists(cli.dir.resolve("a.txt")), "le client a supprime un fichier dont le conflit attend");
            Conflicts.Entry e = hub.conflict("a.txt");
            check(e.mineSize() == 0 && e.mineTime() == 0, "ma version absente: taille et date a 0");
            check(Conflicts.resolve(hub.key, e.copy(), true).resolved() == 1, "ma version (fichier disparu)");
            check(!Files.exists(hub.dir.resolve("a.txt")) && !Files.exists(hub.dir.resolve(e.copy())), "hub: ni fichier ni copie attendus");
            // Le pair avait bien la version ecartee : son fichier est supprime aussi. Sa propre copie
            // (mon ancienne version) reste a trancher chez lui.
            waitFor(() -> !Files.exists(cli.dir.resolve("a.txt")), 60_000, "suppression propagee au client");
            Conflicts.Entry left = cli.conflict("a.txt");
            check(left != null && !left.mineExists() && cli.read(left.copy()).startsWith("version du hub"), "copie du client conservee");
            check(Conflicts.resolve(cli.key, left.copy(), true).resolved() == 1, "client: ma version (fichier disparu)");

            check(Conflicts.resolve(hub.key, hub.conflict("b.txt").copy(), false).resolved() == 1, "l'autre (fichier disparu)");
            check(hub.read("b.txt").startsWith("version du client"), "hub: fichier restaure depuis la copie");
            // Le client garde sa propre copie (mon ancienne version, supprimee ici a la main) : a lui de trancher.
            waitFor(() -> cli.conflict("b.txt") != null, 5_000, "conflit du client sur b.txt");
            Thread.sleep(800);
            check(Conflicts.resolve(cli.key, cli.conflict("b.txt").copy(), true).resolved() == 1, "client: ma version pour b.txt");
            w.converge("fichier disparu");
            check(!Files.exists(hub.dir.resolve("a.txt")) && cli.read("b.txt").startsWith("version du client"), "contenu final");
            Thread.sleep(1500);
            check(hub.conflicts().isEmpty() && cli.conflicts().isEmpty() && !Files.exists(hub.dir.resolve("a.txt")), "etat final instable");
        }
    }

    // =======================================================================
    //  8. Les copies ne quittent jamais le poste
    // =======================================================================

    private static void testCopiesStayLocal() throws Exception {
        step("8. une copie de conflit n'est jamais transmise");
        World w = new World("t8");
        try {
            Post hub = w.hub, cli = w.cli;
            hub.write("a.txt", "depart\n");
            hub.write("a.txt.conflict-peer-20200102-030405", "copie presente avant le scan\n");
            cli.write("dossier/b.txt.conflict-peer-20200102-030405", "copie deja presente chez le pair\n");
            w.start();
            waitFor(() -> Files.exists(cli.dir.resolve("a.txt")), 60_000, "a.txt sur le client");
            hub.write("dossier/c.txt.conflict-peer-20200102-030406", "copie creee pendant la session\n");
            hub.write("dossier/c.txt", "fichier ordinaire\n");
            waitFor(() -> Files.exists(cli.dir.resolve("dossier/c.txt")), 60_000, "c.txt sur le client");
            Files.delete(hub.dir.resolve("a.txt.conflict-peer-20200102-030405"));
            hub.write("d.txt", "temoin\n");
            waitFor(() -> Files.exists(cli.dir.resolve("d.txt")), 60_000, "d.txt sur le client");
            Thread.sleep(800);
            check(!Files.exists(cli.dir.resolve("a.txt.conflict-peer-20200102-030405"))
                    && !Files.exists(cli.dir.resolve("dossier/c.txt.conflict-peer-20200102-030406")), "une copie de conflit a ete transmise");
            check(Files.exists(cli.dir.resolve("dossier/b.txt.conflict-peer-20200102-030405"))
                    && !Files.exists(hub.dir.resolve("dossier/b.txt.conflict-peer-20200102-030405")), "la copie du pair a ete touchee ou transmise");
            // Redemarrage : toujours rien (ni au scan, ni par le manifeste).
            hub.stop(); cli.stop();
            w.start();
            Thread.sleep(1500);   // les deux surveillances sont actives
            hub.write("e.txt", "temoin 2\n");
            waitFor(() -> Files.exists(cli.dir.resolve("e.txt")), 60_000, "e.txt sur le client");
            Thread.sleep(800);
            check(!Files.exists(cli.dir.resolve("dossier/c.txt.conflict-peer-20200102-030406"))
                    && Files.exists(cli.dir.resolve("dossier/b.txt.conflict-peer-20200102-030405"))
                    && !Files.exists(hub.dir.resolve("dossier/b.txt.conflict-peer-20200102-030405")), "copie transmise apres redemarrage");
            // Ces fichiers ne figurent pas au journal : ce ne sont pas des conflits listes.
            check(hub.conflicts().isEmpty() && cli.conflicts().isEmpty(), "copie hors journal listee");
            check(SyncIgnore.conflictCopy("x/a.txt.conflict-peer-20200102-030405") && !SyncIgnore.conflictCopy("x/a.txt"), "regle d'exclusion");
        } finally {
            w.close();
        }
    }

    // =======================================================================
    //  9. Refus
    // =======================================================================

    private static void testRefusals() throws Exception {
        step("9. refus : chemins arbitraires");
        try (World w = world("t9", "a.txt", "b.txt")) {
            Post hub = w.hub;
            w.conflict("a.txt");
            String copy = hub.conflict("a.txt").copy();
            Path outside = Files.write(work.resolve("t9").resolve("dehors.txt.conflict-peer-20200102-030405"), new byte[] { 1 });
            hub.write("b.txt.conflict-peer-20200102-030405", "copie hors journal\n");
            String[][] bad = {
                { hub.key, "../dehors.txt.conflict-peer-20200102-030405", "copie inconnue" },
                { hub.key, "dossier/../" + copy, "copie inconnue" },
                { hub.key, outside.toString().replace('\\', '/'), "copie inconnue" },
                { hub.key, outside.toString(), "copie inconnue" },
                { hub.key, "/" + copy, "copie inconnue" },
                { hub.key, "a.txt", "copie inconnue" },
                { hub.key, ".cotransfert/index.tsv", "copie inconnue" },
                { hub.key, "", "copie inconnue" },
                { hub.key, "b.txt.conflict-peer-20200102-030405", "conflit inconnu" },
                { hub.key, "a.txt.conflict-peer-20200102-030405", "conflit inconnu" },
                { "dossier-absent", copy, "dossier inconnu" },
                { null, copy, "dossier inconnu" },
            };
            for (String[] b : bad) {
                for (boolean mine : new boolean[] { true, false }) {
                    Conflicts.Outcome o = Conflicts.resolve(b[0], b[1], mine);
                    check(o.resolved() == 0 && o.failed().size() == 1 && o.failed().get(0).message().equals(b[2])
                            && o.failed().get(0).copy().equals(b[1]) && o.json().startsWith("{\"ok\":false,\"resolved\":0,\"failed\":[{\"root\":"),
                            "refus attendu pour " + b[1] + ": " + o.json());
                }
                check(Conflicts.locate(b[0], b[1])[0] == null && b[2].equals(Conflicts.locate(b[0], b[1])[1]), "ouvrir: refus attendu pour " + b[1]);
            }
            check(Conflicts.resolveAll("dossier-absent", true).failed().get(0).message().equals("dossier inconnu"), "groupe sur un dossier inconnu");
            check(Files.exists(outside) && Files.exists(hub.dir.resolve(copy)) && Files.exists(hub.dir.resolve("b.txt.conflict-peer-20200102-030405"))
                    && hub.read("a.txt").startsWith("version du hub"), "un refus a modifie un fichier");
            check(hub.dir.equals(Conflicts.locate(hub.key, copy)[0]), "ouvrir: dossier de la copie");
            // Lignes du journal d'une autre forme, ou qui pointent hors du dossier : ignorees.
            Files.writeString(hub.dir.resolve(".cotransfert").resolve("conflicts.log"),
                    "20200102-030405\tb.txt\t(suppression pair ignoree)\n"
                    + "20200102-030405\t../dehors.txt\t-> ../dehors.txt.conflict-peer-20200102-030405\n"
                    + "20200102-030405\tb.txt\t-> a.txt\n"
                    + "ligne sans forme\n",
                    StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
            check(hub.conflicts().size() == 1, "lignes etrangeres du journal listees: " + Conflicts.json());
            check(Conflicts.resolve(hub.key, copy, true).resolved() == 1, "le vrai conflit se resout toujours");
            Files.delete(hub.dir.resolve("b.txt.conflict-peer-20200102-030405"));   // copie hors journal, restee locale
            w.converge("apres les refus");
            Files.delete(outside);
        }
    }

    // =======================================================================
    //  Outils
    // =======================================================================

    private static int statusConflicts(String key) {
        Conflicts.changed(key);
        Matcher m = Pattern.compile("\\{\"name\":" + Pattern.quote(Status.q(key)) + ",\"path\":\"[^\"]*\",\"state\":\"[A-Z_]+\",\"remaining\":\\d+,\"conflicts\":(\\d+)")
                .matcher(Status.get().json());
        check(m.find(), "dossier absent de /api/status: " + key);
        int n = Integer.parseInt(m.group(1));
        check(n == Conflicts.list(key).size(), "compteur de /api/status different de la liste pour " + key);
        return n;
    }

    private static String statusState(String key) {
        Matcher m = Pattern.compile("\\{\"name\":" + Pattern.quote(Status.q(key)) + ",\"path\":\"[^\"]*\",\"state\":\"([A-Z_]+)\"").matcher(Status.get().json());
        return m.find() ? m.group(1) : "?";
    }

    /** Fichiers d'un dossier synchronise (hors .cotransfert) : chemin relatif -> contenu. */
    private static Map<String, String> tree(Path root) throws IOException {
        Map<String, String> out = new TreeMap<>();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path p, BasicFileAttributes a) {
                return p.getFileName().toString().equals(".cotransfert") ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path p, BasicFileAttributes a) throws IOException {
                out.put(root.relativize(p).toString().replace('\\', '/'), new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1));
                return FileVisitResult.CONTINUE;
            }
        });
        return out;
    }

    private static void wipe(Path root) throws Exception {
        if (!Files.exists(root)) return;
        for (int i = 0; i < 20; i++) {
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                    @Override public FileVisitResult visitFile(Path p, BasicFileAttributes a) throws IOException { Files.delete(p); return FileVisitResult.CONTINUE; }
                    @Override public FileVisitResult postVisitDirectory(Path p, IOException e) throws IOException { Files.delete(p); return FileVisitResult.CONTINUE; }
                });
                return;
            } catch (IOException e) {
                Thread.sleep(200);
            }
        }
        throw new IOException("nettoyage impossible: " + root);
    }

    private static void waitFor(BooleanSupplier c, long timeoutMs, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (true) {
            boolean ok;
            try { ok = c.getAsBoolean(); } catch (RuntimeException e) { ok = false; }
            if (ok) return;
            if (System.currentTimeMillis() > end) throw new AssertionError("delai depasse: " + what);
            Thread.sleep(25);
        }
    }

    private static void step(String s) { System.out.println("=== " + s); }

    private static void say(String s) { System.out.println("    " + s); }

    private static void check(boolean ok, String msg) { if (!ok) throw new AssertionError(msg); }
}
