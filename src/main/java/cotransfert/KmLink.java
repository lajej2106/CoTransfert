package cotransfert;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Partage clavier-souris : le lien. Une connexion TCP DEDIEE (un gros fichier de
 * la synchronisation ne retarde jamais la souris), toujours ouverte par le
 * client vers le hub, sur le port de la synchronisation, chiffree par le secret
 * partage (meme poignee de main que {@link Transfer} : voir {@link Crypto}).
 *
 * Ouverture, apres la poignee de main chiffree : le client envoie UN cadre de
 * {@link Transfer} d'operation {@link #OP_KM} (chemin "km", version du protocole
 * clavier-souris dans le champ hash). Le hub, qui lit deja le premier cadre de
 * chaque connexion pour connaitre le dossier (OP_HELLO), reconnait ainsi le lien
 * clavier-souris et le confie a {@link Km}. La suite du flux, dans les deux
 * sens, est une suite d'evenements {@link KmProtocol}.
 *
 * Emission : file d'attente videe par un fil dedie (le fil graphique ne bloque
 * jamais sur le reseau) ; les deplacements consecutifs en attente sont fusionnes.
 * Vie du lien : battement de coeur toutes les 500 ms ; rien recu pendant 2 s =
 * lien ferme (le poste pilote relache alors tout).
 */
final class KmLink implements Closeable {

    /** Operation du cadre d'ouverture : distincte de toutes celles de la synchronisation. */
    static final int OP_KM = Transfer.OP_KM;
    static final String HELLO_PATH = "km";

    static final long HEARTBEAT_MS = 500, TIMEOUT_MS = 2000;
    private static final int MAX_QUEUE = 8192;

    interface Handler { void event(KmProtocol.Event e); }

    private final DataInputStream in;
    private final DataOutputStream out;
    private final Closeable closer;
    private final String peer;

    private final Object qLock = new Object();
    private final ArrayDeque<KmProtocol.Event> queue = new ArrayDeque<>();
    private volatile long heartbeatMs = HEARTBEAT_MS, timeoutMs = TIMEOUT_MS;
    private volatile boolean closed;
    private volatile String reason = "";
    private volatile long lastRecv = System.nanoTime();

    /**
     * Lien sur une paire de flux deja connectes (et deja chiffres).
     * @param closer ferme le support (la socket) ; debloque la lecture en cours
     * @param peer   nom du pair pour le journal
     */
    KmLink(InputStream in, OutputStream out, Closeable closer, String peer) {
        this.in = new DataInputStream(in);
        this.out = new DataOutputStream(new BufferedOutputStream(out, 1024));
        this.closer = closer;
        this.peer = peer == null ? "" : peer;
    }

    /** Cadence du battement de coeur (0 = ne pas en emettre) et delai de silence (0 = pas de garde). */
    KmLink timing(long heartbeatMs, long timeoutMs) {
        this.heartbeatMs = heartbeatMs;
        this.timeoutMs = timeoutMs;
        return this;
    }

    String peer() { return peer; }

    boolean closed() { return closed; }

    /** Raison de la fermeture ("" tant que le lien vit). */
    String reason() { return reason; }

    // --- Ouverture ---------------------------------------------------------

    /**
     * Cote client : connexion sortante vers le hub, poignee de main chiffree puis
     * cadre d'ouverture. Sans cle : refus (jamais de frappes en clair).
     */
    static KmLink dial(String host, int port, Crypto.Key key, int connectTimeoutMs) throws IOException {
        if (key == null) throw new IOException("secret partage requis pour le partage clavier-souris");
        InetSocketAddress addr = new InetSocketAddress(host, port);
        if (addr.isUnresolved()) throw new IOException("nom d'hote introuvable : " + host);
        Socket c = new Socket();
        try {
            c.connect(addr, connectTimeoutMs);
            c.setTcpNoDelay(true);
            c.setKeepAlive(true);
            c.setSoTimeout(Transfer.HANDSHAKE_TIMEOUT_MS);
            Crypto.Channel ch = Crypto.handshake(key, false, c.getInputStream(), c.getOutputStream());
            c.setSoTimeout(0);
            Transfer.Msg hello = new Transfer.Msg();
            hello.op = OP_KM;
            hello.relPath = HELLO_PATH;
            hello.size = 0;
            hello.hash = KmProtocol.VERSION;
            DataOutputStream d = new DataOutputStream(ch.out);
            Transfer.writeFrame(d, hello, null);
            d.flush();
            return new KmLink(ch.in, ch.out, c, host);
        } catch (IOException | RuntimeException e) {
            try { c.close(); } catch (IOException ignore) { }
            throw e;
        }
    }

    /**
     * Cote hub, sur une socket tout juste acceptee (essais, demonstration) : fait
     * la poignee de main chiffree et lit le cadre d'ouverture. Dans l'agent, c'est
     * {@link Transfer} qui accepte et {@link #adopt} qui prend la suite.
     */
    static KmLink accept(Socket c, Crypto.Key key) throws IOException {
        try {
            if (key == null) throw new IOException("secret partage requis pour le partage clavier-souris");
            c.setTcpNoDelay(true);
            c.setKeepAlive(true);
            c.setSoTimeout(Transfer.HANDSHAKE_TIMEOUT_MS);
            Crypto.Channel ch = Crypto.handshake(key, true, c.getInputStream(), c.getOutputStream());
            Transfer.Msg hello = Transfer.readHeader(new DataInputStream(ch.in));
            c.setSoTimeout(0);
            return adopt(hello, ch.in, ch.out, c, String.valueOf(c.getRemoteSocketAddress()));
        } catch (IOException | RuntimeException e) {
            try { c.close(); } catch (IOException ignore) { }
            throw e;
        }
    }

    /** Vrai si ce premier cadre d'une connexion entrante ouvre un lien clavier-souris. */
    static boolean isHello(Transfer.Msg first) { return first != null && first.op == OP_KM; }

    /**
     * Cote hub : prend la suite d'une session chiffree dont le premier cadre,
     * deja lu, est un cadre d'ouverture clavier-souris.
     */
    static KmLink adopt(Transfer.Msg hello, InputStream in, OutputStream out, Closeable closer, String peer) throws IOException {
        if (!isHello(hello) || hello.size != 0) throw new IOException("ce n'est pas un lien clavier-souris");
        if (hello.hash != KmProtocol.VERSION) {
            throw new IOException("protocole clavier-souris v" + hello.hash + " chez le pair, v" + KmProtocol.VERSION
                    + " ici (mettre a jour le jar des deux cotes)");
        }
        return new KmLink(in, out, closer, peer);
    }

    // --- Emission ----------------------------------------------------------

    /** Met l'evenement en file ; ne bloque jamais. Faux si le lien est ferme. */
    boolean send(KmProtocol.Event e) {
        synchronized (qLock) {
            if (closed) return false;
            if (e.type() == KmProtocol.MOVE) {
                KmProtocol.Event last = queue.peekLast();
                if (last != null && last.type() == KmProtocol.MOVE) {
                    int dx = last.a() + e.a(), dy = last.b() + e.b();
                    if (Math.abs(dx) < Short.MAX_VALUE && Math.abs(dy) < Short.MAX_VALUE) {
                        queue.pollLast();
                        queue.addLast(KmProtocol.move(dx, dy));
                        return true;
                    }
                }
            }
            if (queue.size() < MAX_QUEUE) {
                queue.addLast(e);
                qLock.notifyAll();
                return true;
            }
        }
        close("file d'envoi saturee (l'autre poste ne lit plus)");
        return false;
    }

    private void writeLoop() {
        List<KmProtocol.Event> batch = new ArrayList<>();
        long lastWrite = System.nanoTime();
        while (true) {
            synchronized (qLock) {
                if (closed) return;
                if (queue.isEmpty()) {
                    long hb = heartbeatMs;
                    try { qLock.wait(hb > 0 ? hb : 1000); } catch (InterruptedException e) { return; }
                }
                if (closed) return;
                batch.addAll(queue);
                queue.clear();
            }
            long hb = heartbeatMs;
            if (batch.isEmpty()) {
                if (hb <= 0 || (System.nanoTime() - lastWrite) / 1_000_000 < hb / 2) continue;
                batch.add(KmProtocol.heartbeat());
            }
            try {
                for (KmProtocol.Event e : batch) KmProtocol.write(out, e);
                out.flush();
            } catch (IOException | RuntimeException e) {
                close("envoi impossible (" + e.getMessage() + ")");
                return;
            }
            lastWrite = System.nanoTime();
            batch.clear();
        }
    }

    private void watchLoop() {
        while (!closed) {
            long t = timeoutMs;
            try { Thread.sleep(t > 0 ? Math.max(10, Math.min(250, t / 4)) : 250); } catch (InterruptedException e) { return; }
            if (t > 0 && !closed && (System.nanoTime() - lastRecv) / 1_000_000 > t) {
                close("battement de coeur absent depuis " + t + " ms");
                return;
            }
        }
    }

    // --- Reception ---------------------------------------------------------

    /**
     * Fait vivre le lien sur le fil appelant jusqu'a sa fermeture : lit les
     * evenements et les remet a h (les battements de coeur sont absorbes).
     * @return la raison de la fin
     */
    String run(Handler h) {
        lastRecv = System.nanoTime();
        daemon(this::writeLoop, "cotp-km-envoi");
        daemon(this::watchLoop, "cotp-km-garde");
        try {
            while (!closed) {
                KmProtocol.Event e = KmProtocol.read(in);
                lastRecv = System.nanoTime();
                if (e.type() != KmProtocol.HEARTBEAT) h.event(e);
            }
        } catch (EOFException e) {
            close("connexion fermee par l'autre poste");
        } catch (IOException e) {
            close("lien coupe (" + e.getMessage() + ")");
        } catch (RuntimeException e) {
            close("erreur interne (" + e + ")");
        } finally {
            close("lien ferme");
        }
        return reason;
    }

    private static void daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    /** Ferme le lien ; la premiere raison donnee est conservee. */
    void close(String why) {
        synchronized (qLock) {
            if (closed) return;
            reason = why == null || why.isEmpty() ? "lien ferme" : why;
            closed = true;
            queue.clear();
            qLock.notifyAll();
        }
        try { closer.close(); } catch (IOException | RuntimeException ignore) { }
    }

    @Override public void close() { close("lien ferme"); }
}
