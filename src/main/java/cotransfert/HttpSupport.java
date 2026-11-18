package cotransfert;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import javax.net.ssl.SSLException;

/**
 * Socle HTTP des canaux en ligne (WebDAV, Drime) : client du JDK, proxy,
 * magasin de certificats. Uniquement du HTTPS sortant, aucune dependance.
 *
 * On n'affaiblit jamais la verification TLS et on ne contourne pas un proxy :
 * si l'environnement refuse la connexion, le canal est indisponible.
 */
public final class HttpSupport {

    private HttpSupport() { }

    /**
     * A appeler une fois, avant toute activite reseau.
     * @param truststore "windows" : magasin de certificats de Windows (autorites
     *                   installees par l'organisation) ; autre valeur : celui de Java.
     * @param proxy      "system" (ou vide) : reglages proxy du poste ; "none" ; "hote:port".
     */
    public static void init(String truststore, String proxy) {
        if ("windows".equalsIgnoreCase(trim(truststore))) {
            System.setProperty("javax.net.ssl.trustStoreType", "Windows-ROOT");
        }
        String p = trim(proxy);
        if (p.isEmpty() || "system".equalsIgnoreCase(p)) {
            // Doit etre pose avant la premiere utilisation du selecteur de proxy par defaut.
            System.setProperty("java.net.useSystemProxies", "true");
        }
    }

    /** Client HTTP/1.1, redirections suivies, proxy selon la configuration. */
    public static HttpClient client(String proxy) {
        HttpClient.Builder b = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15));
        String p = trim(proxy);
        if ("none".equalsIgnoreCase(p)) {
            b.proxy(HttpClient.Builder.NO_PROXY);
        } else if (p.isEmpty() || "system".equalsIgnoreCase(p)) {
            ProxySelector def = ProxySelector.getDefault();
            if (def != null) b.proxy(def);
        } else {
            int c = p.lastIndexOf(':');
            String host = c > 0 ? p.substring(0, c) : p;
            int port = 8080;
            if (c > 0) {
                try { port = Integer.parseInt(p.substring(c + 1)); } catch (NumberFormatException ignore) { }
            }
            b.proxy(ProxySelector.of(new InetSocketAddress(host, port)));
        }
        return b.build();
    }

    /** Valeur de l'en-tete Authorization pour une authentification Basic. */
    public static String basic(String user, String password) {
        String raw = (user == null ? "" : user) + ":" + (password == null ? "" : password);
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** Encode un chemin relatif pour une URL, segment par segment ("a b/c" devient "a%20b/c"). */
    public static String encodePath(String relPath) {
        StringBuilder sb = new StringBuilder();
        for (String seg : relPath.split("/")) {
            if (seg.isEmpty()) continue;
            if (sb.length() > 0) sb.append('/');
            sb.append(URLEncoder.encode(seg, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.toString();
    }

    /** Delai demande par un en-tete Retry-After en secondes, borne a [1 s, 5 min] ; def si absent ou illisible. */
    public static long retryAfterMs(Optional<String> header, long def) {
        if (header != null && header.isPresent()) {
            try {
                long s = Long.parseLong(header.get().trim());
                return Math.max(1000, Math.min(s * 1000, 300_000));
            } catch (NumberFormatException ignore) { }
        }
        return def;
    }

    /** Cause courte et lisible d'un echec reseau, en francais, sans detail sensible. */
    public static String explain(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SSLException)
                return "certificat du serveur refuse : dans Reglages > Avance, choisir les certificats de confiance"
                        + " de Windows, enregistrer, puis reessayer";
            if (t instanceof HttpConnectTimeoutException) return "connexion impossible (delai depasse)";
            if (t instanceof HttpTimeoutException) return "pas de reponse du serveur (delai depasse)";
            if (t instanceof java.net.UnknownHostException) return "nom du serveur introuvable";
            if (t instanceof java.nio.channels.UnresolvedAddressException) return "nom du serveur introuvable";
            if (t instanceof java.net.ConnectException) return "connexion refusee ou bloquee";
            if (t == t.getCause()) break;
        }
        String m = e == null ? null : e.getMessage();
        return (m == null || m.isBlank()) ? "erreur reseau" : m;
    }

    /** Enveloppe un echec reseau dans une IOException au message court. */
    public static IOException fail(String what, Throwable e) {
        return new IOException(what + " : " + explain(e), e);
    }

    private static String trim(String s) { return s == null ? "" : s.trim(); }
}
