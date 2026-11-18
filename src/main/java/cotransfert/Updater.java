package cotransfert;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

/**
 * Mise a jour de l'autre poste par le canal de transfert.
 *
 * VERSION d'un jar : la date de construction (horodatage de son entree
 * META-INF/MANIFEST.MF, tel qu'il est ecrit dans le jar, donc identique sur
 * tous les postes quel que soit leur fuseau) plus son empreinte SHA-256.
 * Aucune etape de build en plus. Hors d'un jar (classes, essais) : version
 * inconnue, mise a jour desactivee.
 *
 * DANS LE MAGASIN du canal choisi, hors des dossiers synchronises :
 *   _maj/version.scelle          fiche "CTMAJ1 <dateMs> <taille> <sha256 hex>"
 *   _maj/cotransfert.jar.scelle  le jar
 * Canal par lots (webdav, drime, disque) : a la racine du dossier de transit.
 * Lien direct : dans le dossier cotransfert.maj du hub, que le hub sert en HTTP
 * au client ({@link MajRoutes}, {@link HubHttpRelayStore}) ; le client ne fait
 * que des requetes sortantes vers le hub.
 * Les deux sont scelles avec le secret partage (meme chiffrement authentifie
 * que les lots), avec une donnee associee qui leur est propre ("maj|fiche",
 * "maj|jar") : un lot ne peut pas passer pour une mise a jour, ni l'inverse.
 * Un fichier qui ne s'ouvre pas avec le secret est refuse : il ne vient pas de
 * l'autre poste.
 *
 * Le HUB publie (sur demande) et n'installe jamais. Le CLIENT verifie (au
 * demarrage, toutes les 10 minutes, sur demande), installe (sur demande, ou
 * tout seul si update.auto=true) et ne publie jamais. Sont refuses : ce qui ne
 * s'ouvre pas, une version plus ancienne ou egale, une taille ou une empreinte
 * differente de la fiche, ce qui n'est pas un jar dont la classe principale est
 * cotransfert.Agent, et une version marquee "refusee" apres un retour arriere.
 *
 * A COTE DU JAR (cotransfert.jar) :
 *   cotransfert.jar.nouveau    version telechargee et verifiee, en attente d'echange
 *   cotransfert.jar.precedent  copie de la version remplacee (pour le retour arriere)
 *   cotransfert.jar.essai      temoin : la version en place n'a pas encore prouve qu'elle demarre
 *   cotransfert.maj-refusee    empreintes des versions qui n'ont pas demarre sur ce poste
 *
 * ECHANGE ({@link #applyPending}, par la relance ou par CoTransfert.cmd) et
 * RETOUR ARRIERE ({@link #rollback}) : chaque etape est une copie vers un nom
 * temporaire puis un renommage atomique, dans un ordre tel qu'il existe a tout
 * instant un cotransfert.jar complet. Voir les commentaires des deux methodes
 * pour l'etat laisse par un arret a chaque etape.
 */
final class Updater {

    static final String DIR = "_maj";
    static final String FICHE_PATH = DIR + "/version.scelle";
    static final String JAR_PATH = DIR + "/cotransfert.jar.scelle";
    static final String MAGIC = "CTMAJ1";
    static final String MAIN_CLASS = "cotransfert.Agent";
    /** Taille maximale d'un jar publie ou installe (le jar pese quelques centaines de Kio). */
    static final long MAX_JAR_BYTES = 12L << 20;
    /** Taille maximale d'une fiche scellee (elle pese une centaine d'octets). */
    static final int FICHE_MAX_BYTES = 4096;
    static final String SUFFIX_NEW = ".nouveau", SUFFIX_PREV = ".precedent", SUFFIX_TRIAL = ".essai";
    static final String REFUSED_FILE = "cotransfert.maj-refusee";
    private static final int REFUSED_MAX = 20;

    private static final byte[] AAD_FICHE = "maj|fiche".getBytes(StandardCharsets.UTF_8);
    private static final byte[] AAD_JAR = "maj|jar".getBytes(StandardCharsets.UTF_8);
    private static final Pattern FICHE = Pattern.compile(MAGIC + " (\\d{1,15}) (\\d{1,10}) ([0-9a-f]{64})");
    private static final DateTimeFormatter LABEL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // =======================================================================
    //  Version
    // =======================================================================

    /** Version d'un jar : date de construction (ms), taille en octets, empreinte SHA-256 en hexadecimal. */
    record Version(long dateMs, long size, String sha256) {

        /** "2026-11-16 16:41" : la date telle qu'elle est ecrite dans le jar. */
        String label() { return label(dateMs); }

        static String label(long dateMs) {
            return LABEL.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(dateMs), ZoneOffset.UTC));
        }

        String fiche() { return MAGIC + " " + dateMs + " " + size + " " + sha256; }

        static Version parse(String fiche) throws IOException {
            Matcher m = FICHE.matcher(fiche == null ? "" : fiche.trim());
            if (!m.matches()) throw new IOException("fiche de version illisible");
            return new Version(Long.parseLong(m.group(1)), Long.parseLong(m.group(2)), m.group(3));
        }
    }

    /** Le jar en cours d'execution, ou null si l'agent ne tourne pas depuis un jar. */
    static Path runningJar() {
        try {
            Path loc = Paths.get(Updater.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isRegularFile(loc) ? loc.toAbsolutePath() : null;
        } catch (Exception e) {
            return null;
        }
    }

    static String sha256(byte[] b) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
            StringBuilder sb = new StringBuilder(64);
            for (byte x : d) sb.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible dans ce JDK", e);
        }
    }

    /**
     * Version d'un jar CoTransfert. Refuse ce qui n'est pas un jar lisible dont la
     * classe principale est cotransfert.Agent.
     */
    static Version versionOf(Path jar) throws IOException {
        return inspect(jar, Files.readAllBytes(jar));
    }

    /** Comme {@link #versionOf}, pour un fichier dont on tient deja le contenu. */
    private static Version inspect(Path file, byte[] bytes) throws IOException {
        long date;
        try (JarFile jf = new JarFile(file.toFile(), false)) {
            Manifest mf = jf.getManifest();
            String main = mf == null ? null : mf.getMainAttributes().getValue("Main-Class");
            if (main == null || !MAIN_CLASS.equals(main.trim()))
                throw new IOException("ce n'est pas un jar CoTransfert (classe principale inattendue)");
            ZipEntry e = jf.getEntry(JarFile.MANIFEST_NAME);
            LocalDateTime t = e == null ? null : e.getTimeLocal();
            if (t == null) throw new IOException("jar sans date de construction");
            date = t.toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (java.util.zip.ZipException | SecurityException e) {
            throw new IOException("ce n'est pas un jar lisible");
        }
        return new Version(date, bytes.length, sha256(bytes));
    }

    // =======================================================================
    //  Dans le magasin : publier, lire la fiche, telecharger
    // =======================================================================

    /** Depose le jar puis la fiche (dans cet ordre), scelles. Remplace la version publiee precedente. */
    static Version publish(RelayStore store, Crypto.Key key, Path jar) throws IOException {
        if (key == null) throw new IOException("secret partage manquant");
        byte[] bytes = Files.readAllBytes(jar);
        if (bytes.length > MAX_JAR_BYTES) throw new IOException("jar trop volumineux");
        Version v = inspect(jar, bytes);
        store.write(JAR_PATH, Crypto.seal(key, 0, AAD_JAR, bytes));
        store.write(FICHE_PATH, Crypto.seal(key, 0, AAD_FICHE, v.fiche().getBytes(StandardCharsets.US_ASCII)));
        return v;
    }

    /** Version publiee dans le magasin, ou null si rien n'y est publie. Une fiche qui ne s'ouvre pas est refusee. */
    static Version published(RelayStore store, Crypto.Key key) throws IOException {
        if (key == null) throw new IOException("secret partage manquant");
        byte[] sealed = store.read(FICHE_PATH, FICHE_MAX_BYTES);
        if (sealed == null) return null;
        return openFiche(key, sealed);
    }

    /** Ouvre une fiche scellee. Refuse ce qui ne s'ouvre pas avec le secret ou n'est pas une fiche. */
    static Version openFiche(Crypto.Key key, byte[] sealed) throws IOException {
        if (key == null) throw new IOException("secret partage manquant");
        byte[] plain;
        try {
            plain = Crypto.open(key, 0, AAD_FICHE, sealed);
        } catch (IOException e) {
            throw new IOException("fiche de version refusee : elle ne s'ouvre pas avec le secret partage de ce poste");
        }
        return Version.parse(new String(plain, StandardCharsets.US_ASCII));
    }

    /** Ouvre un jar scelle et rend son contenu. Refuse ce qui ne s'ouvre pas avec le secret. */
    static byte[] openJar(Crypto.Key key, byte[] sealed) throws IOException {
        if (key == null) throw new IOException("secret partage manquant");
        try {
            return Crypto.open(key, 0, AAD_JAR, sealed);
        } catch (IOException e) {
            throw new IOException("jar refuse : il ne s'ouvre pas avec le secret partage de ce poste (ou la publication est en cours)");
        }
    }

    /**
     * Pourquoi la version publiee n'est pas une mise a jour pour ce poste ; "" si
     * elle en est une : publiee, pas refusee, empreinte differente et date posterieure.
     */
    static String whyNot(Version current, Version pub, Collection<String> refused) {
        if (pub == null) return "aucune version publiee par le hub pour l'instant";
        if (refused != null && refused.contains(pub.sha256())) return rolledBackMessage(pub.dateMs());
        if (current == null) return "version de ce poste inconnue";
        if (pub.sha256().equals(current.sha256())) return "ce poste est deja a jour (version du " + pub.label() + ")";
        if (pub.dateMs() <= current.dateMs())
            return "ce poste est deja a jour : la version publiee (du " + pub.label() + ") n'est pas plus recente que la sienne";
        return "";
    }

    static String rolledBackMessage(long dateMs) {
        return "la mise a jour du " + Version.label(dateMs) + " n'a pas demarre, version precedente retablie";
    }

    /**
     * Telecharge le jar publie, l'ouvre avec le secret, verifie la taille, le
     * SHA-256 et la date de la fiche, verifie que c'est un jar dont la classe
     * principale est cotransfert.Agent, puis l'ecrit sous "<jar>.nouveau"
     * (ecriture atomique). Rien n'est ecrit sous ce nom si une verification echoue.
     */
    static void stage(RelayStore store, Crypto.Key key, Version fiche, Path jar) throws IOException {
        if (key == null) throw new IOException("secret partage manquant");
        if (fiche.size() <= 0 || fiche.size() > MAX_JAR_BYTES) throw new IOException("taille annoncee refusee");
        byte[] sealed = store.read(JAR_PATH, (int) (fiche.size() + Crypto.SEAL_OVERHEAD + 1));
        if (sealed == null) throw new IOException("jar absent du canal");
        byte[] bytes = openJar(key, sealed);
        if (bytes.length != fiche.size()) throw new IOException("jar refuse : taille differente de la fiche");
        if (!sha256(bytes).equals(fiche.sha256())) throw new IOException("jar refuse : empreinte differente de la fiche");
        Path part = sibling(jar, SUFFIX_NEW + ".part");
        try {
            Files.write(part, bytes);
            Version v = inspect(part, bytes);
            if (v.dateMs() != fiche.dateMs()) throw new IOException("jar refuse : date de construction differente de la fiche");
            MailboxSession.moveAtomic(part, sibling(jar, SUFFIX_NEW));
        } finally {
            Files.deleteIfExists(part);
        }
    }

    // =======================================================================
    //  A cote du jar : echange, retour arriere, versions refusees
    // =======================================================================

    static Path sibling(Path jar, String suffix) { return jar.resolveSibling(jar.getFileName() + suffix); }

    /**
     * Echange : si "<jar>.nouveau" existe, le jar en place est copie sous
     * "<jar>.precedent" et le nouveau prend sa place. A n'appeler que lorsque
     * aucun agent ne tourne depuis ce jar (Windows le verrouille).
     *
     * Etat laisse par un arret brutal :
     *   - pendant la copie vers .precedent : jar intact, .nouveau intact -> l'echange est refait au prochain lancement ;
     *   - apres la copie, avant le renommage : idem (le temoin .essai, s'il est deja pose, fait
     *     seulement recopier .precedent, identique au jar, avant de refaire l'echange) ;
     *   - apres le renommage : jar = nouvelle version, .precedent = l'ancienne, .essai pose tant que
     *     la nouvelle version n'a pas prouve qu'elle demarre.
     * Il n'y a aucun instant sans cotransfert.jar complet (renommage atomique avec remplacement).
     *
     * @return la version mise en place, ou null si rien n'etait en attente
     * @throws IOException echange impossible (jar verrouille...) : rien n'a change ; ou
     *                     fichier en attente invalide : il est ecarte (supprime), le jar reste en place
     */
    static Version applyPending(Path jar) throws IOException {
        Path next = sibling(jar, SUFFIX_NEW);
        if (!Files.isRegularFile(next)) return null;
        Version v;
        try {
            v = versionOf(next);
        } catch (IOException e) {
            Files.deleteIfExists(next);
            throw new IOException("mise a jour en attente invalide, ecartee (" + e.getMessage() + ")");
        }
        if (Files.isRegularFile(jar)) {
            Path tmp = sibling(jar, SUFFIX_PREV + ".part");
            Files.copy(jar, tmp, StandardCopyOption.REPLACE_EXISTING);
            MailboxSession.moveAtomic(tmp, sibling(jar, SUFFIX_PREV));
        }
        Path trial = sibling(jar, SUFFIX_TRIAL);
        Files.write(trial, (v.sha256() + " " + v.dateMs()).getBytes(StandardCharsets.US_ASCII));
        try {
            MailboxSession.moveAtomic(next, jar);
        } catch (IOException e) {
            Files.deleteIfExists(trial);
            throw e;
        }
        return v;
    }

    /** La version en place a prouve qu'elle demarre : le temoin d'essai est retire. */
    static void confirm(Path jar) {
        try {
            Files.deleteIfExists(sibling(jar, SUFFIX_TRIAL));
        } catch (IOException | RuntimeException e) {
            // au pire, le prochain lancement par le script recopie la version precedente
        }
    }

    /**
     * Jar absent (ne devrait pas arriver) : la version precedente est remise en place.
     * @return vrai si le jar a ete retabli
     */
    static boolean heal(Path jar) throws IOException {
        Path prev = sibling(jar, SUFFIX_PREV);
        if (Files.exists(jar) || !Files.isRegularFile(prev)) return false;
        Path tmp = sibling(jar, ".retour");
        Files.copy(prev, tmp, StandardCopyOption.REPLACE_EXISTING);
        MailboxSession.moveAtomic(tmp, jar);
        return true;
    }

    /**
     * Retour arriere : "<jar>.precedent" est recopie a la place du jar, puis
     * l'empreinte fautive est notee dans cotransfert.maj-refusee, puis le temoin
     * d'essai est retire. .precedent est garde.
     *
     * Etat laisse par un arret brutal :
     *   - avant le renommage : jar = version fautive, .essai pose, .precedent intact -> CoTransfert.cmd
     *     remet la version precedente au prochain lancement ;
     *   - apres le renommage, avant la note : jar = version precedente (elle marche) ; la version
     *     fautive n'est pas encore notee, elle sera reproposee une fois, puis notee par la relance ;
     *   - apres la note : etat final.
     *
     * @param bad version fautive a ne plus proposer, ou null
     */
    static void rollback(Path jar, Version bad) throws IOException {
        Path prev = sibling(jar, SUFFIX_PREV);
        if (!Files.isRegularFile(prev)) throw new IOException("version precedente absente");
        Path tmp = sibling(jar, ".retour");
        Files.copy(prev, tmp, StandardCopyOption.REPLACE_EXISTING);
        MailboxSession.moveAtomic(tmp, jar);
        if (bad != null) refuse(jar, bad);
        confirm(jar);
    }

    /** Version en cours d'essai d'apres le temoin (empreinte et date), ou null. */
    static Version trial(Path jar) {
        try {
            String[] p = Files.readString(sibling(jar, SUFFIX_TRIAL), StandardCharsets.US_ASCII).trim().split(" ");
            if (p.length == 2 && p[0].matches("[0-9a-f]{64}")) return new Version(Long.parseLong(p[1]), 0, p[0]);
        } catch (IOException | RuntimeException e) {
            // pas de temoin lisible
        }
        return null;
    }

    static Path refusedFile(Path jar) { return jar.resolveSibling(REFUSED_FILE); }

    /** Versions refusees sur ce poste : empreinte -> date de construction (ms), de la plus ancienne note a la plus recente. */
    static LinkedHashMap<String, Long> refused(Path jar) {
        LinkedHashMap<String, Long> m = new LinkedHashMap<>();
        try {
            Path f = refusedFile(jar);
            if (!Files.isRegularFile(f)) return m;
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String[] p = line.trim().split("\\s+");
                if (p.length < 2 || !p[0].matches("[0-9a-f]{64}")) continue;
                try {
                    m.remove(p[0]);
                    m.put(p[0], Long.parseLong(p[1]));
                } catch (NumberFormatException e) {
                    // ligne ignoree
                }
            }
        } catch (IOException | RuntimeException e) {
            // marqueur illisible : aucune version refusee connue
        }
        return m;
    }

    /** Note une version qui n'a pas demarre : elle ne sera plus proposee sur ce poste. */
    static void refuse(Path jar, Version bad) throws IOException {
        LinkedHashMap<String, Long> m = refused(jar);
        m.remove(bad.sha256());
        m.put(bad.sha256(), bad.dateMs());
        List<String> lines = new ArrayList<>();
        lines.add("# CoTransfert - versions qui n'ont pas demarre sur ce poste (empreinte, date de construction).");
        lines.add("# Elles ne sont plus proposees. Supprimer ce fichier les rend de nouveau installables.");
        int skip = Math.max(0, m.size() - REFUSED_MAX);
        for (Map.Entry<String, Long> e : m.entrySet()) {
            if (skip-- > 0) continue;
            lines.add(e.getKey() + " " + e.getValue());
        }
        Path f = refusedFile(jar), tmp = f.resolveSibling(REFUSED_FILE + ".part");
        try {
            Files.write(tmp, lines, StandardCharsets.UTF_8);
            MailboxSession.moveAtomic(tmp, f);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // =======================================================================
    //  Cote agent : etat pour la page, actions, verification periodique
    // =======================================================================

    /** Resultat d'une action : ok ou non, et une phrase courte pour la page. */
    record Result(boolean ok, String message) { }

    /** Resultat d'une verification. version : libelle de la version publiee, "" s'il n'y en a pas. */
    record Check(boolean ok, boolean available, String version, String message) { }

    private final Path jar;
    private final Version current;
    private final boolean hub;
    private final Crypto.Key key;
    private final Supplier<RelayStore> stores;
    private final String unsupported;
    private final Consumer<String> sink;
    private final ReentrantLock busy = new ReentrantLock();
    /** Empreinte d'un "<jar>.nouveau" deja present au demarrage : un echange precedent n'a pas abouti. */
    private final String stale;
    /** Vrai quand cet agent a ete relance juste apres un retour arriere : annonce jusqu'a son arret. */
    private final boolean rolledBack;
    private final String rolledBackText;
    /** Ce qui a echoue en dernier ("check", "install", "publish"), "" sinon : un echec reste affiche jusqu'a l'essai suivant du meme genre. */
    private String failedBy = "";

    /** Installer tout seul une mise a jour disponible (update.auto). */
    volatile boolean auto;
    /** Redemarrage de l'agent, appele apres une installation automatique. */
    volatile Runnable restart = () -> { };
    /** Periode de la verification automatique et delai avant la premiere, en ms. */
    volatile long periodMs = 600_000, firstDelayMs = 5_000;
    /** Delai avant un nouvel essai quand la derniere verification n'a pas pu lire le canal, en ms. */
    volatile long retryMs = 30_000;
    private volatile boolean lastCheckOk = true;

    private volatile List<String> secrets = List.of();
    private RelayStore store;
    private boolean available;
    private String version = "", state = "", message = "";

    /**
     * @param jar         jar de ce poste, ou null hors jar
     * @param current     sa version, ou null si elle est inconnue
     * @param hub         vrai sur le hub (publie), faux sur le client (verifie et installe)
     * @param key         cle derivee du secret partage, ou null
     * @param stores      fabrique du magasin du canal choisi (appelee une fois, a la demande)
     * @param unsupported pourquoi la mise a jour est indisponible sur ce poste, "" si elle est disponible
     * @param sink        recoit l'etat JSON a chaque changement (Status), ou null
     * @param rolledBackMs date de construction (ms) de la version que la relance vient de retirer
     *                    (retour arriere juste avant ce demarrage), ou 0
     */
    Updater(Path jar, Version current, boolean hub, Crypto.Key key, Supplier<RelayStore> stores, String unsupported,
            Consumer<String> sink, long rolledBackMs) {
        this.jar = jar;
        this.current = current;
        this.hub = hub;
        this.key = key;
        this.stores = stores;
        String why = unsupported == null ? "" : unsupported;
        if (why.isEmpty() && (jar == null || current == null)) why = NOT_A_JAR;
        if (why.isEmpty() && key == null) why = "secret partage manquant";
        if (why.isEmpty() && stores == null) why = "aucun canal par lots n'est choisi";
        this.unsupported = why;
        this.sink = sink;
        String pending = null;
        // Retour arriere juste avant ce demarrage : on le dit tout de suite, et jusqu'a l'arret de cet agent.
        this.rolledBack = rolledBackMs > 0 && !hub;
        this.rolledBackText = rolledBack ? rolledBackMessage(rolledBackMs) : "";
        if (rolledBack) { state = "rolled-back"; message = rolledBackText; }
        if (why.isEmpty() && !hub) {
            try {
                Path next = sibling(jar, SUFFIX_NEW);
                if (Files.isRegularFile(next)) pending = sha256(Files.readAllBytes(next));
            } catch (IOException | RuntimeException e) {
                // illisible : comme s'il n'y en avait pas
            }
        }
        this.stale = pending;
        push();
    }

    static final String NOT_A_JAR = "CoTransfert n'est pas lance depuis un jar";

    /** Valeurs a ne jamais laisser sortir dans un message. */
    void setSecrets(Collection<String> values) {
        List<String> l = new ArrayList<>();
        if (values != null) for (String v : values) if (v != null && v.trim().length() >= 4) l.add(v.trim());
        secrets = List.copyOf(l);
    }

    boolean supported() { return unsupported.isEmpty(); }

    /** Etat pour /api/status ("update" dans "self"). */
    synchronized String json() {
        boolean ok = unsupported.isEmpty();
        return "{\"current\":" + Status.q(current == null ? "" : current.label())
                + ",\"supported\":" + ok
                + ",\"canPublish\":" + (ok && hub)
                + ",\"available\":" + (ok && available)
                + ",\"version\":" + Status.q(version)
                + ",\"state\":" + Status.q(ok || rolledBack ? state : "")
                + ",\"message\":" + Status.q(ok || rolledBack ? message : unsupported) + "}";
    }

    private void push() {
        if (sink != null) sink.accept(json());
    }

    private synchronized void set(Boolean avail, String ver, String st, String msg) {
        if (avail != null) available = avail;
        if (ver != null) version = ver;
        if (st != null) state = st;
        if (msg != null) message = clean(msg);
        push();
    }

    /** Etat et message d'avant une verification, pour les remettre quand elle ne doit pas les effacer. */
    private synchronized String[] snapshot() { return new String[] { state, message, failedBy }; }

    private synchronized void failed(String by, String msg) {
        failedBy = by;
        state = "failed";
        message = clean(msg);
        push();
    }

    /**
     * Fin d'une verification sans mise a jour disponible. Ce qui etait annonce
     * reste affiche : un retour arriere jusqu'a l'arret de l'agent, l'echec d'une
     * installation ou d'une publication jusqu'a l'essai suivant.
     */
    private synchronized void settle(String[] before, boolean avail, String ver, boolean refusedNow, String msg) {
        available = avail;
        version = ver;
        if (rolledBack || refusedNow) {
            state = "rolled-back";
            message = rolledBack ? rolledBackText : clean(msg);
        } else if ("failed".equals(before[0]) && !"check".equals(before[2])) {
            state = "failed";
            message = before[1];
        } else {
            state = "";
            failedBy = "";
            message = clean(msg);
        }
        push();
    }

    private String clean(String s) {
        String o = s == null ? "" : s;
        for (String v : secrets) o = o.replace(v, "***");
        o = o.replace('\n', ' ').replace('\r', ' ').trim();
        return o.length() > 300 ? o.substring(0, 300) : o;
    }

    /** Le magasin du canal, cree a la premiere demande et garde (il met en cache ce qu'il sait du support). */
    private RelayStore store() throws IOException {
        if (store == null) {
            try {
                store = stores.get();
            } catch (RuntimeException e) {
                throw new IOException("canal inutilisable");
            }
            if (store == null) throw new IOException("canal inutilisable");
        }
        if (!store.available()) {
            String why = store.lastError();
            throw new IOException("canal injoignable" + (why == null || why.isBlank() ? "" : " (" + why + ")"));
        }
        return store;
    }

    private boolean acquire() {
        try {
            return busy.tryLock(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static final String BUSY = "une autre operation de mise a jour est en cours, reessayer dans un instant";

    /** Hub : publie la version de ce poste dans le canal. */
    Result publish() {
        if (!unsupported.isEmpty()) return new Result(false, unsupported);
        if (!hub) return new Result(false, "seul le hub publie une version ; ce poste est le client");
        if (!acquire()) return new Result(false, BUSY);
        try {
            Version v = publish(store(), key, jar);
            String m = "version du " + v.label() + " publiee";
            synchronized (this) { failedBy = ""; }
            set(null, v.label(), "", m);
            Agent.log("maj: %s dans le canal", m);
            return new Result(true, m);
        } catch (IOException | RuntimeException e) {
            String m = clean("publication impossible : " + e.getMessage());
            failed("publish", m);
            Agent.log("maj: %s", m);
            return new Result(false, m);
        } finally {
            busy.unlock();
        }
    }

    /** Lit la fiche publiee. Client : dit si c'est une mise a jour pour ce poste. Hub : dit ce qui est publie. */
    Check check() {
        if (!unsupported.isEmpty()) return new Check(false, false, "", unsupported);
        if (!acquire()) return new Check(false, false, "", BUSY);
        String[] before = snapshot();
        try {
            set(null, null, "checking", null);
            Version pub = published(store(), key);
            lastCheckOk = true;
            String label = pub == null ? "" : pub.label();
            if (hub) {
                String m = pub == null ? "aucune version publiee"
                        : "version publiee : du " + label + (pub.sha256().equals(current.sha256()) ? " (celle de ce poste)" : "");
                settle(before, false, label, false, m);
                return new Check(true, false, label, m);
            }
            LinkedHashMap<String, Long> ref = refused(jar);
            String why = whyNot(current, pub, ref.keySet());
            if (why.isEmpty()) {
                String m = "mise a jour disponible : version du " + label;
                if (!isAvailable()) Agent.log("maj: %s", m);
                synchronized (this) { failedBy = ""; }
                // Une version plus recente que celle qui a ete retiree : c'est elle qu'on annonce.
                set(true, label, "", m);
                return new Check(true, true, label, m);
            }
            settle(before, false, label, pub != null && ref.containsKey(pub.sha256()), why);
            return new Check(true, false, label, why);
        } catch (IOException | RuntimeException e) {
            String m = clean("verification impossible : " + e.getMessage());
            lastCheckOk = false;
            // Ce qui etait annonce (retour arriere, echec d'installation) reste affiche : une panne
            // passagere du canal ne l'efface pas.
            if ("rolled-back".equals(before[0]) || ("failed".equals(before[0]) && !"check".equals(before[2])))
                set(null, null, before[0], before[1]);
            else failed("check", m);
            return new Check(false, false, "", m);
        } finally {
            busy.unlock();
        }
    }

    private synchronized boolean isAvailable() { return available; }

    /**
     * Client : relit la fiche, telecharge et verifie le jar publie, l'ecrit sous
     * "<jar>.nouveau". Si le resultat est ok, l'appelant redemarre l'agent : la
     * relance fait l'echange.
     */
    Result install() {
        if (!unsupported.isEmpty()) return new Result(false, unsupported);
        if (hub) return new Result(false, "le hub n'installe pas de mise a jour : c'est lui qui publie");
        if (!acquire()) return new Result(false, BUSY);
        String[] before = snapshot();
        try {
            set(null, null, "downloading", "telechargement de la mise a jour");
            RelayStore s = store();
            Version pub = published(s, key);
            LinkedHashMap<String, Long> ref = refused(jar);
            String why = whyNot(current, pub, ref.keySet());
            if (!why.isEmpty()) {
                // Rien a installer : ce n'est pas un echec, l'etat d'avant est remis.
                before[0] = "downloading".equals(before[0]) || "checking".equals(before[0]) ? "" : before[0];
                synchronized (this) { failedBy = "failed".equals(before[0]) ? before[2] : ""; }
                settle(new String[] { before[0], before[1], "install" }, false, pub == null ? "" : pub.label(),
                        pub != null && ref.containsKey(pub.sha256()), why);
                return new Result(false, why);
            }
            stage(s, key, pub, jar);
            String m = "version du " + pub.label() + " telechargee et verifiee, redemarrage en cours";
            synchronized (this) { failedBy = ""; }
            set(false, pub.label(), "ready", m);
            Agent.log("maj: %s", m);
            return new Result(true, m);
        } catch (IOException | RuntimeException e) {
            String m = clean("installation impossible : " + e.getMessage());
            failed("install", m);
            Agent.log("maj: %s", m);
            return new Result(false, m);
        } finally {
            busy.unlock();
        }
    }

    /**
     * Client : verification au demarrage puis periodique, dans un fil demon ;
     * installation et redemarrage tout seuls si {@link #auto}. Sans effet sur le
     * hub ou quand la mise a jour est indisponible.
     */
    void start() {
        if (!unsupported.isEmpty() || hub) return;
        Thread t = new Thread(() -> {
            long wait = firstDelayMs;
            while (true) {
                try { Thread.sleep(Math.max(0, wait)); } catch (InterruptedException e) { return; }
                try {
                    if (round()) return;
                } catch (RuntimeException e) {
                    Agent.log("maj: verification interrompue (%s)", e.getClass().getSimpleName());
                }
                // Canal injoignable (hub pas encore trouve sur le lien direct, serveur en panne...) :
                // nouvel essai sans attendre toute la periode.
                wait = lastCheckOk ? periodMs : Math.min(periodMs, retryMs);
            }
        }, "cotp-maj");
        t.setDaemon(true);
        t.start();
    }

    /** Une verification ; installe si c'est demande. @return vrai si le redemarrage est lance. */
    boolean round() {
        Check c = check();
        if (!c.available() || !auto) return false;
        if (stale != null) {
            Version pub = null;
            try { pub = published(store(), key); } catch (IOException | RuntimeException e) { /* on ne sait pas : pas d'installation */ }
            if (pub == null || stale.equals(pub.sha256())) {
                // Deja telechargee avant ce demarrage, et pourtant pas en place : ne pas boucler.
                failed("install", "mise a jour deja telechargee mais non appliquee au dernier redemarrage : "
                        + "l'installer depuis la page, ou quitter puis relancer CoTransfert");
                return false;
            }
        }
        Agent.log("maj: installation automatique (update.auto=true)");
        if (!install().ok()) return false;
        restart.run();
        return true;
    }
}
