package cotransfert;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Petite IHM web embarquee (serveur HTTP du JDK, zero dependance). Elle est
 * toujours demarree : c'est par elle que l'utilisateur regle et suit CoTransfert.
 *
 * - Sur le HUB regle : ecoute sur toutes les interfaces. L'etat est lisible
 *   depuis le hub (http://localhost:PORT) ET depuis le client via le lien direct
 *   (http://<ip-hub>:PORT), le client remontant son etat au hub par la
 *   connexion de sync (cadres OP_STATUS).
 * - Sur le CLIENT, et sur tout poste pas encore regle : liee a la seule boucle
 *   locale (http://127.0.0.1:PORT). Rien n'est expose sur le reseau.
 *
 *   GET /             -> la page (ressource embarquee)
 *   GET /api/status   -> JSON agrege (ce poste + le pair), lecture seule, sans secret
 *   /api/config, /api/channel, /api/secret/*, /api/test, /api/browse, /api/quit,
 *   /api/update/*, /api/log
 *                     -> reglages, mise a jour et journal, reserves a ce poste (voir {@link SetupApi})
 *   /maj/liste, /maj/fichier
 *                     -> sur le hub regle sur le lien direct, avec un secret partage : les deux
 *                        fichiers scelles de la mise a jour, pour le client (voir {@link MajRoutes})
 *
 * Les requetes sont servies par un petit groupe de fils demons : un essai de
 * canal ou une boite de dialogue en cours ne bloque pas /api/status.
 */
public final class WebServer {

    private WebServer() { }

    /** Ecoute sur toutes les interfaces (hub), etat seul. */
    public static HttpServer start(int port) throws IOException {
        return start(port, false, null);
    }

    /** Etat seul, sans l'API de reglage. */
    public static HttpServer start(int port, boolean loopbackOnly) throws IOException {
        return start(port, loopbackOnly, null);
    }

    /**
     * @param port         port d'ecoute ; 0 = port libre choisi par le systeme (tests)
     * @param loopbackOnly vrai : n'ecoute que sur 127.0.0.1 (invisible depuis le
     *                     reseau) ; faux : toutes les interfaces.
     * @param api          API de reglage a exposer, ou null (etat seul)
     * @throws java.net.BindException si le port est deja pris
     */
    public static HttpServer start(int port, boolean loopbackOnly, SetupApi api) throws IOException {
        return start(port, loopbackOnly, api, null);
    }

    /**
     * @param maj routes de la mise a jour par le lien direct (hub, avec un secret partage), ou null :
     *            /maj/ repond alors 404
     */
    static HttpServer start(int port, boolean loopbackOnly, SetupApi api, MajRoutes maj) throws IOException {
        InetSocketAddress addr = loopbackOnly
                ? new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), port)
                : new InetSocketAddress(port);
        HttpServer srv = HttpServer.create(addr, 0);

        srv.createContext("/api/status", ex -> reply(ex, 200, "application/json; charset=utf-8", "no-store",
                Status.get().json().getBytes(StandardCharsets.UTF_8)));
        // Route d'API inconnue : une reponse JSON, jamais la page.
        srv.createContext("/api/", ex -> reply(ex, 404, "application/json; charset=utf-8", "no-store",
                "{\"ok\":false,\"message\":\"route inconnue\"}".getBytes(StandardCharsets.UTF_8)));
        if (api != null) api.register(srv);
        // Mise a jour par le lien direct : servie par le hub seulement ; ailleurs, jamais la page.
        srv.createContext("/maj/", ex -> reply(ex, 404, "text/plain; charset=utf-8", "no-store",
                "route inconnue".getBytes(StandardCharsets.UTF_8)));
        if (maj != null) maj.register(srv);
        srv.createContext("/",ex -> reply(ex, 200, "text/html; charset=utf-8", "no-cache", page()));

        AtomicInteger n = new AtomicInteger();
        srv.setExecutor(Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "cotp-web-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        }));
        srv.start();
        return srv;
    }

    private static void reply(HttpExchange ex, int status, String type, String cache, byte[] b) throws IOException {
        try {
            ex.getResponseHeaders().add("Content-Type", type);
            ex.getResponseHeaders().add("Cache-Control", cache);
            ex.sendResponseHeaders(status, b.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(b); }
        } finally {
            ex.close();
        }
    }

    private static byte[] page() throws IOException {
        try (InputStream in = WebServer.class.getResourceAsStream("/cotransfert/web/index.html")) {
            if (in == null) return "<!doctype html><h1>CoTransfert</h1><p>index.html manquant dans le jar.</p>"
                    .getBytes(StandardCharsets.UTF_8);
            return in.readAllBytes();
        }
    }
}
