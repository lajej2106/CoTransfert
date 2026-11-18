package cotransfert;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Redemarrage de l'agent sans script (reglages enregistres, changement de
 * canal, mise a jour). Un processus Java ne peut ni se relancer apres sa propre
 * fin, ni remplacer le jar qu'il execute (Windows le verrouille) : l'agent
 * copie donc son jar dans un fichier temporaire, lance depuis cette copie ce
 * petit programme, puis s'arrete (code 0).
 *
 *   javaw -cp <copie> cotransfert.Relance <pid de l'agent> <jar> <journal ou -> [arguments de l'agent...]
 *
 * La relance :
 *   1. attend la fin de l'agent (30 s au plus ; au-dela elle abandonne : il tourne encore) ;
 *   2. applique une mise a jour en attente ({@link Updater#applyPending}) ;
 *   3. relance "javaw -jar cotransfert.jar" avec les memes arguments, la meme JVM,
 *      et COTRANSFERT_RELANCE=1 (ne pas rouvrir le navigateur) ;
 *   4. apres un echange, surveille le nouvel agent pendant 30 s : s'il s'arrete
 *      avec une erreur, elle remet la version precedente ({@link Updater#rollback}),
 *      note l'empreinte fautive et relance l'ancienne version ;
 *   5. se termine. Sa copie du jar ne peut pas etre supprimee tant qu'elle
 *      tourne : l'agent s'en charge a son prochain demarrage ({@link #cleanTemp}).
 *
 * Tout est detache : entree, sortie et erreur des processus lances sont
 * redirigees vers le peripherique nul, aucune console n'est tenue ouverte. Ce
 * que fait la relance est ecrit dans le journal de l'agent.
 */
public final class Relance {

    private Relance() { }

    static final String ENV_RESTARTED = "COTRANSFERT_RELANCE";
    static final String ENV_CONSOLE = "COTRANSFERT_CONSOLE";
    /** Posee quand le lancement suit un echange : une erreur fatale ne doit pas attendre derriere une boite de dialogue. */
    static final String ENV_TRIAL = "COTRANSFERT_ESSAI";

    /** Posee quand l'ancienne version est relancee apres un retour arriere : date (ms) de la version retiree. */
    static final String ENV_ROLLED_BACK = "COTRANSFERT_RETOUR";

    private static final String TEMP_PREFIX = "cotransfert-relance-";
    private static final long PARENT_WAIT_MS = 30_000, WATCH_MS = 30_000, LOCK_WAIT_MS = 10_000, PLAIN_WATCH_MS = 10_000;

    private static Path log;

    // --- cote agent ----------------------------------------------------------

    /**
     * Lance le processus de relance depuis une copie temporaire du jar. L'appelant
     * s'arrete ensuite (code 0).
     * @throws IOException copie ou lancement impossible : rien n'est lance, l'agent decide quoi faire
     */
    static void spawn(Path jar, String[] agentArgs, Path journal) throws IOException {
        Path copy = Files.createTempFile(TEMP_PREFIX, ".jar");
        try {
            Files.copy(jar, copy, StandardCopyOption.REPLACE_EXISTING);
            List<String> cmd = new ArrayList<>(List.of(java(), "-cp", copy.toString(), Relance.class.getName(),
                    Long.toString(ProcessHandle.current().pid()), jar.toAbsolutePath().toString(),
                    journal == null ? "-" : journal.toAbsolutePath().toString()));
            cmd.addAll(Arrays.asList(agentArgs));
            ProcessBuilder pb = detached(cmd);
            pb.environment().remove(ENV_CONSOLE);
            pb.environment().remove(ENV_ROLLED_BACK);
            pb.start();
        } catch (IOException | RuntimeException e) {
            try { Files.deleteIfExists(copy); } catch (IOException ignore) { /* nettoye au prochain demarrage */ }
            throw e instanceof IOException io ? io : new IOException(String.valueOf(e.getMessage()));
        }
    }

    /**
     * Supprime, au mieux, les copies temporaires laissees par des relances
     * terminees. Une copie encore utilisee (relance en cours) est verrouillee :
     * elle reste, et sera retiree une autre fois.
     */
    static void cleanTemp() {
        try {
            Path tmp = Paths.get(System.getProperty("java.io.tmpdir", "")).toAbsolutePath();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(tmp, TEMP_PREFIX + "*.jar")) {
                for (Path p : ds) {
                    try { Files.deleteIfExists(p); } catch (IOException | RuntimeException e) { /* encore utilisee */ }
                }
            }
        } catch (IOException | RuntimeException e) {
            // dossier temporaire illisible : rien a nettoyer
        }
    }

    /**
     * La JVM en cours, sans console quand c'est possible : le javaw.exe voisin de
     * l'executable de ce processus, sinon cet executable lui-meme.
     */
    static String java() {
        Path exe = null;
        try {
            String c = ProcessHandle.current().info().command().orElse(null);
            if (c != null && !c.isBlank()) exe = Paths.get(c);
        } catch (RuntimeException e) {
            // repli ci-dessous
        }
        if (exe == null || !Files.isRegularFile(exe)) {
            Path bin = Paths.get(System.getProperty("java.home", ""), "bin");
            exe = Files.isRegularFile(bin.resolve("java.exe")) ? bin.resolve("java.exe") : bin.resolve("java");
        }
        Path w = exe.resolveSibling("javaw.exe");
        return (Files.isRegularFile(w) ? w : exe).toString();
    }

    /** Processus sans aucun lien avec les flux de celui-ci : entree, sortie et erreur sur le peripherique nul. */
    private static ProcessBuilder detached(List<String> cmd) {
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        return new ProcessBuilder(cmd)
                .redirectInput(ProcessBuilder.Redirect.from(new File(win ? "NUL" : "/dev/null")))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
    }

    // --- processus de relance ------------------------------------------------

    public static void main(String[] a) {
        if (a.length < 3) return;
        try {
            log = "-".equals(a[2]) ? null : Paths.get(a[2]);
            run(Long.parseLong(a[0]), Paths.get(a[1]), Arrays.asList(a).subList(3, a.length));
        } catch (Throwable t) {
            say("echec (" + t.getClass().getSimpleName() + (t.getMessage() == null ? "" : " : " + t.getMessage())
                    + ") : relancer CoTransfert.cmd");
        }
    }

    private static void say(String line) { Journal.append(log, "relance: " + line); }

    private static void run(long parent, Path jar, List<String> agentArgs) throws Exception {
        // 1. La fin de l'agent : tant qu'il vit, son jar est verrouille et son port est pris.
        Optional<ProcessHandle> ph = ProcessHandle.of(parent);
        if (ph.isPresent()) {
            try {
                ph.get().onExit().get(PARENT_WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                say("l'agent (processus " + parent + ") ne s'est pas arrete en " + PARENT_WAIT_MS / 1000 + " s : relance abandonnee, il tourne toujours");
                return;
            } catch (ExecutionException e) {
                // etat du processus illisible : on continue
            }
        }

        // 2. Mise a jour en attente. Le verrou du jar peut survivre un instant a l'agent : on insiste un peu.
        Updater.Version swapped = null;
        long end = System.currentTimeMillis() + LOCK_WAIT_MS;
        while (true) {
            try {
                swapped = Updater.applyPending(jar);
                break;
            } catch (IOException e) {
                boolean pending = Files.exists(Updater.sibling(jar, Updater.SUFFIX_NEW));
                if (!pending || System.currentTimeMillis() > end) {
                    say("mise a jour non appliquee (" + e.getMessage() + ") : la version en place est relancee");
                    break;
                }
                Thread.sleep(300);
            }
        }
        try {
            if (Updater.heal(jar)) say("cotransfert.jar etait absent : version precedente remise en place");
        } catch (IOException e) {
            say("cotransfert.jar absent et version precedente non retablie (" + e.getMessage() + ")");
        }
        if (!Files.isRegularFile(jar)) {
            say("cotransfert.jar introuvable (" + jar + ") : relance impossible");
            return;
        }

        // 3. Relance, puis 4. surveillance apres un echange.
        if (swapped == null) {
            Process p = launch(jar, agentArgs, false, 0);
            say("agent relance (processus " + p.pid() + ")");
            if (p.waitFor(PLAIN_WATCH_MS, TimeUnit.MILLISECONDS) && p.exitValue() != 0)
                say("l'agent relance s'est arrete avec le code " + p.exitValue() + " : voir les lignes ci-dessus, puis relancer CoTransfert.cmd");
            return;
        }
        say("mise a jour appliquee : version du " + swapped.label() + " ; demarrage surveille pendant " + WATCH_MS / 1000 + " s");
        Process p = launch(jar, agentArgs, true, 0);
        if (!p.waitFor(WATCH_MS, TimeUnit.MILLISECONDS)) {
            Updater.confirm(jar);
            say("la version du " + swapped.label() + " a demarre (processus " + p.pid() + ")");
            return;
        }
        int code = p.exitValue();
        if (code == 0) {
            Updater.confirm(jar);
            say("la version du " + swapped.label() + " a demarre puis s'est arretee normalement");
            return;
        }
        say("la version du " + swapped.label() + " s'est arretee avec le code " + code + " : retour a la version precedente");
        end = System.currentTimeMillis() + LOCK_WAIT_MS;
        while (true) {
            try {
                Updater.rollback(jar, swapped);
                break;
            } catch (IOException e) {
                if (System.currentTimeMillis() > end) {
                    say("retour arriere impossible (" + e.getMessage() + ") : relancer CoTransfert.cmd, qui remet la version precedente");
                    return;
                }
                Thread.sleep(300);
            }
        }
        Process old = launch(jar, agentArgs, false, swapped.dateMs());
        say("version precedente retablie et relancee (processus " + old.pid() + ") ; la version du " + swapped.label() + " ne sera plus proposee");
        if (old.waitFor(PLAIN_WATCH_MS, TimeUnit.MILLISECONDS) && old.exitValue() != 0)
            say("la version precedente s'est arretee avec le code " + old.exitValue() + " : relancer CoTransfert.cmd");
    }

    private static Process launch(Path jar, List<String> agentArgs, boolean trial, long rolledBackMs) throws IOException {
        List<String> cmd = new ArrayList<>(List.of(java(), "-Djava.net.preferIPv4Stack=true", "-jar", jar.toString()));
        cmd.addAll(agentArgs);
        ProcessBuilder pb = detached(cmd);
        pb.environment().put(ENV_RESTARTED, "1");
        pb.environment().remove(ENV_CONSOLE);
        if (trial) pb.environment().put(ENV_TRIAL, "1");
        else pb.environment().remove(ENV_TRIAL);
        if (rolledBackMs > 0) pb.environment().put(ENV_ROLLED_BACK, Long.toString(rolledBackMs));
        else pb.environment().remove(ENV_ROLLED_BACK);
        return pb.start();
    }
}
