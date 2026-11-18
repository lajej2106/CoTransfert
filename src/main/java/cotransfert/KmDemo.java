package cotransfert;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Essai MANUEL du partage clavier-souris entre deux vrais postes, sans l'agent
 * (aucune synchronisation, aucun fichier ecrit). Utilise le vrai clavier, la
 * vraie souris et java.awt.Robot : a lancer volontairement, sur deux postes.
 *
 *   hub    : java -cp cotransfert.jar cotransfert.KmDemo hub    --secret SECRET [--port 47795] [--edge droite]
 *   client : java -cp cotransfert.jar cotransfert.KmDemo client --secret SECRET --peer ADRESSE-DU-HUB
 *                                                               [--port 47795] [--edge gauche]
 *
 * Options communes : --delay 250, --hotkey Ctrl+Alt+F12, --return-button 0|4|5.
 * Prise de controle : pousser le pointeur contre le bord regle. Retour : le
 * raccourci, le bord oppose du poste pilote, ou la touche Windows. Ctrl+C pour quitter.
 */
public final class KmDemo {

    private KmDemo() { }

    static final int DEMO_PORT = 47795;

    public static void main(String[] args) throws Exception {
        String role = args.length > 0 ? args[0] : "";
        if (!role.equals("hub") && !role.equals("client")) {
            System.out.println("usage : KmDemo hub|client --secret SECRET [--peer ADRESSE] [--port " + DEMO_PORT
                    + "] [--edge gauche|droite|haut|bas] [--delay ms] [--hotkey Ctrl+Alt+F12] [--return-button 0|4|5]");
            System.exit(2);
        }
        Crypto.Key key = Crypto.derive(arg(args, "--secret", ""));
        if (key == null) { System.out.println("--secret est obligatoire (le meme sur les deux postes)"); System.exit(2); }
        int port = Integer.parseInt(arg(args, "--port", String.valueOf(DEMO_PORT)));
        Properties p = new Properties();
        p.setProperty(KmSettings.K_ENABLED, "true");
        p.setProperty(KmSettings.K_EDGE, arg(args, "--edge", ""));
        p.setProperty(KmSettings.K_DELAY, arg(args, "--delay", ""));
        p.setProperty(KmSettings.K_HOTKEY, arg(args, "--hotkey", ""));
        p.setProperty(KmSettings.K_RETURN, arg(args, "--return-button", ""));
        List<String> problems = new ArrayList<>();
        KmSettings settings = KmSettings.from(p, problems);
        if (!problems.isEmpty()) { System.out.println(String.join("\n", problems)); System.exit(2); }

        Km km = Km.get();
        km.configure(settings, role.equals("hub"), "direct", true);
        if (!km.usable()) { System.out.println("indisponible : " + km.problem()); System.exit(1); }
        km.start();

        if (role.equals("client")) {
            String peer = arg(args, "--peer", "");
            if (peer.isEmpty()) { System.out.println("--peer est obligatoire pour le client"); System.exit(2); }
            km.clientLoop(() -> peer, port, key);
            return;
        }
        try (ServerSocket srv = new ServerSocket()) {
            srv.setReuseAddress(true);
            srv.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), port));
            Agent.log("km demo: hub en ecoute sur :%d", port);
            while (true) {
                Socket c = srv.accept();
                Thread t = new Thread(() -> {
                    try {
                        Agent.log("km demo: lien ferme (%s)", km.serve(KmLink.accept(c, key)));
                    } catch (Exception e) {
                        Agent.log("km demo: connexion refusee (%s)", e.getMessage());
                    }
                }, "cotp-km-demo");
                t.setDaemon(true);
                t.start();
            }
        }
    }

    private static String arg(String[] args, String name, String def) {
        for (int i = 1; i + 1 < args.length; i++) if (args[i].equals(name)) return args[i + 1];
        return def;
    }
}
