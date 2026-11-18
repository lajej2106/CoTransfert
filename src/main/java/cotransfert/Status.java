package cotransfert;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Modele d'etat partage, lu par le serveur web et mis a jour par le moteur.
 *
 * - "self" : l'etat des dossiers de CE poste.
 * - "peer" : l'etat remonte par le poste d'en face via des cadres OP_STATUS,
 *   quel que soit le canal. Sur le lien direct il arrive en continu (un pair
 *   muet depuis 6 s est considere deconnecte) ; sur un canal asynchrone
 *   ("relais", "valise") il arrive avec des minutes ou des heures de retard :
 *   on garde alors le dernier etat connu et son age, sans regle de fraicheur.
 *   Le poste qui heberge la page web (hub, ou client en boucle locale) agrege
 *   les deux.
 *
 * Canal de transfert : l'utilisateur en choisit UN (ou aucun), il n'y a aucune
 * bascule automatique. Chaque dossier de "self" porte l'etat des quatre canaux
 * connus, toujours dans l'ordre direct, webdav, drime, disque :
 * {@link #setChannelPlan} dit lequel est choisi et lesquels ne sont pas regles,
 * puis l'agent tient a jour l'etat du canal choisi ({@link #setChannelState}).
 * Seul le canal choisi peut etre "active", "waiting" ou "down" ; les autres sont
 * "ready" (regle, pas teste en continu) ou "off" (non regle).
 * Aucun secret ne doit entrer ici ; par precaution les details sont filtres
 * (voir {@link #setSecrets}).
 *
 * Thread-safe ; la page web l'interroge via /api/status (polling).
 */
public final class Status {

    public enum State { OFFLINE, CONNECTING, SYNCING, UP_TO_DATE, CONFLICT }

    public static final class Root {
        public final String name;
        public volatile String path = "";
        public volatile State state = State.OFFLINE;
        public final AtomicInteger remaining = new AtomicInteger();
        /** Conflits en attente (copies ".conflict-peer-" pas encore tranchees) ; survit aux redemarrages. */
        public volatile int conflicts = 0;
        public volatile String lastLine = "";
        /** Canal de la session en cours : "direct", "webdav", "drime", "disque", "relais", "valise" (cf. Transfer.Session.channel). */
        public volatile String channel = "";
        /** Etat du canal asynchrone, libre (ex. "3 lots en attente", "support retirable"). */
        public volatile String channelInfo = "";
        /** Canal choisi par l'utilisateur pour ce dossier, "" si aucun. */
        volatile String selected = "";
        /** Vrai une fois {@link Status#setChannelPlan} appele : l'etat des canaux fait foi pour l'affichage. */
        volatile boolean planned = false;
        /** Etat de chaque canal connu : id -> {etat, detail}. */
        final Map<String, String[]> chan = new ConcurrentHashMap<>();
        /** Nom sous lequel le pair rapporte ce dossier : le meme nom logique (autre seulement dans les essais a deux postes par processus). */
        volatile String peerName;
        Root(String name) { this.name = name; this.peerName = name; }
    }

    /** Etat d'un canal pour la page : state vaut active, waiting, down, ready ou off. */
    public record Channel(String id, String label, boolean selected, String state, String detail) { }

    public static final String CH_ACTIVE = "active", CH_READY = "ready", CH_WAITING = "waiting",
            CH_DOWN = "down", CH_OFF = "off";
    /** Canaux connus, dans l'ordre fixe d'affichage. */
    private static final List<String> KNOWN = List.of("direct", "webdav", "drime", "disque");

    /** Libelle d'un canal pour la page. */
    public static String channelLabel(String id) {
        if (id == null) return "";
        return switch (id) {
            case "direct" -> "Lien direct"; case "webdav" -> "WebDAV"; case "drime" -> "Drime";
            case "disque" -> "Disque relais"; case "valise" -> "Valise"; case "relais" -> "Relais";
            default -> id;
        };
    }

    /** Canal par defaut : le lien TCP direct, seul canal synchrone. */
    public static final String CHANNEL_DIRECT = "direct";
    /** Au-dela de ce silence, un pair suivi par le lien direct est considere deconnecte. */
    public static final long DIRECT_FRESH_MS = 6000;

    /** Etat d'un dossier remonte par le pair. */
    public static final class PeerRoot {
        public volatile String name, state = "OFFLINE", last = "";
        public volatile int remaining, conflicts;
        /** Date de reception (horloge locale) du dernier rapport. */
        public volatile long updated = System.currentTimeMillis();
        /** Canal par lequel ce rapport est arrive : "direct", "relais", "valise". */
        public volatile String channel = CHANNEL_DIRECT;

        /** Anciennete du dernier rapport, en ms. */
        public long ageMs() { return Math.max(0, System.currentTimeMillis() - updated); }
        /** Vrai si le rapport vient du lien direct et date de moins de 6 s. */
        public boolean live() { return isDirect(channel) && ageMs() < DIRECT_FRESH_MS; }
    }

    private static final Status INSTANCE = new Status();
    public static Status get() { return INSTANCE; }

    private final Map<String, Root> roots = new ConcurrentHashMap<>();
    private final Map<String, PeerRoot> peerRoots = new ConcurrentHashMap<>();
    private volatile String host = "", role = "", peerHost = "";
    private volatile long peerSeen = 0;        // dernier rapport, tous canaux confondus
    private volatile long peerSeenDirect = 0;  // dernier rapport recu par le lien direct

    /** Vrai quand ce poste est regle (role valide et au moins un dossier) ; faux = premier reglage. */
    private volatile boolean configured = false;

    /** Instant de demarrage du processus (ms) : change a chaque relance, la page s'en sert pour la detecter. */
    private final long boot = System.currentTimeMillis();

    /** Canal choisi par l'utilisateur pour ce poste ("" = aucun). */
    private volatile String selectedChannel = "";

    /** Etat de la mise a jour par le canal (objet JSON tenu par Updater) ; par defaut : indisponible. */
    private volatile String update = "{\"current\":\"\",\"supported\":false,\"canPublish\":false,\"available\":false,"
            + "\"version\":\"\",\"state\":\"\",\"message\":\"non disponible dans ce mode de lancement\"}";

    /** Pose l'objet JSON "update" de "self" (voir {@link Updater#json}). */
    public void setUpdate(String json) { if (json != null && json.startsWith("{")) this.update = json; }

    public void setConfigured(boolean c) { this.configured = c; }
    public void setSelectedChannel(String id) { this.selectedChannel = KNOWN.contains(id) ? id : ""; }
    public void setHost(String h) { this.host = h; }
    public void setRole(String r) { this.role = r; }
    public String host() { return host; }

    public Root root(String name) { return roots.computeIfAbsent(name, Root::new); }
    public Collection<Root> roots() { return roots.values(); }
    public void drop(String name) { roots.remove(name); }

    // --- mutations moteur (self) -------------------------------------------

    public void setState(String name, State s) { root(name).state = s; }
    public void setPath(String name, String p) { root(name).path = p; }
    public void setChannel(String name, String c) { root(name).channel = c == null ? "" : c; }
    public void setChannelInfo(String name, String info) { root(name).channelInfo = scrub(info); }
    /** Compteur de conflits d'un dossier : le nombre de conflits EN ATTENTE (voir {@link Conflicts}), jamais un compteur de session. */
    private static int pending(Root r) { return r.conflicts = Conflicts.count(r.name); }
    public void log(String name, String line) { root(name).lastLine = line; }
    public void setRemaining(String name, int n) { root(name).remaining.set(Math.max(0, n)); }
    public void addRemaining(String name, int d) { int v = root(name).remaining.addAndGet(d); if (v < 0) root(name).remaining.set(0); }
    public void decRemaining(String name) { if (root(name).remaining.decrementAndGet() < 0) root(name).remaining.set(0); }

    public void recompute(String name, boolean connected) {
        Root r = root(name);
        int waiting = pending(r);   // toujours rafraichi : c'est aussi ce qui est remonte au pair
        if (r.remaining.get() > 0) r.state = State.SYNCING;
        else if (waiting > 0) r.state = State.CONFLICT;
        else if (connected) r.state = State.UP_TO_DATE;
        else r.state = State.OFFLINE;
    }

    // --- canal de transfert (self) -----------------------------------------

    private volatile List<String> secrets = List.of();

    /** Valeurs a ne jamais laisser sortir (secret partage, mot de passe, jeton) : masquees dans les details. */
    public void setSecrets(Collection<String> values) {
        List<String> l = new ArrayList<>();
        if (values != null) for (String v : values) if (v != null && v.trim().length() >= 4) l.add(v.trim());
        secrets = List.copyOf(l);
    }

    /** Texte court pour la page : une ligne, 200 caracteres au plus, valeurs sensibles connues masquees. */
    private String scrub(String s) {
        if (s == null || s.isEmpty()) return "";
        String out = s;
        for (String v : secrets) out = out.replace(v, "***");
        out = out.replace('\n', ' ').replace('\r', ' ').trim();
        return out.length() > 200 ? out.substring(0, 200) : out;
    }

    /**
     * Fixe les canaux d'un dossier. selected : le canal choisi ("" = aucun).
     * off : canaux non regles pour ce dossier et pourquoi ("non configure",
     * "secret partage manquant"). Etats de depart :
     *   - canal non choisi : "ready" s'il est regle, "off" sinon (avec la raison) ;
     *   - canal choisi mais non regle : "down" avec la raison (aucune synchronisation) ;
     *   - canal choisi et regle : "waiting" pour le lien direct (l'autre poste n'est
     *     pas encore connecte), "down" + "verification en cours" pour un canal par
     *     lots, jusqu'au premier etat publie par l'agent ({@link #setChannelState}).
     */
    public void setChannelPlan(String name, String selected, Map<String, String> off) {
        Root r = root(name);
        String sel = KNOWN.contains(selected) ? selected : "";
        for (String id : KNOWN) {
            String why = off == null ? null : off.get(id);
            String[] st;
            if (id.equals(sel)) {
                if (why != null) st = new String[] { CH_DOWN, scrub(why) };
                else if (CHANNEL_DIRECT.equals(id)) st = new String[] { CH_WAITING, "" };
                else st = new String[] { CH_DOWN, "verification en cours" };
            } else {
                st = why != null ? new String[] { CH_OFF, scrub(why) } : new String[] { CH_READY, "" };
            }
            r.chan.put(id, st);
        }
        r.selected = sel;
        r.planned = true;
    }

    /**
     * Etat du canal CHOISI d'un dossier : "active", "waiting" ou "down" (+ raison).
     * Sans effet sur un autre canal : un canal non choisi reste "ready" ou "off".
     */
    public void setChannelState(String name, String id, String state, String detail) {
        Root r = root(name);
        if (id == null || !id.equals(r.selected)) return;
        if (!CH_ACTIVE.equals(state) && !CH_WAITING.equals(state) && !CH_DOWN.equals(state)) return;
        r.chan.put(id, new String[] { state, scrub(detail) });
    }

    /** Les quatre canaux connus d'un dossier, toujours dans l'ordre direct, webdav, drime, disque. */
    public List<Channel> channels(String name) {
        Root r = root(name);
        String sel = r.selected;
        List<Channel> out = new ArrayList<>();
        for (String id : KNOWN) {
            String[] st = r.chan.get(id);
            if (st == null) st = new String[] { CH_OFF, "non configure" };
            out.add(new Channel(id, channelLabel(id), id.equals(sel), st[0], st[1]));
        }
        return out;
    }

    // --- etat du pair (recu par OP_STATUS) ---------------------------------

    /**
     * Rapport d'etat du pair sans canal explicite : on reprend le canal courant
     * du dossier local de meme nom (c'est par lui que le rapport est arrive), et
     * "direct" s'il est inconnu.
     */
    public void peerReport(String pHost, String name, String state, int remaining, int conflicts, String last) {
        Root self = roots.get(name);
        peerReport(pHost, name, state, remaining, conflicts, last, self == null ? CHANNEL_DIRECT : self.channel);
    }

    /**
     * Rapport d'etat du pair arrive par le canal indique ("direct", "relais",
     * "valise" ; vide ou null = "direct"). Seul le canal direct est soumis a la
     * regle de fraicheur des 6 s ; un rapport asynchrone reste valable, avec son age.
     */
    public void peerReport(String pHost, String name, String state, int remaining, int conflicts, String last,
                           String channel) {
        long now = System.currentTimeMillis();
        String ch = isDirect(channel) ? CHANNEL_DIRECT : channel.trim();
        if (pHost != null && !pHost.isEmpty()) this.peerHost = pHost;
        PeerRoot pr = peerRoots.computeIfAbsent(name, k -> new PeerRoot());
        pr.name = name; pr.state = state == null ? "OFFLINE" : state;
        pr.remaining = remaining; pr.conflicts = conflicts;
        pr.last = last == null ? "" : last; pr.channel = ch; pr.updated = now;
        this.peerSeen = now;
        if (isDirect(ch)) this.peerSeenDirect = now;
    }

    /** Vrai pour le canal direct (vide ou null compris), faux pour un canal asynchrone. */
    public static boolean isDirect(String channel) {
        return channel == null || channel.isBlank() || CHANNEL_DIRECT.equals(channel.trim());
    }

    /** Vrai si le pair a donne signe de vie par le lien direct il y a moins de 6 s. */
    public boolean peerConnected() { return System.currentTimeMillis() - peerSeenDirect < DIRECT_FRESH_MS; }
    /** Vrai si au moins un rapport du pair a ete recu, quel que soit le canal. */
    public boolean peerEverSeen() { return peerSeen > 0; }
    /** Anciennete (ms) du dernier rapport du pair, tous canaux confondus ; -1 si jamais vu. */
    public long peerAgeMs() { long t = peerSeen; return t == 0 ? -1 : Math.max(0, System.currentTimeMillis() - t); }
    public String peerHost() { return peerHost; }
    public Collection<PeerRoot> peerRoots() { return peerRoots.values(); }

    public State global() {
        boolean conflict = false, syncing = false, up = false, connecting = false;
        for (Root r : roots.values()) switch (r.state) {
            case CONFLICT -> conflict = true; case SYNCING -> syncing = true;
            case UP_TO_DATE -> up = true; case CONNECTING -> connecting = true; default -> { } }
        if (syncing) return State.SYNCING;
        if (conflict) return State.CONFLICT;
        if (up) return State.UP_TO_DATE;
        if (connecting) return State.CONNECTING;
        return State.OFFLINE;
    }

    /** Etat "en attente de l'autre poste" : affiche a la place de "A jour" tant que le canal choisi n'est pas actif. */
    public static final String WAITING = "WAITING";

    /**
     * Etat montre a l'utilisateur. Le moteur se dit "a jour" des qu'il n'a plus
     * rien a emettre, meme si ses lots attendent dans le spool faute de canal :
     * tant que le canal choisi n'est pas "active" (lien direct connecte, ou magasin
     * joignable), rien n'a pu atteindre le pair et on affiche "en attente" plutot
     * que "a jour".
     *   - canal par lots choisi et magasin joignable ("active") : "A jour" seulement
     *     une fois qu'un rapport du pair est arrive par ce canal depuis le demarrage ;
     *     tant que l'autre poste n'y a jamais ete vu, WAITING (les lots sont deposes,
     *     personne ne les a encore lus) ;
     *   - canal par lots choisi et magasin injoignable : "down", donc WAITING ;
     *   - lien direct choisi et non connecte : aucun moteur ne tourne, le dossier est
     *     OFFLINE (le canal, lui, est "waiting") ;
     *   - aucun canal choisi : aucun moteur ne tourne, le dossier reste OFFLINE et
     *     n'est jamais montre WAITING (c'est a l'utilisateur de choisir un canal).
     * Un dossier dont les canaux n'ont pas ete fixes (valise, essais) garde son etat brut.
     */
    private String shown(Root r) {
        if (r.state != State.UP_TO_DATE || !r.planned) return r.state.name();
        String[] st = r.selected.isEmpty() ? null : r.chan.get(r.selected);
        if (st == null) return State.OFFLINE.name();   // a jour sans canal choisi : impossible, par prudence
        if (!CH_ACTIVE.equals(st[0])) return WAITING;
        if (!CHANNEL_DIRECT.equals(r.selected)) {
            PeerRoot pr = peerRoots.get(r.peerName);
            if (pr == null || !r.selected.equals(pr.channel)) return WAITING;
        }
        return r.state.name();
    }

    /** Etat global montre a l'utilisateur (cf. {@link #shown}). */
    private String shownGlobal() {
        State g = global();
        if (g != State.UP_TO_DATE) return g.name();
        for (Root r : roots.values()) if (State.UP_TO_DATE.name().equals(shown(r))) return g.name();
        return WAITING;
    }

    public static String label(State s) {
        return switch (s) {
            case UP_TO_DATE -> "A jour"; case SYNCING -> "Synchronisation...";
            case CONFLICT -> "Conflit"; case CONNECTING -> "Connexion..."; case OFFLINE -> "Deconnecte";
        };
    }

    // --- serialisation JSON pour /api/status -------------------------------

    public synchronized String json() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"updated\":").append(System.currentTimeMillis());
        sb.append(",\"self\":{\"host\":").append(q(host)).append(",\"role\":").append(q(role))
          .append(",\"global\":").append(q(shownGlobal())).append(",\"roots\":[");
        boolean first = true;
        for (Root r : roots.values()) {
            if (!first) sb.append(','); first = false;
            sb.append("{\"name\":").append(q(r.name)).append(",\"path\":").append(q(r.path))
              .append(",\"state\":").append(q(shown(r))).append(",\"remaining\":").append(r.remaining.get())
              .append(",\"conflicts\":").append(pending(r)).append(",\"last\":").append(q(r.lastLine))
              .append(",\"channel\":").append(q(r.selected)).append(",\"channelInfo\":").append(q(r.channelInfo))
              .append(",\"channels\":[");
            boolean firstCh = true;
            for (Channel c : channels(r.name)) {
                if (!firstCh) sb.append(','); firstCh = false;
                sb.append("{\"id\":").append(q(c.id())).append(",\"label\":").append(q(c.label()))
                  .append(",\"selected\":").append(c.selected()).append(",\"state\":").append(q(c.state())).append(",\"detail\":").append(q(c.detail())).append('}');
            }
            sb.append("]}");
        }
        sb.append("],\"configured\":").append(configured).append(",\"channel\":").append(q(selectedChannel)).append(",\"boot\":").append(boot)
          .append(",\"update\":").append(update).append(",\"km\":").append(kmJson()).append('}');
        // Pair : "connected" = vivant sur le lien direct ; "seen"/"age" = dernier
        // rapport tous canaux confondus (age en ms, -1 si jamais vu). Les dossiers
        // sont toujours listes, meme perimes, avec leur age et leur canal d'arrivee.
        sb.append(",\"peer\":{\"host\":").append(q(peerHost)).append(",\"connected\":").append(peerConnected())
          .append(",\"seen\":").append(peerEverSeen()).append(",\"age\":").append(peerAgeMs())
          .append(",\"roots\":[");
        first = true;
        for (PeerRoot r : peerRoots.values()) {
            if (!first) sb.append(','); first = false;
            sb.append("{\"name\":").append(q(r.name)).append(",\"state\":").append(q(r.state))
              .append(",\"remaining\":").append(r.remaining).append(",\"conflicts\":").append(r.conflicts)
              .append(",\"last\":").append(q(r.last)).append(",\"channel\":").append(q(r.channel))
              .append(",\"age\":").append(r.ageMs()).append(",\"live\":").append(r.live()).append('}');
        }
        sb.append("]}}");
        return sb.toString();
    }

    /** Etat du partage clavier-souris (sans secret) ; un JRE sans module graphique le donne desactive. */
    private static String kmJson() {
        try {
            return Km.get().json();
        } catch (RuntimeException | LinkageError e) {
            return "{\"enabled\":false,\"available\":false,\"problem\":\"\",\"linked\":false,\"peerReady\":false,\"state\":\"idle\"}";
        }
    }

    static String q(String s) {
        if (s == null) return "\"\"";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    // controles, DEL et separateurs de ligne Unicode : toujours echappes
                    if (c < 0x20 || c == 0x7f || c == 0x2028 || c == 0x2029) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }
}
