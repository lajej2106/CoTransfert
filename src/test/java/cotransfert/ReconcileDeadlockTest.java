package cotransfert;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Reconciliation croisee sur le lien direct : chaque poste a beaucoup de fichiers
 * que l'autre n'a pas (cas d'une reorganisation des dossiers). Les deux poussent
 * en meme temps ; si le thread qui lit le flux est aussi celui qui envoie, les
 * tampons TCP se remplissent des deux cotes et plus rien ne bouge.
 *
 * Simple main, code de sortie non nul en cas d'echec :
 *   java -cp "build/classes;build/test-classes" cotransfert.ReconcileDeadlockTest <dossier-de-travail>
 */
public final class ReconcileDeadlockTest {

    private static final int FILES = 300;
    private static final int SIZE = 64 * 1024;   // ~19 Mo par sens : bien au-dela des tampons TCP

    public static void main(String[] args) throws Exception {
        if (args.length < 1) { System.err.println("usage: ReconcileDeadlockTest <dossier-de-travail>"); System.exit(2); }
        Path work = Files.createDirectories(Path.of(args[0]).toAbsolutePath().resolve("reconcile-deadlock"));
        Path a = Files.createDirectories(work.resolve("A")), b = Files.createDirectories(work.resolve("B"));
        Random rnd = new Random(7);
        for (int i = 0; i < FILES; i++) {
            write(a.resolve("nouveau/a" + (i % 10) + "/f" + i + ".bin"), rnd);
            write(b.resolve("ancien/b" + (i % 10) + "/f" + i + ".bin"), rnd);
        }

        Socket[] pair = connectedPair();
        Transfer.TcpSession sa = new Transfer.TcpSession(pair[0]);
        Transfer.TcpSession sb = new Transfer.TcpSession(pair[1]);
        Thread ta = new Thread(() -> new SyncEngine(a, true, SyncEngine.ConflictPolicy.KEEP_BOTH).run(sa, "hub[t]", "t"), "moteur-A");
        Thread tb = new Thread(() -> new SyncEngine(b, false, SyncEngine.ConflictPolicy.KEEP_BOTH).run(sb, "client[t]", "t"), "moteur-B");
        ta.setDaemon(true); tb.setDaemon(true);
        ta.start(); tb.start();

        long deadline = System.currentTimeMillis() + 90_000;
        boolean ok = false;
        while (System.currentTimeMillis() < deadline) {
            TreeMap<String, Long> ta1 = tree(a), tb1 = tree(b);
            if (ta1.size() == 2 * FILES && ta1.equals(tb1)) { ok = true; break; }
            Thread.sleep(250);
        }
        if (!ok) {
            System.err.printf("ECHEC: pas de convergence en 90 s (A=%d, B=%d fichiers sur %d) -> reconciliation bloquee%n",
                    tree(a).size(), tree(b).size(), 2 * FILES);
            System.exit(1);
        }
        sa.close(); sb.close();
        ta.join(15_000); tb.join(15_000);
        if (ta.isAlive() || tb.isAlive()) { System.err.println("ECHEC: un moteur n'a pas rendu la main apres close()"); System.exit(1); }
        System.out.println("OK: reconciliation croisee de " + (2 * FILES) + " fichiers sans blocage");
        System.exit(0);
    }

    /** Deux sockets reliees en boucle locale, avec de petits tampons pour forcer le blocage. */
    private static Socket[] connectedPair() throws IOException {
        try (ServerSocket srv = new ServerSocket()) {
            srv.setReceiveBufferSize(16 * 1024);
            srv.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            Socket c = new Socket();
            c.setSendBufferSize(16 * 1024);
            c.setReceiveBufferSize(16 * 1024);
            c.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), srv.getLocalPort()), 3000);
            Socket s = srv.accept();
            s.setSendBufferSize(16 * 1024);
            return new Socket[] { s, c };
        }
    }

    private static void write(Path p, Random rnd) throws IOException {
        Files.createDirectories(p.getParent());
        byte[] b = new byte[SIZE];
        rnd.nextBytes(b);
        Files.write(p, b);
    }

    /** Arbre synchronise (hors .cotransfert) : chemin -> taille. */
    private static TreeMap<String, Long> tree(Path root) throws IOException {
        TreeMap<String, Long> m = new TreeMap<>();
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(Files::isRegularFile).forEach(p -> {
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (rel.startsWith(".cotransfert/")) return;
                try { m.put(rel, Files.size(p)); } catch (IOException e) { /* en cours d'ecriture */ }
            });
        }
        return m;
    }
}
