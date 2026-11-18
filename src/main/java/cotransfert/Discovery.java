package cotransfert;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BRIQUE 1 - Auto-decouverte sur lien direct APIPA (169.254.0.0/16).
 *
 * Mecanisme valide par POC entre un hub et un client verrouille :
 *   - Les IP APIPA sont dynamiques : rien n'est code en dur.
 *   - Le poste "hub" peut ecouter ; le poste "client" (verrouille) ne peut RIEN
 *     recevoir qu'il n'ait pas initie (ni entrant, ni broadcast, ni reponse
 *     unicast a un broadcast). Seuls le sortant et le trafic retour marchent.
 *
 * Donc :
 *   1. Le hub diffuse en continu un beacon UDP broadcast. Le client ne le voit
 *      pas au niveau applicatif, MAIS la trame remplit sa table de voisinage
 *      ARP sous le pare-feu.
 *   2. Le client lit les voisins 169.254.x de chaque interface (via "arp -a").
 *      Sur un lien point-a-point, il n'y a qu'un voisin : le hub.
 *   3. Le client confirme par un handshake UNICAST (DISCOVER -> ACK). La reponse
 *      passe car c'est du trafic retour sur un flux qu'il a initie.
 *
 * Mode LAN (optionnel, desactive par defaut, {@link #setLanDiscovery}) : meme
 * mecanisme etendu aux interfaces IPv4 privees (10/8, 172.16/12, 192.168/16),
 * avec l'adresse de broadcast propre a chaque interface. On ne contacte QUE des
 * adresses deja presentes dans le cache ARP (aucun balayage de sous-reseau), au
 * plus MAX_PROBES voisins par interface et par tour. Un ACK ne prouve rien :
 * hors lien direct, c'est le secret partage qui authentifie le hub (Transfer).
 */
public final class Discovery {

    public static final int UDP_PORT = 47778;
    static final String MAGIC = "COTP1";
    static final String BROADCAST = "169.254.255.255";
    /** Cadence de la boucle de presence. Sur le lien direct on veut une connexion en une seconde ou deux. */
    static final long BEACON_EVERY_MS = 500;
    /** Hors lien direct (mode LAN), le broadcast reste rare : un tour sur LAN_EVERY. */
    static final int LAN_EVERY = 4;
    /** Relecture du cache ARP par le hub : un tour sur ARP_EVERY (la commande arp coute un processus). */
    static final int ARP_EVERY = 4;
    static final int PING_TIMEOUT_MS = 300;
    /** Plafond de voisins sondes par interface et par tour. */
    static final int MAX_PROBES = 32;
    static final int PROBE_WAIT_MS = 600;
    static final long RESOLVE_EVERY_MS = 300;

    private static final Pattern ARP_LINE = Pattern.compile(
            "(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})\\s+([0-9a-fA-F]{2}(?:[-:][0-9a-fA-F]{2}){5})");

    /** Le pair resolu. */
    public static final class Peer {
        public final String ip;       // IP du pair (APIPA, ou privee en mode LAN)
        public final String host;     // hostname annonce
        public final String localIp;  // notre IP sur l'interface qui joint le pair
        Peer(String ip, String host, String localIp) {
            this.ip = ip; this.host = host; this.localIp = localIp;
        }
    }

    /** Une adresse IPv4 locale utilisable pour la decouverte. */
    static final class Link {
        final String ip;         // notre adresse sur l'interface
        final String broadcast;  // adresse de broadcast de l'interface
        final int prefix;        // longueur du prefixe reseau
        final boolean apipa;
        Link(String ip, String broadcast, int prefix, boolean apipa) {
            this.ip = ip; this.broadcast = broadcast; this.prefix = prefix; this.apipa = apipa;
        }
    }

    private final String selfHost;
    private volatile boolean lan;
    /** Rotation des voisins sondes quand le cache ARP depasse MAX_PROBES (par IP locale). */
    private final Map<String, Integer> probeOffset = new HashMap<>();

    public Discovery(String selfHost) { this.selfHost = selfHost; }

    // --- Memoire des pairs deja vus sur un lien direct ------------------------
    //
    // Le cache ARP d'un poste ne connait l'autre que si l'un des deux lui a parle
    // directement : un broadcast ne suffit pas toujours, et apres un cable
    // debranche puis rebranche le cache est vide. Les adresses APIPA etant
    // stables d'un branchement a l'autre, chaque poste retient les dernieres
    // adresses de son pair et s'adresse a elles directement (un paquet UDP
    // unicast, sur le lien APIPA seulement) : la resolution ARP qui en decoule
    // suffit a relancer la decouverte, sans aucun balayage.

    static final int MAX_REMEMBERED = 4;
    private static final String MEMORY_FILE = "cotransfert.voisins";
    private static final java.util.LinkedHashSet<String> REMEMBERED = new java.util.LinkedHashSet<>();
    private static boolean memoryLoaded;

    private static java.nio.file.Path memoryFile() {
        java.nio.file.Path jar = Updater.runningJar();
        return jar == null ? null : jar.resolveSibling(MEMORY_FILE);
    }

    private static boolean apipa(String ip) {
        return ip != null && ip.matches("169\\.254\\.\\d{1,3}\\.\\d{1,3}") && !ip.equals(BROADCAST);
    }

    /** Dernieres adresses APIPA du pair, la plus recente en dernier. */
    static synchronized List<String> remembered() {
        if (!memoryLoaded) {
            memoryLoaded = true;
            java.nio.file.Path f = memoryFile();
            try {
                if (f != null && java.nio.file.Files.isRegularFile(f)) {
                    for (String line : java.nio.file.Files.readAllLines(f)) {
                        String ip = line.trim();
                        if (apipa(ip) && REMEMBERED.size() < MAX_REMEMBERED) REMEMBERED.add(ip);
                    }
                }
            } catch (Exception ignore) { }
        }
        return new ArrayList<>(REMEMBERED);
    }

    /** Retient l'adresse du pair (APIPA seulement). Sans effet si elle est deja la plus recente. */
    static synchronized void remember(String ip) {
        if (!apipa(ip)) return;
        List<String> before = remembered();
        if (!before.isEmpty() && before.get(before.size() - 1).equals(ip)) return;
        REMEMBERED.remove(ip);
        REMEMBERED.add(ip);
        while (REMEMBERED.size() > MAX_REMEMBERED) REMEMBERED.remove(REMEMBERED.iterator().next());
        java.nio.file.Path f = memoryFile();
        if (f == null) return;
        try {
            java.nio.file.Files.write(f, new ArrayList<>(REMEMBERED));
        } catch (Exception ignore) { }   // la memoire reste valable pour ce lancement
    }

    /** Pour les tests : oublie tout, sans toucher au fichier. */
    static synchronized void forgetForTest() { REMEMBERED.clear(); memoryLoaded = true; }

    /**
     * Mode LAN (opt-in) : le beacon du hub est aussi diffuse sur les interfaces
     * IPv4 privees, et le client y cherche aussi ses voisins. Defaut : false
     * (APIPA seul).
     */
    public void setLanDiscovery(boolean on) { this.lan = on; }

    // --- Beacon : diffuse notre presence en broadcast (les deux roles) -------

    public void startBeacon(final String role) {
        // Hors APIPA, seul le beacon du hub sert (il peuple l'ARP des clients) :
        // le client n'emet donc aucun broadcast sur un reseau partage.
        final boolean hub = "hub".equals(role);
        Thread t = new Thread(new Runnable() {
            public void run() {
                int tour = 0;
                while (!Thread.currentThread().isInterrupted()) {
                    for (Link l : links(lan && hub)) {
                        if (!l.apipa && tour % LAN_EVERY != 0) continue;
                        sendBroadcast(l.ip, l.broadcast, MAGIC + "|BEACON|" + role + "|" + selfHost + "|" + l.ip);
                        // Le hub explore en boucle le lien direct : il s'adresse directement aux
                        // dernieres adresses connues du client et aux voisins du lien. C'est ce qui
                        // le fait apparaitre chez le client des que le cable est branche.
                        if (hub && l.apipa) {
                            java.util.LinkedHashSet<String> cibles = new java.util.LinkedHashSet<>(remembered());
                            if (tour % ARP_EVERY == 0) cibles.addAll(arpNeighbors(l.ip));
                            cibles.remove(l.ip);
                            for (String ip : cibles) nudge(l.ip, ip);
                        }
                    }
                    tour++;
                    sleep(BEACON_EVERY_MS);
                }
            }
        }, "cotp-beacon");
        t.setDaemon(true);
        t.start();
    }

    /** Un seul fil pour les pings : un ping sans reponse ne retarde jamais la boucle de presence. */
    private final java.util.concurrent.ExecutorService pinger = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cotp-ping");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Long> lastNudge = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Le hub se signale directement a une adresse du lien direct : un paquet de
     * presence unicast, puis un ping (API systeme, sans droit particulier). L'un
     * comme l'autre obligent les deux postes a se resoudre en ARP : le hub entre
     * alors dans la table de voisinage du client, qui le trouve a son tour suivant.
     * Jamais plus d'une fois par cadence et par adresse, lien direct seulement.
     */
    void nudge(final String localIp, final String ip) {
        if (!apipa(ip) || !apipa(localIp) || ip.equals(localIp)) return;
        long now = System.nanoTime() / 1_000_000L;
        Long prev = lastNudge.get(ip);
        if (prev != null && now - prev < BEACON_EVERY_MS - 50) return;
        lastNudge.put(ip, now);
        if (lastNudge.size() > 64) lastNudge.clear();
        sendBroadcast(localIp, ip, MAGIC + "|BEACON|hub|" + selfHost + "|" + localIp);
        try {
            pinger.execute(() -> {
                try {
                    NetworkInterface ni = NetworkInterface.getByInetAddress(InetAddress.getByName(localIp));
                    InetAddress.getByName(ip).isReachable(ni, 0, PING_TIMEOUT_MS);
                } catch (Exception ignore) { }
            });
        } catch (RuntimeException ignore) { }
    }

    private void sendBroadcast(String localIp, String broadcast, String payload) {
        try (DatagramSocket ds = new DatagramSocket(new InetSocketAddress(InetAddress.getByName(localIp), 0))) {
            ds.setBroadcast(true);
            byte[] b = payload.getBytes("US-ASCII");
            ds.send(new DatagramPacket(b, b.length, InetAddress.getByName(broadcast), UDP_PORT));
        } catch (Exception ignore) { }
    }

    // --- ROLE HUB : repond aux DISCOVER unicast, absorbe les broadcasts -------

    public interface PeerListener { void onPeer(Peer p); }

    /** Boucle bloquante : ecoute UDP et repond aux DISCOVER. */
    public void serveHubDiscovery(PeerListener listener) throws Exception {
        try (DatagramSocket sock = new DatagramSocket(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), UDP_PORT))) {
            byte[] buf = new byte[2048];
            while (true) {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                sock.receive(pkt);
                String[] p = new String(buf, 0, pkt.getLength(), "US-ASCII").split("\\|");
                if (p.length < 4 || !p[0].equals(MAGIC)) continue;
                String kind = p[1], role = p[2], host = p[3];
                if ("hub".equals(role)) continue; // ignore nos propres beacons
                if ("DISCOVER".equals(kind)) {
                    byte[] ack = (MAGIC + "|ACK|hub|" + selfHost + "|" + pkt.getAddress().getHostAddress())
                            .getBytes("US-ASCII");
                    sock.send(new DatagramPacket(ack, ack.length, pkt.getAddress(), pkt.getPort()));
                }
                // Le client vient de se signaler : on lui repond tout de suite, directement.
                String from = pkt.getAddress().getHostAddress();
                remember(from);
                if (apipa(from)) for (Link l : links(false)) nudge(l.ip, from);
                if (listener != null) listener.onPeer(new Peer(pkt.getAddress().getHostAddress(), host, null));
            }
        }
    }

    // --- ROLE CLIENT : resout le hub -----------------------------------------

    /** Bloque jusqu'a resolution. */
    public Peer resolveHub() {
        while (true) {
            Peer p = round();
            if (p != null) return p;
            sleep(RESOLVE_EVERY_MS); // le hub se signale en boucle : il sera bientot dans la table ARP
        }
    }

    /** Comme resolveHub() mais abandonne : rend null apres timeoutMs (au moins un tour). */
    public Peer resolveHub(long timeoutMs) {
        long deadline = System.nanoTime() + Math.max(0, timeoutMs) * 1_000_000L;
        while (true) {
            Peer p = round();
            if (p != null) return p;
            long leftMs = (deadline - System.nanoTime()) / 1_000_000L;
            if (leftMs <= 0 || Thread.currentThread().isInterrupted()) return null;
            sleep(Math.min(RESOLVE_EVERY_MS, leftMs));
        }
    }

    /** Un tour : sonde les voisins ARP de chaque interface eligible. */
    private synchronized Peer round() {
        for (Link l : links(lan)) {
            List<String> targets = new ArrayList<>(pick(l.ip, neighbors(l)));
            // Dernieres adresses connues du hub, meme absentes du cache ARP (cable
            // rebranche) : les interroger suffit a les y faire revenir.
            if (l.apipa) {
                for (String ip : remembered()) if (!ip.equals(l.ip) && !targets.contains(ip)) targets.add(ip);
            }
            if (targets.isEmpty()) continue;
            Peer p = probe(l.ip, targets);
            if (p != null) { remember(p.ip); return p; }
        }
        return null;
    }

    /** Au plus MAX_PROBES voisins ; la fenetre tourne d'un tour a l'autre pour couvrir tout le cache. */
    private List<String> pick(String localIp, List<String> all) {
        if (all.size() <= MAX_PROBES) return all;
        Integer prev = probeOffset.get(localIp);
        int start = (prev == null ? 0 : prev) % all.size();
        List<String> out = new ArrayList<>(MAX_PROBES);
        for (int i = 0; i < MAX_PROBES; i++) out.add(all.get((start + i) % all.size()));
        probeOffset.put(localIp, (start + MAX_PROBES) % all.size());
        return out;
    }

    /**
     * Envoie un DISCOVER unicast a chaque cible puis attend le premier ACK d'un
     * hub venant de l'une d'elles (trafic retour d'un flux que nous avons initie).
     */
    private Peer probe(String localIp, List<String> targets) {
        try (DatagramSocket ds = new DatagramSocket(new InetSocketAddress(InetAddress.getByName(localIp), 0))) {
            byte[] m = (MAGIC + "|DISCOVER|client|" + selfHost + "|" + localIp).getBytes("US-ASCII");
            Set<String> asked = new HashSet<>();
            for (String t : targets) {
                try {
                    ds.send(new DatagramPacket(m, m.length, InetAddress.getByName(t), UDP_PORT));
                    asked.add(t);
                } catch (Exception ignore) { }
            }
            if (asked.isEmpty()) return null;
            long deadline = System.nanoTime() + PROBE_WAIT_MS * 1_000_000L;
            byte[] rb = new byte[2048];
            while (true) {
                int left = (int) ((deadline - System.nanoTime()) / 1_000_000L);
                if (left <= 0) return null;
                ds.setSoTimeout(left);
                DatagramPacket resp = new DatagramPacket(rb, rb.length);
                ds.receive(resp);
                String from = resp.getAddress().getHostAddress();
                if (!asked.contains(from)) continue;
                String[] p = new String(rb, 0, resp.getLength(), "US-ASCII").split("\\|");
                if (p.length >= 4 && p[0].equals(MAGIC) && p[1].equals("ACK") && p[2].equals("hub")) {
                    return new Peer(from, p[3], localIp);
                }
            }
        } catch (Exception ignore) { }
        return null;
    }

    // --- Helpers systeme -----------------------------------------------------

    static List<String> localApipaIps() {
        List<String> out = new ArrayList<>();
        for (Link l : links(false)) out.add(l.ip);
        return out;
    }

    /** Interfaces IPv4 actives eligibles : APIPA toujours, privees seulement si lan. */
    static List<Link> links(boolean lan) {
        List<Link> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (!(a instanceof Inet4Address)) continue;
                    if (a.isLinkLocalAddress()) {
                        out.add(new Link(a.getHostAddress(), BROADCAST, 16, true));
                    } else if (lan && a.isSiteLocalAddress()) {
                        InetAddress bc = ia.getBroadcast();
                        int prefix = ia.getNetworkPrefixLength();
                        if (bc == null || prefix < 8 || prefix > 30) continue; // pas de broadcast exploitable
                        out.add(new Link(a.getHostAddress(), bc.getHostAddress(), prefix, false));
                    }
                }
            }
        } catch (Exception ignore) { }
        return out;
    }

    /** Voisins a sonder sur ce lien : uniquement des entrees du cache ARP, dans le sous-reseau du lien. */
    static List<String> neighbors(Link l) {
        if (l.apipa) return arpNeighbors(l.ip);
        List<String> res = new ArrayList<>();
        for (String ip : arpTable(l.ip)) {
            if (ip.equals(l.broadcast) || !sameSubnet(ip, l.ip, l.prefix)) continue;
            res.add(ip);
        }
        return res;
    }

    /** Voisins APIPA (169.254.x) de l'interface portant localIp. */
    static List<String> arpNeighbors(String localIp) {
        List<String> res = new ArrayList<>();
        for (String ip : arpTable(localIp)) {
            if (ip.startsWith("169.254.") && !ip.equals(BROADCAST)) res.add(ip);
        }
        return res;
    }

    /**
     * Table de voisinage de l'interface portant localIp. "arp -a -N <ifaceIp>"
     * ne necessite pas d'admin ; le parsing regex est insensible a la langue.
     * Lecture seule du cache : aucune trame n'est emise.
     */
    static List<String> arpTable(String localIp) {
        List<String> res = new ArrayList<>();
        try {
            Process p = new ProcessBuilder("arp", "-a", "-N", localIp).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    Matcher m = ARP_LINE.matcher(line);
                    if (!m.find()) continue;
                    String ip = m.group(1), mac = m.group(2).toLowerCase().replace(':', '-');
                    if (ip.equals(localIp)) continue;
                    if (mac.startsWith("ff-") || mac.startsWith("01-00-5e")) continue; // broadcast/multicast
                    if (mac.equals("00-00-00-00-00-00")) continue;                     // entree incomplete
                    res.add(ip);
                }
            }
            p.waitFor();
        } catch (Exception ignore) { }
        return res;
    }

    /** true si ip et localIp partagent le meme prefixe IPv4. */
    static boolean sameSubnet(String ip, String localIp, int prefix) {
        try {
            int a = toInt(ip), b = toInt(localIp);
            int mask = prefix <= 0 ? 0 : (int) (0xFFFFFFFFL << (32 - prefix));
            return (a & mask) == (b & mask);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static int toInt(String ipv4) {
        String[] p = ipv4.split("\\.");
        if (p.length != 4) throw new IllegalArgumentException(ipv4);
        int v = 0;
        for (String s : p) {
            int o = Integer.parseInt(s);
            if (o < 0 || o > 255) throw new IllegalArgumentException(ipv4);
            v = (v << 8) | o;
        }
        return v;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
