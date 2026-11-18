package cotransfert;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Configuration persistante de l'agent, dans cotransfert.conf, place a cote du
 * jar et du script de lancement (dans le dossier du projet CoTransfert).
 *
 * Elle memorise le role du poste, la politique de conflit, et surtout LE OU LES
 * dossiers a synchroniser, pour survivre aux arrets/relances et permettre d'en
 * ajouter. Chaque poste a sa propre conf (les chemins locaux different d'un
 * poste a l'autre) ; les dossiers sont apparies par leur NOM logique.
 *
 * Format (clef=valeur), exemple :
 *   role=hub
 *   master=true
 *   conflict=keep-both
 *   root.MonProjet=C:\work\MonProjet
 *   root.Autre=D:\work\autre
 *
 * Canal de transfert : un seul a la fois, choisi par l'utilisateur dans la page.
 *   channel=direct|webdav|drime|disque     vide = aucun canal choisi, aucune synchronisation
 * Reglage des canaux (cles optionnelles ; on peut en regler plusieurs, un seul sert) :
 *   webdav.url= webdav.user= webdav.password=    canal WebDAV (les trois sont requises)
 *   drime.token= drime.folder= drime.workspace= drime.url=    canal Drime (jeton requis)
 *   relay= et relay.NomLogique=            canal disque
 *   proxy=system|none|hote:port            tls.truststore=java|windows
 * Partage clavier-souris (lien direct, secret requis ; voir KmSettings) :
 *   km.enabled= km.edge= km.edge.delay= km.hotkey= km.return.button=
 * Mise a jour par le canal (cote client) :
 *   update.auto=true|false                 installer tout seul la version publiee par le hub (defaut : false)
 * Les secrets (secret, webdav.password, drime.token) restent dans ce fichier
 * local ; ils ne sont jamais journalises ni exposes par l'IHM.
 * L'ancienne cle "channels" (ordre d'une bascule automatique) est ignoree a la
 * lecture et n'est plus ecrite.
 */
public final class Config {

    /** Canaux connus, dans l'ordre fixe d'affichage. */
    public static final List<String> KNOWN_CHANNELS = List.of("direct", "webdav", "drime", "disque");
    public static final String DEFAULT_DRIME_FOLDER = "CoTransfertRelais";

    public String role = "";
    public Boolean master = null;            // null => defaut (hub=maitre)
    public String conflict = "keep-both";
    public final LinkedHashMap<String, String> roots = new LinkedHashMap<>(); // nom -> chemin local

    /** Hub force (nom ou IP), sans decouverte. Vide = decouverte automatique. */
    public String peer = "";
    /** Portee de la decouverte : "link" (lien direct APIPA, defaut) ou "lan" (reseaux prives aussi). */
    public String discovery = "link";
    /** Secret partage (identique sur les deux postes) : authentifie le pair et chiffre les echanges. */
    public String secret = "";
    /** Dossier relais par defaut (canal disque) ; "*:\Dossier" = cherche sur tous les lecteurs. */
    public String relay = "";
    /** Dossier relais propre a un dossier synchronise : nom logique -> spec. */
    public final LinkedHashMap<String, String> relays = new LinkedHashMap<>();

    /** Canal de transfert choisi : "direct", "webdav", "drime", "disque", ou "" (aucun : rien n'est synchronise). */
    public String channel = "";
    /** Canal WebDAV : URL du dossier de transit, identifiant, mot de passe d'application. */
    public String webdavUrl = "", webdavUser = "", webdavPassword = "";
    /** Canal Drime : jeton personnel, dossier de transit, URL de l'API (vide = officielle). */
    public String drimeToken = "", drimeFolder = DEFAULT_DRIME_FOLDER, drimeUrl = "";
    /** Canal Drime : espace de travail (0 = espace personnel). */
    public long drimeWorkspace = 0;
    /** Proxy des canaux en ligne : "system" (reglages du poste), "none" ou "hote:port". */
    public String proxy = "system";
    /** Certificats : "java" ou "windows" (magasin de Windows, autorites installees par l'organisation). */
    public String tlsTruststore = "java";

    /** Mise a jour par le canal : vrai = le client installe tout seul la version publiee par le hub. */
    public boolean updateAuto = false;

    /** Partage clavier-souris : cles "km.*" telles qu'ecrites dans le fichier (voir {@link KmSettings}). */
    public final java.util.Properties km = new java.util.Properties();

    /** Spec du relais pour ce dossier (propre, sinon defaut), ou "" si aucun. */
    public String relayFor(String rootName) {
        String r = relays.get(rootName);
        return (r != null && !r.isEmpty()) ? r : relay;
    }

    /** Nom de canal normalise : un des canaux connus, ou "" (aucun, ou nom inconnu). */
    public static String channelId(String v) {
        String id = v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
        return KNOWN_CHANNELS.contains(id) ? id : "";
    }

    /** Vrai quand les trois cles obligatoires du canal WebDAV sont renseignees. */
    public boolean webdavConfigured() {
        return !webdavUrl.isBlank() && !webdavUser.isBlank() && !webdavPassword.isBlank();
    }

    /** Vrai quand le jeton du canal Drime est renseigne. */
    public boolean drimeConfigured() { return !drimeToken.isBlank(); }

    /** Vrai quand un dossier relais (canal disque) est renseigne pour ce dossier. */
    public boolean diskConfigured(String rootName) { return !relayFor(rootName).isBlank(); }

    /** Vrai quand le poste est regle : role valide et au moins un dossier. */
    public boolean configured() {
        return ("hub".equals(role) || "client".equals(role)) && !roots.isEmpty();
    }

    public static Config load(Path file) {
        Config c = new Config();
        try {
            if (!Files.exists(file)) return c;
            for (String line : Files.readAllLines(file)) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) continue;
                int eq = s.indexOf('=');
                if (eq < 0) continue;
                String k = s.substring(0, eq).trim();
                String v = s.substring(eq + 1).trim();
                switch (k.toLowerCase()) {
                    case "role": c.role = v.toLowerCase(); break;
                    case "master": c.master = Boolean.parseBoolean(v); break;
                    case "conflict": c.conflict = v; break;
                    case "peer": c.peer = v; break;
                    case "discovery": c.discovery = v.toLowerCase(); break;
                    case "secret": c.secret = v; break;
                    case "relay": c.relay = v; break;
                    case "channel":
                        c.channel = channelId(v);
                        if (c.channel.isEmpty() && !v.isEmpty())
                            Agent.log("config: canal inconnu ignore dans channel= (connus : %s) : aucun canal choisi", String.join(", ", KNOWN_CHANNELS));
                        break;
                    case "channels": break;   // ancienne bascule automatique : ignoree, plus ecrite
                    case "webdav.url": c.webdavUrl = v; break;
                    case "webdav.user": c.webdavUser = v; break;
                    case "webdav.password": c.webdavPassword = v; break;
                    case "drime.token": c.drimeToken = v; break;
                    case "drime.folder": c.drimeFolder = v.isEmpty() ? DEFAULT_DRIME_FOLDER : v; break;
                    case "drime.url": c.drimeUrl = v; break;
                    case "drime.workspace":
                        try { c.drimeWorkspace = Long.parseLong(v); }
                        catch (NumberFormatException e) { Agent.log("config: drime.workspace illisible, 0 utilise"); }
                        break;
                    case "proxy": c.proxy = v.isEmpty() ? "system" : v; break;
                    case "tls.truststore": c.tlsTruststore = v.isEmpty() ? "java" : v.toLowerCase(Locale.ROOT); break;
                    case "update.auto": c.updateAuto = Boolean.parseBoolean(v); break;
                    default:
                        if (k.toLowerCase().startsWith("root.")) {
                            c.roots.put(k.substring(5), v);
                        } else if (k.toLowerCase().startsWith("relay.")) {
                            c.relays.put(k.substring(6), v);
                        } else if (k.toLowerCase().startsWith("km.")) {
                            c.km.setProperty(k.toLowerCase(Locale.ROOT), v);
                        }
                }
            }
        } catch (IOException e) {
            Agent.log("config: lecture impossible (%s)", e.getMessage());
        }
        return c;
    }

    public void save(Path file) {
        try {
            write(file);
        } catch (IOException e) {
            Agent.log("config: ecriture impossible (%s)", e.getMessage());
        }
    }

    /**
     * Ecriture atomique : fichier temporaire voisin puis renommage. Un arret en
     * cours d'ecriture ne laisse jamais un cotransfert.conf tronque.
     */
    public void write(Path file) throws IOException {
        Path abs = file.toAbsolutePath();
        Files.createDirectories(abs.getParent());
        Path tmp = abs.resolveSibling(abs.getFileName() + ".tmp");
        try {
            Files.write(tmp, lines());
            MailboxSession.moveAtomic(tmp, abs);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private List<String> lines() {
            List<String> lines = new ArrayList<>();
            lines.add("# CoTransfert - configuration locale de ce poste.");
            lines.add("# role : hub (ecoute) ou client (se connecte).");
            lines.add("role=" + role);
            if (master != null) lines.add("master=" + master);
            lines.add("conflict=" + conflict);
            lines.add("# Dossiers a synchroniser : root.<NomLogique>=<chemin local>");
            lines.add("# Le NomLogique doit etre identique sur les deux postes ; le chemin, non.");
            for (Map.Entry<String, String> e : roots.entrySet()) {
                lines.add("root." + e.getKey() + "=" + e.getValue());
            }
            lines.add("# Hub force (nom ou IP) ; vide = decouverte automatique sur le lien direct.");
            lines.add((peer.isEmpty() ? "# peer=" : "peer=" + peer));
            lines.add("# discovery=link (lien direct seulement, defaut) ou lan (reseaux prives aussi).");
            lines.add("discovery=" + discovery);
            lines.add("# Secret partage, identique sur les deux postes : authentifie le pair et chiffre");
            lines.add("# les echanges. Obligatoire hors lien direct. Generer : --new-secret");
            lines.add((secret.isEmpty() ? "# secret=" : "secret=" + secret));
            lines.add("# Canal de transfert : un seul a la fois, choisi dans la page (le meme sur les deux postes).");
            lines.add("# direct, webdav, drime ou disque ; vide = aucun canal choisi, rien n'est synchronise.");
            lines.add(opt("channel", channelId(channel)));
            lines.add("# Canal WebDAV : dossier de transit sur un stockage en ligne (https), identifiant et");
            lines.add("# mot de passe d'application. Les trois cles sont requises, ainsi que secret=.");
            lines.add("# Exemple : https://cloud.example.org/remote.php/dav/files/utilisateur/CoTransfertRelais");
            lines.add(opt("webdav.url", webdavUrl));
            lines.add(opt("webdav.user", webdavUser));
            lines.add(opt("webdav.password", webdavPassword));
            lines.add("# Canal Drime : jeton personnel cree dans les reglages du compte (requiert secret=).");
            lines.add("# drime.folder : dossier de transit ; drime.workspace : espace de travail (0 = personnel) ;");
            lines.add("# drime.url : adresse de l'API, a ne changer que pour des essais.");
            lines.add(opt("drime.token", drimeToken));
            lines.add((DEFAULT_DRIME_FOLDER.equals(drimeFolder) ? "# drime.folder=" : "drime.folder=") + drimeFolder);
            lines.add((drimeWorkspace == 0 ? "# drime.workspace=" : "drime.workspace=") + drimeWorkspace);
            lines.add(opt("drime.url", drimeUrl));
            lines.add("# Canal disque (aucune connexion possible) : dossier relais accessible aux deux");
            lines.add("# postes (partage, dossier cloud, cle USB). \"*:\\Dossier\" = cherche sur tous les lecteurs.");
            lines.add("# relay.<NomLogique>=... pour un relais propre a un dossier.");
            lines.add((relay.isEmpty() ? "# relay=" : "relay=" + relay));
            for (Map.Entry<String, String> e : relays.entrySet()) {
                lines.add("relay." + e.getKey() + "=" + e.getValue());
            }
            lines.add("# Reseau des canaux en ligne. proxy : system (reglages du poste, defaut), none ou hote:port.");
            lines.add("# tls.truststore : java (defaut) ou windows (certificats installes par l'organisation).");
            lines.add(("system".equalsIgnoreCase(proxy) ? "# proxy=" : "proxy=") + proxy);
            lines.add(("java".equals(tlsTruststore) ? "# tls.truststore=" : "tls.truststore=") + tlsTruststore);
            lines.add("# Mise a jour par le canal : true = le client installe tout seul la version publiee par le hub.");
            lines.add((updateAuto ? "update.auto=" : "# update.auto=") + updateAuto);
            lines.addAll(KmSettings.from(km, null).confLines());
            return lines;
    }

    /** Ligne "cle=valeur", ou la cle en commentaire quand la valeur est vide. */
    private static String opt(String key, String value) {
        return value.isEmpty() ? "# " + key + "=" : key + "=" + value;
    }
}
