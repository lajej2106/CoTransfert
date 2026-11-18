package cotransfert;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Magasin de la mise a jour sur le lien direct, cote CLIENT : il parle aux
 * routes /maj/ du hub ({@link MajRoutes}) par des requetes HTTP SORTANTES, a
 * l'adresse ou le hub a ete trouve (decouverte, ou adresse forcee). Le client
 * n'ecoute jamais.
 *
 * Il ne connait que les deux fichiers de la publication ({@link Updater#FICHE_PATH}
 * et {@link Updater#JAR_PATH}), deja scelles avec le secret partage : le
 * transport HTTP n'ajoute ni ne retire rien a leur protection. Aucun proxy
 * n'est utilise (adresse de lien direct), aucune redirection n'est suivie.
 *
 * Tant que le hub n'est pas localise ou ne repond pas, le magasin est
 * indisponible ({@link #available} faux, raison dans {@link #lastError}) ; il
 * n'y a pas de suppression par le reseau ({@link #delete} echoue toujours).
 */
public final class HubHttpRelayStore implements RelayStore {

    static final String NOT_FOUND = "hub pas encore trouve sur le lien direct";
    static final String NOT_SERVED = "le hub ne sert pas la mise a jour par le lien direct"
            + " (meme secret partage a definir sur le hub, ou version du hub trop ancienne)";
    private static final int CONNECT_MS = 4_000, READ_MS = 30_000, PROBE_READ_MS = 5_000;

    private final Supplier<String> hub;
    private final int port;
    private volatile long probedAt;
    private volatile boolean probedUp;
    private volatile String lastError = "";

    /**
     * @param hub  adresse du hub connue maintenant (nom ou IP), ou null tant qu'il n'est pas localise ; ne bloque pas
     * @param port port de la page du hub
     */
    public HubHttpRelayStore(Supplier<String> hub, int port) {
        this.hub = hub;
        this.port = port;
    }

    @Override public String id() { return "direct"; }

    @Override public String describe() { return "hub par le lien direct (HTTP, port " + port + ")"; }

    @Override public boolean available() {
        // Hub pas encore localise : aucune requete, et rien n'est garde en cache (il peut apparaitre a tout instant).
        String host = null;
        try {
            host = hub.get();
        } catch (RuntimeException e) {
            // comme s'il n'etait pas localise
        }
        if (host == null || host.isBlank()) {
            lastError = NOT_FOUND;
            probedAt = 0;
            return false;
        }
        long now = System.nanoTime() / 1_000_000;
        if (probedAt != 0 && now - probedAt < pollMs()) return probedUp;
        boolean up;
        try {
            listing();
            up = true;
            lastError = "";
        } catch (IOException | RuntimeException e) {
            up = false;
            lastError = message(e);
        }
        probedUp = up;
        probedAt = now == 0 ? 1 : now;
        return up;
    }

    @Override public String lastError() { return lastError; }

    @Override public List<Entry> list(String dir) throws IOException {
        if (!Updater.DIR.equals(dir)) return new ArrayList<>();
        return listing();
    }

    @Override public byte[] read(String path, int maxBytes) throws IOException {
        String name = name(path);
        long cap = maxBytes > 0 ? maxBytes : MajRoutes.JAR_MAX_BYTES;
        HttpURLConnection c = open("GET", MajRoutes.FILE_PATH, "nom=" + name + "&max=" + cap, READ_MS);
        try {
            int code = code(c);
            if (code == 404) return null;
            if (code != 200) throw refused(code);
            try (InputStream in = c.getInputStream()) {
                return in.readNBytes((int) cap);
            } catch (IOException e) {
                throw unreachable(e);
            }
        } finally {
            c.disconnect();
        }
    }

    @Override public void write(String path, Path src) throws IOException {
        name(path);
        if (Files.size(src) > MajRoutes.JAR_MAX_BYTES) throw new IOException("fichier trop volumineux");
        write(path, Files.readAllBytes(src));
    }

    @Override public void write(String path, byte[] data) throws IOException {
        String name = name(path);
        long limit = MajRoutes.FICHE_NAME.equals(name) ? Updater.FICHE_MAX_BYTES : MajRoutes.JAR_MAX_BYTES;
        if (data.length > limit) throw new IOException("fichier trop volumineux");
        HttpURLConnection c = open("PUT", MajRoutes.FILE_PATH, "nom=" + name, READ_MS);
        try {
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(data.length);
            c.setRequestProperty("Content-Type", "application/octet-stream");
            try (OutputStream o = c.getOutputStream()) {
                o.write(data);
            } catch (IOException e) {
                throw unreachable(e);
            }
            int code = code(c);
            if (code != 200) throw refused(code);
        } finally {
            c.disconnect();
        }
    }

    @Override public void delete(String path) throws IOException {
        throw new IOException("pas de suppression par le lien direct");
    }

    // --- interne ---------------------------------------------------------------

    /** Nom du fichier pour les routes du hub ; refuse tout autre chemin que les deux fichiers de la publication. */
    private static String name(String path) throws IOException {
        if (Updater.FICHE_PATH.equals(path)) return MajRoutes.FICHE_NAME;
        if (Updater.JAR_PATH.equals(path)) return MajRoutes.JAR_NAME;
        throw new IOException("chemin refuse: " + path);
    }

    private List<Entry> listing() throws IOException {
        HttpURLConnection c = open("GET", MajRoutes.LIST_PATH, null, PROBE_READ_MS);
        try {
            int code = code(c);
            if (code != 200) throw refused(code);
            String body;
            try (InputStream in = c.getInputStream()) {
                body = new String(in.readNBytes(4096), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw unreachable(e);
            }
            String[] lines = body.split("\n");
            // Un hub plus ancien repond a tout chemin inconnu par sa page : ce n'est pas la liste.
            if (!MajRoutes.LIST_MAGIC.equals(lines[0].trim())) throw new IOException(NOT_SERVED);
            List<Entry> out = new ArrayList<>();
            for (int i = 1; i < lines.length; i++) {
                String l = lines[i].trim();
                int sp = l.indexOf(' ');
                if (sp <= 0) continue;
                String n = l.substring(sp + 1);
                if (!MajRoutes.FICHE_NAME.equals(n) && !MajRoutes.JAR_NAME.equals(n)) continue;
                try {
                    out.add(new Entry(n, Long.parseLong(l.substring(0, sp))));
                } catch (NumberFormatException e) {
                    // ligne ignoree
                }
            }
            return out;
        } finally {
            c.disconnect();
        }
    }

    private HttpURLConnection open(String method, String path, String query, int readMs) throws IOException {
        String host = null;
        try {
            host = hub.get();
        } catch (RuntimeException e) {
            // comme si le hub n'etait pas localise
        }
        if (host == null || host.isBlank()) throw new IOException(NOT_FOUND);
        try {
            HttpURLConnection c = (HttpURLConnection) new URI("http", null, host.trim(), port, path, query, null)
                    .toURL().openConnection(Proxy.NO_PROXY);
            c.setConnectTimeout(CONNECT_MS);
            c.setReadTimeout(readMs);
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            c.setRequestMethod(method);
            return c;
        } catch (URISyntaxException | IllegalArgumentException | ClassCastException e) {
            throw new IOException("adresse du hub inutilisable");
        }
    }

    private static int code(HttpURLConnection c) throws IOException {
        try {
            int code = c.getResponseCode();
            if (code != 200) {
                // Le corps d'un refus n'est pas lu comme un message : il vient du reseau.
                try (InputStream err = c.getErrorStream()) {
                    if (err != null) err.readNBytes(4096);
                } catch (IOException ignore) {
                    // sans importance
                }
            }
            return code;
        } catch (IOException e) {
            throw unreachable(e);
        }
    }

    private static IOException unreachable(IOException e) {
        if (e instanceof java.net.SocketTimeoutException) return new IOException("hub injoignable : pas de reponse (delai depasse)");
        return new IOException("hub injoignable : " + HttpSupport.explain(e));
    }

    private static IOException refused(int code) {
        return new IOException(switch (code) {
            case 403 -> "refuse par le hub : le contenu ne s'ouvre pas avec son secret partage (le meme secret doit etre regle sur les deux postes)";
            case 404 -> NOT_SERVED;
            case 409 -> "refuse par le hub : fiche et jar incoherents, ou version plus ancienne que celle deja publiee";
            case 413 -> "refuse par le hub : fichier trop volumineux";
            default -> "reponse inattendue du hub (HTTP " + code + ")";
        });
    }

    private static String message(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? "hub injoignable" : m;
    }
}
