package cotransfert;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Partage clavier-souris : raccourci clavier pour PARTIR vers l'autre poste.
 *
 * Java pur ne peut pas ecouter un raccourci global quand sa fenetre n'a pas le
 * focus. On s'appuie donc sur un mecanisme standard de Windows : un raccourci
 * .lnk place dans le Menu Demarrer (ou sur le Bureau) de l'utilisateur peut
 * porter une touche de raccourci, que l'Explorateur surveille. Sa cible appelle
 * la route de bascule de l'agent sur la boucle locale :
 *
 *   curl.exe ... -X POST -H "X-CoTransfert: 1" -H "Content-Type: application/json"
 *            -d "{}" http://127.0.0.1:PORT/api/km/toggle
 *
 * (curl.exe est fourni avec Windows ; a defaut, la cible est powershell.exe avec
 * Invoke-RestMethod.) La requete respecte telles quelles les gardes de
 * {@link SetupApi} : boucle locale, Host 127.0.0.1, pas d'Origin, en-tete
 * X-CoTransfert, corps JSON.
 *
 * Le .lnk est cree par Windows PowerShell 5.1 (objet COM WScript.Shell), sans
 * module ni droit administrateur. Les valeurs passent par des variables
 * d'environnement du processus : le script est fixe, sans guillemets doubles,
 * et rien de ce que l'utilisateur regle n'y est insere. Si la creation echoue
 * (PowerShell restreint par le poste), on le dit et la fonction marche sans
 * (bord d'ecran, bouton de la page).
 */
final class KmShortcut {

    private KmShortcut() { }

    static final String LNK_NAME = "CoTransfert - clavier et souris.lnk";
    static final String TOGGLE_PATH = "/api/km/toggle";
    /** Dossiers speciaux acceptes (noms de System.Environment+SpecialFolder). */
    static final String FOLDER_START_MENU = "Programs", FOLDER_DESKTOP = "Desktop";

    static final String ENV_FOLDER = "COTRANSFERT_KM_DOSSIER", ENV_NAME = "COTRANSFERT_KM_NOM",
            ENV_TARGET = "COTRANSFERT_KM_CIBLE", ENV_ARGS = "COTRANSFERT_KM_ARGUMENTS",
            ENV_HOTKEY = "COTRANSFERT_KM_TOUCHE", ENV_DESC = "COTRANSFERT_KM_DESCRIPTION";

    /** Script de creation : fixe, sans guillemets doubles ; WindowStyle 7 = fenetre reduite. */
    static final String CREATE_SCRIPT = String.join("; ",
            "$ErrorActionPreference = 'Stop'",
            "$d = [Environment]::GetFolderPath($env:" + ENV_FOLDER + ")",
            "if (-not $d) { throw 'dossier introuvable' }",
            "$p = Join-Path $d $env:" + ENV_NAME,
            "$s = New-Object -ComObject WScript.Shell",
            "$l = $s.CreateShortcut($p)",
            "$l.TargetPath = $env:" + ENV_TARGET,
            "$l.Arguments = $env:" + ENV_ARGS,
            "$l.Hotkey = $env:" + ENV_HOTKEY,
            "$l.WindowStyle = 7",
            "$l.Description = $env:" + ENV_DESC,
            "$l.Save()",
            "Write-Output ('OK ' + $p)");

    /** Script de suppression : retire le .lnk du Menu Demarrer et du Bureau. */
    static final String REMOVE_SCRIPT = String.join("; ",
            "$ErrorActionPreference = 'Stop'",
            "foreach ($n in 'Programs', 'Desktop') { $d = [Environment]::GetFolderPath($n); if ($d) { $p = Join-Path $d $env:" + ENV_NAME
                    + "; if (Test-Path -LiteralPath $p) { Remove-Item -LiteralPath $p -Force } } }",
            "Write-Output 'OK'");

    /** Ce qui serait lance : la commande, ses variables d'environnement, et le contenu du raccourci. */
    record Plan(List<String> command, Map<String, String> env, String target, String arguments, String hotkey) { }

    record Result(boolean ok, String message) {
        String json() { return "{\"ok\":" + ok + ",\"message\":" + Status.q(message) + "}"; }
    }

    private static String systemRoot() {
        String r = System.getenv("SystemRoot");
        return r == null || r.isBlank() ? "C:\\Windows" : r;
    }

    static String powershellExe() { return systemRoot() + "\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"; }

    static String curlExe() { return systemRoot() + "\\System32\\curl.exe"; }

    private static List<String> powershell(String script) {
        return List.of(powershellExe(), "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script);
    }

    /** Arguments de curl.exe pour la bascule (aucun proxy pour la boucle locale, 5 s au plus). */
    static String curlArguments(int webPort) {
        return "-s -o NUL -m 5 --noproxy \"*\" -X POST -H \"X-CoTransfert: 1\" -H \"Content-Type: application/json\" -d \"{}\" "
                + "http://127.0.0.1:" + webPort + TOGGLE_PATH;
    }

    /** Arguments de powershell.exe pour la bascule, quand curl.exe est absent. */
    static String powershellArguments(int webPort) {
        return "-NoProfile -NonInteractive -WindowStyle Hidden -Command \"Invoke-RestMethod -Method Post -Uri http://127.0.0.1:"
                + webPort + TOGGLE_PATH + " -Headers @{'X-CoTransfert'='1'} -ContentType 'application/json' -Body '{}' | Out-Null\"";
    }

    /**
     * Prepare la creation, sans rien lancer.
     * @param webPort port de la page de ce poste
     * @param hotkey  raccourci regle (non null)
     * @param folder  {@link #FOLDER_START_MENU} ou {@link #FOLDER_DESKTOP}
     * @param useCurl vrai : cible curl.exe ; faux : cible powershell.exe
     */
    static Plan planCreate(int webPort, KmSettings.Hotkey hotkey, String folder, boolean useCurl) {
        if (hotkey == null) throw new IllegalArgumentException("aucun raccourci regle");
        if (webPort < 1 || webPort > 65535) throw new IllegalArgumentException("port invalide");
        if (!FOLDER_START_MENU.equals(folder) && !FOLDER_DESKTOP.equals(folder)) throw new IllegalArgumentException("dossier inconnu");
        String target = useCurl ? curlExe() : powershellExe();
        String arguments = useCurl ? curlArguments(webPort) : powershellArguments(webPort);
        Map<String, String> env = new LinkedHashMap<>();
        env.put(ENV_FOLDER, folder);
        env.put(ENV_NAME, LNK_NAME);
        env.put(ENV_TARGET, target);
        env.put(ENV_ARGS, arguments);
        env.put(ENV_HOTKEY, hotkey.lnk());
        env.put(ENV_DESC, "Envoyer le clavier et la souris vers l'autre poste, ou les reprendre (" + hotkey.text() + ")");
        return new Plan(powershell(CREATE_SCRIPT), env, target, arguments, hotkey.lnk());
    }

    static Plan planRemove() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put(ENV_NAME, LNK_NAME);
        return new Plan(powershell(REMOVE_SCRIPT), env, "", "", "");
    }

    /** Cree (ou remplace) le raccourci dans le Menu Demarrer de l'utilisateur. A refaire si le port de la page change. */
    static Result create(int webPort, KmSettings.Hotkey hotkey) {
        if (hotkey == null) return new Result(false, "aucun raccourci clavier n'est regle");
        if (!windows()) return new Result(false, "raccourci disponible sous Windows seulement");
        Plan plan;
        try {
            plan = planCreate(webPort, hotkey, FOLDER_START_MENU, Files.isRegularFile(Paths.get(curlExe())));
        } catch (RuntimeException e) {
            return new Result(false, "raccourci non cree (" + e.getMessage() + ")");
        }
        Result r = run(plan);
        if (!r.ok()) return new Result(false, "raccourci Windows non cree (" + r.message()
                + ") : le partage clavier-souris reste utilisable par le bord d'ecran et par la page");
        return new Result(true, "raccourci cree : " + hotkey.text() + " (" + r.message() + ")");
    }

    static Result remove() {
        if (!windows()) return new Result(true, "rien a retirer");
        Result r = run(planRemove());
        return r.ok() ? new Result(true, "raccourci retire") : new Result(false, "raccourci non retire (" + r.message() + ")");
    }

    private static boolean windows() { return System.getProperty("os.name", "").toLowerCase().contains("win"); }

    /** Lance la commande preparee (15 s au plus). ok : message = chemin du .lnk ; sinon la raison. */
    private static Result run(Plan plan) {
        Process p = null;
        try {
            if (!Files.isRegularFile(Path.of(plan.command().get(0)))) return new Result(false, "Windows PowerShell introuvable");
            ProcessBuilder pb = new ProcessBuilder(plan.command()).redirectErrorStream(true);
            pb.environment().putAll(plan.env());
            p = pb.start();
            p.getOutputStream().close();
            final Process proc = p;
            final String[] out = { "" };
            Thread reader = new Thread(() -> {
                try (InputStream in = proc.getInputStream()) {
                    out[0] = new String(in.readNBytes(8192), StandardCharsets.UTF_8);
                    in.transferTo(java.io.OutputStream.nullOutputStream());
                } catch (IOException e) { /* sortie perdue : le code de sortie suffit */ }
            }, "cotp-km-raccourci");
            reader.setDaemon(true);
            reader.start();
            if (!p.waitFor(15, TimeUnit.SECONDS)) return new Result(false, "PowerShell ne repond pas");
            reader.join(2000);
            String text = out[0].trim();
            for (String line : text.split("\\R")) {
                if (p.exitValue() == 0 && line.startsWith("OK")) return new Result(true, line.substring(2).trim());
            }
            return new Result(false, "PowerShell a refuse (politique du poste ?)");
        } catch (IOException e) {
            return new Result(false, "PowerShell n'a pas pu etre lance");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, "interrompu");
        } finally {
            if (p != null && p.isAlive()) p.destroyForcibly();
        }
    }
}
