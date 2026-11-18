package cotransfert;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

/**
 * Essai de la mise a jour par le canal (Updater) et du journal (Journal), sans
 * dependance : simple main, code de sortie non nul en cas d'echec, aucun
 * processus lance, aucun service reel. Les jars sont fabriques ici.
 *
 *   1. Version : date, taille et empreinte d'un vrai jar ; ce qui n'en est pas un est refuse.
 *   2. Publier (hub) -> verifier -> installer (client) sur un magasin disque, avec une vraie
 *      cle : fichiers _maj/ a la racine du magasin, scelles ; cotransfert.jar.nouveau
 *      identique au jar publie ; le hub n'installe pas, le client ne publie pas.
 *   3. Refus : mauvais secret, fiche alteree, jar altere, version plus ancienne ou egale,
 *      mauvaise taille, mauvaise empreinte, pas un jar, mauvaise classe principale,
 *      donnee associee d'un lot, version notee refusee.
 *   4. Echange (nouveau -> jar, jar -> precedent, temoin d'essai) et ses cas limites.
 *   5. Retour arriere (precedent retabli, marqueur ecrit) et versions refusees.
 *   6. Etats pour la page : indisponible et pourquoi, retour arriere annonce jusqu'a
 *      l'arret, echec garde jusqu'a l'essai suivant, installation automatique.
 *   7. Journal : archivage au-dela de la taille maximale, dernieres lignes.
 *   8. Meme aller-retour par un faux serveur WebDAV et un faux serveur Drime.
 *   9. Lien direct : meme aller-retour entre le dossier local du hub (servi par les routes
 *      /maj/ de sa page) et le magasin HTTP du client ; publication par le client ; refus
 *      (autre secret, nom hors liste, parcours de chemin, fichier trop gros, version plus
 *      ancienne, suppression) ; hub pas encore trouve, arrete ou sans ces routes.
 *
 * Usage : java -cp <classes> cotransfert.UpdateTest <dossier de travail>
 */
public final class UpdateTest {

    private static final String SECRET = "secret-partage-maj-0123456789";
    private static final String USER = "utilisateur";
    private static final String PASSWORD = "mot-de-passe-webdav-maj-7731";
    private static final String TOKEN = "jeton-drime-maj-552190";

    /** Dates de construction des jars d'essai (heure telle qu'elle est ecrite dans le jar). */
    private static final LocalDateTime T1 = LocalDateTime.of(2026, 3, 1, 10, 0, 0);
    private static final LocalDateTime T2 = LocalDateTime.of(2026, 3, 2, 11, 30, 0);
    private static final LocalDateTime T3 = LocalDateTime.of(2026, 3, 3, 9, 15, 0);

    private static Path work;
    private static Crypto.Key key;
    private static int n;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) { System.err.println("usage: UpdateTest <dossier de travail>"); System.exit(2); }
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
        key = Crypto.derive(SECRET);

        version();
        roundTripDisk();
        refusals();
        swap();
        rollback();
        states();
        journal();
        online();
        direct();

        System.out.println("UPDATE TEST: OK");
        System.exit(0);
    }

    // --- 1. version -----------------------------------------------------------

    private static void version() throws Exception {
        Path dir = fresh("version");
        Path jar = jar(dir.resolve("cotransfert.jar"), T1, Updater.MAIN_CLASS, "v1");
        byte[] bytes = Files.readAllBytes(jar);
        Updater.Version v = Updater.versionOf(jar);
        check(v.dateMs() == ms(T1), "date de construction: " + v.dateMs() + " attendu " + ms(T1));
        check(v.size() == bytes.length, "taille: " + v.size());
        check(v.sha256().equals(Updater.sha256(bytes)) && v.sha256().matches("[0-9a-f]{64}"), "empreinte: " + v.sha256());
        check(v.label().equals("2026-03-01 10:00"), "libelle: " + v.label());
        // Empreinte connue : SHA-256 de "abc".
        check(Updater.sha256("abc".getBytes(StandardCharsets.US_ASCII))
                .equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"), "SHA-256 de reference");

        Updater.Version back = Updater.Version.parse(v.fiche());
        check(back.equals(v) && v.fiche().startsWith("CTMAJ1 " + ms(T1) + " " + bytes.length + " "), "fiche: " + v.fiche());
        for (String bad : new String[] { "", "CTMAJ2 1 2 " + v.sha256(), "CTMAJ1 1 2 abc", "CTMAJ1 x 2 " + v.sha256(),
                v.fiche() + " suite", "CTMAJ1 1 " + v.sha256() }) {
            check(throwsIo(() -> Updater.Version.parse(bad)), "fiche acceptee a tort: " + bad);
        }

        Path text = dir.resolve("a.txt");
        Files.write(text, "pas un jar".getBytes(StandardCharsets.US_ASCII));
        check(throwsIo(() -> Updater.versionOf(text)), "un fichier texte passe pour un jar");
        Path other = jar(dir.resolve("autre.jar"), T1, "exemple.Principal", "x");
        check(throwsIo(() -> Updater.versionOf(other)), "classe principale inattendue acceptee");
        Path none = jar(dir.resolve("sans.jar"), T1, null, "x");
        check(throwsIo(() -> Updater.versionOf(none)), "jar sans classe principale accepte");
        check(throwsIo(() -> Updater.versionOf(dir.resolve("absent.jar"))), "jar absent accepte");
        // Deux constructions du meme contenu a deux dates : deux versions differentes.
        Path later = jar(dir.resolve("plus-tard.jar"), T2, Updater.MAIN_CLASS, "v1");
        Updater.Version v2 = Updater.versionOf(later);
        check(v2.dateMs() > v.dateMs() && !v2.sha256().equals(v.sha256()), "deux dates, meme version");
        ok("version d'un jar (date, taille, empreinte, fiche, refus de ce qui n'est pas un jar CoTransfert)");
    }

    // --- 2. aller-retour sur un magasin disque ---------------------------------

    /** Un poste d'essai : son dossier, son jar, son Updater et le dernier etat pousse vers la page. */
    private static final class Post {
        final Path dir, jar;
        final Updater up;
        final AtomicReference<String> json = new AtomicReference<>("");
        final List<String> states = new ArrayList<>();

        Post(String name, boolean hub, LocalDateTime built, String marker, Crypto.Key k,
             java.util.function.Supplier<RelayStore> stores, String unsupported, long rolledBackMs) throws IOException {
            dir = fresh(name);
            jar = jar(dir.resolve("cotransfert.jar"), built, Updater.MAIN_CLASS, marker);
            up = new Updater(jar, Updater.versionOf(jar), hub, k, stores, unsupported, this::accept, rolledBackMs);
        }

        private synchronized void accept(String j) {
            json.set(j);
            try {
                String st = String.valueOf(obj(j).get("state"));
                if (states.isEmpty() || !states.get(states.size() - 1).equals(st)) states.add(st);
            } catch (IOException e) {
                throw new IllegalStateException("etat illisible: " + j);
            }
        }

        Map<String, Object> state() throws IOException { return obj(up.json()); }
        Path next() { return Updater.sibling(jar, Updater.SUFFIX_NEW); }
    }

    private static void roundTripDisk() throws Exception {
        Path relay = fresh("relais-disque");
        Post hub = new Post("hub", true, T2, "v2", key, () -> new DirRelayStore(relay.toString()), "", 0);
        Post client = new Post("client", false, T1, "v1", key, () -> new DirRelayStore(relay.toString()), "", 0);
        roundTrip(hub, client, "disque");

        // Dans le magasin : deux fichiers, a la racine, sous _maj/, scelles.
        Path fiche = relay.resolve("_maj").resolve("version.scelle"), sealed = relay.resolve("_maj").resolve("cotransfert.jar.scelle");
        check(Files.isRegularFile(fiche) && Files.isRegularFile(sealed), "fichiers _maj/ absents de la racine du magasin");
        try (java.util.stream.Stream<Path> s = Files.list(relay)) {
            check(s.allMatch(p -> p.getFileName().toString().equals("_maj")), "autre chose que _maj/ a la racine du magasin");
        }
        byte[] plain = Files.readAllBytes(hub.jar), stored = Files.readAllBytes(sealed);
        check(stored.length == plain.length + Crypto.SEAL_OVERHEAD, "taille du jar scelle: " + stored.length);
        check(indexOf(stored, "META-INF".getBytes(StandardCharsets.US_ASCII)) < 0 && !(stored[0] == 'P' && stored[1] == 'K'),
                "le jar est depose en clair");
        check(indexOf(Files.readAllBytes(fiche), "CTMAJ1".getBytes(StandardCharsets.US_ASCII)) < 0, "la fiche est deposee en clair");
        check(!new String(stored, StandardCharsets.ISO_8859_1).contains(SECRET), "secret dans le magasin");
        ok("publier -> verifier -> installer sur un magasin disque (fichiers scelles sous _maj/, jar recu identique)");
    }

    /** Publie depuis le hub, verifie et installe sur le client, puis controle tout ce qui en resulte. */
    private static void roundTrip(Post hub, Post client, String what) throws Exception {
        Updater.Version v1 = Updater.versionOf(client.jar), v2 = Updater.versionOf(hub.jar);

        Map<String, Object> s = client.state();
        check(s.get("current").equals(v1.label()) && Boolean.TRUE.equals(s.get("supported")) && Boolean.FALSE.equals(s.get("canPublish"))
                && Boolean.FALSE.equals(s.get("available")) && "".equals(s.get("version")) && "".equals(s.get("state"))
                && "".equals(s.get("message")), what + ": etat initial du client: " + client.up.json());
        s = hub.state();
        check(Boolean.TRUE.equals(s.get("supported")) && Boolean.TRUE.equals(s.get("canPublish")) && s.get("current").equals(v2.label()),
                what + ": etat initial du hub: " + hub.up.json());

        // Rien de publie.
        Updater.Check c = client.up.check();
        check(c.ok() && !c.available() && c.version().isEmpty() && c.message().contains("aucune version publiee"),
                what + ": verification sans publication: " + c);
        Updater.Result r = client.up.install();
        check(!r.ok() && r.message().contains("aucune version publiee") && !Files.exists(client.next()), what + ": installation sans publication: " + r);
        check("".equals(client.state().get("state")), what + ": rien a installer n'est pas un echec: " + client.up.json());

        // Le client ne publie pas, le hub n'installe pas.
        r = client.up.publish();
        check(!r.ok() && r.message().contains("hub"), what + ": le client a publie: " + r);
        r = hub.up.install();
        check(!r.ok() && !Files.exists(hub.next()), what + ": le hub a installe: " + r);

        r = hub.up.publish();
        check(r.ok() && r.message().equals("version du " + v2.label() + " publiee"), what + ": publication: " + r);
        c = hub.up.check();
        check(c.ok() && !c.available() && c.version().equals(v2.label()), what + ": verification cote hub: " + c);

        c = client.up.check();
        check(c.ok() && c.available() && c.version().equals(v2.label()) && c.message().contains(v2.label()), what + ": verification: " + c);
        s = client.state();
        check(Boolean.TRUE.equals(s.get("available")) && s.get("version").equals(v2.label()) && "".equals(s.get("state")),
                what + ": etat apres verification: " + client.up.json());
        check(s.get("current").equals(v1.label()), what + ": current a change sans installation");

        client.states.clear();
        r = client.up.install();
        check(r.ok(), what + ": installation: " + r);
        check(client.states.equals(List.of("downloading", "ready")), what + ": etats publies pendant l'installation: " + client.states);
        check(Arrays.equals(Files.readAllBytes(client.next()), Files.readAllBytes(hub.jar)), what + ": cotransfert.jar.nouveau differe du jar publie");
        check(Updater.versionOf(client.jar).equals(v1), what + ": le jar en place a change avant l'echange");
        s = client.state();
        check("ready".equals(s.get("state")) && Boolean.FALSE.equals(s.get("available")) && s.get("version").equals(v2.label()),
                what + ": etat apres installation: " + client.up.json());
        try (java.util.stream.Stream<Path> l = Files.list(client.dir)) {
            check(l.noneMatch(p -> p.getFileName().toString().endsWith(".part")), what + ": fichier temporaire laisse a cote du jar");
        }

        // Apres l'echange, un agent neuf sur la nouvelle version : a jour, etat vide ("ready" ne survit pas).
        check(Updater.applyPending(client.jar).equals(v2), what + ": echange");
        Updater.confirm(client.jar);
        AtomicReference<String> j = new AtomicReference<>();
        Updater fresh = new Updater(client.jar, Updater.versionOf(client.jar), false, key, storesOf(client), "", j::set, 0);
        s = obj(j.get());
        check(s.get("current").equals(v2.label()) && "".equals(s.get("state")) && Boolean.FALSE.equals(s.get("available")),
                what + ": etat d'un agent neuf apres mise a jour: " + j.get());
        c = fresh.check();
        check(c.ok() && !c.available() && c.message().contains("deja a jour"), what + ": version egale proposee: " + c);
        // On remet le client en version 1 pour la suite.
        Updater.rollback(client.jar, null);
        check(Updater.versionOf(client.jar).equals(v1), what + ": retour a la version 1");
    }

    private static final Map<Post, java.util.function.Supplier<RelayStore>> STORES = new java.util.IdentityHashMap<>();

    private static java.util.function.Supplier<RelayStore> storesOf(Post p) {
        java.util.function.Supplier<RelayStore> s = STORES.get(p);
        if (s != null) return s;
        Path relay = work.resolve("relais-disque");
        return () -> new DirRelayStore(relay.toString());
    }

    // --- 3. refus ---------------------------------------------------------------

    private static void refusals() throws Exception {
        Path relay = fresh("relais-refus");
        DirRelayStore store = new DirRelayStore(relay.toString());
        Path fiche = relay.resolve("_maj").resolve("version.scelle"), sealed = relay.resolve("_maj").resolve("cotransfert.jar.scelle");
        Post hub = new Post("refus-hub", true, T2, "v2", key, () -> store, "", 0);
        Post client = new Post("refus-client", false, T1, "v1", key, () -> store, "", 0);
        Updater.Version v1 = Updater.versionOf(client.jar), v2 = Updater.versionOf(hub.jar);
        check(hub.up.publish().ok(), "refus: publication de depart");
        byte[] goodFiche = Files.readAllBytes(fiche), goodJar = Files.readAllBytes(sealed);

        // Mauvais secret : rien ne s'ouvre.
        Post stranger = new Post("refus-autre-secret", false, T1, "v1", Crypto.derive("un-autre-secret-0123456789"), () -> store, "", 0);
        Updater.Check c = stranger.up.check();
        check(!c.ok() && !c.available() && c.message().contains("secret partage"), "mauvais secret, verification: " + c);
        Updater.Result r = stranger.up.install();
        check(!r.ok() && !Files.exists(stranger.next()), "mauvais secret, installation: " + r);
        check("failed".equals(stranger.state().get("state")) && !stranger.up.json().contains("un-autre-secret"), "mauvais secret, etat: " + stranger.up.json());
        check(throwsIo(() -> Updater.published(store, null)) && throwsIo(() -> Updater.publish(store, null, hub.jar)), "sans cle");

        // Fiche alteree (un octet), tronquee, ou vide.
        for (int at : new int[] { 0, 5, goodFiche.length / 2, goodFiche.length - 1 }) {
            byte[] bad = goodFiche.clone();
            bad[at] ^= 0x01;
            Files.write(fiche, bad);
            c = client.up.check();
            check(!c.ok() && !c.available(), "fiche alteree a l'octet " + at + ": " + c);
            check(!client.up.install().ok() && !Files.exists(client.next()), "fiche alteree a l'octet " + at + ": installee");
        }
        Files.write(fiche, Arrays.copyOf(goodFiche, goodFiche.length - 3));
        check(!client.up.check().ok(), "fiche tronquee acceptee");
        Files.write(fiche, new byte[0]);
        check(!client.up.check().ok(), "fiche vide acceptee");
        Files.write(fiche, goodFiche);
        check(client.up.check().available(), "fiche retablie non reconnue");
        check("".equals(client.state().get("state")), "un echec de verification doit s'effacer a la verification suivante: " + client.up.json());

        // Jar scelle altere, tronque, allonge, absent : la fiche est bonne, l'installation echoue.
        byte[][] bads = { flip(goodJar, 0), flip(goodJar, 20), flip(goodJar, goodJar.length / 2), flip(goodJar, goodJar.length - 1),
                Arrays.copyOf(goodJar, goodJar.length - 1), Arrays.copyOf(goodJar, goodJar.length + 1), new byte[0] };
        for (int i = 0; i < bads.length; i++) {
            Files.write(sealed, bads[i]);
            r = client.up.install();
            check(!r.ok() && !Files.exists(client.next()), "jar altere (cas " + i + ") installe: " + r);
            check("failed".equals(client.state().get("state")), "jar altere (cas " + i + "): etat " + client.up.json());
        }
        // L'echec d'une installation reste affiche apres une verification reussie, jusqu'a l'essai suivant.
        c = client.up.check();
        check(c.ok() && c.available() && "".equals(client.state().get("state")), "mise a jour disponible apres un echec: " + client.up.json());
        Files.delete(sealed);
        r = client.up.install();
        check(!r.ok() && r.message().contains("absent") && !Files.exists(client.next()), "jar absent installe: " + r);
        Files.write(sealed, goodJar);

        // Fiche authentique mais fausse : taille, empreinte, date.
        byte[] plainJar = Files.readAllBytes(hub.jar);
        String[] lies = {
                "CTMAJ1 " + v2.dateMs() + " " + (v2.size() + 1) + " " + v2.sha256(),
                "CTMAJ1 " + v2.dateMs() + " " + (v2.size() - 1) + " " + v2.sha256(),
                "CTMAJ1 " + v2.dateMs() + " " + v2.size() + " " + Updater.sha256("autre".getBytes(StandardCharsets.US_ASCII)),
                "CTMAJ1 " + (v2.dateMs() + 60_000) + " " + v2.size() + " " + v2.sha256(),
                "CTMAJ1 " + v2.dateMs() + " 0 " + v2.sha256(),
                "CTMAJ1 " + v2.dateMs() + " 999999999 " + v2.sha256() };
        for (String lie : lies) {
            store.write(Updater.FICHE_PATH, seal("maj|fiche", lie.getBytes(StandardCharsets.US_ASCII)));
            r = client.up.install();
            check(!r.ok() && !Files.exists(client.next()), "fiche fausse installee (" + lie.substring(0, 30) + "...): " + r);
        }

        // Scelle avec le bon secret, mais ce n'est pas un jar CoTransfert.
        Path tmp = fresh("refus-faux");
        byte[] garbage = "ceci n'est pas une archive".getBytes(StandardCharsets.US_ASCII);
        publishRaw(store, garbage, ms(T3));
        r = client.up.install();
        check(!r.ok() && !Files.exists(client.next()), "contenu quelconque installe: " + r);
        byte[] wrongMain = Files.readAllBytes(jar(tmp.resolve("b.jar"), T3, "exemple.Principal", "x"));
        publishRaw(store, wrongMain, ms(T3));
        r = client.up.install();
        check(!r.ok() && !Files.exists(client.next()), "jar d'une autre classe principale installe: " + r);
        byte[] noMain = Files.readAllBytes(jar(tmp.resolve("c.jar"), T3, null, "x"));
        publishRaw(store, noMain, ms(T3));
        check(!client.up.install().ok() && !Files.exists(client.next()), "jar sans classe principale installe");

        // Donnee associee d'un lot : un lot authentique ne passe ni pour une fiche ni pour un jar.
        store.write(Updater.FICHE_PATH, seal("classeur|hub", v2.fiche().getBytes(StandardCharsets.US_ASCII)));
        check(!client.up.check().ok(), "fiche scellee comme un lot acceptee");
        store.write(Updater.FICHE_PATH, seal("maj|jar", v2.fiche().getBytes(StandardCharsets.US_ASCII)));
        check(!client.up.check().ok(), "fiche scellee comme un jar acceptee");
        store.write(Updater.FICHE_PATH, goodFiche);
        store.write(Updater.JAR_PATH, seal("maj|fiche", plainJar));
        check(!client.up.install().ok() && !Files.exists(client.next()), "jar scelle comme une fiche installe");
        store.write(Updater.JAR_PATH, seal("classeur|hub", plainJar));
        check(!client.up.install().ok() && !Files.exists(client.next()), "jar scelle comme un lot installe");

        // Version plus ancienne, ou egale : jamais proposee, jamais installee.
        Post oldHub = new Post("refus-vieux-hub", true, T1.minusDays(1), "v0", key, () -> store, "", 0);
        check(oldHub.up.publish().ok(), "publication d'une version ancienne");
        c = client.up.check();
        check(c.ok() && !c.available() && c.message().contains("deja a jour"), "version plus ancienne proposee: " + c);
        r = client.up.install();
        check(!r.ok() && !Files.exists(client.next()), "version plus ancienne installee: " + r);
        // Meme date, autre contenu : pas "posterieure".
        Post sameDate = new Post("refus-meme-date", true, T1, "autre contenu", key, () -> store, "", 0);
        check(sameDate.up.publish().ok() && !Updater.versionOf(sameDate.jar).sha256().equals(v1.sha256()), "publication a la meme date");
        c = client.up.check();
        check(c.ok() && !c.available(), "version de meme date proposee: " + c);
        check(!client.up.install().ok() && !Files.exists(client.next()), "version de meme date installee");
        // La sienne.
        Files.copy(client.jar, sameDate.jar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        check(Updater.publish(store, key, sameDate.jar).equals(v1), "publication de la version du client");
        c = client.up.check();
        check(c.ok() && !c.available() && c.message().contains("deja a jour"), "sa propre version proposee: " + c);
        check(!client.up.install().ok() && !Files.exists(client.next()), "sa propre version installee");
        // Les messages distinguent "rien de publie" de "deja a jour".
        Files.delete(fiche);
        c = client.up.check();
        check(c.ok() && !c.available() && c.message().contains("aucune version publiee") && !c.message().contains("deja a jour"),
                "message sans publication: " + c);

        // Version notee refusee : plus proposee, plus installable ; une plus recente l'est.
        check(hub.up.publish().ok() && client.up.check().available(), "version 2 de nouveau publiee");
        Updater.refuse(client.jar, v2);
        c = client.up.check();
        check(c.ok() && !c.available() && c.message().contains("n'a pas demarre"), "version refusee proposee: " + c);
        Map<String, Object> s = client.state();
        check("rolled-back".equals(s.get("state")) && Boolean.FALSE.equals(s.get("available")) && String.valueOf(s.get("message")).contains("2026-03-02 11:30"),
                "etat pour une version refusee: " + client.up.json());
        r = client.up.install();
        check(!r.ok() && !Files.exists(client.next()), "version refusee installee: " + r);
        Post newer = new Post("refus-hub-3", true, T3, "v3", key, () -> store, "", 0);
        check(newer.up.publish().ok(), "publication de la version 3");
        c = client.up.check();
        check(c.ok() && c.available() && c.version().equals("2026-03-03 09:15") && "".equals(client.state().get("state")),
                "version plus recente que la refusee: " + c + " " + client.up.json());
        check(client.up.install().ok() && Arrays.equals(Files.readAllBytes(client.next()), Files.readAllBytes(newer.jar)), "installation de la version 3");
        ok("refus (mauvais secret, fiche ou jar alteres, taille, empreinte, date, pas un jar, autre classe principale, "
                + "donnee associee d'un lot, version ancienne, egale ou refusee)");
    }

    /** Depose un contenu quelconque comme s'il etait publie : jar et fiche coherents, scelles avec le bon secret. */
    private static void publishRaw(RelayStore store, byte[] content, long dateMs) throws IOException {
        store.write(Updater.JAR_PATH, seal("maj|jar", content));
        String fiche = "CTMAJ1 " + dateMs + " " + content.length + " " + Updater.sha256(content);
        store.write(Updater.FICHE_PATH, seal("maj|fiche", fiche.getBytes(StandardCharsets.US_ASCII)));
    }

    private static byte[] seal(String aad, byte[] plain) throws IOException {
        return Crypto.seal(key, 0, aad.getBytes(StandardCharsets.UTF_8), plain);
    }

    private static byte[] flip(byte[] b, int at) {
        byte[] c = b.clone();
        c[at] ^= 0x40;
        return c;
    }

    // --- 4. echange -------------------------------------------------------------

    private static void swap() throws Exception {
        Path dir = fresh("echange");
        Path j = jar(dir.resolve("cotransfert.jar"), T1, Updater.MAIN_CLASS, "v1");
        Path next = Updater.sibling(j, ".nouveau"), prev = Updater.sibling(j, ".precedent"), trial = Updater.sibling(j, ".essai");
        check(next.getFileName().toString().equals("cotransfert.jar.nouveau") && prev.getFileName().toString().equals("cotransfert.jar.precedent"),
                "noms des fichiers d'echange");
        byte[] b1 = Files.readAllBytes(j);

        // Rien en attente.
        check(Updater.applyPending(j) == null && Arrays.equals(Files.readAllBytes(j), b1) && !Files.exists(prev) && !Files.exists(trial),
                "echange sans rien en attente");

        // Nouveau -> jar, jar -> precedent, temoin pose.
        byte[] b2 = Files.readAllBytes(jar(next, T2, Updater.MAIN_CLASS, "v2"));
        Updater.Version v2 = Updater.applyPending(j);
        check(v2 != null && v2.sha256().equals(Updater.sha256(b2)) && v2.dateMs() == ms(T2), "version rendue par l'echange: " + v2);
        check(Arrays.equals(Files.readAllBytes(j), b2), "le nouveau jar n'est pas en place");
        check(Arrays.equals(Files.readAllBytes(prev), b1), "l'ancien jar n'est pas sous .precedent");
        check(!Files.exists(next), ".nouveau toujours la apres l'echange");
        Updater.Version t = Updater.trial(j);
        check(Files.isRegularFile(trial) && t != null && t.sha256().equals(v2.sha256()) && t.dateMs() == v2.dateMs(), "temoin d'essai: " + t);
        only(dir, "cotransfert.jar", "cotransfert.jar.precedent", "cotransfert.jar.essai");
        Updater.confirm(j);
        check(!Files.exists(trial) && Updater.trial(j) == null, "temoin non retire");
        Updater.confirm(j);   // sans effet quand il n'y en a pas

        // Deuxieme echange : .precedent est remplace par la version 2.
        byte[] b3 = Files.readAllBytes(jar(next, T3, Updater.MAIN_CLASS, "v3"));
        check(Updater.applyPending(j) != null && Arrays.equals(Files.readAllBytes(j), b3) && Arrays.equals(Files.readAllBytes(prev), b2),
                "deuxieme echange");
        Updater.confirm(j);

        // En attente invalide : ecarte, le jar reste, rien d'autre ne change.
        for (byte[] bad : new byte[][] { "n'importe quoi".getBytes(StandardCharsets.US_ASCII), new byte[0], Arrays.copyOf(b2, b2.length / 2) }) {
            Files.write(next, bad);
            check(throwsIo(() -> Updater.applyPending(j)), "fichier en attente invalide accepte");
            check(Arrays.equals(Files.readAllBytes(j), b3) && Arrays.equals(Files.readAllBytes(prev), b2) && !Files.exists(next) && !Files.exists(trial),
                    "etat apres un fichier en attente invalide");
        }
        Path wrong = jar(next, T3.plusDays(1), "exemple.Principal", "x");
        check(throwsIo(() -> Updater.applyPending(j)) && !Files.exists(wrong) && Arrays.equals(Files.readAllBytes(j), b3), "jar d'une autre classe echange");

        // Jar absent : la version en attente prend la place ; sinon la precedente est remise.
        Files.delete(j);
        Files.write(next, b2);
        check(Updater.applyPending(j) != null && Arrays.equals(Files.readAllBytes(j), b2), "echange sans jar en place");
        Updater.confirm(j);
        Files.delete(j);
        check(Updater.heal(j) && Arrays.equals(Files.readAllBytes(j), Files.readAllBytes(prev)), "jar absent non retabli depuis .precedent");
        check(!Updater.heal(j), "heal sur un jar present");
        only(dir, "cotransfert.jar", "cotransfert.jar.precedent");
        ok("echange (nouveau -> jar, jar -> precedent, temoin d'essai, fichier en attente invalide ecarte, jar absent retabli)");
    }

    // --- 5. retour arriere --------------------------------------------------------

    private static void rollback() throws Exception {
        Path dir = fresh("retour");
        Path j = jar(dir.resolve("cotransfert.jar"), T1, Updater.MAIN_CLASS, "v1");
        Path next = Updater.sibling(j, ".nouveau"), prev = Updater.sibling(j, ".precedent"), trial = Updater.sibling(j, ".essai");
        Path marker = dir.resolve("cotransfert.maj-refusee");
        byte[] b1 = Files.readAllBytes(j);

        check(throwsIo(() -> Updater.rollback(j, null)) && Arrays.equals(Files.readAllBytes(j), b1), "retour arriere sans version precedente");
        check(Updater.refused(j).isEmpty() && !Files.exists(marker), "versions refusees au depart");

        jar(next, T2, Updater.MAIN_CLASS, "v2 fautive");
        Updater.Version bad = Updater.applyPending(j);
        check(Files.exists(trial), "temoin absent avant le retour arriere");
        Updater.rollback(j, bad);
        check(Arrays.equals(Files.readAllBytes(j), b1), "la version precedente n'est pas retablie");
        check(Arrays.equals(Files.readAllBytes(prev), b1), ".precedent doit rester");
        check(!Files.exists(trial), "temoin toujours la apres le retour arriere");
        check(Files.isRegularFile(marker), "marqueur cotransfert.maj-refusee absent");
        Map<String, Long> ref = Updater.refused(j);
        check(ref.size() == 1 && Long.valueOf(bad.dateMs()).equals(ref.get(bad.sha256())), "marqueur: " + ref);
        check(new String(Files.readAllBytes(marker), StandardCharsets.UTF_8).contains(bad.sha256() + " " + bad.dateMs()), "contenu du marqueur");
        only(dir, "cotransfert.jar", "cotransfert.jar.precedent", "cotransfert.maj-refusee");
        check(!Updater.whyNot(Updater.versionOf(j), bad, ref.keySet()).isEmpty(), "version refusee encore proposee");

        // Plusieurs versions refusees : toutes gardees (les 20 dernieres), la meme notee une seule fois.
        Updater.refuse(j, bad);
        for (int i = 0; i < 25; i++) Updater.refuse(j, new Updater.Version(1000 + i, 10, Updater.sha256(("v" + i).getBytes(StandardCharsets.US_ASCII))));
        ref = Updater.refused(j);
        check(ref.size() == 20 && !ref.containsKey(bad.sha256()) && ref.containsValue(1024L) && !ref.containsValue(1004L), "marqueur borne: " + ref.size());
        // Marqueur abime : lignes illisibles ignorees.
        Files.write(marker, List.of("# commentaire", "pas une empreinte 12", bad.sha256() + " pas-un-nombre", bad.sha256() + " 42", ""));
        ref = Updater.refused(j);
        check(ref.size() == 1 && Long.valueOf(42).equals(ref.get(bad.sha256())), "marqueur abime: " + ref);
        ok("retour arriere (version precedente retablie, marqueur ecrit, temoin retire, versions refusees)");
    }

    // --- 6. etats pour la page ----------------------------------------------------

    private static void states() throws Exception {
        Path relay = fresh("relais-etats");
        DirRelayStore store = new DirRelayStore(relay.toString());

        // Indisponible : la raison, courte, dans "message" ; aucune action possible.
        Post off = new Post("etats-direct", false, T1, "v1", key, () -> store, Agent.NO_SECRET_DIRECT, 0);
        Map<String, Object> s = off.state();
        check(Boolean.FALSE.equals(s.get("supported")) && Boolean.FALSE.equals(s.get("canPublish")) && Boolean.FALSE.equals(s.get("available"))
                && Agent.NO_SECRET_DIRECT.equals(s.get("message")) && "".equals(s.get("state"))
                && "2026-03-01 10:00".equals(s.get("current")), "etat indisponible: " + off.up.json());
        check(!off.up.check().ok() && !off.up.install().ok() && !off.up.publish().ok(), "action acceptee alors que la mise a jour est indisponible");
        check(off.up.check().message().contains("lien direct"), "raison absente de la reponse");
        Post noKey = new Post("etats-sans-secret", true, T1, "v1", null, () -> store, "", 0);
        s = noKey.state();
        check(Boolean.FALSE.equals(s.get("supported")) && Boolean.FALSE.equals(s.get("canPublish")) && "secret partage manquant".equals(s.get("message")),
                "etat sans secret: " + noKey.up.json());
        check(!noKey.up.publish().ok() && !Files.exists(relay.resolve("_maj")), "publication sans secret");
        Post noStore = new Post("etats-sans-canal", false, T1, "v1", key, null, "", 0);
        check(Boolean.FALSE.equals(noStore.state().get("supported")) && String.valueOf(noStore.state().get("message")).contains("aucun canal"),
                "etat sans canal: " + noStore.up.json());
        AtomicReference<String> j = new AtomicReference<>();
        Updater noJar = new Updater(null, null, true, key, () -> store, "", j::set, 0);
        s = obj(j.get());
        check(Boolean.FALSE.equals(s.get("supported")) && "".equals(s.get("current")) && "CoTransfert n'est pas lance depuis un jar".equals(s.get("message")),
                "etat hors jar: " + j.get());
        check(!noJar.publish().ok() && !noJar.check().ok(), "action hors jar");
        // Tous les champs attendus par la page, dans tous les cas.
        for (String json : new String[] { off.up.json(), noKey.up.json(), j.get() }) {
            Map<String, Object> m = obj(json);
            check(m.keySet().equals(new java.util.LinkedHashSet<>(List.of("current", "supported", "canPublish", "available", "version", "state", "message"))),
                    "champs de l'etat: " + json);
        }

        // Magasin absent : echec dit clairement, sans exception ; il revient, tout repart.
        Path gone = work.resolve("relais-absent");
        Post waiting = new Post("etats-magasin-absent", false, T1, "v1", key, () -> new DirRelayStore(gone.toString()), "", 0);
        Updater.Check c = waiting.up.check();
        check(!c.ok() && c.message().contains("injoignable") && "failed".equals(waiting.state().get("state")), "magasin absent: " + c);
        Files.createDirectories(gone);
        c = waiting.up.check();
        check(c.ok() && !c.available() && "".equals(waiting.state().get("state")), "magasin revenu: " + c + " " + waiting.up.json());

        // Retour arriere juste avant ce demarrage : annonce des le depart, et jusqu'a l'arret de l'agent.
        Post hub = new Post("etats-hub", true, T2, "v2", key, () -> store, "", 0);
        Updater.Version v2 = Updater.versionOf(hub.jar);
        check(hub.up.publish().ok(), "etats: publication");
        Post back = new Post("etats-retour", false, T1, "v1", key, () -> store, "", v2.dateMs());
        Updater.refuse(back.jar, v2);
        s = back.state();
        check("rolled-back".equals(s.get("state")) && Boolean.FALSE.equals(s.get("available"))
                && "la mise a jour du 2026-03-02 11:30 n'a pas demarre, version precedente retablie".equals(s.get("message")),
                "etat juste apres un retour arriere: " + back.up.json());
        c = back.up.check();
        check(c.ok() && !c.available(), "version fautive reproposee apres le retour arriere: " + c);
        check(!back.up.install().ok() && !Files.exists(back.next()), "version fautive reinstallee apres le retour arriere");
        check("rolled-back".equals(back.state().get("state")) && String.valueOf(back.state().get("message")).contains("n'a pas demarre"),
                "retour arriere efface par une verification: " + back.up.json());
        Files.delete(relay.resolve("_maj").resolve("version.scelle"));
        c = back.up.check();
        check(c.ok() && c.message().contains("aucune version publiee") && "rolled-back".equals(back.state().get("state"))
                && String.valueOf(back.state().get("message")).contains("n'a pas demarre"),
                "retour arriere efface quand plus rien n'est publie: " + back.up.json());
        // Un agent relance plus tard (sans retour arriere juste avant) ne l'annonce plus tant que rien de refuse n'est publie.
        Post later = new Post("etats-plus-tard", false, T1, "v1", key, () -> store, "", 0);
        Updater.refuse(later.jar, v2);
        check("".equals(later.state().get("state")) && later.up.check().ok() && "".equals(later.state().get("state")),
                "retour arriere annonce sans raison: " + later.up.json());
        // Meme sans canal utilisable, le retour arriere qui vient d'avoir lieu est annonce.
        Post backOff = new Post("etats-retour-sans-canal", false, T1, "v1", key, () -> store, "aucun canal par lots n'est choisi", v2.dateMs());
        s = backOff.state();
        check("rolled-back".equals(s.get("state")) && String.valueOf(s.get("message")).contains("n'a pas demarre") && Boolean.FALSE.equals(s.get("supported")),
                "retour arriere sans canal: " + backOff.up.json());

        // Echec d'installation : garde apres une verification reussie sans mise a jour, efface par l'essai suivant.
        check(hub.up.publish().ok(), "etats: nouvelle publication");
        Post cl = new Post("etats-client", false, T1, "v1", key, () -> store, "", 0);
        Path sealed = relay.resolve("_maj").resolve("cotransfert.jar.scelle");
        byte[] good = Files.readAllBytes(sealed);
        Files.write(sealed, flip(good, 40));
        Updater.Result r = cl.up.install();
        check(!r.ok() && "failed".equals(cl.state().get("state")) && String.valueOf(cl.state().get("message")).startsWith("installation impossible"),
                "echec d'installation: " + cl.up.json());
        Files.delete(relay.resolve("_maj").resolve("version.scelle"));
        check(cl.up.check().ok() && "failed".equals(cl.state().get("state")) && String.valueOf(cl.state().get("message")).startsWith("installation impossible"),
                "echec d'installation efface par une verification: " + cl.up.json());
        Files.write(sealed, good);
        check(hub.up.publish().ok() && cl.up.install().ok() && "ready".equals(cl.state().get("state")), "essai suivant: " + cl.up.json());
        Files.delete(cl.next());

        // Installation automatique : une verification suffit, le redemarrage est demande une fois.
        Post auto = new Post("etats-auto", false, T1, "v1", key, () -> store, "", 0);
        AtomicInteger restarts = new AtomicInteger();
        auto.up.restart = restarts::incrementAndGet;
        check(!auto.up.round() && restarts.get() == 0 && !Files.exists(auto.next()) && Boolean.TRUE.equals(auto.state().get("available")),
                "installation sans update.auto: " + auto.up.json());
        auto.up.auto = true;
        check(auto.up.round() && restarts.get() == 1, "installation automatique: redemarrage non demande");
        check(Arrays.equals(Files.readAllBytes(auto.next()), Files.readAllBytes(hub.jar)) && "ready".equals(auto.state().get("state")),
                "installation automatique: " + auto.up.json());
        // Le fichier est reste en attente au demarrage suivant (echange non fait) : pas de boucle de redemarrages.
        AtomicReference<String> sj = new AtomicReference<>();
        Updater stuck = new Updater(auto.jar, Updater.versionOf(auto.jar), false, key, () -> store, "", sj::set, 0);
        stuck.auto = true;
        stuck.restart = restarts::incrementAndGet;
        check(!stuck.round() && restarts.get() == 1 && "failed".equals(obj(sj.get()).get("state")), "boucle d'installation automatique: " + sj.get());
        check(stuck.install().ok(), "installation manuelle apres un echange non fait");
        // Rien de publie de neuf : la verification periodique ne redemarre rien.
        Post same = new Post("etats-auto-a-jour", false, T2, "v2", key, () -> store, "", 0);
        same.up.auto = true;
        same.up.restart = restarts::incrementAndGet;
        check(!same.up.round() && restarts.get() == 1 && !Files.exists(same.next()), "redemarrage sans mise a jour");
        // Le hub ne verifie ni n'installe tout seul.
        hub.up.auto = true;
        hub.up.restart = restarts::incrementAndGet;
        check(!hub.up.round() && restarts.get() == 1 && !Files.exists(hub.next()), "le hub s'est mis a jour");

        // Aucun secret dans un message.
        Post leak = new Post("etats-fuite", true, T1, "v1", key, () -> new FailingStore("echec avec " + SECRET + " dedans"), "", 0);
        leak.up.setSecrets(List.of(SECRET));
        r = leak.up.publish();
        check(!r.ok() && !r.message().contains(SECRET) && !leak.up.json().contains(SECRET) && r.message().contains("***"), "secret dans un message: " + r);
        ok("etats pour la page (indisponible et pourquoi, retour arriere annonce, echec garde, installation automatique, pas de secret)");
    }

    /** Magasin joignable dont toute ecriture echoue avec un message donne. */
    private static final class FailingStore implements RelayStore {
        private final String why;
        FailingStore(String why) { this.why = why; }
        @Override public String id() { return "disque"; }
        @Override public String describe() { return "magasin d'essai"; }
        @Override public boolean available() { return true; }
        @Override public String lastError() { return ""; }
        @Override public List<Entry> list(String dir) { return List.of(); }
        @Override public byte[] read(String path, int maxBytes) { return null; }
        @Override public void write(String path, Path src) throws IOException { throw new IOException(why); }
        @Override public void write(String path, byte[] data) throws IOException { throw new IOException(why); }
        @Override public void delete(String path) { }
    }

    // --- 7. journal ---------------------------------------------------------------

    private static void journal() throws Exception {
        Path dir = fresh("journal");
        Path log = dir.resolve("cotransfert.log"), old = dir.resolve("cotransfert.log.1");
        check(Journal.fileFor(dir.resolve("cotransfert.conf")).equals(log), "emplacement du journal: " + Journal.fileFor(dir.resolve("cotransfert.conf")));
        check(Journal.MAX_BYTES == 2L << 20, "taille maximale du journal");

        check(!Journal.rotate(dir.resolve("absent.log"), 10), "archivage d'un journal absent");
        Files.write(log, "courte ligne\n".getBytes(StandardCharsets.UTF_8));
        check(!Journal.rotate(log, 100) && Files.exists(log) && !Files.exists(old), "archivage d'un petit journal");
        byte[] big = new byte[5000];
        Arrays.fill(big, (byte) 'a');
        Files.write(log, big);
        check(Journal.rotate(log, 4999) && !Files.exists(log) && Files.size(old) == 5000, "journal non archive au-dela de la taille maximale");
        // Un seul fichier archive : le precedent est remplace.
        Files.write(log, Arrays.copyOf(big, 300));
        check(Journal.rotate(log, 100) && Files.size(old) == 300 && !Files.exists(log), "archive precedente non remplacee");
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            check(s.count() == 1, "plus d'un fichier archive");
        }
        // Exactement a la limite : pas d'archivage.
        Files.write(log, Arrays.copyOf(big, 100));
        check(!Journal.rotate(log, 100), "archivage a la limite exacte");
        Files.delete(log);

        Journal.append(log, "premiere");
        Journal.append(log, "deuxieme avec accent \u00e9");
        for (int i = 3; i <= 1200; i++) Journal.append(log, "ligne " + i);
        List<String> t = Journal.tail(log, 2);
        check(t.size() == 2 && t.get(0).endsWith(" ligne 1199") && t.get(1).endsWith(" ligne 1200") && t.get(1).matches("\\d\\d:\\d\\d:\\d\\d ligne 1200"),
                "dernieres lignes: " + t);
        check(Journal.tail(log, 5000).size() == 1000 && Journal.tail(log, 0).size() == 1 && Journal.tail(log, 200).size() == 200, "bornes du nombre de lignes");
        Files.delete(log);
        Journal.append(log, "seule \u00e9");
        check(Journal.tail(log, 10).size() == 1 && Journal.tail(log, 10).get(0).endsWith("seule \u00e9"), "journal d'une ligne: " + Journal.tail(log, 10));
        check(Journal.tail(dir.resolve("absent.log"), 10).isEmpty() && Journal.tail(null, 10).isEmpty(), "journal absent");
        Journal.append(null, "sans journal");   // sans effet, sans exception
        ok("journal (archivage au-dela de la taille maximale, un seul fichier archive, dernieres lignes)");
    }

    // --- 8. faux serveurs WebDAV et Drime -------------------------------------------

    private static void online() throws Exception {
        FakeWebDavServer dav = new FakeWebDavServer(USER, PASSWORD);
        FakeDrimeServer drime = new FakeDrimeServer(TOKEN);
        try {
            // Magasins construits comme en production (Agent.Channels), un par poste.
            Config c = new Config();
            c.webdavUrl = dav.baseUrl(); c.webdavUser = USER; c.webdavPassword = PASSWORD;
            c.drimeUrl = drime.apiUrl(); c.drimeToken = TOKEN; c.drimeFolder = "Transit/Essai";
            c.proxy = "none";
            Agent.Channels hubCh = new Agent.Channels(c, true), clientCh = new Agent.Channels(c, true);
            try {
                check(hubCh.updateProblem("webdav").isEmpty() && hubCh.updateProblem("drime").isEmpty(), "canaux en ligne non regles");
                check(!hubCh.updateProblem("direct").isEmpty() && "non configure".equals(hubCh.updateProblem("disque")), "canaux sans mise a jour");

                Post hub = new Post("webdav-hub", true, T2, "v2", key, () -> hubCh.updateStore("webdav"), "", 0);
                Post client = new Post("webdav-client", false, T1, "v1", key, () -> clientCh.updateStore("webdav"), "", 0);
                STORES.put(client, () -> clientCh.updateStore("webdav"));
                roundTrip(hub, client, "webdav");
                check(dav.files().equals(List.of("_maj/cotransfert.jar.scelle", "_maj/version.scelle")), "fichiers sur le serveur WebDAV: " + dav.files());
                check(dav.file("_maj/cotransfert.jar.scelle").length == Files.size(hub.jar) + Crypto.SEAL_OVERHEAD, "taille du jar scelle sur le serveur WebDAV");
                check(indexOf(dav.file("_maj/version.scelle"), "CTMAJ1".getBytes(StandardCharsets.US_ASCII)) < 0, "fiche en clair sur le serveur WebDAV");
                // Fiche alteree sur le serveur : refusee.
                dav.put("_maj/version.scelle", flip(dav.file("_maj/version.scelle"), 20));
                check(!client.up.check().ok() && !client.up.install().ok() && !Files.exists(client.next()), "fiche alteree sur le serveur WebDAV acceptee");
                // Serveur en panne : echec dit clairement, puis tout repart.
                check(hub.up.publish().ok(), "webdav: nouvelle publication");
                dav.setDown(true);
                Updater.Check k = client.up.check();
                check(!k.ok() && !k.message().contains(PASSWORD), "serveur WebDAV en panne: " + k);
                dav.setDown(false);
                ok("meme aller-retour par un faux serveur WebDAV (fichiers _maj/ a la racine du dossier de transit)");

                hub = new Post("drime-hub", true, T2, "v2", key, () -> hubCh.updateStore("drime"), "", 0);
                client = new Post("drime-client", false, T1, "v1", key, () -> clientCh.updateStore("drime"), "", 0);
                STORES.put(client, () -> clientCh.updateStore("drime"));
                roundTrip(hub, client, "drime");
                FakeDrimeServer.Item fiche = drime.find("Transit/Essai/_maj/version.scelle"), sealed = drime.find("Transit/Essai/_maj/cotransfert.jar.scelle");
                check(fiche != null && sealed != null && !fiche.folder() && !sealed.folder(), "fichiers _maj/ absents du dossier de transit Drime");
                check(sealed.size() == Files.size(hub.jar) + Crypto.SEAL_OVERHEAD, "taille du jar scelle sur Drime: " + sealed.size());
                check(indexOf(drime.content(fiche.id()), "CTMAJ1".getBytes(StandardCharsets.US_ASCII)) < 0, "fiche en clair sur Drime");
                check(drime.authLeaks() == 0, "jeton envoye hors de l'API Drime");
                // Republier remplace la version publiee : un seul fichier vivant de chaque nom.
                Post hub3 = new Post("drime-hub-3", true, T3, "v3", key, () -> hubCh.updateStore("drime"), "", 0);
                check(hub3.up.publish().ok(), "drime: publication de la version 3");
                Updater.Check k3 = client.up.check();
                check(k3.ok() && k3.available() && k3.version().equals("2026-03-03 09:15"), "drime: version 3 non vue: " + k3);
                check(client.up.install().ok() && Arrays.equals(Files.readAllBytes(client.next()), Files.readAllBytes(hub3.jar)), "drime: installation de la version 3");
                long live = drime.entries().stream().filter(i -> !i.folder() && i.name().endsWith(".scelle")).count();
                check(live == 2, "drime: fichiers de mise a jour vivants: " + live);
                ok("meme aller-retour par un faux serveur Drime (fichiers _maj/ dans le dossier de transit)");
            } finally {
                hubCh.shutdown();
                clientCh.shutdown();
            }
        } finally {
            dav.stop();
            drime.stop();
        }
    }

    // --- 9. lien direct -------------------------------------------------------------

    /** Reponse d'une requete HTTP brute vers les routes du hub : code (ou -1 si la connexion a casse) et corps. */
    private record Raw(int status, byte[] body) { }

    private static Raw http(int port, String method, String pathAndQuery, byte[] body, boolean chunked) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) java.net.URI.create("http://127.0.0.1:" + port + pathAndQuery)
                    .toURL().openConnection(java.net.Proxy.NO_PROXY);
            c.setConnectTimeout(4000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod(method);
            try {
                if (body != null) {
                    c.setDoOutput(true);
                    if (chunked) c.setChunkedStreamingMode(8192);
                    else c.setFixedLengthStreamingMode(body.length);
                    try (OutputStream o = c.getOutputStream()) { o.write(body); }
                }
                int code = c.getResponseCode();
                java.io.InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
                byte[] b = in == null ? new byte[0] : in.readAllBytes();
                if (in != null) in.close();
                return new Raw(code, b);
            } finally {
                c.disconnect();
            }
        } catch (IOException e) {
            return new Raw(-1, new byte[0]);
        }
    }

    /** Empreinte de tous les fichiers sous un dossier, par chemin relatif : pour prouver que rien n'a bouge. */
    private static Map<String, String> snapshot(Path dir) throws IOException {
        Map<String, String> m = new java.util.TreeMap<>();
        List<Path> files;
        try (java.util.stream.Stream<Path> s = Files.walk(dir)) {
            files = s.filter(Files::isRegularFile).toList();
        }
        for (Path p : files) m.put(dir.relativize(p).toString().replace('\\', '/'), Updater.sha256(Files.readAllBytes(p)));
        return m;
    }

    private static void direct() throws Exception {
        // Le poste hub : sa configuration (temoin : jamais lue ni modifiee par le reseau) et, a cote, cotransfert.maj.
        Path hubHome = fresh("direct-poste-hub");
        Path conf = hubHome.resolve("cotransfert.conf");
        Files.write(conf, List.of("role=hub", "secret=" + SECRET));
        Path root = MajRoutes.rootFor(conf);
        check(root.equals(hubHome.resolve("cotransfert.maj")), "dossier de la publication: " + root);
        Path fiche = root.resolve("_maj").resolve("version.scelle"), sealed = root.resolve("_maj").resolve("cotransfert.jar.scelle");

        // Le hub sert sa page avec les routes /maj/ ; l'adresse du hub n'est pas encore connue du client.
        com.sun.net.httpserver.HttpServer srv = WebServer.start(0, true, null, new MajRoutes(root, key));
        final int port = srv.getAddress().getPort();
        AtomicReference<String> hubAddr = new AtomicReference<>();
        java.util.function.Supplier<RelayStore> hubStores = () -> MajRoutes.localStore(root);
        java.util.function.Supplier<RelayStore> clientStores = () -> new HubHttpRelayStore(hubAddr::get, port);
        try {
            Post hub = new Post("direct-hub", true, T2, "v2", key, hubStores, "", 0);
            Post client = new Post("direct-client", false, T1, "v1", key, clientStores, "", 0);
            STORES.put(client, clientStores);
            Updater.Version v1 = Updater.versionOf(client.jar), v2 = Updater.versionOf(hub.jar);

            // Hub pas encore trouve : indisponible, dit clairement, sans exception ; puis il apparait.
            HubHttpRelayStore lost = new HubHttpRelayStore(hubAddr::get, port);
            check(!lost.available() && lost.lastError().contains("pas encore trouve"), "hub non localise: " + lost.lastError());
            check(throwsIo(() -> lost.read(Updater.FICHE_PATH, 100)) && throwsIo(() -> lost.write(Updater.FICHE_PATH, new byte[1])),
                    "lecture ou ecriture sans hub");
            Post early = new Post("direct-client-en-attente", false, T1, "v1", key, clientStores, "", 0);
            Updater.Check c = early.up.check();
            check(!c.ok() && !c.available() && c.message().contains("pas encore trouve") && "failed".equals(early.state().get("state")),
                    "verification sans hub: " + c + " " + early.up.json());
            hubAddr.set("127.0.0.1");
            check(lost.available() && lost.lastError().isEmpty() && lost.id().equals("direct") && !lost.describe().contains(SECRET),
                    "hub trouve: " + lost.lastError());
            c = early.up.check();
            check(c.ok() && !c.available() && "".equals(early.state().get("state")), "verification une fois le hub trouve: " + c + " " + early.up.json());
            check(lost.read(Updater.FICHE_PATH, 100) == null && lost.list("_maj").isEmpty() && lost.list("ailleurs").isEmpty(), "magasin vide");

            // Meme aller-retour que sur les autres canaux : le hub publie, le client voit, installe.
            roundTrip(hub, client, "direct");
            check(Files.isRegularFile(fiche) && Files.isRegularFile(sealed), "fichiers _maj/ absents du dossier du hub");
            only(root.resolve("_maj"), "version.scelle", "cotransfert.jar.scelle");
            only(hubHome, "cotransfert.conf", "cotransfert.maj");
            byte[] stored = Files.readAllBytes(sealed);
            check(stored.length == Files.size(hub.jar) + Crypto.SEAL_OVERHEAD && !(stored[0] == 'P' && stored[1] == 'K')
                    && indexOf(Files.readAllBytes(fiche), "CTMAJ1".getBytes(StandardCharsets.US_ASCII)) < 0, "publication en clair dans le dossier du hub");
            // Ce que le client lit par HTTP est exactement ce que le hub a range.
            check(Arrays.equals(lost.read(Updater.JAR_PATH, 0), stored) && Arrays.equals(lost.read(Updater.FICHE_PATH, 4096), Files.readAllBytes(fiche)),
                    "contenu lu par HTTP different du dossier du hub");
            check(lost.read(Updater.JAR_PATH, 10).length == 10, "lecture bornee");
            List<RelayStore.Entry> ls = lost.list("_maj");
            check(ls.size() == 2 && ls.contains(new RelayStore.Entry("version.scelle", Files.size(fiche)))
                    && ls.contains(new RelayStore.Entry("cotransfert.jar.scelle", stored.length)), "liste: " + ls);
            Raw raw = http(port, "GET", "/maj/liste", null, false);
            check(raw.status == 200 && new String(raw.body, StandardCharsets.UTF_8).startsWith("CTMAJ-LISTE 1\n"), "route /maj/liste: " + raw.status);
            // Installation automatique : identique aux autres canaux.
            Post auto = new Post("direct-auto", false, T1, "v1", key, clientStores, "", 0);
            AtomicInteger restarts = new AtomicInteger();
            auto.up.restart = restarts::incrementAndGet;
            auto.up.auto = true;
            check(auto.up.round() && restarts.get() == 1 && Arrays.equals(Files.readAllBytes(auto.next()), Files.readAllBytes(hub.jar)),
                    "direct: installation automatique: " + auto.up.json());
            ok("lien direct : le hub publie dans son dossier local, le client voit et installe par HTTP sortant (meme aller-retour)");

            // Le client publie (PUT vers le hub) : le hub la voit, dans son dossier local.
            Path tmp = fresh("direct-jars");
            Path jar3 = jar(tmp.resolve("v3.jar"), T3, Updater.MAIN_CLASS, "v3");
            Updater.Version v3 = Updater.publish(lost, key, jar3);
            check(Updater.published(MajRoutes.localStore(root), key).equals(v3), "publication du client non vue dans le dossier du hub");
            c = hub.up.check();
            check(c.ok() && c.version().equals("2026-03-03 09:15"), "publication du client non vue par le hub: " + c);
            check(Arrays.equals(Updater.openJar(key, Files.readAllBytes(sealed)), Files.readAllBytes(jar3)), "jar publie par le client different");
            only(root.resolve("_maj"), "version.scelle", "cotransfert.jar.scelle");
            c = client.up.check();
            check(c.ok() && c.available() && c.version().equals("2026-03-03 09:15"), "version 3 non proposee au client: " + c);
            ok("lien direct : le client publie par PUT, le hub voit la version dans son dossier");

            // A partir d'ici, rien ne doit changer dans le poste hub.
            Map<String, String> before = snapshot(hubHome);

            // Mauvais secret : refuse (403), par le magasin comme par une requete brute.
            Crypto.Key other = Crypto.derive("un-autre-secret-0123456789");
            Path jar4 = jar(tmp.resolve("v4.jar"), T3.plusDays(1), Updater.MAIN_CLASS, "v4");
            String why;
            try {
                Updater.publish(lost, other, jar4);
                why = "";
            } catch (IOException e) {
                why = String.valueOf(e.getMessage());
            }
            check(why.contains("refuse par le hub") && why.contains("secret") && !why.contains("un-autre-secret"), "publication avec un autre secret: " + why);
            byte[] strangerFiche = Crypto.seal(other, 0, "maj|fiche".getBytes(StandardCharsets.UTF_8), v3.fiche().getBytes(StandardCharsets.US_ASCII));
            byte[] garbage = "ceci n'est pas scelle".getBytes(StandardCharsets.US_ASCII);
            for (String name : new String[] { "version.scelle", "cotransfert.jar.scelle" }) {
                check(http(port, "PUT", "/maj/fichier?nom=" + name, garbage, false).status == 403, "contenu non scelle accepte: " + name);
                check(http(port, "PUT", "/maj/fichier?nom=" + name, strangerFiche, false).status == 403, "contenu d'un autre secret accepte: " + name);
                check(http(port, "PUT", "/maj/fichier?nom=" + name, new byte[0], false).status == 403, "contenu vide accepte: " + name);
            }
            // Bon secret, mauvaise donnee associee : un lot ou une fiche ne passent pas pour un jar, ni l'inverse.
            check(http(port, "PUT", "/maj/fichier?nom=cotransfert.jar.scelle", seal("maj|fiche", Files.readAllBytes(jar4)), false).status == 403
                    && http(port, "PUT", "/maj/fichier?nom=cotransfert.jar.scelle", seal("classeur|hub", Files.readAllBytes(jar4)), false).status == 403
                    && http(port, "PUT", "/maj/fichier?nom=version.scelle", seal("maj|jar", v3.fiche().getBytes(StandardCharsets.US_ASCII)), false).status == 403,
                    "donnee associee inattendue acceptee");
            // Fiche authentique sans son jar : refusee (409).
            Updater.Version v4 = Updater.versionOf(jar4);
            check(http(port, "PUT", "/maj/fichier?nom=version.scelle", seal("maj|fiche", v4.fiche().getBytes(StandardCharsets.US_ASCII)), false).status == 409,
                    "fiche sans son jar acceptee");
            check(snapshot(hubHome).equals(before), "le poste hub a change apres des depots refuses: " + snapshot(hubHome).keySet());
            check(Updater.published(lost, key).equals(v3), "publication abimee par des depots refuses");

            // Version plus ancienne rejouee par le reseau : refusee, la publication reste.
            check(throwsIo(() -> Updater.publish(lost, key, hub.jar)), "version plus ancienne publiee par-dessus une plus recente");
            check(snapshot(hubHome).equals(before) && Updater.published(lost, key).equals(v3), "publication remplacee par une version plus ancienne");

            // Nom hors liste, parcours de chemin, autre route, autre methode : refuses.
            String[] names = { "", "..", "../cotransfert.conf", "..%2Fcotransfert.conf", "%2e%2e%2fcotransfert.conf", "..%5Ccotransfert.conf",
                    "version.scelle/../../cotransfert.conf", "_maj/version.scelle", "_maj%2Fversion.scelle", "version.scelle.part",
                    "cotransfert.jar.scelle.part", "cotransfert.jar.scelle.recu", "VERSION.SCELLE", "version.scelle%00", "version.scelle%20",
                    "cotransfert.conf", "autre.txt", "version.scelle&nom=cotransfert.jar.scelle" };
            byte[] goodFiche = Files.readAllBytes(fiche);
            byte[] canary = "role=hub".getBytes(StandardCharsets.US_ASCII);
            for (String name : names) {
                Raw g = http(port, "GET", "/maj/fichier?nom=" + name, null, false);
                check(g.status == 400 && indexOf(g.body, canary) < 0, "nom accepte en lecture: [" + name + "] -> " + g.status);
                check(http(port, "PUT", "/maj/fichier?nom=" + name, goodFiche, false).status == 400, "nom accepte en ecriture: [" + name + "]");
            }
            check(http(port, "GET", "/maj/fichier", null, false).status == 400 && http(port, "GET", "/maj/fichier?autre=version.scelle", null, false).status == 400,
                    "requete sans nom acceptee");
            for (String path : new String[] { "/maj/", "/maj/autre", "/maj/listes", "/maj/liste/x", "/maj/fichier/version.scelle",
                    "/maj/fichier/../../cotransfert.conf", "/maj/../cotransfert.conf", "/maj/_maj/version.scelle" }) {
                Raw g = http(port, "GET", path + "?nom=version.scelle", null, false);
                check(g.status != 200 || (indexOf(g.body, canary) < 0 && !Arrays.equals(g.body, goodFiche)),
                        "route inattendue servie: " + path + " -> " + g.status);
                check(g.status == 404 || path.contains(".."), "route inattendue: " + path + " -> " + g.status);
            }
            for (String method : new String[] { "DELETE", "POST" }) {
                check(http(port, method, "/maj/fichier?nom=version.scelle", null, false).status == 405, method + " accepte sur /maj/fichier");
                check(http(port, method, "/maj/liste", null, false).status == 405, method + " accepte sur /maj/liste");
            }
            check(http(port, "PUT", "/maj/liste", goodFiche, false).status == 405, "PUT accepte sur /maj/liste");
            check(throwsIo(() -> lost.delete(Updater.FICHE_PATH)) && throwsIo(() -> lost.read("_maj/../cotransfert.conf", 100))
                    && throwsIo(() -> lost.read("autre/version.scelle", 100)) && throwsIo(() -> lost.write("_maj/autre.scelle", goodFiche))
                    && throwsIo(() -> lost.write("../cotransfert.conf", goodFiche)), "le magasin du client accepte un autre chemin ou une suppression");
            check(snapshot(hubHome).equals(before), "le poste hub a change apres des requetes refusees: " + snapshot(hubHome).keySet());
            only(hubHome, "cotransfert.conf", "cotransfert.maj");

            // Fichier trop gros : refuse (413) avant toute ecriture, longueur annoncee ou non.
            byte[] bigFiche = new byte[Updater.FICHE_MAX_BYTES + 1];
            check(http(port, "PUT", "/maj/fichier?nom=version.scelle", bigFiche, false).status == 413, "fiche trop grosse acceptee");
            check(http(port, "PUT", "/maj/fichier?nom=version.scelle", bigFiche, true).status == 413, "fiche trop grosse acceptee (sans longueur annoncee)");
            byte[] bigJar = new byte[(int) MajRoutes.JAR_MAX_BYTES + 1];
            int st = http(port, "PUT", "/maj/fichier?nom=cotransfert.jar.scelle", bigJar, false).status;
            check(st == 413 || st == -1, "jar trop gros accepte: " + st);
            st = http(port, "PUT", "/maj/fichier?nom=cotransfert.jar.scelle", bigJar, true).status;
            check(st == 413 || st == -1, "jar trop gros accepte (sans longueur annoncee): " + st);
            check(throwsIo(() -> lost.write(Updater.JAR_PATH, bigJar)) && throwsIo(() -> lost.write(Updater.FICHE_PATH, bigFiche)),
                    "le magasin du client envoie un fichier trop gros");
            check(snapshot(hubHome).equals(before) && Updater.published(lost, key).equals(v3), "le poste hub a change apres un fichier trop gros");
            ok("lien direct : refus (autre secret, non scelle, nom hors liste, parcours de chemin, trop gros, version plus ancienne, "
                    + "suppression), publication intacte");

            // Regles de disponibilite (Agent) : avec un secret, disponible ; sans secret, la raison dit quoi faire.
            Agent.Channels ch = new Agent.Channels(new Config(), true);
            check(Agent.updateProblem(hub.jar, v2, true, "direct", true, ch, true, true).isEmpty()
                    && Agent.updateProblem(client.jar, v1, true, "direct", true, ch, false, false).isEmpty(), "lien direct avec secret: indisponible");
            String noSecret = Agent.updateProblem(hub.jar, v2, true, "direct", false, ch, true, true);
            check(noSecret.equals(Agent.NO_SECRET_DIRECT) && noSecret.contains("meme secret partage sur les deux postes")
                    && noSecret.equals(Agent.updateProblem(client.jar, v1, true, "direct", false, ch, false, true)), "lien direct sans secret: " + noSecret);
            check(Agent.updateProblem(hub.jar, v2, true, "direct", true, ch, true, false).contains("--no-web"), "hub sans page");
            check(Agent.updateProblem(null, null, true, "direct", true, ch, true, true).equals(Updater.NOT_A_JAR)
                    && !Agent.updateProblem(hub.jar, v2, false, "direct", true, ch, true, true).isEmpty()
                    && Agent.updateProblem(hub.jar, v2, true, "", true, ch, true, true).contains("aucun canal"), "autres raisons changees");
            Post noKeyHub = new Post("direct-sans-secret", true, T2, "v2", null, hubStores, noSecret, 0);
            Map<String, Object> s = noKeyHub.state();
            check(Boolean.FALSE.equals(s.get("supported")) && Boolean.FALSE.equals(s.get("canPublish")) && noSecret.equals(s.get("message")),
                    "etat sans secret sur le lien direct: " + noKeyHub.up.json());

            // Hub sans ces routes (pas de secret : 404), ou plus ancien (il repond par sa page) : indisponible, sans exception.
            com.sun.net.httpserver.HttpServer plain = WebServer.start(0, true, null, null);
            com.sun.net.httpserver.HttpServer old = com.sun.net.httpserver.HttpServer.create(
                    new java.net.InetSocketAddress(java.net.InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 }), 0), 0);
            old.createContext("/", ex -> {
                byte[] b = "<!doctype html><title>page</title>".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, b.length);
                try (OutputStream o = ex.getResponseBody()) { o.write(b); }
                ex.close();
            });
            old.start();
            try {
                for (com.sun.net.httpserver.HttpServer h : List.of(plain, old)) {
                    HubHttpRelayStore none = new HubHttpRelayStore(() -> "127.0.0.1", h.getAddress().getPort());
                    check(!none.available() && none.lastError().contains("ne sert pas la mise a jour"), "hub sans routes /maj/: " + none.lastError());
                }
                check(http(plain.getAddress().getPort(), "GET", "/maj/fichier?nom=version.scelle", null, false).status == 404
                        && http(plain.getAddress().getPort(), "PUT", "/maj/fichier?nom=version.scelle", goodFiche, false).status == 404,
                        "routes /maj/ servies sans secret");
            } finally {
                plain.stop(0);
                old.stop(0);
            }
            check(throwsRuntime(() -> new MajRoutes(root, null)), "routes creees sans secret");

            // Hub arrete : indisponible, raison courte, aucune exception ; l'etat pour la page le dit.
            srv.stop(0);
            HubHttpRelayStore down = new HubHttpRelayStore(() -> "127.0.0.1", port);
            check(!down.available() && down.lastError().contains("hub injoignable"), "hub arrete: " + down.lastError());
            check(throwsIo(() -> down.read(Updater.FICHE_PATH, 100)) && throwsIo(() -> down.list("_maj")), "lecture d'un hub arrete");
            Post orphan = new Post("direct-hub-arrete", false, T1, "v1", key, () -> new HubHttpRelayStore(() -> "127.0.0.1", port), "", 0);
            c = orphan.up.check();
            check(!c.ok() && !c.available() && c.message().contains("injoignable") && "failed".equals(orphan.state().get("state")),
                    "verification avec un hub arrete: " + c);
            Updater.Result r = orphan.up.install();
            check(!r.ok() && !Files.exists(orphan.next()), "installation avec un hub arrete: " + r);
            check(!new HubHttpRelayStore(() -> { throw new IllegalStateException("essai"); }, port).available()
                    && !new HubHttpRelayStore(() -> "  ", port).available()
                    && !new HubHttpRelayStore(() -> "adresse invalide avec espaces", port).available(), "adresse du hub inutilisable acceptee");
            ok("lien direct : hub pas encore trouve, arrete, sans secret ou trop ancien -> indisponible et dit clairement, sans exception");
        } finally {
            srv.stop(0);
        }
    }

    private static boolean throwsRuntime(Runnable r) {
        try {
            r.run();
            return false;
        } catch (RuntimeException e) {
            return true;
        }
    }

    // --- outils -------------------------------------------------------------------

    /**
     * Fabrique un jar : manifeste date (c'est lui qui porte la version), classe
     * principale donnee (null = aucune), et un fichier dont le contenu distingue les versions.
     */
    static Path jar(Path file, LocalDateTime built, String mainClass, String marker) throws IOException {
        long t = built.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        try (OutputStream o = Files.newOutputStream(file); JarOutputStream j = new JarOutputStream(o)) {
            ZipEntry mf = new ZipEntry("META-INF/MANIFEST.MF");
            mf.setTime(t);
            j.putNextEntry(mf);
            String text = "Manifest-Version: 1.0\r\n" + (mainClass == null ? "" : "Main-Class: " + mainClass + "\r\n") + "\r\n";
            j.write(text.getBytes(StandardCharsets.UTF_8));
            j.closeEntry();
            ZipEntry e = new ZipEntry("cotransfert/version.txt");
            e.setTime(t);
            j.putNextEntry(e);
            j.write(marker.getBytes(StandardCharsets.UTF_8));
            j.closeEntry();
        }
        return file;
    }

    /** Date de construction attendue : l'heure ecrite dans le jar, lue comme une heure UTC. */
    private static long ms(LocalDateTime built) { return built.toInstant(ZoneOffset.UTC).toEpochMilli(); }

    private interface Io { void run() throws IOException; }

    private static boolean throwsIo(Io r) {
        try {
            r.run();
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    private static Map<String, Object> obj(String json) throws IOException {
        Object o = DrimeRelayStore.parseJson(json);
        if (!(o instanceof Map<?, ?> m)) throw new IOException("objet JSON attendu: " + json);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
        return out;
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int k = 0; k < needle.length; k++) if (hay[i + k] != needle[k]) continue outer;
            return i;
        }
        return -1;
    }

    /** Le dossier ne contient que ces fichiers. */
    private static void only(Path dir, String... names) throws IOException {
        List<String> got = new ArrayList<>();
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            s.forEach(p -> got.add(p.getFileName().toString()));
        }
        java.util.Collections.sort(got);
        List<String> want = new ArrayList<>(List.of(names));
        java.util.Collections.sort(want);
        check(got.equals(want), "contenu de " + dir.getFileName() + ": " + got + ", attendu " + want);
    }

    private static Path fresh(String name) throws IOException {
        Path d = work.resolve(name);
        wipe(d);
        return Files.createDirectories(d);
    }

    private static void ok(String what) { System.out.println("  ok  " + (++n) + ". " + what); }

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
