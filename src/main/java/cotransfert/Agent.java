package cotransfert;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * CoTransfert - agent de synchronisation bidirectionnelle entre un HUB et un
 * CLIENT relies par un lien reseau direct (cable Thunderbolt/USB4, Ethernet...),
 * sans droits admin, sans service, sans pilote, sans install native.
 *
 * Livre en .jar, execute par un runtime Java deja present sur le poste :
 * l'executable est java.exe (signe), le .jar n'est que de la donnee -> il passe
 * la ou un binaire natif non signe est bloque (EDR, AppLocker...).
 *
 * Config persistante : cotransfert.conf, place a cote du jar. Il memorise le
 * role, la politique de conflit et LE OU LES dossiers a synchroniser (apparies
 * entre postes par un NOM logique). Les exclusions sont dans
 * <dossier>/.cotransfert/.syncignore (synchronise, 100% iso par defaut).
 *
 * Canal de transfert : UN SEUL a la fois, choisi par l'utilisateur dans la page
 * (cle channel=), le meme sur les deux postes. Aucun canal n'est choisi par
 * defaut et il n'y a AUCUNE bascule automatique : si le canal choisi tombe,
 * l'agent attend son retour et le dit.
 *   - aucun    : page seule sur la boucle locale ; ni synchronisation, ni ecoute,
 *                ni decouverte ;
 *   - "direct" : le hub ecoute et diffuse son beacon, le client le decouvre et
 *                ouvre la connexion TCP (il attend le hub indefiniment) ;
 *   - "webdav" : lots chiffres deposes dans un dossier WebDAV en ligne ;
 *   - "drime"  : meme chose par l'API du stockage Drime ;
 *   - "disque" : meme chose dans un dossier accessible aux deux postes
 *                (partage, cle USB ou disque deplace d'un poste a l'autre).
 * Pour webdav, drime et disque, il n'y a ni ecoute, ni decouverte, ni connexion
 * au pair : chaque poste ouvre tout de suite une session par lots
 * (MailboxSession) directement sur le magasin du canal (RelayStore), y depose
 * ses lots et y lit ceux de l'autre, sans attendre que l'autre poste soit la.
 * webdav et drime exigent un secret partage. "valise" reste un echange ponctuel
 * a la main (--export / --import d'une archive).
 *
 * Changement de canal : le dernier canal utilise par dossier est note dans
 * <dossier>/.cotransfert/mailbox/canal.properties (cle "canal"). Voir
 * {@link #prepareChannel} et {@link #directSession} pour les regles (aucune
 * perte, aucun rejeu).
 *
 * Point d'entree unique : CoTransfert.cmd, qui lance "javaw -jar cotransfert.jar"
 * sans console puis se termine. L'agent sert toujours l'IHM web et ouvre la
 * page ; tout s'y regle (role, dossiers, secret, canaux, choix du canal). Sans
 * role ou sans dossier : page seule sur la boucle locale. Les options ci-dessous
 * restent valables.
 *
 * Sans console : ce que l'agent ecrivait a l'ecran va dans cotransfert.log
 * ({@link Journal}) ; une erreur fatale au demarrage s'affiche dans une petite
 * boite de dialogue ({@link #fatal}) ; l'agent s'arrete par le bouton "Quitter"
 * de la page. Redemarrage (reglages enregistres, changement de canal, mise a
 * jour) : un seul endroit, {@link #restart}, qui passe par {@link Relance} ;
 * avec CoTransfert.cmd --console (COTRANSFERT_CONSOLE=1) l'agent sort avec le
 * code 75 et c'est le script qui le relance. Mise a jour de l'autre poste par
 * le canal choisi, lien direct compris : {@link Updater}.
 *
 * Exemples :
 *   java -jar cotransfert.jar                 (lit cotransfert.conf ; sans reglage, ouvre la page)
 *   java -jar cotransfert.jar --role hub --dir MonProjet=C:\work\MonProjet --channel direct
 *   java -jar cotransfert.jar --relay "*:\CoTransfertRelais" --channel disque
 *   java -jar cotransfert.jar --webdav-url https://cloud.example.org/dav/CoTransfertRelais
 *                             --webdav-user utilisateur --webdav-password mot-de-passe-d-application
 *                             --channel webdav
 *   java -jar cotransfert.jar --export valise.zip   /   --import valise.zip
 *   java -jar cotransfert.jar --role client --bench --gib 8
 */
public final class Agent {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** Session par lots interrompue : pause avant le nouvel essai, doublee a chaque echec rapproche. */
    private static final long RETRY_MIN_MS = 5_000, RETRY_MAX_MS = 300_000;
    /** Une session par lots qui a tenu plus longtemps que cela remet la pause au minimum. */
    private static final long RETRY_RESET_MS = 120_000;
    /** Au demarrage, attente maximale par dossier du nettoyage de l'ancien magasin (au mieux, jamais bloquant). */
    static final long CLEAN_WAIT_MS = 4_000;

    public static void log(String fmt, Object... args) {
        System.out.println(LocalTime.now().format(TS) + " " + String.format(fmt, args));
    }

    /** Posee par CoTransfert.cmd --console : une console est la, et c'est le script qui relance sur le code 75. */
    private static final String ENV_CONSOLE = Relance.ENV_CONSOLE;
    /** Arguments de ce lancement, repris tels quels au redemarrage. */
    private static volatile String[] launchArgs = new String[0];

    private static boolean consoleMode() { return "1".equals(System.getenv(ENV_CONSOLE)); }

    /** Vrai si une vraie console est attachee (java lance a la main dans un terminal). */
    private static boolean hasConsole() {
        java.io.Console c = System.console();
        if (c == null) return false;
        try {
            // JDK 22+ : une console peut exister meme quand les flux sont rediriges ; isTerminal() dit la verite.
            return Boolean.TRUE.equals(java.io.Console.class.getMethod("isTerminal").invoke(c));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true;
        }
    }

    /**
     * Arret sur une erreur que l'utilisateur doit voir. Le message va dans le
     * journal (et a l'ecran s'il y en a un) ; sans console, il est aussi montre
     * dans une petite boite de dialogue, sauf quand c'est la relance qui essaie
     * une nouvelle version (elle attend le code de sortie pour revenir en arriere).
     * Sans affichage possible : journal seulement.
     */
    static void fatal(int code, String message) {
        System.out.println(message);
        if (!consoleMode() && !hasConsole() && !"1".equals(System.getenv(Relance.ENV_TRIAL))) {
            Path j = Journal.path();
            DesktopSupport.error(message + (j == null ? "" : "\n\nJournal : " + j));
        }
        System.exit(code);
    }

    /**
     * Redemarrage de l'agent : le seul endroit par lequel passent les reglages
     * enregistres, le changement de canal et la mise a jour.
     *   - CoTransfert.cmd --console : code 75, le script relance l'agent ;
     *   - sinon : le processus de relance est lance depuis une copie du jar, puis
     *     l'agent s'arrete (code 0). La relance applique une mise a jour en attente
     *     et relance l'agent avec les memes arguments ;
     *   - hors d'un jar (classes) : code 75, a relancer a la main.
     */
    static void restart(String why) {
        Path jar = Updater.runningJar();
        // Demander un redemarrage prouve que cette version marche : fin de l'essai.
        if (jar != null) Updater.confirm(jar);
        if (consoleMode()) {
            log("%s : arret avec le code 75, CoTransfert.cmd --console relance l'agent", why);
            System.exit(SetupApi.EXIT_RESTART);
        }
        if (jar == null) {
            log("%s : arret avec le code 75. Hors d'un jar il n'y a pas de relance automatique : relance l'agent a la main.", why);
            System.exit(SetupApi.EXIT_RESTART);
        }
        try {
            Relance.spawn(jar, launchArgs, Journal.path());
            log("%s : redemarrage (processus de relance lance)", why);
            System.exit(0);
        } catch (IOException | RuntimeException e) {
            fatal(SetupApi.EXIT_RESTART, "CoTransfert doit redemarrer (" + why + ") mais le redemarrage automatique a echoue ("
                    + e.getMessage() + "). Relance CoTransfert.cmd.");
        }
    }

    // --- etat partage par dossier (un seul moteur a la fois par dossier) ------

    /** Session directe en cours par dossier (cote hub : une reconnexion remplace l'ancienne). */
    private static final Map<String, Transfer.Session> ACTIVE = new ConcurrentHashMap<>();
    /** Verrou par dossier : garantit qu'un seul SyncEngine tourne sur un dossier. */
    private static final Map<String, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private static ReentrantLock lock(String name) { return LOCKS.computeIfAbsent(name, k -> new ReentrantLock()); }

    public static void main(String[] args) {
        launchArgs = args.clone();
        try {
            run(args);
        } catch (Throwable t) {
            t.printStackTrace();
            fatal(2, "CoTransfert s'est arrete sur une erreur inattendue : " + t);
        }
    }

    private static void run(String[] args) throws Exception {
        boolean bench = has(args, "--bench");
        long gib = Long.parseLong(arg(args, "--gib", "4"));

        Path confPath = resolveConfigPath(arg(args, "--config", ""));
        // Modes ponctuels (mesure de debit, valise, generation d'un secret) : a l'ecran, sans journal.
        boolean oneShot = bench || has(args, "--new-secret") || !arg(args, "--export", "").isEmpty() || !arg(args, "--import", "").isEmpty();
        final Path jar = Updater.runningJar();
        Updater.Version version = null;
        if (!oneShot) {
            Journal.start(confPath, consoleMode() || hasConsole());
            try {
                if (jar != null) version = Updater.versionOf(jar);
            } catch (IOException | RuntimeException e) {
                // version inconnue : la mise a jour sera indisponible
            }
            log("==== CoTransfert demarre le %s (%s, processus %d) ====", java.time.LocalDate.now(),
                    version == null ? "version inconnue, hors jar" : "version du " + version.label(), ProcessHandle.current().pid());
            if (jar != null) {
                // Cette version a tenu 20 s : elle demarre, le temoin d'essai d'une mise a jour est retire.
                // Au passage, les copies temporaires des relances terminees sont supprimees.
                Thread t = new Thread(() -> {
                    Relance.cleanTemp();
                    sleep(20_000);
                    Updater.confirm(jar);
                    sleep(40_000);
                    Relance.cleanTemp();
                }, "cotp-essai-ok");
                t.setDaemon(true);
                t.start();
            }
        }
        // Un fichier de configuration illisible ne doit pas passer pour un poste jamais regle
        // (l'enregistrement suivant l'ecraserait).
        if (Files.exists(confPath)) {
            try {
                Files.readAllLines(confPath);
            } catch (IOException | RuntimeException e) {
                fatal(2, "Le fichier de configuration est illisible (" + e.getClass().getSimpleName() + ") : " + confPath
                        + ". Corrige-le ou supprime-le (il doit etre en texte UTF-8), puis relance CoTransfert.");
            }
        }
        Config cfg = Config.load(confPath);

        // Les arguments CLI ont priorite sur le fichier de config.
        boolean confChanged = false;
        if (!arg(args, "--role", "").isEmpty()) cfg.role = arg(args, "--role", "").toLowerCase();
        if (!arg(args, "--on-conflict", "").isEmpty()) cfg.conflict = arg(args, "--on-conflict", "");
        if (has(args, "--master")) cfg.master = Boolean.TRUE;
        if (has(args, "--slave")) cfg.master = Boolean.FALSE;
        if (!arg(args, "--relay", "").isEmpty()) { cfg.relay = arg(args, "--relay", ""); confChanged = true; }
        if (!arg(args, "--secret", "").isEmpty()) { cfg.secret = arg(args, "--secret", ""); confChanged = true; }
        if (!arg(args, "--discovery", "").isEmpty()) { cfg.discovery = arg(args, "--discovery", "").toLowerCase(); confChanged = true; }
        String chArg = arg(args, "--channel", "").trim().toLowerCase();
        if (!chArg.isEmpty()) {
            if (chArg.equals("aucun")) cfg.channel = "";
            else if (!Config.channelId(chArg).isEmpty()) cfg.channel = chArg;
            else fatal(2, "Canal inconnu : " + chArg + ". Attendu : --channel direct|webdav|drime|disque (ou aucun).");
            confChanged = true;
        }
        if (has(args, "--channels"))
            log("--channels n'existe plus (plus de bascule automatique) : option ignoree. Choisis UN canal dans la page, ou --channel direct|webdav|drime|disque");
        if (!arg(args, "--webdav-url", "").isEmpty()) { cfg.webdavUrl = arg(args, "--webdav-url", "").trim(); confChanged = true; }
        if (!arg(args, "--webdav-user", "").isEmpty()) { cfg.webdavUser = arg(args, "--webdav-user", "").trim(); confChanged = true; }
        if (!arg(args, "--webdav-password", "").isEmpty()) { cfg.webdavPassword = arg(args, "--webdav-password", "").trim(); confChanged = true; }
        if (!arg(args, "--drime-token", "").isEmpty()) { cfg.drimeToken = arg(args, "--drime-token", "").trim(); confChanged = true; }
        if (!arg(args, "--drime-folder", "").isEmpty()) { cfg.drimeFolder = arg(args, "--drime-folder", "").trim(); confChanged = true; }
        if (!arg(args, "--proxy", "").isEmpty()) { cfg.proxy = arg(args, "--proxy", "").trim(); confChanged = true; }
        if (!arg(args, "--truststore", "").isEmpty()) { cfg.tlsTruststore = arg(args, "--truststore", "").trim().toLowerCase(); confChanged = true; }
        if (has(args, "--update-auto")) { cfg.updateAuto = true; confChanged = true; }
        if (has(args, "--new-secret")) {
            cfg.secret = Crypto.generateSecret();
            cfg.save(confPath);
            System.out.println("Secret genere et enregistre dans " + confPath);
            System.out.println("Recopie cette ligne dans cotransfert.conf de l'autre poste :");
            System.out.println("  secret=" + cfg.secret);
            return;
        }

        // --dir name=path (ou --dir path) ajoute/maj un dossier et persiste la conf.
        for (String d : allArgs(args, "--dir")) {
            String name, path;
            int eq = d.indexOf('=');
            if (eq > 0) { name = d.substring(0, eq); path = d.substring(eq + 1); }
            else { path = d; name = Paths.get(d).getFileName().toString(); }
            cfg.roots.put(name, path);
            confChanged = true;
        }
        if (confChanged || !Files.exists(confPath)) cfg.save(confPath);

        final boolean master = (cfg.master != null) ? cfg.master : "hub".equals(cfg.role);
        final SyncEngine.ConflictPolicy policy = parsePolicy(cfg.conflict);
        final String host = hostname();
        final String role = cfg.role;
        final String channel = Config.channelId(cfg.channel);
        int webPort = Integer.parseInt(arg(args, "--web-port", "47790"));
        boolean noWeb = has(args, "--no-web");
        final boolean lan = "lan".equals(cfg.discovery);
        String exportZip = arg(args, "--export", "");
        String importZip = arg(args, "--import", "");
        // La page est toujours servie, sauf --no-web et les modes ponctuels (mesure de debit, valise).
        final boolean web = !noWeb && !bench && exportZip.isEmpty() && importZip.isEmpty();
        // Un secret court se casse hors ligne : on impose la longueur d'un secret genere.
        String blocked = "";
        if (!cfg.secret.isBlank() && cfg.secret.trim().length() < 16) {
            if (!web) fatal(2, "Secret partage trop court (16 caracteres minimum). Genere-en un : --new-secret");
            blocked = "secret partage trop court (16 caracteres minimum)";
        }
        final Crypto.Key key = Crypto.derive(cfg.secret);
        Status.get().setHost(host);
        Status.get().setRole(role);
        Status.get().setSelectedChannel(channel);

        // --- Valise : echange ponctuel par archive, sans aucune connexion -------
        if (!exportZip.isEmpty() || !importZip.isEmpty()) {
            if (!"hub".equals(role) && !"client".equals(role))
                fatal(2, "Valise : le role (hub ou client) doit etre defini (--role ou cotransfert.conf).");
            if (cfg.roots.isEmpty()) fatal(2, "Valise : aucun dossier a synchroniser. Ajoute-en un : --dir Nom=C:\\chemin");
            MailboxSession.Codec codec = codec(key, "valise", role);
            if (!importZip.isEmpty()) Valise.importFrom(cfg.roots, role, master, policy, Paths.get(importZip), codec);
            if (!exportZip.isEmpty()) Valise.exportTo(cfg.roots, role, master, policy, Paths.get(exportZip), codec);
            return;
        }

        // --- IHM web et reglages : toujours la, meme sans aucune configuration ----
        final boolean configured = cfg.configured() && blocked.isEmpty();
        Status.get().setConfigured(configured);
        // Partage clavier-souris (optionnel, lien direct et secret requis) : etat pose avant de servir la page.
        if (!bench) configureKm(cfg, configured ? channel : "", key != null);
        // Etat des canaux pose AVANT de servir la page : chaque dossier regle y figure avec ses
        // quatre canaux des la premiere lecture de /api/status, meme sans canal choisi.
        final Channels channels = new Channels(cfg, key != null);
        final List<String> sensitive = List.of(cfg.secret, cfg.webdavPassword, cfg.drimeToken);
        Status.get().setSecrets(sensitive);
        Journal.setSecrets(sensitive);
        Updater updater = null;
        final Path majRoot = MajRoutes.rootFor(confPath);
        if (!bench) {
            for (Map.Entry<String, String> e : cfg.roots.entrySet()) {
                Status.get().setPath(e.getKey(), e.getValue());
                // Conflits en attente : listes et resolus depuis la page, qu'une session tourne ou non.
                try { Conflicts.register(e.getKey(), Paths.get(e.getValue())); } catch (RuntimeException bad) { /* chemin invalide : signale ailleurs */ }
                Status.get().setChannelPlan(e.getKey(), channel, channels.off(e.getKey()));
            }
            // Reglages proxy et certificats : avant toute activite reseau, et seulement pour un canal en ligne.
            if (configured && key != null && ("webdav".equals(channel) || "drime".equals(channel)))
                HttpSupport.init(cfg.tlsTruststore, cfg.proxy);
            // Mise a jour par le canal : etat pose avant de servir la page, meme quand elle est indisponible.
            // Canal par lots : le magasin du canal. Lien direct : le dossier local du hub, que le
            // client lit par des requetes sortantes vers la page du hub (meme port sur les deux postes).
            final int hubWebPort = webPort;
            java.util.function.Supplier<RelayStore> updateStores = !"direct".equals(channel) ? () -> channels.updateStore(channel)
                    : "hub".equals(role) ? () -> MajRoutes.localStore(majRoot)
                    : () -> new HubHttpRelayStore(Agent::knownHub, hubWebPort);
            updater = new Updater(jar, version, "hub".equals(role), key, updateStores,
                    updateProblem(jar, version, configured, channel, key != null, channels, "hub".equals(role), web),
                    Status.get()::setUpdate, rolledBackEnv());
            updater.setSecrets(sensitive);
            updater.auto = cfg.updateAuto;
            updater.restart = () -> restart("mise a jour telechargee");
        }
        if (web) {
            // Hub regle avec un canal choisi : toutes les interfaces (etat lisible depuis le
            // client). Sinon (premier reglage, client, aucun canal) : boucle locale seule.
            SetupApi api = new SetupApi(confPath, host, Agent::exitFromPage);
            api.updater = updater;
            api.webPort = webPort;
            // Hub sur le lien direct, mise a jour disponible : il sert sa publication au client (routes /maj/).
            MajRoutes maj = configured && "hub".equals(role) && "direct".equals(channel) && key != null
                    && updater != null && updater.supported() ? new MajRoutes(majRoot, key) : null;
            boolean started = startWeb(webPort, !(configured && "hub".equals(role) && !channel.isEmpty()),
                    api, maj, !has(args, "--no-browser"));
            if (!configured) {
                if (!started) fatal(2, "La page de CoTransfert n'a pas pu demarrer sur le port " + webPort
                        + " : sans elle, ce poste ne peut pas etre regle. Voir le journal.");
                // Premier reglage : ni synchronisation, ni decouverte, ni ecoute reseau.
                if (!blocked.isEmpty()) log("demarrage impossible : %s. A corriger dans la page : %s", blocked, pageUrl(webPort));
                else log("CoTransfert n'est pas encore regle : choisis le role et les dossiers dans la page %s (aucune synchronisation d'ici la)", pageUrl(webPort));
                idle();
            }
        }

        if (!"hub".equals(role) && !"client".equals(role)) usage();
        final boolean hub = "hub".equals(role);

        // --- Mesure de debit : lien direct seul, sans dossier ni canal choisi -------
        if (bench) {
            if (hub) {
                startHubDiscovery(host, lan);
                Transfer.benchServer();
            } else {
                String hubIp = new HubLocator(host, arg(args, "--peer", cfg.peer), lan, key).locate(0);
                Transfer.benchClient(hubIp, gib << 30);
            }
            return;
        }

        if (cfg.roots.isEmpty()) fatal(2, "Aucun dossier a synchroniser. Ajoute-en un : --dir Nom=C:\\chemin");

        // --- Canal de transfert : un seul, celui que l'utilisateur a choisi ---------
        if (channel.isEmpty()) {
            // Aucun canal choisi : la page seule. Ni synchronisation, ni ecoute, ni decouverte.
            String why = "Aucun canal de transfert n'est choisi : rien n'est synchronise, rien n'ecoute et rien n'est cherche sur le reseau.";
            if (!web) fatal(2, why + " Choisis-en un : --channel direct|webdav|drime|disque");
            log("%s Choisis le canal dans la page %s (le meme sur les deux postes).", why, pageUrl(webPort));
            idle();
        }

        log("%s %s : pret (canal=%s, maitre=%s, conflits=%s, dossiers=%s, chiffrement=%s)",
                role, host, channel, master, policy, cfg.roots.keySet(), key != null ? "oui" : "non");

        // Client : verification de la mise a jour au demarrage puis periodique, quel que soit le canal.
        if (updater != null) updater.start();
        if ("direct".equals(channel)) {
            if (hub) hubDirect(cfg, host, lan, channels, master, policy, key, web);
            else clientDirect(cfg, host, arg(args, "--peer", cfg.peer), lan, channels, master, policy, key);
            return;
        }

        // --- Canal par lots (webdav, drime, disque) : ni ecoute, ni decouverte, ni connexion au pair ---
        // (Reglages proxy et certificats deja poses plus haut, avant de servir la page.)
        log("%s : canal %s -> ni ecoute de synchronisation, ni decouverte ; une session par lots par dossier, tout de suite",
                role, channel);
        List<Thread> threads = new ArrayList<>();
        for (Map.Entry<String, String> e : cfg.roots.entrySet()) {
            final String name = e.getKey();
            final String local = e.getValue();
            String problem = channels.problem(channel, name);
            if (!problem.isEmpty()) {
                // Canal choisi mais inutilisable pour ce dossier : etat "down" deja pose, la page reste la.
                log("%s[%s]: canal %s inutilisable (%s) : ce dossier n'est pas synchronise. A corriger dans la page.",
                        role, name, channel, channels.why(channel, name));
                continue;
            }
            prepareChannel(name, role, Paths.get(local), channel, channels, CLEAN_WAIT_MS);
            Thread th = new Thread(() -> batchLoop(name, local, role, channel, channels, master, policy, key), "cotp-lots-" + name);
            th.setDaemon(false);
            threads.add(th);
            th.start();
        }
        for (Thread th : threads) th.join();   // ne revient que si aucun dossier n'a de session
        if (web) idle();                       // la page reste la pour corriger les reglages
        fatal(2, "Canal " + channel + " inutilisable : aucun dossier n'est synchronise (voir le journal).");
    }

    /** Date de construction (ms) de la version que la relance vient de retirer avant ce demarrage, ou 0. */
    private static long rolledBackEnv() {
        try {
            String v = System.getenv(Relance.ENV_ROLLED_BACK);
            return v == null || v.isBlank() ? 0 : Long.parseLong(v.trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    static final String NO_SECRET_DIRECT = "secret partage manquant : sur le lien direct, la mise a jour demande"
            + " le meme secret partage sur les deux postes";

    /** Pourquoi la mise a jour par le canal est indisponible sur ce poste, en une phrase pour la page ; "" sinon. */
    static String updateProblem(Path jar, Updater.Version version, boolean configured, String channel,
                                        boolean hasSecret, Channels channels, boolean hub, boolean web) {
        // Raisons courtes : la page les affiche apres "Mise a jour indisponible pour l'instant : ".
        if (jar == null || version == null) return Updater.NOT_A_JAR;
        if (!configured) return "ce poste n'est pas encore regle";
        if (channel.isEmpty()) return "aucun canal par lots n'est choisi";
        if ("direct".equals(channel)) {
            // Le lien direct accepte une synchronisation sans secret ; la mise a jour, non : elle est scellee avec lui.
            if (!hasSecret) return NO_SECRET_DIRECT;
            if (hub && !web) return "la page du hub est desactivee (--no-web) : c'est elle qui sert la mise a jour au client sur le lien direct";
            return "";
        }
        if (!hasSecret) return "secret partage manquant";
        String why = channels.updateProblem(channel);
        return why.isEmpty() ? "" : "canal " + channel + " inutilisable (" + why + ")";
    }

    private static void usage() {
        System.out.println("CoTransfert - usage :");
        System.out.println("  java -jar cotransfert.jar [--config <fichier>]");
        System.out.println("       [--role hub|client] [--master|--slave]");
        System.out.println("       [--on-conflict keep-both|master-wins|newer-wins]");
        System.out.println("       [--dir Nom=C:\\chemin] (repetable ; persiste dans cotransfert.conf)");
        System.out.println("       [--channel direct|webdav|drime|disque|aucun]   (UN canal, le meme sur les deux postes ; aucun par defaut)");
        System.out.println("       [--peer <nom ou ip>] [--discovery link|lan]");
        System.out.println("       [--secret <secret partage>] [--new-secret]");
        System.out.println("       [--webdav-url <https://...>] [--webdav-user <identifiant>] [--webdav-password <mot de passe d'application>]");
        System.out.println("       [--drime-token <jeton>] [--drime-folder <dossier de transit>]");
        System.out.println("       [--relay <dossier relais | *:\\Dossier>]   (reglage du canal disque)");
        System.out.println("       [--proxy system|none|hote:port] [--truststore java|windows]");
        System.out.println("       [--export <valise.zip>] [--import <valise.zip>]");
        System.out.println("       [--web-port <n>] [--no-web] [--no-browser] [--bench [--gib N]]");
        System.out.println("       [--update-auto]   (client : installer tout seul la version publiee par le hub)");
        System.out.println("Sans --role, le role est lu dans cotransfert.conf (a cote du jar).");
        System.out.println("Sans --no-web, tout se regle dans la page ouverte par l'agent (aucune option n'est requise).");
        fatal(2, "CoTransfert : le role de ce poste (hub ou client) n'est pas regle. Retire --no-web pour le regler dans la page, ou ajoute --role hub|client.");
    }

    // =======================================================================
    //  IHM web : demarrage, instance unique, navigateur, arret demande par la page
    // =======================================================================

    /** Posee par la relance (ou par CoTransfert.cmd --console apres un code 75) : la page ouverte se reconnecte seule. */
    private static final String ENV_RESTARTED = Relance.ENV_RESTARTED;

    private static String pageUrl(int port) { return "http://localhost:" + port + "/"; }

    /**
     * Demarre l'IHM et ouvre la page. Port deja pris par un CoTransfert : on montre
     * la page existante et on sort (code 0, pas de second agent) ; par autre chose :
     * code 2. @return faux si l'IHM n'a pas pu demarrer pour une autre raison.
     */
    private static boolean startWeb(int port, boolean loopbackOnly, SetupApi api, MajRoutes maj, boolean browser) {
        try {
            WebServer.start(port, loopbackOnly, api, maj);
        } catch (java.net.BindException e) {
            if (isCoTransfert(port)) {
                System.out.println("CoTransfert tourne deja sur ce poste : " + pageUrl(port) + " (pas de second agent).");
                if (browser) DesktopSupport.openBrowser(pageUrl(port));
                System.exit(0);
            }
            fatal(2, "Le port " + port + " est deja pris par un autre programme : la page de CoTransfert ne peut pas demarrer. "
                    + "Ferme ce programme, ou choisis un autre port : --web-port <n> (ou --no-web pour s'en passer).");
        } catch (IOException | RuntimeException e) {
            log("IHM web non demarree (%s)", e.getMessage());
            return false;
        }
        if (loopbackOnly) {
            log("IHM web : %s  (ce poste uniquement)", pageUrl(port));
        } else {
            String ip = Discovery.localApipaIps().stream().findFirst().orElse("127.0.0.1");
            log("IHM web : http://localhost:%d  (depuis le client : http://%s:%d ; reglages reserves a ce poste)", port, ip, port);
        }
        // Une fois par lancement ; pas apres une relance demandee par la page, qui est deja ouverte.
        if (browser && !"1".equals(System.getenv(ENV_RESTARTED))) {
            Thread t = new Thread(() -> {
                if (!DesktopSupport.openBrowser(pageUrl(port))) log("navigateur non ouvert : ouvre %s a la main", pageUrl(port));
            }, "cotp-navigateur");
            t.setDaemon(true);
            t.start();
        }
        return true;
    }

    /** Vrai si un agent CoTransfert repond deja sur ce port (son /api/status contient "self"). */
    private static boolean isCoTransfert(int port) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) java.net.URI.create("http://127.0.0.1:" + port + "/api/status")
                    .toURL().openConnection(java.net.Proxy.NO_PROXY);
            c.setConnectTimeout(2000);
            c.setReadTimeout(3000);
            if (c.getResponseCode() != 200) return false;
            try (java.io.InputStream in = c.getInputStream()) {
                Object o = DrimeRelayStore.parseJson(new String(in.readNBytes(1 << 20), StandardCharsets.UTF_8));
                return o instanceof Map<?, ?> m && m.get("self") instanceof Map;
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Arret demande par la page : 75 = redemarrer (reglages, canal ou mise a jour), 0 = quitter. */
    private static void exitFromPage(int code) {
        if (code == SetupApi.EXIT_RESTART) restart("redemarrage demande depuis la page (reglages, canal ou mise a jour)");
        log("arret demande depuis la page");
        System.exit(code);
    }

    /** Reste en vie sans rien faire d'autre que servir la page. */
    private static void idle() {
        while (true) sleep(60_000);
    }

    // =======================================================================
    //  Canaux regles et magasins par dossier
    // =======================================================================

    /** Vrai pour un canal par lots (webdav, drime, disque). */
    static boolean isBatch(String id) {
        return "webdav".equals(id) || "drime".equals(id) || "disque".equals(id);
    }

    /**
     * Canaux regles d'apres la configuration, et fabrique des magasins. Un canal
     * par lots est utilisable s'il est regle et, pour webdav et drime, si un secret
     * partage existe (tout ce qui transite en ligne est chiffre). Les clients HTTP
     * sont crees a la demande et partages par tous les dossiers du processus ;
     * chaque session a son propre magasin.
     */
    static final class Channels {
        private final Config cfg;
        private final boolean hasSecret;
        /** Regle enfreinte par l'adresse WebDAV (jamais une valeur saisie), null si elle est acceptable. */
        private final String webdavBad;
        private java.net.http.HttpClient http, httpNoRedirect;

        Channels(Config cfg, boolean hasSecret) {
            this.cfg = cfg;
            this.hasSecret = hasSecret;
            String bad = null;
            if (cfg.webdavConfigured()) {
                try {
                    new WebDavRelayStore(cfg.webdavUrl, cfg.webdavUser, cfg.webdavPassword, http()).close();
                } catch (IllegalArgumentException e) {
                    bad = String.valueOf(e.getMessage());
                }
            }
            this.webdavBad = bad;
        }

        /**
         * Pourquoi ce canal n'est pas utilisable pour ce dossier, en deux mots pour la
         * page : "non configure", "secret partage manquant", "adresse invalide" ; ""
         * s'il est utilisable. Le lien direct n'a rien a regler.
         */
        String problem(String id, String rootName) {
            return switch (id == null ? "" : id) {
                case "direct" -> "";
                case "webdav" -> !cfg.webdavConfigured() ? "non configure" : !hasSecret ? "secret partage manquant"
                        : webdavBad != null ? "adresse invalide" : "";
                case "drime" -> !cfg.drimeConfigured() ? "non configure" : !hasSecret ? "secret partage manquant" : "";
                case "disque" -> cfg.diskConfigured(rootName) ? "" : "non configure";
                default -> "canal inconnu";
            };
        }

        /** Comme {@link #problem}, avec la regle enfreinte quand il y en a une (pour le journal et l'essai de canal). */
        String why(String id, String rootName) {
            String p = problem(id, rootName);
            return "adresse invalide".equals(p) && webdavBad != null ? webdavBad : p;
        }

        /** Canaux non utilisables pour ce dossier et pourquoi (pour l'IHM). */
        Map<String, String> off(String rootName) {
            Map<String, String> m = new java.util.LinkedHashMap<>();
            for (String id : Config.KNOWN_CHANNELS) {
                String p = problem(id, rootName);
                if (!p.isEmpty()) m.put(id, p);
            }
            return m;
        }

        /**
         * Dossier relais qui porte la mise a jour sur le canal disque : le relais par
         * defaut ; a defaut, celui du premier dossier (par ordre de nom logique, le
         * meme sur les deux postes) qui en a un en propre. "" si aucun.
         */
        private String updateRelay() {
            if (!cfg.relay.isBlank()) return cfg.relay;
            for (String n : new java.util.TreeSet<>(cfg.relays.keySet()))
                if (cfg.roots.containsKey(n) && !cfg.relays.get(n).isBlank()) return cfg.relays.get(n);
            return "";
        }

        /** Comme {@link #why}, pour la mise a jour (qui ne depend d'aucun dossier synchronise). */
        String updateProblem(String id) {
            if ("disque".equals(id)) return updateRelay().isBlank() ? "non configure" : "";
            return isBatch(id) ? why(id, "") : "pas un canal par lots";
        }

        /**
         * Magasin du canal par lots pour la mise a jour : le meme magasin que les
         * sessions, a la racine du dossier de transit (les fichiers _maj/ y sont
         * hors des dossiers synchronises). L'appelant le garde et le ferme.
         */
        RelayStore updateStore(String id) {
            return "disque".equals(id) ? new DirRelayStore(updateRelay()) : store(id, "");
        }

        /** Magasin neuf d'un canal par lots utilisable ({@link #problem} vide). L'appelant le ferme. */
        RelayStore store(String id, String rootName) {
            return switch (id) {
                case "webdav" -> new WebDavRelayStore(cfg.webdavUrl, cfg.webdavUser, cfg.webdavPassword, http());
                case "drime" -> new DrimeRelayStore(cfg.drimeUrl, cfg.drimeToken, cfg.drimeFolder, cfg.drimeWorkspace, httpNoRedirect());
                case "disque" -> new DirRelayStore(cfg.relayFor(rootName));
                default -> throw new IllegalArgumentException("pas un canal par lots: " + id);
            };
        }

        /** Libere les clients HTTP (essai de canal depuis la page) ; les magasins ne servent plus ensuite. */
        synchronized void shutdown() {
            if (httpNoRedirect != null) httpNoRedirect.shutdownNow();
            if (http != null) http.shutdownNow();
            httpNoRedirect = null;
            http = null;
        }

        private synchronized java.net.http.HttpClient http() {
            if (http == null) http = HttpSupport.client(cfg.proxy);
            return http;
        }

        /** Meme client sans suivi des redirections : le magasin Drime les suit lui-meme, sans son jeton. */
        private synchronized java.net.http.HttpClient httpNoRedirect() {
            if (httpNoRedirect == null) {
                java.net.http.HttpClient src = http();
                java.net.http.HttpClient.Builder b = java.net.http.HttpClient.newBuilder().version(src.version())
                        .followRedirects(java.net.http.HttpClient.Redirect.NEVER);
                src.connectTimeout().ifPresent(b::connectTimeout);
                src.proxy().ifPresent(b::proxy);
                httpNoRedirect = b.build();
            }
            return httpNoRedirect;
        }
    }

    // =======================================================================
    //  Changement de canal : dernier canal utilise par dossier, aucune perte
    // =======================================================================
    //
    // Le spool (<dossier>/.cotransfert/mailbox) porte le flux de lots sortant
    // (lots scelles gardes jusqu'a leur acquittement) et la position de lecture
    // dans le flux du pair (lu.pos). Ce flux est independant du magasin.
    //
    //   lots -> lots (ex. webdav -> drime), au demarrage : le spool est garde tel
    //     quel. Les lots non acquittes sont redeposes dans le nouveau magasin et la
    //     lecture reprend au lot attendu (lu.pos inchange). On retire au mieux nos
    //     propres lots de l'ancien magasin : ils restent dans le spool, donc rien
    //     n'est perdu, et un magasin abandonne ne garde pas de vieux lots.
    //
    //   lots -> direct : RIEN n'est abandonne au demarrage. Les lots non acquittes
    //     ne sont abandonnes qu'au moment ou le manifeste du pair arrive par le lien
    //     direct : c'est la reconciliation du lien direct qui resynchronise tout,
    //     et elle seule. Abandonner avant (cable jamais branche, puis retour a un
    //     canal par lots) ferait disparaitre des modifications en silence : le
    //     moteur, sur un canal par lots, ne renvoie pas ce que son index dit deja
    //     envoye. lu.pos n'est jamais touche : si le pair a lui aussi reinitialise
    //     son flux, la lecture se recale sur son nouveau lot 1 ; sinon elle
    //     continue la ou elle en etait.
    //
    //   direct -> lots : rien a faire, le spool a ete vide par la session directe
    //     (ou n'a jamais servi).
    //
    // "canal" dans canal.properties = dernier canal REELLEMENT utilise : un canal
    // par lots des que sa session demarre, "direct" seulement apres une
    // reconciliation directe. Les autres cles du fichier (ancienne bascule
    // automatique) sont ignorees.

    private static final String CHANNEL_FILE = "canal.properties";
    /** Un seul changement de canal a la fois (deux sessions directes peuvent se chevaucher cote hub). */
    private static final Object SWITCH_LOCK = new Object();
    /** Chemin du manifeste dans un cadre OP_FILE (meme sentinelle que SyncEngine). */
    private static final String MANIFEST_PATH = "\u0000MANIFEST";

    static Path spoolOf(Path local) { return local.resolve(".cotransfert").resolve("mailbox"); }

    /** Dernier canal utilise par ce dossier d'apres son spool : un canal connu, ou "" (inconnu, jamais note). */
    static String recordedChannel(Path spool) {
        Path f = spool.resolve(CHANNEL_FILE);
        if (!Files.isRegularFile(f)) return "";
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(f)) {
            p.load(in);
        } catch (IOException | RuntimeException e) {
            return "";
        }
        return Config.channelId(p.getProperty("canal", ""));
    }

    private static boolean recordChannel(Path spool, String id) {
        try {
            Files.createDirectories(spool);
            Properties p = new Properties();
            p.setProperty("canal", id);
            Path tmp = spool.resolve(CHANNEL_FILE + ".part");
            try (OutputStream out = Files.newOutputStream(tmp)) {
                p.store(out, "CoTransfert - dernier canal utilise par ce dossier");
            }
            MailboxSession.moveAtomic(tmp, spool.resolve(CHANNEL_FILE));
            return true;
        } catch (IOException | RuntimeException e) {
            log("canal: %s non enregistre dans %s (%s)", id, spool, e.getMessage());
            return false;
        }
    }

    /**
     * A appeler au demarrage, par dossier, AVANT d'ouvrir une session par lots sur
     * le canal choisi. Ne supprime jamais rien du dossier synchronise ni du spool.
     * Si le dernier canal utilise etait un autre canal par lots, retire au mieux
     * nos propres lots de l'ancien magasin (attente bornee par cleanWaitMs), puis
     * note le canal choisi. Sans effet pour "direct" (voir {@link #directSession})
     * et quand aucun canal n'est choisi.
     */
    static void prepareChannel(String name, String side, Path local, String selected, Channels channels, long cleanWaitMs) {
        if (!isBatch(selected)) return;
        synchronized (SWITCH_LOCK) {
            Path spool = spoolOf(local);
            String prev = recordedChannel(spool);
            if (selected.equals(prev)) return;
            if (isBatch(prev)) {
                log("%s[%s]: changement de canal %s -> %s : les lots non acquittes restent dans le spool et sont redeposes dans le nouveau canal",
                        side, name, prev, selected);
                cleanOwnDirection(name, side, prev, channels, cleanWaitMs);
            } else if ("direct".equals(prev)) {
                log("%s[%s]: changement de canal direct -> %s", side, name, selected);
            }
            recordChannel(spool, selected);
        }
    }

    /**
     * Enveloppe une session du lien direct. Tant que le dernier canal utilise par
     * ce dossier n'est pas "direct", l'arrivee du manifeste du pair declenche, une
     * seule fois, l'abandon des lots non acquittes du spool (le flux sortant est
     * reinitialise), le nettoyage au mieux de l'ancien magasin, puis note "direct".
     * Un dossier qui n'a jamais eu de spool est rendu tel quel.
     */
    static Transfer.Session directSession(String name, String side, Path local, Transfer.Session s, Channels channels) {
        final Path spool = spoolOf(local);
        if (!Files.isDirectory(spool) || "direct".equals(recordedChannel(spool))) return s;
        return new Transfer.Session() {
            private boolean done;
            @Override public void sendFile(Transfer.Msg m, InputStream src) throws IOException { s.sendFile(m, src); }
            @Override public Transfer.Msg recvHeader() throws IOException {
                Transfer.Msg m = s.recvHeader();
                if (!done && (m.op == Transfer.OP_MANIFEST || (m.op == Transfer.OP_FILE && MANIFEST_PATH.equals(m.relPath)))) {
                    done = true;
                    leaveBatch(name, side, spool, channels);
                }
                return m;
            }
            @Override public void copyContentTo(Transfer.Msg m, OutputStream dst) throws IOException { s.copyContentTo(m, dst); }
            @Override public void close() { s.close(); }
            @Override public boolean interactive() { return s.interactive(); }
            @Override public String channel() { return s.channel(); }
        };
    }

    /** Le lien direct reconcilie ce dossier : les lots du spool n'ont plus lieu d'etre. */
    private static void leaveBatch(String name, String side, Path spool, Channels channels) {
        synchronized (SWITCH_LOCK) {
            String prev = recordedChannel(spool);
            if ("direct".equals(prev)) return;
            log("%s[%s]: le lien direct reconcilie ce dossier : abandon des lots non acquittes de l'ancien canal%s",
                    side, name, prev.isEmpty() ? "" : " (" + prev + ")");
            if (!discardSpool(name, side, spool)) return;   // on reessaiera a la prochaine session directe
            recordChannel(spool, "direct");
            if (isBatch(prev)) cleanOwnDirection(name, side, prev, channels, 0);
        }
    }

    /**
     * Abandonne les lots sortants du spool sans toucher a aucun magasin : une
     * session ephemere sur un magasin toujours absent, puis
     * {@link MailboxSession#discardOutbound} (qui reinitialise le flux sortant).
     * La position de lecture (lu.pos) n'est pas modifiee.
     */
    private static boolean discardSpool(String name, String side, Path spool) {
        try {
            MailboxSession mb = new MailboxSession(name, side, spool, new NoStore(), null);
            try {
                mb.discardOutbound();
            } finally {
                mb.close();
            }
            return true;
        } catch (IOException | RuntimeException e) {
            log("%s[%s]: spool illisible, lots non abandonnes (%s)", side, name, e.getMessage());
            return false;
        }
    }

    /** Magasin toujours absent : rien n'y est lu, ecrit ni supprime. */
    private static final class NoStore implements RelayStore {
        @Override public String id() { return "spool"; }
        @Override public String describe() { return "changement de canal, aucun magasin"; }
        @Override public boolean available() { return false; }
        @Override public String lastError() { return "changement de canal"; }
        @Override public List<Entry> list(String dir) throws IOException { throw new IOException("aucun magasin"); }
        @Override public byte[] read(String path, int maxBytes) throws IOException { throw new IOException("aucun magasin"); }
        @Override public void write(String path, Path src) throws IOException { throw new IOException("aucun magasin"); }
        @Override public void write(String path, byte[] data) throws IOException { throw new IOException("aucun magasin"); }
        @Override public void delete(String path) throws IOException { throw new IOException("aucun magasin"); }
    }

    /**
     * Retire, au mieux, nos propres lots de l'ancien magasin s'il est encore regle
     * et joignable : uniquement notre sens (les lots du pair et les acquittements
     * ne sont pas touches), dans un fil a part. waitMs &gt; 0 : on attend la fin au
     * plus ce temps ; au-dela le nettoyage continue en arriere-plan. Un echec est
     * sans consequence : ces lots restent dans le spool tant qu'ils ne sont pas
     * acquittes.
     */
    private static void cleanOwnDirection(String name, String side, String oldId, Channels channels, long waitMs) {
        if (!channels.problem(oldId, name).isEmpty()) {
            log("%s[%s]: ancien canal %s plus regle, son magasin n'est pas nettoye", side, name, oldId);
            return;
        }
        Thread t = new Thread(() -> {
            try (RelayStore st = channels.store(oldId, name)) {
                if (!st.available()) {
                    log("%s[%s]: ancien canal %s injoignable, son magasin n'est pas nettoye", side, name, oldId);
                    return;
                }
                String dir = MailboxSession.safeName(name) + "/" + MailboxSession.direction(side);
                int n = 0;
                for (RelayStore.Entry e : st.list(dir)) {
                    if (e.name().endsWith(".part") || MailboxSession.lotSeq(e.name()) > 0) {
                        st.delete(dir + "/" + e.name());
                        n++;
                    }
                }
                log("%s[%s]: ancien canal %s nettoye (%d lot(s) retire(s) de notre sens)", side, name, oldId, n);
            } catch (IOException | RuntimeException e) {
                log("%s[%s]: nettoyage de l'ancien canal %s incomplet (%s)", side, name, oldId, e.getMessage());
            }
        }, "cotp-menage-" + name);
        t.setDaemon(true);
        t.start();
        if (waitMs <= 0) return;
        try {
            t.join(waitMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (t.isAlive()) log("%s[%s]: nettoyage de l'ancien canal %s toujours en cours, on n'attend pas", side, name, oldId);
    }

    // =======================================================================
    //  Canal "direct" : le hub ecoute, le client decouvre et se connecte
    // =======================================================================

    private static void startHubDiscovery(String host, boolean lan) throws IOException {
        Discovery disc = new Discovery(host);
        disc.setLanDiscovery(lan);
        disc.startBeacon("hub");
        Thread t = new Thread(() -> {
            try { disc.serveHubDiscovery(p -> { }); } catch (Exception e) { log("hub discovery: %s", e); }
        }, "cotp-hub-disc");
        t.setDaemon(true);
        t.start();
    }

    /** Lit les reglages "km.*" et prepare le partage clavier-souris ; sans effet s'il est desactive ou inutilisable. */
    private static void configureKm(Config cfg, String channel, boolean hasSecret) {
        try {
            List<String> problems = new ArrayList<>();
            KmSettings s = KmSettings.from(cfg.km, problems);
            for (String p : problems) log("config: %s -> valeur par defaut utilisee", p);
            Km.get().configure(s, "hub".equals(cfg.role), channel, hasSecret);
            Km.get().start();
        } catch (RuntimeException | LinkageError e) {
            log("km: partage clavier-souris indisponible sur ce poste (%s)", e.getClass().getSimpleName());
        }
    }

    /**
     * Cote hub : si le premier cadre d'une connexion entrante ouvre un lien
     * clavier-souris, le confie a km (bloque tant que le lien vit, puis ferme la
     * session) et rend vrai. Sinon ne touche a rien et rend faux.
     */
    static boolean serveKm(Km km, Transfer.Session s, Transfer.Msg first, boolean encrypted) {
        if (!KmLink.isHello(first)) return false;
        try {
            if (s instanceof Transfer.TcpSession t) km.serveHub(first, t.rawIn(), t.rawOut(), s::close, encrypted);
            else log("hub: lien clavier-souris refuse (hors lien direct)");
        } catch (RuntimeException | LinkageError e) {
            log("hub: lien clavier-souris interrompu (%s)", e.getClass().getSimpleName());
        } finally {
            s.close();
        }
        return true;
    }

    private static void hubDirect(Config cfg, String host, boolean lan, Channels channels, boolean master,
                                  SyncEngine.ConflictPolicy policy, Crypto.Key key, boolean web) throws IOException {
        final Status st = Status.get();
        final Map<String, String> roots = cfg.roots;
        startHubDiscovery(host, lan);
        log("hub : au 1er lancement, accepter l'alerte pare-feu (Reseaux publics).");
        for (String n : roots.keySet()) st.setChannelState(n, "direct", Status.CH_WAITING, "client non connecte");
        try {
            // Chaque connexion entrante porte un HELLO nommant le dossier a synchroniser.
            Transfer.serveHub(s -> {
                String name = null;
                try {
                    Transfer.Msg hello = s.recvHeader();
                    // Connexion dediee au partage clavier-souris : elle quitte ici le protocole de synchronisation.
                    if (KmLink.isHello(hello) && serveKm(Km.get(), s, hello, key != null)) return;
                    if (hello.op != Transfer.OP_HELLO) { log("hub: handshake invalide, connexion fermee"); s.close(); return; }
                    if (hello.hash != Transfer.PROTO_VERSION) {
                        log("hub: client en protocole v%d, attendu v%d -> mets a jour le jar du client (connexion fermee)", hello.hash, Transfer.PROTO_VERSION);
                        s.close(); return;
                    }
                    name = hello.relPath;
                    String local = roots.get(name);
                    if (local == null) {
                        log("hub: dossier inconnu demande par le pair: %s (ajoute-le a cotransfert.conf)", name);
                        name = null;
                        s.close(); return;
                    }
                    // Une reconnexion remplace la session precedente de ce dossier (connexion morte).
                    Transfer.Session prev = ACTIVE.put(name, s);
                    if (prev != null) prev.close();
                    ReentrantLock l = lock(name);
                    l.lock();
                    try {
                        st.setChannelState(name, "direct", Status.CH_ACTIVE, "");
                        new SyncEngine(Paths.get(local), master, policy)
                                .run(directSession(name, "hub", Paths.get(local), s, channels), "hub[" + name + "]", name);
                    } finally {
                        l.unlock();
                    }
                } catch (Exception e) {
                    log("hub: session interrompue (%s)", e.getMessage());
                } finally {
                    s.close();
                    if (name != null) {
                        ACTIVE.remove(name, s);
                        if (ACTIVE.get(name) == null) st.setChannelState(name, "direct", Status.CH_WAITING, "client non connecte");
                    }
                }
            }, key);
        } catch (IOException e) {
            if (!web) throw e;
            // Port de synchronisation indisponible : la page reste la pour le dire.
            log("hub : ecoute de synchronisation impossible sur le port %d (%s)", Transfer.PORT, e.getMessage());
            for (String n : roots.keySet())
                st.setChannelState(n, "direct", Status.CH_DOWN, "ecoute impossible sur le port " + Transfer.PORT);
            idle();
        }
    }

    private static void clientDirect(Config cfg, String host, String forced, boolean lan, Channels channels, boolean master,
                                     SyncEngine.ConflictPolicy policy, Crypto.Key key) throws InterruptedException {
        final HubLocator locator = new HubLocator(host, forced, lan, key);
        directLocator = locator;
        // Partage clavier-souris : sa propre connexion sortante vers le hub, a l'adresse trouvee par la synchronisation.
        try {
            if (Km.get().usable()) {
                Thread km = new Thread(() -> Km.get().clientLoop(Agent::knownHub, Transfer.PORT, key), "cotp-km");
                km.setDaemon(true);
                km.start();
            }
        } catch (LinkageError e) {
            // JRE sans module graphique : pas de partage clavier-souris, la synchronisation continue
        }
        // Un fil par dossier ; chacun attend le hub indefiniment (aucun repli sur un autre canal).
        List<Thread> threads = new ArrayList<>();
        for (Map.Entry<String, String> e : cfg.roots.entrySet()) {
            final String name = e.getKey();
            final String local = e.getValue();
            Thread th = new Thread(() -> clientDirectLoop(name, local, locator, channels, master, policy, key), "cotp-client-" + name);
            th.setDaemon(false);
            threads.add(th);
            th.start();
        }
        for (Thread th : threads) th.join();
    }

    /** Boucle d'un dossier cote client sur le lien direct : trouver le hub, se connecter, recommencer. */
    private static void clientDirectLoop(String name, String local, HubLocator locator, Channels channels,
                                         boolean master, SyncEngine.ConflictPolicy policy, Crypto.Key key) {
        final Status st = Status.get();
        st.setChannelState(name, "direct", Status.CH_WAITING, "recherche du hub");
        while (true) {
            String hub = locator.locate(0);   // sans delai : on attend le hub indefiniment
            if (hub == null) {
                String why = locator.problem();
                if (why.isEmpty()) st.setChannelState(name, "direct", Status.CH_WAITING, "hub introuvable");
                else st.setChannelState(name, "direct", Status.CH_DOWN, why);
                sleep(5000);
                continue;
            }
            boolean established = Transfer.dialOnce(hub, s -> {
                try {
                    Transfer.Msg hello = new Transfer.Msg();
                    hello.op = Transfer.OP_HELLO; hello.relPath = name; hello.size = 0;
                    hello.hash = Transfer.PROTO_VERSION;
                    s.sendFile(hello, null);
                    st.setChannelState(name, "direct", Status.CH_ACTIVE, "");
                    new SyncEngine(Paths.get(local), master, policy)
                            .run(directSession(name, "client", Paths.get(local), s, channels), "client[" + name + "]", name);
                } catch (Exception ex) {
                    log("client[%s]: session interrompue (%s)", name, ex.getMessage());
                } finally {
                    s.close();
                }
            }, key, 2000);
            if (established) {
                st.setChannelState(name, "direct", Status.CH_WAITING, "connexion fermee, reconnexion");
                log("client[%s]: session directe fermee, reconnexion...", name);
            } else {
                st.setChannelState(name, "direct", Status.CH_WAITING, "hub injoignable");
                locator.invalidate(hub);
            }
            sleep(500);   // lien direct : on retente vite, la decouverte donne le rythme
        }
    }

    // =======================================================================
    //  Canaux par lots : une session directement sur le magasin du canal
    // =======================================================================

    /**
     * Une session par lots sur le magasin d'un canal : MailboxSession construite
     * directement sur le RelayStore, moteur de synchronisation, etats pour la
     * page. Le magasin appartient a cette session et est ferme avec elle. Identique
     * cote hub et cote client : aucun des deux n'attend l'autre pour deposer.
     */
    static final class BatchRun implements AutoCloseable {
        final MailboxSession session;
        private final RelayStore store;
        private final String statusKey, side;
        private final Path local;

        private BatchRun(MailboxSession session, RelayStore store, String statusKey, String side, Path local) {
            this.session = session;
            this.store = store;
            this.statusKey = statusKey;
            this.side = side;
            this.local = local;
        }

        /**
         * @param name      nom logique du dossier (identique sur les deux postes)
         * @param statusKey nom du dossier dans Status (le nom logique, sauf essais a deux postes dans un processus)
         * @param store     magasin du canal choisi ; ferme par {@link #close}, ou ici si l'ouverture echoue
         * @param tuning    options de session (essais), ou null ; codec et info sont poses ici
         */
        static BatchRun open(String name, String statusKey, String side, Path local, RelayStore store,
                             Crypto.Key key, MailboxSession.Options tuning) throws IOException {
            final Status st = Status.get();
            MailboxSession.Options o = tuning != null ? tuning : new MailboxSession.Options();
            o.codec = codec(key, name, side);
            o.info = line -> {
                st.setChannelInfo(statusKey, line);
                publish(statusKey, store, line);
            };
            try {
                // Secret change depuis le scellement des lots en attente : le pair ne pourra
                // jamais les ouvrir. On les abandonne ; l'echange des manifestes de la
                // nouvelle session renvoie ce qui manque au pair.
                if (MailboxSession.outboundStale(spoolOf(local), codec(key, name, MailboxSession.other(side)))) {
                    log("%s[%s]: les lots en attente ont ete scelles avec un autre secret : abandonnes, la synchro reprend du debut",
                            side, name);
                    MailboxSession old = new MailboxSession(name, side, spoolOf(local), store, o);
                    old.discardOutbound();
                    old.close();
                }
                return new BatchRun(new MailboxSession(name, side, spoolOf(local), store, o), store, statusKey, side, local);
            } catch (IOException | RuntimeException e) {
                store.close();
                throw e;
            }
        }

        /**
         * Etat du canal d'apres la ligne d'information de la session : "down" avec la
         * raison quand le magasin est absent, indisponible ou inaccessible, "active"
         * sinon (magasin joignable : les lots y sont deposes et lus).
         */
        private static void publish(String statusKey, RelayStore store, String line) {
            String id = store.id();
            boolean absent = line.startsWith(id + " absent");
            if (!absent && !line.startsWith(id + " indisponible") && !line.startsWith(id + " inaccessible")) {
                Status.get().setChannelState(statusKey, id, Status.CH_ACTIVE, "");
                return;
            }
            String why = store.lastError();
            if (why == null || why.isBlank()) why = absent ? "support absent" : "magasin inaccessible";
            if ("disque".equals(id) && "support absent".equals(why)) why = "disque absent";
            Status.get().setChannelState(statusKey, id, Status.CH_DOWN, why);
        }

        /** Fait tourner le moteur sur la session ; bloque jusqu'a sa fin (fermeture, ou erreur du flux). */
        void sync(boolean master, SyncEngine.ConflictPolicy policy) {
            new SyncEngine(local, master, policy).run(session, side + "[" + statusKey + "]", statusKey);
        }

        @Override public void close() {
            session.close();
            store.close();
            Status.get().setChannelInfo(statusKey, "");
        }
    }

    /**
     * Boucle d'un dossier sur un canal par lots, identique cote hub et cote client.
     * Le magasin injoignable n'arrete pas la session : elle reessaie d'elle-meme et
     * les lots attendent dans le spool. Si la session s'arrete quand meme (spool
     * illisible, lot du pair inutilisable, pair redemarre au milieu d'un cadre...),
     * on en rouvre une apres une pause, indefiniment, sans jamais changer de canal.
     */
    private static void batchLoop(String name, String local, String side, String id, Channels channels,
                                  boolean master, SyncEngine.ConflictPolicy policy, Crypto.Key key) {
        final Status st = Status.get();
        long pause = RETRY_MIN_MS;
        while (true) {
            long t0 = System.currentTimeMillis();
            String err = "", why = "";
            try (BatchRun run = BatchRun.open(name, name, side, Paths.get(local), channels.store(id, name), key, null)) {
                ReentrantLock l = lock(name);
                l.lock();
                try {
                    run.sync(master, policy);
                } finally {
                    l.unlock();
                    why = run.session.fatalError();
                }
            } catch (IOException | RuntimeException e) {
                err = " (" + e.getMessage() + ")";
            }
            if (System.currentTimeMillis() - t0 > RETRY_RESET_MS) pause = RETRY_MIN_MS;
            st.setChannelState(name, id, Status.CH_DOWN, (why.isBlank()
                    ? "session interrompue (voir le journal)" : why) + ", nouvel essai dans " + pause / 1000 + " s");
            log("%s[%s]: session %s interrompue%s, nouvel essai dans %d s", side, name, id, err, pause / 1000);
            sleep(pause);
            pause = Math.min(pause * 2, RETRY_MAX_MS);
        }
    }

    /**
     * Localisation du hub, partagee par tous les dossiers : adresse forcee
     * (--peer / peer=), sinon decouverte. Refuse un hub hors lien direct sans
     * secret partage.
     */
    /** Localisation du hub en service sur le lien direct (client), ou null. */
    private static volatile HubLocator directLocator;

    /**
     * Adresse du hub telle que la session directe l'utilise en ce moment, ou null
     * tant qu'il n'est pas localise. Sert a la mise a jour par le lien direct.
     */
    static String knownHub() {
        HubLocator l = directLocator;
        return l == null ? null : l.known();
    }

    private static final class HubLocator {
        private final String forced;
        private final Discovery disc;
        private final Crypto.Key key;
        private volatile String cached;
        private boolean warned;
        private volatile String problem = "";

        HubLocator(String host, String forced, boolean lan, Crypto.Key key) {
            this.forced = forced == null ? "" : forced.trim();
            this.key = key;
            if (this.forced.isEmpty()) {
                disc = new Discovery(host);
                disc.setLanDiscovery(lan);
                disc.startBeacon("client");
                log("client %s : recherche du hub (decouverte %s)...", host, lan ? "lien direct + reseau local" : "lien direct");
            } else {
                disc = null;
                log("client %s : hub force = %s", host, this.forced);
            }
        }

        /** @param timeoutMs 0 = attendre indefiniment. @return hote du hub, ou null. */
        synchronized String locate(long timeoutMs) {
            if (cached != null) return cached;
            String found;
            if (disc == null) {
                found = forced;
            } else {
                Discovery.Peer p = timeoutMs <= 0 ? disc.resolveHub() : disc.resolveHub(timeoutMs);
                if (p == null) return null;
                found = p.ip;
                log("client: hub resolu = %s (%s) via interface locale %s", p.ip, p.host, p.localIp);
            }
            if (key == null) {
                try {
                    if (Crypto.requiresSecret(InetAddress.getByName(found))) {
                        if (!warned) {
                            log("client: %s n'est pas sur un lien direct : un secret partage est obligatoire.", found);
                            log("        Lance --new-secret sur un poste et recopie la ligne secret= dans cotransfert.conf de l'autre.");
                            warned = true;
                        }
                        problem = "secret partage obligatoire (le hub n'est pas sur un lien direct)";
                        return null;
                    }
                } catch (IOException e) {
                    return null; // nom non resolu : hub injoignable pour l'instant
                }
            }
            problem = "";
            cached = found;
            return found;
        }

        /** Adresse du hub si elle est connue maintenant, sinon null. Ne bloque jamais (lu hors verrou). */
        String known() { return cached; }

        /** Pourquoi le hub trouve ne peut pas servir, "" sinon (hub pas encore trouve compris). */
        String problem() { return problem; }

        synchronized void invalidate(String host) {
            if (host != null && host.equals(cached)) cached = null;
        }
    }

    /**
     * Adaptateur de chiffrement des lots : la donnee authentifiee lie chaque lot
     * a son dossier et a son sens, pour qu'un lot ne puisse pas etre rejoue ailleurs.
     */
    static MailboxSession.Codec codec(Crypto.Key key, String scope, String side) {
        if (key == null) return null;
        final String other = "hub".equals(side) ? "client" : "hub";
        final byte[] aadOut = (scope + "|" + side).getBytes(StandardCharsets.UTF_8);
        final byte[] aadIn = (scope + "|" + other).getBytes(StandardCharsets.UTF_8);
        return new MailboxSession.Codec() {
            @Override public byte[] seal(long seq, byte[] plain) throws IOException { return Crypto.seal(key, seq, aadOut, plain); }
            @Override public byte[] open(long seq, byte[] sealed) throws IOException { return Crypto.open(key, seq, aadIn, sealed); }
        };
    }

    private static SyncEngine.ConflictPolicy parsePolicy(String v) {
        if (v == null) return SyncEngine.ConflictPolicy.KEEP_BOTH;
        switch (v.toLowerCase()) {
            case "master-wins": return SyncEngine.ConflictPolicy.MASTER_WINS;
            case "newer-wins":  return SyncEngine.ConflictPolicy.NEWER_WINS;
            default:            return SyncEngine.ConflictPolicy.KEEP_BOTH;
        }
    }

    /** cotransfert.conf a cote du jar (ou chemin --config, ou dossier courant en repli). */
    private static Path resolveConfigPath(String override) {
        if (!override.isEmpty()) return Paths.get(override);
        try {
            Path loc = Paths.get(Agent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path base = Files.isDirectory(loc) ? loc : loc.getParent();
            return base.resolve("cotransfert.conf");
        } catch (Exception e) {
            return Paths.get("cotransfert.conf").toAbsolutePath();
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { /* reveil volontaire */ }
    }

    // --- helpers args --------------------------------------------------------

    private static String arg(String[] a, String key, String def) {
        for (int i = 0; i < a.length - 1; i++) if (a[i].equals(key)) return a[i + 1];
        return def;
    }

    private static List<String> allArgs(String[] a, String key) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length - 1; i++) if (a[i].equals(key)) out.add(a[i + 1]);
        return out;
    }

    private static boolean has(String[] a, String key) {
        for (String s : a) if (s.equals(key)) return true;
        return false;
    }

    private static String hostname() {
        try { return java.net.InetAddress.getLocalHost().getHostName(); }
        catch (Exception e) { return "?"; }
    }
}
