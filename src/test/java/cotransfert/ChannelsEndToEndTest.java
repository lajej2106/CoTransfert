package cotransfert;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Essai de bout en bout du choix manuel du canal, sans dependance (simple main,
 * code de sortie non nul en cas d'echec, aucun service reel) :
 *
 *   SyncEngine -- MailboxSession --+-- WebDavRelayStore -- FakeWebDavServer
 *      (Agent.BatchRun)            +-- DrimeRelayStore  -- FakeDrimeServer
 *                                  +-- DirRelayStore    -- dossier temporaire
 *   SyncEngine -- TcpSession (Agent.directSession)      -- socket de boucle locale
 *
 * Deux vrais dossiers (hub et client), lots chiffres comme en production. Chaque
 * poste demarre par le meme chemin que Agent : Agent.prepareChannel, puis
 * Agent.BatchRun sur le magasin construit par Agent.Channels (ou
 * Agent.directSession pour le lien direct). Seule la periode de scrutation est
 * raccourcie. Aucun marqueur, aucune bascule : un canal a la fois, celui choisi.
 *
 *   0. Config : channel= aller-retour, channels= ignoree et plus ecrite.
 *   1. Pour chaque canal par lots (webdav, drime, disque) : le hub SEUL depose
 *      ses lots sans qu'aucun client se soit jamais connecte ; le client arrive,
 *      les deux cotes convergent ; magasin en panne -> aucune bascule, les lots
 *      attendent, etat "down" ; retour du magasin -> reprise. /api/status a
 *      chaque etape, sans aucun secret.
 *   2. Changements de canal : lots -> lots avec des lots non acquittes (un poste
 *      bascule avant l'autre), "direct" choisi sans jamais de connexion puis
 *      retour, lots -> direct -> lots, retour a un magasin deja utilise. Aucun
 *      changement perdu, aucun rejeu, aucun .conflict-peer-.
 *   3. Abandon des lots (discardOutbound) directement sur un magasin.
 *   4. Serveur en ligne qui ne repond plus : la fermeture de la session ne
 *      reste pas bloquee.
 *
 * Usage : java -cp <classes> cotransfert.ChannelsEndToEndTest <dossier de travail>
 */
public final class ChannelsEndToEndTest {

    private static final String NAME = "Projet E2E";
    private static final String SECRET = "secret-partage-e2e-0123456789abcdef";
    private static final String USER = "utilisateur";
    private static final String PASSWORD = "mot-de-passe-webdav-e2e-7731";
    private static final String TOKEN = "jeton-drime-e2e-553199";
    private static final String DRIME_FOLDER = "Transit E2E";
    private static final long FAST_POLL = 150;
    private static final List<String> BATCH = List.of("webdav", "drime", "disque");

    private static Path work;
    private static PrintStream console;
    private static final ByteArrayOutputStream journal = new ByteArrayOutputStream();

    public static void main(String[] args) throws Exception {
        if (args.length < 1) { System.err.println("usage: ChannelsEndToEndTest <dossier de travail>"); System.exit(2); }
        work = Paths.get(args[0]).toAbsolutePath().normalize();
        Thread dog = new Thread(() -> {
            try { Thread.sleep(400_000); } catch (InterruptedException e) { return; }
            System.err.println("ECHEC: delai global depasse (blocage)");
            Runtime.getRuntime().halt(3);
        }, "watchdog");
        dog.setDaemon(true);
        dog.start();
        // Tout ce qui est journalise est aussi garde en memoire : aucun secret ne doit y paraitre.
        console = System.out;
        System.setOut(new PrintStream(new Tee(console, journal), true));
        try {
            wipe(work);
            Files.createDirectories(work);
            Status.get().setSecrets(List.of(SECRET, PASSWORD, TOKEN));
            testConfig();
            testStatusWithoutSession();
            for (String id : BATCH) testChannel(id);
            testSwitches();
            testDiscard();
            testStalledServer();
            checkNoSecret(journal.toString(StandardCharsets.UTF_8), "journal");
            wipe(work);
            System.out.println("CHANNELS E2E TEST: OK");
            System.exit(0);
        } catch (Throwable t) {
            System.err.println("CHANNELS E2E TEST: ECHEC");
            t.printStackTrace();
            System.exit(1);
        }
    }

    // =======================================================================
    //  0. Config : aller-retour de toutes les cles
    // =======================================================================

    private static void testConfig() throws Exception {
        step("0. configuration");
        Path d = work.resolve("t0");
        Files.createDirectories(d);

        // Valeurs par defaut : fichier absent, puis enregistrement et relecture. Aucun canal choisi.
        Config def = Config.load(d.resolve("absent.conf"));
        check(def.channel.isEmpty(), "aucun canal ne doit etre choisi par defaut: " + def.channel);
        check(def.drimeFolder.equals("CoTransfertRelais") && def.drimeWorkspace == 0 && def.drimeUrl.isEmpty(), "drime par defaut");
        check(def.proxy.equals("system") && def.tlsTruststore.equals("java"), "reseau par defaut");
        check(!def.webdavConfigured() && !def.drimeConfigured() && !def.diskConfigured("X"), "rien n'est configure par defaut");
        Path f0 = d.resolve("defaut.conf");
        def.save(f0);
        for (String line : Files.readAllLines(f0)) {
            String s = line.trim();
            for (String k : new String[] { "channel", "webdav.", "drime.", "proxy", "tls.truststore" })
                check(!s.startsWith(k), "cle non renseignee ecrite hors commentaire: " + s);
            check(!s.contains("channels"), "l'ancienne cle channels ne doit plus etre ecrite: " + s);
        }
        String text = Files.readString(f0);
        for (String k : new String[] { "# channel=", "# webdav.url=", "# webdav.user=", "# webdav.password=", "# drime.token=",
                "# drime.folder=", "# drime.workspace=", "# drime.url=", "# proxy=", "# tls.truststore=", "# relay=" })
            check(text.contains(k), "ligne commentee absente: " + k);
        sameConfig(def, Config.load(f0), "defauts");

        // Toutes les cles renseignees, y compris l'ancienne cle channels : ignoree sans erreur.
        Path f1 = d.resolve("complet.conf");
        Files.write(f1, List.of(
                "role=client", "master=false", "conflict=newer-wins", "root.Doc=C:\\work\\Doc", "root.Autre projet=D:\\work\\autre",
                "peer=hub.example.org", "discovery=lan", "secret=" + SECRET,
                "channels= Disque , inconnu, WEBDAV,drime,webdav ", "channel= WebDAV ",
                "webdav.url=https://cloud.example.org/remote.php/dav/files/utilisateur/Transit=A",
                "webdav.user=" + USER, "webdav.password=" + PASSWORD,
                "drime.token=" + TOKEN, "drime.folder=Mon Transit", "drime.workspace=42", "drime.url=https://api.example.org/api/v1",
                "relay=*:\\CoTransfertRelais", "relay.Doc=E:\\relais doc",
                "proxy=proxy.example.org:3128", "tls.truststore=Windows"));
        Config c = Config.load(f1);
        check(c.channel.equals("webdav"), "channel: " + c.channel);
        check(c.webdavUrl.endsWith("/Transit=A") && c.webdavUser.equals(USER) && c.webdavPassword.equals(PASSWORD), "webdav");
        check(c.drimeToken.equals(TOKEN) && c.drimeFolder.equals("Mon Transit") && c.drimeWorkspace == 42
                && c.drimeUrl.equals("https://api.example.org/api/v1"), "drime");
        check(c.proxy.equals("proxy.example.org:3128") && c.tlsTruststore.equals("windows"), "reseau");
        check(c.webdavConfigured() && c.drimeConfigured() && c.diskConfigured("Doc") && c.diskConfigured("Autre projet"), "canaux configures");
        check(c.relayFor("Doc").equals("E:\\relais doc") && c.relayFor("Autre projet").equals("*:\\CoTransfertRelais"), "relais par dossier");
        Path f2 = d.resolve("complet2.conf");
        c.save(f2);
        check(!Files.readString(f2).contains("channels"), "channels reecrite");
        check(Files.readAllLines(f2).contains("channel=webdav"), "channel= non ecrite");
        Config c2 = Config.load(f2);
        sameConfig(c, c2, "aller-retour");
        Path f3 = d.resolve("complet3.conf");
        c2.save(f3);
        check(Files.readAllLines(f2).equals(Files.readAllLines(f3)), "l'enregistrement n'est pas stable");
        // Chaque valeur de channel fait l'aller-retour ; un nom inconnu vaut "aucun canal".
        for (String id : new String[] { "", "direct", "webdav", "drime", "disque" }) {
            c2.channel = id;
            c2.save(f3);
            check(Config.load(f3).channel.equals(id), "aller-retour de channel=" + id);
        }
        Files.write(f3, List.of("role=hub", "channel=ftp", "channels=direct,webdav"));
        check(Config.load(f3).channel.isEmpty(), "un canal inconnu ne doit pas etre choisi");

        // Canal webdav incomplet : pas configure ; sans secret : canaux en ligne inutilisables.
        Config part = new Config();
        part.webdavUrl = "https://cloud.example.org/dav";
        part.webdavUser = USER;
        check(!part.webdavConfigured(), "webdav sans mot de passe ne doit pas etre configure");
        Config ns = Config.load(f1);
        Agent.Channels sans = new Agent.Channels(ns, false);
        check("secret partage manquant".equals(sans.problem("webdav", "Doc")) && "secret partage manquant".equals(sans.off("Doc").get("drime")), "raison sans secret");
        check(sans.problem("disque", "Doc").isEmpty() && sans.problem("direct", "Doc").isEmpty(), "disque et direct n'exigent pas de secret");
        check(sans.off("Doc").keySet().equals(new java.util.LinkedHashSet<>(List.of("webdav", "drime"))), "canaux hors service: " + sans.off("Doc"));
        sans.shutdown();
        Agent.Channels avec = new Agent.Channels(ns, true);
        check(avec.off("Doc").isEmpty(), "avec secret: " + avec.off("Doc"));
        RelayStore[] st = { avec.store("webdav", "Doc"), avec.store("drime", "Doc"), avec.store("disque", "Doc") };
        check(st[0] instanceof WebDavRelayStore && st[1] instanceof DrimeRelayStore && st[2] instanceof DirRelayStore, "magasin de chaque canal");
        for (RelayStore s : st) { checkNoSecret(s.describe(), "description de " + s.id()); s.close(); }
        avec.shutdown();
        ns.webdavUrl = "http://cloud.example.org/dav";   // http hors boucle locale : refuse
        ns.relay = "";
        ns.relays.clear();
        Agent.Channels bad = new Agent.Channels(ns, true);
        check("adresse invalide".equals(bad.problem("webdav", "Doc")) && bad.why("webdav", "Doc").contains("https"), "webdav en http refuse: " + bad.why("webdav", "Doc"));
        check("non configure".equals(bad.problem("disque", "Doc")) && bad.problem("drime", "Doc").isEmpty(), "disque non regle");
        bad.shutdown();
    }

    /**
     * /api/status sans aucune session : chaque dossier regle est liste avec ses
     * quatre canaux, qu'aucun canal ne soit choisi, que le canal choisi ne soit pas
     * regle ou que le secret manque. Un canal choisi n'est jamais "ready" ni "off".
     */
    private static void testStatusWithoutSession() {
        step("0bis. /api/status sans session (aucun canal, canal non regle, secret manquant)");
        Status st = Status.get();
        Config c = new Config();
        c.role = "hub";
        c.roots.put("Doc", "C:\\work\\Doc");
        c.drimeToken = TOKEN;
        String key = "SANS-SESSION";
        // Aucun canal choisi, sans secret : drime regle mais sans secret, le reste non regle.
        Agent.Channels chs = new Agent.Channels(c, false);
        st.setSelectedChannel("");
        st.setPath(key, c.roots.get("Doc"));
        st.setChannelPlan(key, "", chs.off("Doc"));
        String json = st.json();
        check(json.contains(",\"channel\":\"\",\"boot\":"), "self.channel vide attendu: " + json);
        check(rootOf(json, key).contains("\"channel\":\"\"") && "OFFLINE".equals(rootState(json, key)), "dossier sans canal: " + rootOf(json, key));
        check(channelsOf(json, key).equals("[{\"id\":\"direct\",\"label\":\"Lien direct\",\"selected\":false,\"state\":\"ready\",\"detail\":\"\"},"
                + "{\"id\":\"webdav\",\"label\":\"WebDAV\",\"selected\":false,\"state\":\"off\",\"detail\":\"non configure\"},"
                + "{\"id\":\"drime\",\"label\":\"Drime\",\"selected\":false,\"state\":\"off\",\"detail\":\"secret partage manquant\"},"
                + "{\"id\":\"disque\",\"label\":\"Disque relais\",\"selected\":false,\"state\":\"off\",\"detail\":\"non configure\"}]"),
                "canaux sans canal choisi: " + channelsOf(json, key));
        say("/api/status, aucun canal choisi : " + channelsOf(json, key));
        // Canal choisi mais inutilisable : "down" avec la raison, jamais "ready" ni "off".
        String[][] cases = { { "webdav", "non configure" }, { "drime", "secret partage manquant" }, { "disque", "non configure" } };
        for (String[] k : cases) {
            st.setSelectedChannel(k[0]);
            st.setChannelPlan(key, k[0], chs.off("Doc"));
            json = st.json();
            check("down".equals(state(json, key, k[0])) && k[1].equals(detail(json, key, k[0])), "canal choisi inutilisable " + k[0] + ": " + channelsOf(json, key));
            check(channelsOf(json, key).contains("{\"id\":\"" + k[0] + "\",\"label\":\"" + Status.channelLabel(k[0]) + "\",\"selected\":true,"), "selected: " + channelsOf(json, key));
            check(count(channelsOf(json, key), "\"selected\":true") == 1 && count(channelsOf(json, key), "\"id\":") == 4, "un seul canal choisi parmi quatre");
            check("OFFLINE".equals(rootState(json, key)) && rootOf(json, key).contains("\"channel\":\"" + k[0] + "\""), "etat du dossier: " + rootOf(json, key));
            // Un etat "ready" ou "off" pousse par erreur sur le canal choisi est refuse.
            st.setChannelState(key, k[0], Status.CH_READY, "");
            st.setChannelState(key, k[0], Status.CH_OFF, "");
            check("down".equals(state(st.json(), key, k[0])), "canal choisi passe a ready/off");
        }
        // Lien direct choisi, pas encore connecte : "waiting" ; canal par lots regle : "down" le temps de la verification.
        st.setSelectedChannel("direct");
        st.setChannelPlan(key, "direct", chs.off("Doc"));
        check("waiting".equals(state(st.json(), key, "direct")), "direct non connecte: " + channelsOf(st.json(), key));
        chs.shutdown();
        chs = new Agent.Channels(c, true);
        st.setChannelPlan(key, "drime", chs.off("Doc"));
        check("down".equals(state(st.json(), key, "drime")) && !detail(st.json(), key, "drime").isEmpty(), "drime avant le premier essai: " + channelsOf(st.json(), key));
        check("ready".equals(state(st.json(), key, "direct")), "direct non choisi: ready");
        chs.shutdown();
        checkNoSecret(st.json(), "/api/status");
        st.drop(key);
    }

    private static void sameConfig(Config a, Config b, String what) {
        check(a.role.equals(b.role) && java.util.Objects.equals(a.master, b.master) && a.conflict.equals(b.conflict), what + ": role");
        check(a.roots.equals(b.roots) && new ArrayList<>(a.roots.keySet()).equals(new ArrayList<>(b.roots.keySet())), what + ": dossiers");
        check(a.peer.equals(b.peer) && a.discovery.equals(b.discovery) && a.secret.equals(b.secret), what + ": pair, secret");
        check(a.relay.equals(b.relay) && a.relays.equals(b.relays), what + ": relais");
        check(a.channel.equals(b.channel), what + ": channel " + a.channel + " / " + b.channel);
        check(a.webdavUrl.equals(b.webdavUrl) && a.webdavUser.equals(b.webdavUser) && a.webdavPassword.equals(b.webdavPassword), what + ": webdav");
        check(a.drimeToken.equals(b.drimeToken) && a.drimeFolder.equals(b.drimeFolder) && a.drimeWorkspace == b.drimeWorkspace
                && a.drimeUrl.equals(b.drimeUrl), what + ": drime");
        check(a.proxy.equals(b.proxy) && a.tlsTruststore.equals(b.tlsTruststore), what + ": reseau");
    }

    // =======================================================================
    //  Un poste : dossier, canal choisi, session, moteur
    // =======================================================================

    /** Magasin reel dont seule la periode de scrutation est raccourcie (fast), le temps d'un essai. */
    private static final class Fast implements RelayStore {
        final RelayStore in;
        volatile boolean fast = true;
        /** Faux : la disponibilite garde son cache reel de 10 s (seule la scrutation est acceleree). */
        volatile boolean probe = true;
        private volatile long lastForget;

        Fast(RelayStore in) { this.in = in; tune(); }

        Fast(RelayStore in, boolean probe) { this(in); this.probe = probe; }

        void setFast(boolean f) { fast = f; tune(); }

        private void tune() {
            if (in instanceof DrimeRelayStore d) { d.probeTtlMs = fast ? FAST_POLL : 10_000; d.cacheTtlMs = fast ? FAST_POLL : 4_000; }
        }

        @Override public String id() { return in.id(); }
        @Override public String describe() { return in.describe(); }
        @Override public boolean available() {
            if (fast && probe && in instanceof WebDavRelayStore w) {
                long n = System.nanoTime() / 1_000_000;
                if (n - lastForget >= FAST_POLL) { lastForget = n; w.forgetProbe(); }
            }
            return in.available();
        }
        @Override public String lastError() { return in.lastError(); }
        @Override public List<Entry> list(String d) throws IOException { return in.list(d); }
        @Override public byte[] read(String p, int max) throws IOException { return in.read(p, max); }
        @Override public void write(String p, Path src) throws IOException { in.write(p, src); }
        @Override public void write(String p, byte[] data) throws IOException { in.write(p, data); }
        @Override public void delete(String p) throws IOException { in.delete(p); }
        @Override public long pollMs() { return fast ? FAST_POLL : in.pollMs(); }
        @Override public long maxLotBytes() { return in.maxLotBytes(); }
        @Override public void close() { in.close(); }
    }

    private static final class Post {
        final String side, key;
        final Path dir;
        String channel = "";
        Agent.Channels channels;
        Agent.BatchRun run;
        Transfer.Session direct;
        Thread engine;

        Post(String side, String key, Path dir) { this.side = side; this.key = key; this.dir = dir; }

        /**
         * Demarre ce poste sur un canal par lots comme le fait Agent : etat des canaux,
         * Agent.prepareChannel (changement de canal), puis une session directement
         * sur le magasin du canal choisi. Aucune attente de l'autre poste.
         */
        void start(Config cfg) throws IOException {
            check(run == null && direct == null, key + " deja demarre");
            channel = cfg.channel;
            channels = new Agent.Channels(cfg, true);
            Status.get().setChannelPlan(key, channel, channels.off(NAME));
            check(channels.problem(channel, NAME).isEmpty(), "canal inutilisable: " + channels.why(channel, NAME));
            Agent.prepareChannel(NAME, side, dir, channel, channels, Agent.CLEAN_WAIT_MS);
            MailboxSession.Options o = new MailboxSession.Options();
            o.flushIdleMs = 200;
            run = Agent.BatchRun.open(NAME, key, side, dir, new Fast(channels.store(channel, NAME)), Crypto.derive(cfg.secret), o);
            final Agent.BatchRun r = run;
            engine = new Thread(() -> r.sync("hub".equals(side), SyncEngine.ConflictPolicy.KEEP_BOTH), "moteur-" + key);
            engine.setDaemon(true);
            engine.start();
        }

        /** Demarre ce poste sur le lien direct comme le fait Agent, sur une socket deja connectee. */
        void startDirect(Config cfg, Socket sock) {
            check(run == null && direct == null, key + " deja demarre");
            channel = "direct";
            channels = new Agent.Channels(cfg, true);
            Status.get().setChannelPlan(key, channel, channels.off(NAME));
            Agent.prepareChannel(NAME, side, dir, channel, channels, Agent.CLEAN_WAIT_MS);
            direct = Agent.directSession(NAME, side, dir, new Transfer.TcpSession(sock), channels);
            Status.get().setChannelState(key, "direct", Status.CH_ACTIVE, "");
            final Transfer.Session s = direct;
            engine = new Thread(() -> new SyncEngine(dir, "hub".equals(side), SyncEngine.ConflictPolicy.KEEP_BOTH)
                    .run(s, side + "[" + key + "]", key), "moteur-" + key);
            engine.setDaemon(true);
            engine.start();
        }

        boolean drained() { return run != null && run.session.outboundDrained(); }

        void stop() throws InterruptedException {
            if (run == null && direct == null) return;
            if (run != null) run.close();
            if (direct != null) direct.close();
            engine.join(30_000);
            check(!engine.isAlive(), "le moteur " + key + " ne s'arrete pas");
            run = null;
            direct = null;
            channels.shutdown();
        }
    }

    /** Deux postes, et les trois magasins regles des deux cotes (un seul est choisi a la fois). */
    private static final class World {
        final Path hubDir, cliDir, relay, relayAway;
        final FakeWebDavServer dav = new FakeWebDavServer(USER, PASSWORD);
        final FakeDrimeServer drime = new FakeDrimeServer(TOKEN);
        final Post hub, cli;

        World(Path d, String tag) throws IOException {
            hubDir = d.resolve("hub");
            cliDir = d.resolve("client");
            relay = d.resolve("relais");
            relayAway = d.resolve("relais-debranche");
            Files.createDirectories(relay);
            Files.createDirectories(hubDir);
            Files.createDirectories(cliDir);
            hub = new Post("hub", tag + "-hub", hubDir);
            cli = new Post("client", tag + "-client", cliDir);
            // Deux postes dans un processus : chacun retrouve les rapports de l'autre sous le nom de l'autre.
            Status.get().root(hub.key).peerName = cli.key;
            Status.get().root(cli.key).peerName = hub.key;
        }

        Config config(String role, String channel) {
            Config c = new Config();
            c.role = role;
            c.secret = SECRET;
            c.channel = channel;
            c.webdavUrl = dav.baseUrl(); c.webdavUser = USER; c.webdavPassword = PASSWORD;
            c.drimeToken = TOKEN; c.drimeUrl = drime.apiUrl(); c.drimeFolder = DRIME_FOLDER;
            c.relay = relay.toString();
            c.proxy = "none";
            return c;
        }

        /** Tout demarrage passe par ici : le choix global du poste (self.channel) suit. */
        void start(Post p, String channel) throws IOException {
            Status.get().setSelectedChannel(channel);
            p.start(config(p.side, channel));
        }

        /** Noms des fichiers presents dans ce magasin, sous le dossier de ce sens ("hub" ou "client" ; null = partout). */
        List<String> names(String id, String from) {
            String dir = from == null ? null : MailboxSession.direction(from);
            List<String> out = new ArrayList<>();
            switch (id) {
                case "webdav" -> {
                    for (String f : dav.files()) if (dir == null || f.contains(dir + "/")) out.add(f.substring(f.lastIndexOf('/') + 1));
                }
                case "drime" -> {
                    if (dir == null) {
                        for (FakeDrimeServer.Item it : drime.entries()) if (!it.folder()) out.add(it.name());
                    } else {
                        FakeDrimeServer.Item folder = drime.find(DRIME_FOLDER + "/" + NAME + "/" + dir);
                        if (folder != null)
                            for (FakeDrimeServer.Item it : drime.entries())
                                if (!it.folder() && it.parentId() != null && it.parentId() == folder.id()) out.add(it.name());
                    }
                }
                default -> {
                    Path root = Files.isDirectory(relay) ? relay : relayAway;
                    for (Path p : files(dir == null ? root : root.resolve(NAME).resolve(dir))) out.add(p.getFileName().toString());
                }
            }
            return out;
        }

        /** Nombre de lots de ce sens presents dans ce magasin (null = les deux sens). */
        int lots(String id, String from) {
            int n = 0;
            for (String f : names(id, from)) if (f.endsWith(".lot")) n++;
            return n;
        }

        /** Nombre de depots jamais faits dans ce magasin (lots et acquittements). */
        int writes(String id) {
            return switch (id) {
                case "webdav" -> dav.requests("PUT");
                case "drime" -> drime.count("POST /uploads");
                default -> names("disque", null).size();
            };
        }

        /** Coupe ou retablit un magasin : serveur arrete, ou disque relais debranche (dossier deplace). */
        void setDown(String id, boolean down) throws Exception {
            switch (id) {
                case "webdav" -> dav.setDown(down);
                case "drime" -> drime.setDown(down);
                default -> {
                    Path from = down ? relay : relayAway, to = down ? relayAway : relay;
                    IOException last = null;
                    for (int i = 0; i < 100; i++) {
                        try { Files.move(from, to); return; } catch (IOException e) { last = e; Thread.sleep(50); }
                    }
                    throw last;
                }
            }
        }

        void close() throws InterruptedException {
            hub.stop();
            cli.stop();
            dav.stop();
            drime.stop();
        }
    }

    /** Lots scelles, pas encore acquittes, dans le spool local de ce dossier. */
    private static int spoolLots(Path dir) { return lotFiles(Agent.spoolOf(dir).resolve("out")); }

    // =======================================================================
    //  1. Un canal par lots, de bout en bout : hub seul, client, panne, retour
    // =======================================================================

    private static void testChannel(String id) throws Exception {
        step("1. canal " + id + " : hub seul, puis client, panne sans bascule, retour");
        Path d = work.resolve("c-" + id);
        World w = new World(d, "E2E-" + id);
        Post hub = w.hub, cli = w.cli;
        try {
            Random rnd = new Random(42);
            byte[] gros = new byte[300_000];
            rnd.nextBytes(gros);
            put(w.hubDir, "a.txt", "version 1 de a, ecrite sur le hub\n".getBytes(StandardCharsets.UTF_8));
            put(w.hubDir, "sous dossier/gros.bin", gros);
            put(w.hubDir, "a supprimer par le client.txt", "h".getBytes(StandardCharsets.UTF_8));

            // .syncignore : des regles ecrites sur UN poste (date ancienne), l'autre n'a
            // que le modele cree d'office a son demarrage (plus recent, et le hub est
            // maitre) : les regles ecrites doivent l'emporter dans les deux sens.
            final boolean reglesSurLeHub = !"drime".equals(id);
            final String regles = "# regles de l'essai\n*.jamais-synchro\n";
            final long ancien = System.currentTimeMillis() - 86_400_000L;
            if (reglesSurLeHub) {
                put(w.hubDir, ".cotransfert/.syncignore", regles.getBytes(StandardCharsets.UTF_8));
                Files.setLastModifiedTime(w.hubDir.resolve(".cotransfert/.syncignore"), java.nio.file.attribute.FileTime.fromMillis(ancien));
            }

            // --- le hub SEUL : aucun client ne s'est jamais connecte, aucun marqueur, et pourtant il depose ---
            w.start(hub, id);
            waitFor(() -> w.lots(id, "hub") > 0, 40_000, "le hub seul doit deposer ses lots dans " + id);
            say("hub seul : " + w.lots(id, "hub") + " lot(s) deposes dans " + id + " sans qu'aucun client existe : " + w.names(id, "hub"));
            check(w.lots(id, "client") == 0, "lots d'un client qui n'existe pas");
            for (String f : w.names(id, null)) check(!f.endsWith(".canal"), "fichier marqueur dans le magasin: " + f);
            waitFor(() -> "active".equals(state(Status.get().json(), hub.key, id)), 20_000, "canal " + id + " actif cote hub");
            // Canal actif mais pair jamais vu : "en attente de l'autre poste", pas "a jour".
            waitFor(() -> "WAITING".equals(rootState(Status.get().json(), hub.key)), 20_000, "hub seul montre en attente: " + rootState(Status.get().json(), hub.key));
            Thread.sleep(800);
            check("WAITING".equals(rootState(Status.get().json(), hub.key)), "hub seul montre a jour alors que le pair n'a jamais ete vu");
            waitFor(() -> Status.get().root(hub.key).channelInfo.contains("en attente du pair"), 20_000,
                    "ligne d'information du hub seul: " + Status.get().root(hub.key).channelInfo);
            checkStatus(hub, id, "active");
            check(!hub.drained(), "sans client, les lots du hub ne peuvent pas etre acquittes");

            // --- le client arrive : convergence dans les deux sens ---
            put(w.cliDir, "c.txt", "version 1 de c, ecrite sur le client\n".getBytes(StandardCharsets.UTF_8));
            put(w.cliDir, "dossier \u00e9t\u00e9/d.txt", text(5000, 7));
            put(w.cliDir, "a supprimer par le hub.txt", "c".getBytes(StandardCharsets.UTF_8));
            if (!reglesSurLeHub) {
                put(w.cliDir, ".cotransfert/.syncignore", regles.getBytes(StandardCharsets.UTF_8));
                Files.setLastModifiedTime(w.cliDir.resolve(".cotransfert/.syncignore"), java.nio.file.attribute.FileTime.fromMillis(ancien));
            }
            w.start(cli, id);
            converge(hub, cli, "premiere synchro par " + id);
            waitFor(() -> sameRules(w.hubDir, regles) && sameRules(w.cliDir, regles), 40_000,
                    "les regles ecrites du .syncignore doivent remplacer le modele par defaut de l'autre poste");
            check(Files.exists(w.cliDir.resolve("sous dossier/gros.bin")) && Files.exists(w.hubDir.resolve("dossier \u00e9t\u00e9/d.txt")), "fichiers initiaux");
            put(w.cliDir, "a.txt", "version 2 de a, modifiee sur le client\n".getBytes(StandardCharsets.UTF_8));
            put(w.hubDir, "nouveau du hub.txt", text(2000, 1));
            Files.delete(w.hubDir.resolve("a supprimer par le hub.txt"));
            Files.delete(w.cliDir.resolve("a supprimer par le client.txt"));
            converge(hub, cli, "modifications et suppressions par " + id);
            check(new String(Files.readAllBytes(w.hubDir.resolve("a.txt")), StandardCharsets.UTF_8).startsWith("version 2"), "a.txt modifie cote hub");
            check(!Files.exists(w.cliDir.resolve("a supprimer par le hub.txt")) && !Files.exists(w.hubDir.resolve("a supprimer par le client.txt")), "suppressions propagees");
            waitFor(() -> "active".equals(state(Status.get().json(), cli.key, id)), 20_000, "canal " + id + " actif cote client");
            waitFor(() -> "UP_TO_DATE".equals(rootState(Status.get().json(), hub.key)) && "UP_TO_DATE".equals(rootState(Status.get().json(), cli.key)),
                    40_000, "a jour des deux cotes une fois le pair vu sur le canal");
            checkStatus(hub, id, "active");
            checkStatus(cli, id, "active");
            waitFor(() -> hub.drained() && cli.drained() && w.lots(id, null) == 0, 40_000, id + " vide de ses lots acquittes: " + w.names(id, null));
            for (String other : BATCH) if (!other.equals(id)) check(w.writes(other) == 0, "depot dans un canal non choisi: " + other);

            // --- un dossier entier supprime d'un coup : fichiers ET dossiers disparaissent chez le pair ---
            for (int i = 0; i < 25; i++) put(w.hubDir, "arbre a retirer/niveau " + (i % 5) + "/sous/f" + i + ".txt", text(300, 40 + i));
            converge(hub, cli, "arbre cree par " + id);
            check(Files.isDirectory(w.cliDir.resolve("arbre a retirer/niveau 4/sous")), "arbre recu par le client");
            try (java.util.stream.Stream<Path> all = Files.walk(w.hubDir.resolve("arbre a retirer"))) {
                for (Path p : all.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
            }
            converge(hub, cli, "arbre supprime par " + id);
            waitFor(() -> !Files.exists(w.cliDir.resolve("arbre a retirer")), 40_000,
                    "le dossier supprime sur le hub doit disparaitre du client, dossiers vides compris");
            check(Files.exists(w.cliDir.resolve("sous dossier/gros.bin")), "les autres dossiers du client sont intacts");

            // --- un dossier renomme deux fois de suite sur le client : le hub ne garde que le dernier nom ---
            for (int i = 0; i < 5; i++) put(w.cliDir, "classeur/dossier/piece " + i + ".txt", text(400, 70 + i));
            converge(hub, cli, "dossier a renommer cree par " + id);
            Files.move(w.cliDir.resolve("classeur/dossier"), w.cliDir.resolve("classeur/dossier_etape"));
            Thread.sleep(700);
            Files.move(w.cliDir.resolve("classeur/dossier_etape"), w.cliDir.resolve("classeur/dossier_final"));
            converge(hub, cli, "dossier renomme par " + id);
            waitFor(() -> !Files.exists(w.hubDir.resolve("classeur/dossier")) && !Files.exists(w.hubDir.resolve("classeur/dossier_etape")), 40_000,
                    "les anciens noms du dossier renomme doivent disparaitre du hub");
            try (java.util.stream.Stream<Path> s = Files.list(w.hubDir.resolve("classeur/dossier_final"))) {
                check(s.count() == 5, "le dossier renomme arrive complet sur le hub");
            }
            try (java.util.stream.Stream<Path> all = Files.walk(w.cliDir.resolve("classeur"))) {
                for (Path p : all.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
            }
            converge(hub, cli, "dossier renomme retire par " + id);
            waitFor(() -> !Files.exists(w.hubDir.resolve("classeur")), 40_000, "le dossier retire sur le client doit disparaitre du hub");

            // --- le magasin choisi tombe : AUCUNE bascule, les lots attendent, l'etat le dit ---
            w.setDown(id, true);
            put(w.hubDir, "b.txt", text(3000, 2));
            put(w.cliDir, "c.txt", "version 2 de c, modifiee sur le client pendant la panne\n".getBytes(StandardCharsets.UTF_8));
            Files.delete(w.cliDir.resolve("nouveau du hub.txt"));
            waitFor(() -> {
                String j = Status.get().json();
                return "down".equals(state(j, hub.key, id)) && "down".equals(state(j, cli.key, id));
            }, 40_000, "canal " + id + " indisponible des deux cotes");
            String why = detail(Status.get().json(), cli.key, id);
            check(!why.isEmpty(), id + " indisponible sans raison affichee");
            say("panne de " + id + " : etat down, raison affichee = " + why);
            waitFor(() -> spoolLots(w.hubDir) > 0 && spoolLots(w.cliDir) > 0, 20_000, "les lots attendent dans le spool");
            waitFor(() -> "WAITING".equals(rootState(Status.get().json(), hub.key)), 20_000,
                    "dossier montre en attente tant que le canal choisi est indisponible: " + rootState(Status.get().json(), hub.key));
            Thread.sleep(1500);
            checkStatus(hub, id, "down");
            checkStatus(cli, id, "down");
            check(!Files.exists(w.cliDir.resolve("b.txt")) && Files.exists(w.hubDir.resolve("nouveau du hub.txt")), "rien ne doit passer pendant la panne");
            for (String other : BATCH) if (!other.equals(id)) check(w.writes(other) == 0, "bascule vers un autre canal pendant la panne: " + other);
            check(!hub.drained() && !cli.drained(), "lots non acquittes attendus pendant la panne");
            check(Status.get().root(cli.key).channelInfo.length() > 0, "ligne d'information du canal vide");

            // --- le magasin revient : reprise, sans rien perdre ---
            w.setDown(id, false);
            converge(hub, cli, "reprise au retour de " + id);
            check(Files.exists(w.cliDir.resolve("b.txt")) && !Files.exists(w.hubDir.resolve("nouveau du hub.txt")), "echanges apres la panne");
            waitFor(() -> {
                String j = Status.get().json();
                return "active".equals(state(j, hub.key, id)) && "active".equals(state(j, cli.key, id));
            }, 40_000, "canal " + id + " de nouveau actif");
            put(w.cliDir, "f.txt", text(1500, 4));
            put(w.hubDir, "sous dossier/gros.bin", Arrays.copyOf(gros, 200_000));
            converge(hub, cli, "synchro apres le retour de " + id);
            waitFor(() -> hub.drained() && cli.drained() && w.lots(id, null) == 0, 40_000, id + " vide a la fin: " + w.names(id, null));

            // --- bilan ---
            Map<String, byte[]> th = tree(w.hubDir), tc = tree(w.cliDir);
            check(sameTree(th, tc), "arbres differents a la fin");
            check(th.keySet().equals(new java.util.TreeSet<>(List.of("a.txt", "b.txt", "c.txt", "dossier \u00e9t\u00e9/d.txt", "f.txt", "sous dossier/gros.bin"))),
                    "contenu final inattendu: " + th.keySet());
            check(new String(th.get("a.txt"), StandardCharsets.UTF_8).startsWith("version 2"), "a.txt final");
            check(new String(th.get("c.txt"), StandardCharsets.UTF_8).startsWith("version 2"), "c.txt final");
            check(th.get("sous dossier/gros.bin").length == 200_000, "gros.bin final");
            check(noConflict(w.hubDir) && noConflict(w.cliDir), "fichier .conflict-peer- inattendu");
            check(Status.get().root(hub.key).conflicts == 0 && Status.get().root(cli.key).conflicts == 0, "conflit signale");
            check(w.drime.trash().isEmpty(), "corbeille drime non vide: " + w.drime.trash());
            check(w.drime.authLeaks() == 0, "jeton envoye au stockage");
            for (String f : w.names(id, null)) check(!f.endsWith(".part") && !f.endsWith(".canal"), "fichier inattendu dans le magasin: " + f);
            for (String other : BATCH) if (!other.equals(id)) check(w.writes(other) == 0, "depot dans un canal non choisi: " + other);
            check(Agent.recordedChannel(Agent.spoolOf(w.hubDir)).equals(id) && Agent.recordedChannel(Agent.spoolOf(w.cliDir)).equals(id), "dernier canal utilise non note");
            String json = Status.get().json();
            checkNoSecret(json, "/api/status");
            say("/api/status, canaux du client : " + channelsOf(json, cli.key));
        } finally {
            w.close();
        }
        // Tout ce qui a transite etait chiffre : aucun contenu en clair sur le disque relais.
        check(!containsBytes(d.resolve("relais"), "version 2 de a".getBytes(StandardCharsets.UTF_8)), "contenu en clair sur le relais");
    }

    private static void converge(Post hub, Post cli, String what) throws InterruptedException {
        waitFor(() -> {
            try { return sameTree(tree(hub.dir), tree(cli.dir)); } catch (IOException e) { return false; }
        }, 60_000, what);
        // Stable : toujours identiques un instant plus tard (rien en cours d'application).
        Thread.sleep(400);
        waitFor(() -> {
            try { return sameTree(tree(hub.dir), tree(cli.dir)); } catch (IOException e) { return false; }
        }, 60_000, what + " (stable)");
        say("converge : " + what);
    }

    /**
     * Verifie /api/status pour ce poste : les quatre canaux dans l'ordre fixe, un
     * seul "selected" (le canal choisi) dans l'etat attendu, les autres "ready"
     * (regles, non choisis), le canal du dossier et le choix global du poste.
     */
    private static void checkStatus(Post p, String id, String state) {
        String json = Status.get().json();
        String ch = channelsOf(json, p.key);
        Matcher m = Pattern.compile("\\{\"id\":\"([a-z]+)\",\"label\":\"([^\"]*)\",\"selected\":(true|false),\"state\":\"([a-z]+)\",\"detail\":\"([^\"]*)\"\\}")
                .matcher(ch);
        List<String> ids = new ArrayList<>(), labels = new ArrayList<>();
        int at = 1;
        while (m.find()) {
            check(m.start() == at, p.key + ": forme inattendue des canaux: " + ch);
            at = m.end() + 1;
            ids.add(m.group(1));
            labels.add(m.group(2));
            boolean sel = m.group(1).equals(id);
            check(m.group(3).equals(String.valueOf(sel)), p.key + ": selected de " + m.group(1) + ": " + ch);
            check(m.group(4).equals(sel ? state : "ready"), p.key + ": etat de " + m.group(1) + ": " + ch);
            if (!sel || "active".equals(state)) check(m.group(5).isEmpty(), p.key + ": detail inattendu: " + ch);
            else if ("down".equals(state)) check(!m.group(5).isEmpty(), p.key + ": canal indisponible sans raison: " + ch);
        }
        check(at == ch.length() + 1 || at == ch.length(), p.key + ": forme inattendue des canaux: " + ch);
        check(ids.equals(List.of("direct", "webdav", "drime", "disque")), p.key + ": ordre des canaux: " + ch);
        check(labels.equals(List.of("Lien direct", "WebDAV", "Drime", "Disque relais")), p.key + ": libelles: " + ch);
        check(rootOf(json, p.key).contains("\"channel\":\"" + id + "\""), p.key + ": canal du dossier: " + rootOf(json, p.key));
        check(json.contains("\"configured\":") && json.contains(",\"channel\":\"" + id + "\",\"boot\":"), "self.channel: " + json);
        check(Status.get().channels(p.key).size() == 4, "Status.channels");
        checkNoSecret(json, "/api/status");
    }

    // --- lecture rudimentaire du JSON de /api/status ---

    private static String rootOf(String json, String key) {
        int i = json.indexOf("{\"name\":\"" + key + "\",\"path\"");
        check(i >= 0, "dossier absent de /api/status: " + key);
        int j = json.indexOf("]}", json.indexOf("\"channels\":[", i));
        return json.substring(i, j + 2);
    }

    private static String channelsOf(String json, String key) {
        String r = rootOf(json, key);
        return r.substring(r.indexOf("\"channels\":") + 11, r.length() - 1);
    }

    /** Etat du dossier tel qu'il est montre (WAITING a la place de UP_TO_DATE tant que le canal choisi n'est pas actif). */
    private static String rootState(String json, String key) {
        Matcher m = Pattern.compile("\"state\":\"([A-Z_]+)\"").matcher(rootOf(json, key));
        return m.find() ? m.group(1) : "?";
    }

    private static String field(String json, String key, String id, String field) {
        Matcher m = Pattern.compile("\\{\"id\":\"" + id + "\",\"label\":\"[^\"]*\",\"selected\":(?:true|false),\"state\":\"([a-z]*)\",\"detail\":\"([^\"]*)\"\\}")
                .matcher(channelsOf(json, key));
        return m.find() ? m.group("state".equals(field) ? 1 : 2) : "?";
    }

    private static String state(String json, String key, String id) { return field(json, key, id, "state"); }

    private static String detail(String json, String key, String id) { return field(json, key, id, "detail"); }

    private static void checkNoSecret(String text, String where) {
        String basic = Base64.getEncoder().encodeToString((USER + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
        for (String s : new String[] { SECRET, PASSWORD, TOKEN, basic })
            check(!text.contains(s), "secret visible dans " + where);
    }

    // =======================================================================
    //  2. Changements de canal, par le chemin de demarrage de Agent
    // =======================================================================

    private static boolean sameRules(Path dir, String expected) {
        try { return expected.equals(str(dir, ".cotransfert/.syncignore")); } catch (IOException e) { return false; }
    }

    private static String str(Path dir, String rel) throws IOException {
        return new String(Files.readAllBytes(dir.resolve(rel)), StandardCharsets.UTF_8);
    }

    private static void write(Path dir, String rel, String content) throws IOException {
        put(dir, rel, content.getBytes(StandardCharsets.UTF_8));
    }

    private static String recorded(Path dir) { return Agent.recordedChannel(Agent.spoolOf(dir)); }

    private static void testSwitches() throws Exception {
        step("2. changements de canal (meme chemin de demarrage que Agent)");
        Path d = work.resolve("t2");
        World w = new World(d, "CHG");
        Post hub = w.hub, cli = w.cli;
        try {
            write(w.hubDir, "a.txt", "a version 1 (hub)\n");
            put(w.hubDir, "h0.txt", text(3000, 11));
            put(w.cliDir, "c0.txt", text(3000, 12));
            w.start(hub, "webdav");
            w.start(cli, "webdav");
            converge(hub, cli, "depart sur webdav");
            waitFor(() -> hub.drained() && cli.drained() && w.lots("webdav", null) == 0, 40_000, "webdav vide");
            check(recorded(w.hubDir).equals("webdav") && recorded(w.cliDir).equals("webdav"), "dernier canal note: webdav");

            // --- 2a. webdav -> drime avec des lots non acquittes ; le hub bascule avant le client ---
            cli.stop();
            write(w.hubDir, "a.txt", "a version 2 (hub, pendant que le client est arrete)\n");
            put(w.hubDir, "h1.txt", text(2000, 13));
            waitFor(() -> w.lots("webdav", "hub") > 0, 20_000, "lots du hub deposes dans webdav, non acquittes");
            hub.stop();
            put(w.cliDir, "c1.txt", text(2000, 14));   // modification hors session
            int pending = spoolLots(w.hubDir);
            check(pending > 0, "lots non acquittes attendus dans le spool du hub");
            w.start(hub, "drime");
            check(spoolLots(w.hubDir) >= pending, "le changement de canal a retire des lots du spool");
            waitFor(() -> w.lots("drime", "hub") >= pending, 40_000, "lots non acquittes redeposes dans drime");
            waitFor(() -> w.lots("webdav", "hub") == 0, 20_000, "sens du hub nettoye dans l'ancien magasin: " + w.names("webdav", "hub"));
            check(recorded(w.hubDir).equals("drime"), "dernier canal note cote hub: " + recorded(w.hubDir));
            // Le client est encore sur webdav : chacun depose de son cote, rien ne passe, rien ne se perd.
            w.start(cli, "webdav");
            put(w.cliDir, "c2.txt", text(2000, 15));
            waitFor(() -> w.lots("webdav", "client") > 0, 20_000, "le client depose dans webdav");
            Thread.sleep(1500);
            check(!Files.exists(w.hubDir.resolve("c1.txt")) && !Files.exists(w.cliDir.resolve("h1.txt")), "des canaux differents ne doivent rien echanger");
            checkStatus(cli, "webdav", "active");
            check("active".equals(state(Status.get().json(), hub.key, "drime")), "hub actif sur drime");
            cli.stop();
            w.start(cli, "drime");
            converge(hub, cli, "webdav -> drime, lots non acquittes redeposes");
            check(str(w.cliDir, "a.txt").startsWith("a version 2") && Files.exists(w.cliDir.resolve("h1.txt"))
                    && Files.exists(w.hubDir.resolve("c1.txt")) && Files.exists(w.hubDir.resolve("c2.txt")), "changement perdu au passage webdav -> drime");
            check(noConflict(w.hubDir) && noConflict(w.cliDir), "conflit au passage webdav -> drime");
            waitFor(() -> w.lots("webdav", "client") == 0, 20_000, "sens du client nettoye dans l'ancien magasin");
            waitFor(() -> hub.drained() && cli.drained() && w.lots("drime", null) == 0, 40_000, "drime vide: " + w.names("drime", null));
            checkStatus(hub, "drime", "active");
            checkStatus(cli, "drime", "active");

            // --- 2b. "direct" choisi mais jamais connecte, puis retour a drime : rien n'est abandonne ---
            cli.stop();
            write(w.hubDir, "a.txt", "a version 3 (hub, non acquittee)\n");
            waitFor(() -> w.lots("drime", "hub") > 0, 20_000, "lot du hub depose dans drime");
            hub.stop();
            int kept = spoolLots(w.hubDir);
            check(kept > 0, "lots non acquittes attendus avant le passage a direct");
            Agent.Channels chs = new Agent.Channels(w.config("hub", "direct"), true);
            Agent.prepareChannel(NAME, "hub", w.hubDir, "direct", chs, Agent.CLEAN_WAIT_MS);      // demarrage sur "direct", cable jamais branche
            Agent.prepareChannel(NAME, "client", w.cliDir, "direct", chs, Agent.CLEAN_WAIT_MS);
            chs.shutdown();
            check(spoolLots(w.hubDir) == kept && recorded(w.hubDir).equals("drime"), "lots abandonnes sans reconciliation directe");
            w.start(hub, "drime");
            w.start(cli, "drime");
            converge(hub, cli, "direct jamais connecte puis retour a drime");
            check(str(w.cliDir, "a.txt").startsWith("a version 3"), "modification perdue: " + str(w.cliDir, "a.txt"));
            check(noConflict(w.hubDir) && noConflict(w.cliDir), "conflit apres un passage a vide par direct");
            waitFor(() -> hub.drained() && cli.drained() && w.lots("drime", null) == 0, 40_000, "drime vide");

            // --- 2c. drime -> direct (reconciliation reelle) -> webdav ---
            cli.stop();
            write(w.hubDir, "a.txt", "a version 4 (hub, non acquittee)\n");
            put(w.hubDir, "h2.txt", text(2000, 16));
            waitFor(() -> w.lots("drime", "hub") > 0, 20_000, "lots du hub deposes dans drime");
            hub.stop();
            put(w.cliDir, "c3.txt", text(2000, 17));   // modification hors session
            check(spoolLots(w.hubDir) > 0, "lots non acquittes attendus avant le lien direct");
            Status.get().setSelectedChannel("direct");
            try (ServerSocket ss = new ServerSocket(0, 5, InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 }))) {
                Socket c = new Socket(InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 }), ss.getLocalPort());
                Socket h = ss.accept();
                hub.startDirect(w.config("hub", "direct"), h);
                cli.startDirect(w.config("client", "direct"), c);
            }
            converge(hub, cli, "reconciliation par le lien direct");
            check(str(w.cliDir, "a.txt").startsWith("a version 4") && Files.exists(w.cliDir.resolve("h2.txt")) && Files.exists(w.hubDir.resolve("c3.txt")),
                    "reconciliation directe incomplete");
            waitFor(() -> spoolLots(w.hubDir) == 0 && spoolLots(w.cliDir) == 0, 20_000, "lots non acquittes abandonnes apres la reconciliation directe");
            waitFor(() -> recorded(w.hubDir).equals("direct") && recorded(w.cliDir).equals("direct"), 20_000, "dernier canal note: direct");
            waitFor(() -> w.lots("drime", null) == 0, 30_000, "ancien magasin nettoye apres le passage a direct: " + w.names("drime", null));
            checkStatus(hub, "direct", "active");
            write(w.cliDir, "a.txt", "a version 5 (client, par le lien direct)\n");
            converge(hub, cli, "modification en direct");
            check(str(w.hubDir, "a.txt").startsWith("a version 5"), "a.txt par le lien direct");
            hub.stop();
            cli.stop();
            put(w.hubDir, "h3.txt", text(2000, 18));   // modification hors session
            for (String s : BATCH) check(w.lots(s, null) == 0, "lots restes dans " + s + " apres le passage a direct: " + w.names(s, null));
            w.start(hub, "webdav");
            w.start(cli, "webdav");
            converge(hub, cli, "direct -> webdav");
            check(Files.exists(w.cliDir.resolve("h3.txt")), "modification hors session perdue");
            put(w.cliDir, "c4.txt", text(2000, 19));
            write(w.hubDir, "a.txt", "a version 6 (hub, par webdav)\n");
            converge(hub, cli, "echanges par webdav apres le lien direct");
            check(str(w.cliDir, "a.txt").startsWith("a version 6"), "a.txt apres direct -> webdav: " + str(w.cliDir, "a.txt"));
            check(noConflict(w.hubDir) && noConflict(w.cliDir), "rejeu d'un ancien lot apres le passage par direct (.conflict-peer-)");
            waitFor(() -> hub.drained() && cli.drained() && w.lots("webdav", null) == 0, 40_000, "webdav vide");

            // --- 2d. retour a drime, magasin deja utilise puis quitte : aucun rejeu ---
            hub.stop();
            cli.stop();
            w.start(cli, "drime");   // cette fois le client bascule le premier
            write(w.cliDir, "a.txt", "a version 7 (client, par drime)\n");
            w.start(hub, "drime");
            put(w.hubDir, "h4.txt", text(2000, 20));
            converge(hub, cli, "webdav -> drime (magasin deja utilise)");
            check(str(w.hubDir, "a.txt").startsWith("a version 7"), "a.txt final: " + str(w.hubDir, "a.txt"));
            waitFor(() -> hub.drained() && cli.drained() && w.lots("drime", null) == 0, 40_000, "drime vide a la fin");

            // --- bilan ---
            Map<String, byte[]> th = tree(w.hubDir);
            check(th.keySet().equals(new java.util.TreeSet<>(List.of("a.txt", "c0.txt", "c1.txt", "c2.txt", "c3.txt", "c4.txt",
                    "h0.txt", "h1.txt", "h2.txt", "h3.txt", "h4.txt"))), "contenu final inattendu: " + th.keySet());
            check(noConflict(w.hubDir) && noConflict(w.cliDir), "fichier .conflict-peer- inattendu");
            check(Status.get().root(hub.key).conflicts == 0 && Status.get().root(cli.key).conflicts == 0, "conflit signale");
            check(w.drime.trash().isEmpty(), "corbeille drime non vide: " + w.drime.trash());
            check(lotFiles(w.relay) == 0, "le canal disque, jamais choisi, a recu des lots");
            for (String s : BATCH) for (String f : w.names(s, null)) check(!f.endsWith(".canal"), "fichier marqueur: " + f);
            checkNoSecret(Status.get().json(), "/api/status");
        } finally {
            w.close();
        }
    }

    // =======================================================================
    //  3. discardOutbound directement sur un magasin
    // =======================================================================

    private static void testDiscard() throws Exception {
        step("3. abandon des lots sur un magasin");
        Path d = work.resolve("t3");
        Path relay = d.resolve("relais");
        Files.createDirectories(relay);
        Path out = relay.resolve("doc").resolve("client-vers-hub");
        MailboxSession h = new MailboxSession("doc", "hub", d.resolve("hub"), bare(relay), opts());
        MailboxSession c = new MailboxSession("doc", "client", d.resolve("client"), bare(relay), opts());
        send(c, "a1");
        expect(h, "a1");
        send(h, "h1");
        expect(c, "h1");
        // Un cadre n'est acquitte que lorsque le suivant est demande : chaque cote le demande.
        acknowledge(c);
        acknowledge(h);
        h.close();   // le hub s'absente : ce qui suit n'est jamais acquitte
        for (int i = 0; i < 3; i++) send(c, "perdu" + i);
        waitFor(() -> lotFiles(out) > 0, 10_000, "lots deposes sans lecteur");
        check(!c.outboundDrained(), "lots non acquittes attendus");
        c.discardOutbound();
        check(c.outboundDrained(), "plus rien a emettre apres l'abandon");
        check(lotFiles(out) == 0, "lots abandonnes encore sur le relais: " + lotFiles(out));
        try { send(c, "apres"); fail("emission acceptee apres l'abandon"); } catch (IOException ok) { }
        c.close();
        check(lotFiles(d.resolve("client").resolve("out")) == 0, "lots abandonnes encore dans le spool");

        // Sessions suivantes sur les memes spools : le flux repart, rien d'abandonne ne revient.
        h = new MailboxSession("doc", "hub", d.resolve("hub"), bare(relay), opts());
        c = new MailboxSession("doc", "client", d.resolve("client"), bare(relay), opts());
        send(c, "b1");
        expect(h, "b1");
        send(h, "h2");
        expect(c, "h2");
        send(c, "b2");
        expect(h, "b2");
        h.close();
        c.close();
    }

    private static RelayStore bare(Path relay) { return new Fast(new DirRelayStore(relay.toString())); }

    private static MailboxSession.Options opts() {
        MailboxSession.Options o = new MailboxSession.Options();
        o.flushIdleMs = 40;
        return o;
    }

    private static void send(MailboxSession s, String path) throws IOException {
        byte[] b = ("contenu de " + path).getBytes(StandardCharsets.UTF_8);
        Transfer.Msg m = new Transfer.Msg();
        m.op = Transfer.OP_FILE; m.relPath = path; m.size = b.length; m.modTime = 1234; m.hash = 99;
        s.sendFile(m, new ByteArrayInputStream(b));
    }

    /** Demande le cadre suivant en arriere-plan, ce qui acquitte le precedent ; se termine a la fermeture. */
    private static void acknowledge(MailboxSession s) throws InterruptedException {
        Thread t = new Thread(() -> {
            try { s.recvHeader(); } catch (IOException | RuntimeException closed) { }
        }, "acquittement");
        t.setDaemon(true);
        t.start();
        Thread.sleep(300);
    }

    /** Lit le cadre suivant dans un thread a part (delai borne) et verifie son nom. */
    private static void expect(MailboxSession s, String path) throws Exception {
        AtomicReference<Object> got = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                Transfer.Msg m = s.recvHeader();
                s.copyContentTo(m, new ByteArrayOutputStream());
                got.set(m.relPath);
            } catch (Throwable e) {
                got.set(e);
            }
        }, "lecteur");
        t.setDaemon(true);
        t.start();
        t.join(20_000);
        check(!t.isAlive(), "cadre " + path + " jamais arrive");
        check(path.equals(got.get()), "attendu " + path + ", recu " + got.get());
    }

    // =======================================================================
    //  3. Serveur qui ne repond plus : fermeture bornee
    // =======================================================================

    private static void testStalledServer() throws Exception {
        step("4. serveur en ligne qui ne repond plus");
        Path d = work.resolve("t4");
        FakeWebDavServer dav = new FakeWebDavServer(USER, PASSWORD);
        StallProxy proxy = new StallProxy(java.net.URI.create(dav.baseUrl()).getPort());
        String viaProxy = dav.baseUrl().replace(":" + java.net.URI.create(dav.baseUrl()).getPort() + "/", ":" + proxy.port() + "/");
        java.net.http.HttpClient http = HttpSupport.client("none");
        Fast hs = new Fast(new WebDavRelayStore(dav.baseUrl(), USER, PASSWORD, http));
        // Cote client, la disponibilite garde son cache reel : comme en production, le serveur
        // se tait alors que le magasin se croit joignable, et une lecture de lot reste sans reponse.
        Fast cs = new Fast(new WebDavRelayStore(viaProxy, USER, PASSWORD, http), false);
        MailboxSession h = new MailboxSession("doc", "hub", d.resolve("hub"), hs, opts());
        MailboxSession c = new MailboxSession("doc", "client", d.resolve("client"), cs, opts());
        try {
            send(c, "a1");
            expect(h, "a1");
            send(h, "h1");
            expect(c, "h1");
            // Comme le recepteur du moteur : un thread attend le cadre suivant.
            AtomicReference<Throwable> end = new AtomicReference<>();
            Thread reader = new Thread(() -> {
                try { c.recvHeader(); end.set(new AssertionError("cadre inattendu")); } catch (Throwable e) { end.set(e); }
            }, "recepteur");
            reader.setDaemon(true);
            reader.start();
            Thread.sleep(300);
            proxy.stall();
            for (int i = 0; i < 3; i++) send(c, "bloque" + i);   // un depot part et reste sans reponse
            waitFor(() -> proxy.swallowed() > 0, 20_000, "requete en attente sur le serveur muet");
            Thread.sleep(500);
            // Arret de l'agent (changement de canal, relance) : la session doit se fermer sans attendre le serveur.
            long t0 = System.nanoTime();
            Thread closer = new Thread(() -> { c.discardOutbound(); c.close(); }, "fermeture");
            closer.setDaemon(true);
            closer.start();
            closer.join(100_000);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            check(!closer.isAlive(), "fermeture toujours bloquee apres " + ms + " ms sur un serveur muet");
            reader.join(15_000);
            check(!reader.isAlive(), "le recepteur reste bloque apres la fermeture");
            say("serveur muet : abandon + fermeture de la session en " + ms + " ms");
            check(ms < 60_000, "fermeture trop lente sur un serveur muet: " + ms + " ms");
        } finally {
            h.close();
            c.close();
            hs.close();
            cs.close();
            proxy.close();
            dav.stop();
        }
    }

    /** Relais TCP devant un serveur de test ; stall() : les connexions restent ouvertes mais plus rien ne passe. */
    private static final class StallProxy implements AutoCloseable {
        private final ServerSocket srv;
        private final int target;
        private volatile boolean stalled, closed;
        private final AtomicInteger swallowed = new AtomicInteger();
        private final List<Socket> socks = new CopyOnWriteArrayList<>();

        StallProxy(int target) throws IOException {
            this.target = target;
            this.srv = new ServerSocket(0, 50, InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 }));
            Thread t = new Thread(() -> {
                while (!closed) {
                    try {
                        Socket in = srv.accept();
                        Socket out = new Socket(InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 }), this.target);
                        socks.add(in);
                        socks.add(out);
                        pipe(in, out, true);
                        pipe(out, in, false);
                    } catch (IOException e) {
                        if (closed) return;
                    }
                }
            }, "relais-muet");
            t.setDaemon(true);
            t.start();
        }

        int port() { return srv.getLocalPort(); }
        void stall() { stalled = true; }
        int swallowed() { return swallowed.get(); }

        private void pipe(Socket from, Socket to, boolean request) {
            Thread t = new Thread(() -> {
                byte[] buf = new byte[8192];
                try {
                    InputStream is = from.getInputStream();
                    OutputStream os = to.getOutputStream();
                    int n;
                    while ((n = is.read(buf)) >= 0) {
                        if (stalled) { if (request) swallowed.addAndGet(n); continue; }
                        os.write(buf, 0, n);
                        os.flush();
                    }
                } catch (IOException e) {
                    // connexion fermee
                }
                if (!stalled) {
                    try { to.close(); } catch (IOException ignore) { }
                }
            }, "relais-muet-tuyau");
            t.setDaemon(true);
            t.start();
        }

        @Override public void close() {
            closed = true;
            try { srv.close(); } catch (IOException ignore) { }
            for (Socket s : socks) { try { s.close(); } catch (IOException ignore) { } }
        }
    }

    // =======================================================================
    //  Outils
    // =======================================================================

    private static final class Tee extends OutputStream {
        private final OutputStream a, b;
        Tee(OutputStream a, OutputStream b) { this.a = a; this.b = b; }
        @Override public synchronized void write(int c) throws IOException { a.write(c); b.write(c); }
        @Override public synchronized void write(byte[] buf, int off, int len) throws IOException { a.write(buf, off, len); b.write(buf, off, len); }
        @Override public void flush() throws IOException { a.flush(); }
    }

    private static byte[] text(int n, int seed) {
        StringBuilder sb = new StringBuilder();
        Random r = new Random(seed);
        while (sb.length() < n) sb.append("ligne ").append(r.nextInt(100000)).append(" du fichier ").append(seed).append('\n');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void put(Path dir, String rel, byte[] data) throws IOException {
        Path f = dir.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.write(f, data);
    }

    /** Fichiers d'un dossier synchronise (hors .cotransfert) : chemin relatif -> contenu. */
    private static Map<String, byte[]> tree(Path root) throws IOException {
        Map<String, byte[]> out = new TreeMap<>();
        if (!Files.isDirectory(root)) return out;
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path p, BasicFileAttributes a) {
                return p.getFileName().toString().equals(".cotransfert") ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path p, BasicFileAttributes a) throws IOException {
                out.put(root.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
                return FileVisitResult.CONTINUE;
            }
        });
        return out;
    }

    private static boolean sameTree(Map<String, byte[]> a, Map<String, byte[]> b) {
        if (!a.keySet().equals(b.keySet())) return false;
        for (Map.Entry<String, byte[]> e : a.entrySet()) if (!Arrays.equals(e.getValue(), b.get(e.getKey()))) return false;
        return true;
    }

    private static List<Path> files(Path root) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(root)) return out;
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult visitFile(Path p, BasicFileAttributes a) { out.add(p); return FileVisitResult.CONTINUE; }
                @Override public FileVisitResult visitFileFailed(Path p, IOException e) { return FileVisitResult.CONTINUE; }
            });
        } catch (IOException e) {
            return out;
        }
        return out;
    }

    private static boolean noConflict(Path root) {
        for (Path p : files(root)) if (p.getFileName().toString().contains(".conflict-peer-")) return false;
        return true;
    }

    /** Nombre de fichiers de lot sous ce dossier (sous-dossiers compris). */
    private static int lotFiles(Path root) {
        int n = 0;
        for (Path p : files(root)) if (p.getFileName().toString().endsWith(".lot")) n++;
        return n;
    }

    private static int lots(List<String> paths) {
        int n = 0;
        for (String p : paths) if (p.endsWith(".lot")) n++;
        return n;
    }

    private static int drimeLots(FakeDrimeServer srv) {
        int n = 0;
        for (Object it : srv.entries()) if (it.toString().contains(".lot")) n++;
        return n;
    }

    private static boolean containsBytes(Path root, byte[] needle) throws IOException {
        for (Path p : files(root)) {
            byte[] b = Files.readAllBytes(p);
            outer:
            for (int i = 0; i + needle.length <= b.length; i++) {
                for (int j = 0; j < needle.length; j++) if (b[i + j] != needle[j]) continue outer;
                return true;
            }
        }
        return false;
    }

    private static int count(String s, String sub) {
        int n = 0;
        for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + sub.length())) n++;
        return n;
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

    private static void fail(String msg) { throw new AssertionError(msg); }
}
