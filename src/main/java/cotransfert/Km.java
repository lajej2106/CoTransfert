package cotransfert;

import java.awt.Point;
import java.awt.Rectangle;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.Supplier;

/**
 * Partage clavier-souris : le controleur (voir SPEC-km.md).
 *
 * Quand les deux postes sont relies par le lien direct, l'utilisateur pilote
 * l'autre poste avec le clavier et la souris de celui-ci, dans un sens ou dans
 * l'autre. Fonction optionnelle, desactivee par defaut, reservee au canal
 * "direct" et a un secret partage (flux chiffre).
 *
 * Presse-papiers (option km.clipboard, active par defaut avec le partage
 * clavier-souris, effective si les deux postes l'ont) : le TEXTE copie suit le clavier. Il part vers l'autre poste
 * quand on en prend le controle, et revient quand le controle est rendu. Jamais
 * de fichier ni d'image, jamais d'envoi en dehors d'un passage du controle.
 *
 * Machine a etats, un seul sens a la fois :
 *   IDLE     inactif ;
 *   DRIVING  ce poste pilote l'autre : {@link KmCapture} envoie ses evenements ;
 *   DRIVEN   ce poste est pilote : {@link KmInject} rejoue les evenements recus.
 * Deux prises de controle croisees : le hub garde la main, le client cede.
 * Lien coupe ou battement de coeur absent : retour a IDLE, et tout ce qui etait
 * enfonce sur le poste pilote est relache.
 *
 * Points d'entree de l'agent : {@link #configure}, {@link #start},
 * {@link #serveHub} (hub), {@link #clientLoop} (client), {@link #toggle}
 * (page et raccourci), {@link #json} (etat).
 */
final class Km {

    enum State {
        IDLE("idle"), DRIVING("driving"), DRIVEN("driven");
        final String id;
        State(String id) { this.id = id; }
    }

    /** Le poste, vu par le controleur ; les essais en fournissent une doublure. */
    interface Env {
        boolean headless();
        /** Injection (cree a la premiere prise de controle recue). */
        KmInject.Sink sink();
        KmCapture.Grabber grabber();
        /** Position du pointeur, ou null. */
        Point pointer();
        Rectangle[] screens();
        long nowMs();
        /** Presse-papiers du poste, ou null s'il n'y en a pas. */
        default Clip clip() { return null; }
    }

    /** Presse-papiers du poste, texte seulement. */
    interface Clip {
        /** Le texte present, ou null (vide, autre chose que du texte, presse-papiers occupe). */
        String read();
        void write(String text);
    }

    record Result(boolean ok, String message, String state) {
        String json() {
            return "{\"ok\":" + ok + ",\"message\":" + Status.q(message) + ",\"state\":" + Status.q(state) + "}";
        }
    }

    /** Apres un retour, une bascule qui arrive dans ce delai est ignoree (raccourci vu deux fois). */
    static final long TOGGLE_GUARD_MS = 1200;
    private static final long POLL_MS = 15;

    private static final Km INSTANCE = new Km(new AwtEnv());

    /** Le controleur de l'agent. */
    static Km get() { return INSTANCE; }

    private final Env env;
    private KmSettings settings = KmSettings.DEFAULT;
    private boolean hub;
    private String problem = "fonction desactivee";
    private boolean hasSecret;

    private KmLink link;
    private boolean peerAccepts;
    private State state = State.IDLE;
    private int generation;
    private KmInject.Sink sink;
    private KmCapture.Grabber grabber;
    private KmEdge localEdge = new KmEdge(KmSettings.Edge.NONE, 0);
    private KmEdge returnEdge;
    private int drivenButtons;
    private Point grabPoint;
    private long lastStopMs = Long.MIN_VALUE / 2;
    private String message = "";
    private boolean polling;
    private boolean peerClipboard;
    private Clip clip;
    /** Dernier texte envoye ou recu : il n'est pas renvoye tant qu'il n'a pas change. */
    private String lastClip;
    private java.io.ByteArrayOutputStream clipIn;
    private int clipExpected;

    Km(Env env) { this.env = env; }

    // --- Reglage -----------------------------------------------------------

    /**
     * A appeler au demarrage de l'agent.
     * @param channel   canal choisi ("direct", "webdav", ...)
     * @param hasSecret vrai si un secret partage est regle
     */
    synchronized void configure(KmSettings s, boolean hub, String channel, boolean hasSecret) {
        this.settings = s == null ? KmSettings.DEFAULT : s;
        this.hub = hub;
        this.hasSecret = hasSecret;
        this.localEdge = new KmEdge(settings.edge, settings.edgeDelayMs);
        String p;
        if (!settings.enabled) p = "fonction desactivee";
        else if (!"direct".equals(channel)) p = "disponible seulement sur le lien direct";
        else if (!hasSecret) p = "secret partage requis (le clavier n'est jamais envoye en clair)";
        else if (headless()) p = "pas d'ecran sur ce poste";
        else p = "";
        this.problem = p;
        if (settings.enabled) Agent.log("km: partage clavier-souris %s", p.isEmpty() ? "pret" : "indisponible : " + p);
    }

    private boolean headless() {
        try { return env.headless(); } catch (RuntimeException | LinkageError e) { return true; }
    }

    /** "" si la fonction est utilisable sur ce poste, sinon la raison. */
    synchronized String problem() { return problem; }

    synchronized boolean usable() { return problem.isEmpty(); }

    synchronized KmSettings settings() { return settings; }

    synchronized State state() { return state; }

    /** Vrai quand le lien est etabli et que l'autre poste accepte d'etre pilote. */
    synchronized boolean ready() { return link != null && peerAccepts; }

    /** Lance la surveillance du bord d'ecran (fil demon). Sans effet si la fonction est inutilisable. */
    synchronized void start() {
        if (polling || !problem.isEmpty()) return;
        polling = true;
        Thread t = new Thread(() -> {
            while (true) {
                try { Thread.sleep(POLL_MS); } catch (InterruptedException e) { return; }
                try { tick(); } catch (RuntimeException | LinkageError e) { /* prochaine scrutation */ }
            }
        }, "cotp-km-bord");
        t.setDaemon(true);
        t.start();
    }

    // --- Lien --------------------------------------------------------------

    /**
     * Cote hub : appele par l'accept de la synchronisation quand le premier cadre
     * d'une connexion est un cadre d'ouverture clavier-souris
     * ({@link KmLink#isHello}). Bloque tant que le lien vit, puis ferme tout.
     * Un hub ou la fonction est desactivee garde le lien ouvert et refuse les
     * prises de controle : le client sait pourquoi et ne se reconnecte pas en boucle.
     * @param encrypted vrai si la session a passe la poignee de main chiffree
     */
    void serveHub(Transfer.Msg hello, InputStream in, OutputStream out, Closeable closer, boolean encrypted) {
        try {
            if (!encrypted) throw new IOException("lien non chiffre refuse");
            serve(KmLink.adopt(hello, in, out, closer, "client"));
        } catch (IOException e) {
            Agent.log("km: lien clavier-souris refuse : %s", e.getMessage());
            try { closer.close(); } catch (IOException ignore) { }
        }
    }

    /**
     * Cote client : boucle sans fin (fil dedie). Ouvre le lien vers le hub, le
     * fait vivre, recommence. Rend la main tout de suite si la fonction est
     * inutilisable sur ce poste.
     * @param hubHost donne l'adresse du hub, ou null tant qu'il est introuvable
     */
    void clientLoop(Supplier<String> hubHost, int port, Crypto.Key key) {
        String lastError = "";
        while (usable()) {
            String host = hubHost.get();
            long wait = 3000;
            if (host == null || host.isEmpty()) {
                wait = 5000;
            } else {
                try {
                    KmLink l = KmLink.dial(host, port, key, 5000);
                    lastError = "";
                    Agent.log("km: lien clavier-souris ouvert vers %s", host);
                    String why = serve(l);
                    Agent.log("km: lien clavier-souris ferme (%s)", why);
                    if (why.contains("protocole")) wait = 30000;
                } catch (IOException e) {
                    String m = String.valueOf(e.getMessage());
                    if (!m.equals(lastError)) Agent.log("km: lien clavier-souris impossible (%s), nouveaux essais en silence", m);
                    lastError = m;
                    wait = 5000;
                }
            }
            try { Thread.sleep(wait); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    /**
     * Fait vivre un lien etabli sur le fil appelant, jusqu'a sa fermeture. Un
     * nouveau lien remplace le precedent.
     * @return la raison de la fin
     */
    String serve(KmLink l) {
        KmLink old;
        synchronized (this) {
            old = link;
            if (old != null) detach(old);
            link = l;
            peerAccepts = false;
            peerClipboard = false;
            clipIn = null;
            l.send(KmProtocol.hello((problem.isEmpty() ? KmProtocol.FLAG_ACCEPTS : 0) | (hub ? KmProtocol.FLAG_HUB : 0)
                    | (problem.isEmpty() && settings.clipboard ? KmProtocol.FLAG_CLIPBOARD : 0)));
        }
        if (old != null) old.close("remplace par une nouvelle connexion");
        String why;
        try {
            why = l.run(e -> onEvent(l, e));
        } finally {
            synchronized (this) { detach(l); }
        }
        return why;
    }

    /** Le lien l n'est plus le lien courant : retour a l'etat inactif, tout est relache. */
    private void detach(KmLink l) {
        if (link != l) return;
        link = null;
        peerAccepts = false;
        peerClipboard = false;
        clipIn = null;
        if (state == State.DRIVING) endDriving("lien coupe", KmProtocol.POS_NONE, false);
        else if (state == State.DRIVEN) endDriven("lien coupe");
    }

    private synchronized void onEvent(KmLink l, KmProtocol.Event e) {
        if (l != link) return;
        switch (e.type()) {
            case KmProtocol.HELLO -> {
                if (e.a() != KmProtocol.VERSION) {
                    l.close("protocole clavier-souris v" + e.a() + " chez l'autre poste, v" + KmProtocol.VERSION + " ici");
                    return;
                }
                peerAccepts = (e.b() & KmProtocol.FLAG_ACCEPTS) != 0;
                peerClipboard = (e.b() & KmProtocol.FLAG_CLIPBOARD) != 0;
            }
            case KmProtocol.CLIP_BEGIN -> {
                boolean ok = problem.isEmpty() && settings.clipboard && e.a() > 0 && e.a() <= KmProtocol.CLIP_MAX_BYTES;
                clipExpected = e.a();
                clipIn = ok ? new java.io.ByteArrayOutputStream(e.a()) : null;
            }
            case KmProtocol.CLIP_DATA -> {
                if (clipIn != null && e.data() != null) {
                    if (clipIn.size() + e.data().length > clipExpected) clipIn = null;
                    else clipIn.write(e.data(), 0, e.data().length);
                }
            }
            case KmProtocol.CLIP_END -> {
                java.io.ByteArrayOutputStream got = clipIn;
                clipIn = null;
                if (got != null && got.size() == clipExpected) clipReceived(new String(got.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
            }
            case KmProtocol.GRAB -> onGrab(l, e);
            case KmProtocol.MOVE -> { if (state == State.DRIVEN) sink.move(e.a(), e.b()); }
            case KmProtocol.WHEEL -> { if (state == State.DRIVEN) sink.wheel(e.a()); }
            case KmProtocol.KEY -> { if (state == State.DRIVEN) sink.key(e.a(), e.b(), e.down()); }
            case KmProtocol.BUTTON -> {
                if (state == State.DRIVEN) {
                    if (e.a() >= 1 && e.a() <= 30) drivenButtons = e.down() ? drivenButtons | (1 << e.a()) : drivenButtons & ~(1 << e.a());
                    sink.button(e.a(), e.down());
                }
            }
            case KmProtocol.RELEASE_ALL -> {
                if (state == State.DRIVEN) { drivenButtons = 0; sink.releaseAll(); }
            }
            case KmProtocol.RELEASE -> { if (state == State.DRIVEN) endDriven("controle rendu par l'autre poste"); }
            case KmProtocol.RETURN -> { if (state == State.DRIVING) endDriving("retour demande par l'autre poste", e.a(), true); }
            case KmProtocol.REFUSE -> {
                if (state == State.DRIVING) {
                    endDriving("refuse par l'autre poste (" + switch (e.a()) {
                        case KmProtocol.REFUSE_DISABLED -> "fonction desactivee";
                        case KmProtocol.REFUSE_NO_SCREEN -> "pas d'ecran";
                        default -> "injection impossible";
                    } + ")", KmProtocol.POS_NONE, false);
                }
            }
            default -> { }
        }
    }

    private void onGrab(KmLink l, KmProtocol.Event e) {
        if (!problem.isEmpty()) {
            l.send(KmProtocol.refuse(settings.enabled && hasSecret && headless() ? KmProtocol.REFUSE_NO_SCREEN : KmProtocol.REFUSE_DISABLED));
            return;
        }
        if (state == State.DRIVING) {
            if (hub) return;                                   // prises croisees : le hub garde la main
            endDriving("l'autre poste a pris le controle en meme temps", KmProtocol.POS_NONE, false);
        } else if (state == State.DRIVEN) {
            sink.releaseAll();
        }
        try {
            if (sink == null) sink = env.sink();
            KmSettings.Edge from = KmSettings.Edge.fromWire(e.a());
            if (from != KmSettings.Edge.NONE && e.b() != KmProtocol.POS_NONE) sink.enter(from.opposite(), e.b());
            returnEdge = from == KmSettings.Edge.NONE ? null : new KmEdge(from.opposite(), settings.edgeDelayMs);
        } catch (RuntimeException | LinkageError ex) {
            Agent.log("km: ce poste ne peut pas etre pilote (%s)", ex.getMessage());
            state = State.IDLE;
            l.send(KmProtocol.refuse(KmProtocol.REFUSE_ERROR));
            return;
        }
        drivenButtons = 0;
        state = State.DRIVEN;
        say("ce poste est pilote par l'autre poste");
    }

    // --- Presse-papiers ----------------------------------------------------

    private Clip clip() {
        if (clip == null) clip = env.clip();
        return clip;
    }

    /**
     * Envoie le texte du presse-papiers a l'autre poste, s'il a change depuis le
     * dernier envoi ou la derniere reception. Appele au passage du controle.
     */
    private void sendClip() {
        if (!problem.isEmpty() || !settings.clipboard || !peerClipboard || link == null) return;
        try {
            Clip c = clip();
            String text = c == null ? null : c.read();
            if (text == null || text.isEmpty() || text.equals(lastClip)) return;
            java.util.List<KmProtocol.Event> frames = KmProtocol.clip(text);
            if (frames.isEmpty()) {
                Agent.log("km: presse-papiers non envoye (texte de plus de %d Ko)", KmProtocol.CLIP_MAX_BYTES / 1024);
                return;
            }
            lastClip = text;
            for (KmProtocol.Event f : frames) if (!link.send(f)) return;
        } catch (RuntimeException | LinkageError e) {
            Agent.log("km: presse-papiers illisible sur ce poste (%s)", e.getClass().getSimpleName());
        }
    }

    private void clipReceived(String text) {
        if (text.isEmpty()) return;
        try {
            Clip c = clip();
            if (c == null) return;
            c.write(text);
            lastClip = text;
        } catch (RuntimeException | LinkageError e) {
            Agent.log("km: presse-papiers non modifiable sur ce poste (%s)", e.getClass().getSimpleName());
        }
    }

    // --- Prise et rendu du controle ----------------------------------------

    /**
     * Bascule (bouton de la page, raccourci Windows) : inactif = prendre le
     * controle ; je pilote = le rendre ; je suis pilote = demander a l'autre de le rendre.
     */
    synchronized Result toggle() {
        switch (state) {
            case DRIVING:
                endDriving("bascule", KmProtocol.POS_NONE, true);
                return result(true, "controle rendu");
            case DRIVEN:
                if (link != null) link.send(KmProtocol.askReturn(KmProtocol.POS_NONE));
                return result(true, "retour demande a l'autre poste");
            default:
                if (env.nowMs() - lastStopMs < TOGGLE_GUARD_MS) return result(true, "controle rendu");
                return take("bascule", KmProtocol.POS_NONE);
        }
    }

    /** Prend le controle de l'autre poste. pos : position le long du bord (POS_NONE hors bord d'ecran). */
    synchronized Result take(String why, int pos) {
        if (!problem.isEmpty()) return result(false, problem);
        if (link == null) return result(false, "l'autre poste n'est pas connecte par le lien direct");
        if (!peerAccepts) return result(false, "partage clavier-souris desactive ou indisponible sur l'autre poste");
        if (state == State.DRIVING) return result(false, "ce poste pilote deja l'autre");
        if (state == State.DRIVEN) return result(false, "ce poste est pilote par l'autre");
        grabPoint = env.pointer();
        state = State.DRIVING;
        final int g = ++generation;
        link.send(KmProtocol.grab(settings.edge.wire(), pos));
        try {
            if (grabber == null) grabber = env.grabber();
            grabber.start(settings, new KmCapture.Listener() {
                @Override public void event(KmProtocol.Event e) { fromCapture(g, e); }
                @Override public void ended(String w) { captureEnded(g, w); }
            });
        } catch (RuntimeException | LinkageError e) {
            state = State.IDLE;
            link.send(KmProtocol.release());
            return result(false, "capture impossible sur ce poste (" + e.getClass().getSimpleName() + ")");
        }
        sendClip();
        say("clavier et souris envoyes a l'autre poste (" + why + ")");
        return result(true, message);
    }

    /** Rend le controle (sans effet si ce poste ne pilote pas). */
    synchronized Result giveBack(String why) {
        if (state == State.DRIVING) endDriving(why, KmProtocol.POS_NONE, true);
        return result(true, "controle rendu");
    }

    private synchronized void fromCapture(int g, KmProtocol.Event e) {
        if (g == generation && state == State.DRIVING && link != null) link.send(e);
    }

    private synchronized void captureEnded(int g, String why) {
        if (g == generation && state == State.DRIVING) endDriving(why, KmProtocol.POS_NONE, true);
    }

    /** DRIVING -> IDLE. pos : ou reposer le pointeur le long du bord (retour par le bord du poste pilote). */
    private void endDriving(String why, int pos, boolean tellPeer) {
        state = State.IDLE;
        generation++;
        lastStopMs = env.nowMs();
        if (tellPeer && link != null) link.send(KmProtocol.release());
        Point park = null;
        try {
            Rectangle[] screens = env.screens();
            if (pos != KmProtocol.POS_NONE && settings.edge != KmSettings.Edge.NONE) park = KmEdge.entryPoint(settings.edge, pos, screens);
            else if (grabPoint != null && KmEdge.contact(settings.edge, grabPoint, screens) >= 0)
                park = KmEdge.entryPoint(settings.edge, KmEdge.contact(settings.edge, grabPoint, screens), screens);
            else park = grabPoint;
        } catch (RuntimeException | LinkageError e) { /* le pointeur reste ou il est */ }
        localEdge.reset();
        try { if (grabber != null) grabber.stop(park); } catch (RuntimeException | LinkageError e) { /* rien de plus a fermer */ }
        say("clavier et souris de retour sur ce poste (" + why + ")");
    }

    /** DRIVEN -> IDLE : tout ce qui etait enfonce est relache. */
    private void endDriven(String why) {
        state = State.IDLE;
        drivenButtons = 0;
        returnEdge = null;
        localEdge.reset();
        try { if (sink != null) sink.releaseAll(); } catch (RuntimeException | LinkageError e) { /* rien de plus a relacher */ }
        sendClip();
        say("ce poste n'est plus pilote (" + why + ")");
    }

    // --- Bord d'ecran ------------------------------------------------------

    /**
     * Une scrutation du pointeur (toutes les ~15 ms par {@link #start}, ou a la
     * main dans les essais). Inactif : bord regle pousse = prendre le controle.
     * Pilote : bord oppose pousse (aucun bouton enfonce) = demander le retour.
     */
    synchronized void tick() {
        if (!problem.isEmpty() || link == null || state == State.DRIVING) return;
        if (state == State.IDLE && (settings.edge == KmSettings.Edge.NONE || !peerAccepts)) return;
        if (state == State.DRIVEN && returnEdge == null) return;
        Point p = env.pointer();
        if (p == null) return;
        Rectangle[] screens = env.screens();
        long now = env.nowMs();
        if (state == State.IDLE) {
            if (localEdge.poll(p, screens, now)) take("bord d'ecran", localEdge.fraction());
        } else if (returnEdge.poll(p, screens, now) && drivenButtons == 0) {
            // Bouton enfonce (glisser vers le bord) : pas de retour ; il faudra quitter le bord puis le repousser.
            link.send(KmProtocol.askReturn(returnEdge.fraction()));
        }
    }

    // --- Etat --------------------------------------------------------------

    private void say(String m) {
        message = m;
        Agent.log("km: %s", m);
    }

    private Result result(boolean ok, String m) { return new Result(ok, m, state.id); }

    /**
     * Etat court pour /api/status, sans secret :
     * {"enabled","available","problem","linked","peerReady","state","edge","delay","hotkey","returnButton",
     *  "clipboard","peerClipboard","ignored","message"}
     */
    synchronized String json() {
        int ignored = 0;
        try { if (sink != null) ignored = sink.ignored(); } catch (RuntimeException e) { /* compteur indisponible */ }
        return "{\"enabled\":" + settings.enabled + ",\"available\":" + problem.isEmpty()
                + ",\"problem\":" + Status.q(settings.enabled ? problem : "")
                + ",\"linked\":" + (link != null) + ",\"peerReady\":" + (link != null && peerAccepts)
                + ",\"state\":" + Status.q(state.id)
                + ",\"edge\":" + Status.q(settings.edge.id) + ",\"delay\":" + settings.edgeDelayMs
                + ",\"hotkey\":" + Status.q(settings.hotkey == null ? "" : settings.hotkey.text())
                + ",\"returnButton\":" + settings.returnButton
                + ",\"clipboard\":" + settings.clipboard + ",\"peerClipboard\":" + (link != null && peerClipboard)
                + ",\"ignored\":" + ignored + ",\"message\":" + Status.q(message) + "}";
    }

    /** Le vrai poste : AWT. Rien n'est cree tant que la fonction ne sert pas. */
    private static final class AwtEnv implements Env {
        @Override public boolean headless() { return java.awt.GraphicsEnvironment.isHeadless(); }
        @Override public KmInject.Sink sink() { return KmInject.robot(); }
        @Override public KmCapture.Grabber grabber() { return KmCapture.awt(); }
        @Override public Point pointer() { return KmInject.pointer(); }
        @Override public Rectangle[] screens() { return KmInject.screens(); }
        @Override public long nowMs() { return System.nanoTime() / 1_000_000; }
        @Override public Clip clip() { return new AwtClip(); }
    }

    /** Presse-papiers de Windows par AWT. Occupe par une autre application : quelques essais rapproches. */
    private static final class AwtClip implements Clip {
        private static java.awt.datatransfer.Clipboard board() { return java.awt.Toolkit.getDefaultToolkit().getSystemClipboard(); }

        @Override public String read() {
            for (int i = 0; i < 3; i++) {
                try {
                    java.awt.datatransfer.Clipboard b = board();
                    if (!b.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.stringFlavor)) return null;
                    return (String) b.getData(java.awt.datatransfer.DataFlavor.stringFlavor);
                } catch (IllegalStateException e) {
                    try { Thread.sleep(20); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null; }
                } catch (java.awt.datatransfer.UnsupportedFlavorException | IOException e) {
                    return null;
                }
            }
            return null;
        }

        @Override public void write(String text) {
            for (int i = 0; i < 3; i++) {
                try {
                    board().setContents(new java.awt.datatransfer.StringSelection(text), null);
                    return;
                } catch (IllegalStateException e) {
                    try { Thread.sleep(20); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                }
            }
        }
    }
}
