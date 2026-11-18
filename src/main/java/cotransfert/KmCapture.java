package cotransfert;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.WindowEvent;
import java.awt.event.WindowFocusListener;
import java.awt.image.BufferedImage;

/**
 * Partage clavier-souris : capture sur le poste qui envoie.
 *
 * Une fenetre AWT plein ecran, sans decoration, toujours au premier plan,
 * sombre et translucide (opacite uniforme : pas de transparence par pixel),
 * curseur masque, sur l'ecran ou se trouve le pointeur. Un bandeau opaque dit
 * en permanence ou vont les frappes et comment revenir. Le deplacement relatif
 * est obtenu en ramenant le pointeur au centre avec java.awt.Robot.
 *
 * Les touches que Windows garde pour lui (touche Windows, Alt+Tab,
 * Ctrl+Alt+Suppr, Win+L) ne sont pas captables : des que la fenetre perd le
 * focus, la capture s'arrete et le controleur fait tout relacher sur l'autre
 * poste. Si la fenetre n'obtient pas le focus clavier, la capture s'arrete
 * aussi : jamais de souris envoyee d'un cote et de frappes restees de l'autre.
 */
final class KmCapture {

    private KmCapture() { }

    /** Ce que la capture remonte au controleur (sur le fil graphique). */
    interface Listener {
        void event(KmProtocol.Event e);
        /** La capture s'arrete d'elle-meme (raccourci, bouton de retour, focus perdu, erreur). */
        void ended(String why);
    }

    /** La capture vue par le controleur ; les essais en fournissent une doublure. */
    interface Grabber {
        /** Ouvre la capture (sans attendre). */
        void start(KmSettings settings, Listener listener);
        /** Ferme la capture (sans attendre) puis pose le pointeur en park (null = le laisser). */
        void stop(Point park);
    }

    /** La vraie capture AWT. Jamais ouverte par les essais. */
    static Grabber awt() { return new AwtGrabber(); }

    static final String BANNER = "Clavier et souris envoyés à l'autre poste";

    static String bannerText(KmSettings s) {
        StringBuilder sb = new StringBuilder(BANNER);
        if (s.hotkey != null) sb.append(" — ").append(s.hotkey.text()).append(" pour revenir");
        else if (s.returnButton != 0) sb.append(" — bouton ").append(s.returnButton).append(" de la souris pour revenir");
        else if (s.edge != KmSettings.Edge.NONE) sb.append(" — pousser la souris contre le bord opposé pour revenir");
        else sb.append(" — touche Windows pour revenir");
        return sb.toString();
    }

    // ------------------------------------------------------------------------
    // Deplacement relatif : logique pure (essayee sans fenetre).
    // ------------------------------------------------------------------------

    /**
     * Transforme les positions absolues recues en deplacements relatifs. Le
     * pointeur n'est ramene au centre que lorsqu'il s'en eloigne de plus de
     * radius ; les evenements deja en file au moment du recentrage restent
     * comptes par rapport au precedent, et le premier evenement proche du centre
     * apres un recentrage est compte par rapport au centre : ni saut, ni double
     * compte.
     */
    static final class Relative {
        private final int cx, cy, radius;
        private int lastX, lastY;
        private boolean warping;
        private int sinceWarp;
        private boolean warpWanted;

        Relative(int cx, int cy, int radius) {
            this.cx = cx;
            this.cy = cy;
            this.radius = Math.max(8, radius);
            this.lastX = cx;
            this.lastY = cy;
        }

        /** @return {dx, dy}, ou null si le pointeur n'a pas bouge */
        int[] feed(int x, int y) {
            int dx, dy;
            if (warping && dist(x, y) <= radius / 2) {
                dx = x - cx;
                dy = y - cy;
                warping = false;
            } else {
                dx = x - lastX;
                dy = y - lastY;
                if (warping && ++sinceWarp > 50) warping = false;   // recentrage perdu : on le redemandera
            }
            lastX = x;
            lastY = y;
            if (!warping && dist(x, y) > radius) {
                warping = true;
                sinceWarp = 0;
                warpWanted = true;
            }
            return dx == 0 && dy == 0 ? null : new int[] { dx, dy };
        }

        /** Vrai une fois quand il faut ramener le pointeur au centre. */
        boolean takeWarp() {
            boolean w = warpWanted;
            warpWanted = false;
            return w;
        }

        private int dist(int x, int y) { return Math.max(Math.abs(x - cx), Math.abs(y - cy)); }
    }

    // ------------------------------------------------------------------------
    // Capture AWT.
    // ------------------------------------------------------------------------

    private static final float OPACITY = 0.30f;
    /**
     * Essai, desactive par defaut (-Dcotransfert.km.focusClick=true) : si Windows
     * ne donne pas le clavier a la fenetre de capture, cliquer une fois dans NOTRE
     * fenetre pour l'activer. Sans cela, la capture renonce simplement.
     */
    private static final boolean FOCUS_CLICK = Boolean.getBoolean("cotransfert.km.focusClick");
    /** Touche Windows : jamais transmise (elle ouvre le menu de CE poste et arrete la capture). */
    private static final int VK_WINDOWS = 0x020C;

    private static final class CaptureFrame extends Frame {
        private static final long serialVersionUID = 1L;
        CaptureFrame(GraphicsConfiguration gc) { super("CoTransfert", gc); }
        @Override public void update(Graphics g) { paint(g); }
        @Override public void paint(Graphics g) {
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, getWidth(), getHeight());
        }
    }

    private static final class BannerWindow extends Window {
        private static final long serialVersionUID = 1L;
        private final String text;
        BannerWindow(Frame owner, String text) {
            super(owner);
            this.text = text;
        }
        @Override public void paint(Graphics g) {
            g.setColor(new Color(0x1F, 0x29, 0x37));
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(new Color(0xF5, 0x9E, 0x0B));
            g.fillRect(0, 0, 6, getHeight());
            g.setColor(Color.WHITE);
            g.setFont(getFont());
            FontMetrics fm = g.getFontMetrics();
            g.drawString(text, 20, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
        }
    }

    private static final class AwtGrabber implements Grabber {
        private Session current;   // fil graphique uniquement

        @Override public void start(KmSettings settings, Listener listener) {
            EventQueue.invokeLater(() -> {
                close(null);
                Session s = new Session(settings, listener);
                current = s;
                try {
                    s.open();
                } catch (Exception | LinkageError e) {
                    s.end("capture impossible (" + e.getClass().getSimpleName() + ")");
                }
            });
        }

        @Override public void stop(Point park) {
            EventQueue.invokeLater(() -> close(park));
        }

        private void close(Point park) {
            Session s = current;
            current = null;
            if (s != null) s.close(park);
        }
    }

    /** Une capture, de l'ouverture a la fermeture ; tout se passe sur le fil graphique. */
    private static final class Session extends MouseAdapter implements KeyListener, WindowFocusListener {
        private final KmSettings settings;
        private final Listener listener;
        private Frame frame;
        private Window banner;
        private Robot robot;
        private Relative rel;
        private int cx, cy;
        private boolean over, focused;
        private int swallow;
        private javax.swing.Timer focusTimer;
        private int focusTicks;

        Session(KmSettings settings, Listener listener) {
            this.settings = settings;
            this.listener = listener;
        }

        void open() throws Exception {
            PointerInfo pi = MouseInfo.getPointerInfo();
            GraphicsDevice dev = pi != null ? pi.getDevice()
                    : GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            GraphicsConfiguration gc = dev.getDefaultConfiguration();
            Rectangle b = gc.getBounds();
            robot = new Robot();
            robot.setAutoDelay(0);
            cx = b.x + b.width / 2;
            cy = b.y + b.height / 2;
            rel = new Relative(cx, cy, Math.min(b.width, b.height) / 4);

            Frame f = new CaptureFrame(gc);
            f.setUndecorated(true);
            f.setType(Window.Type.UTILITY);          // pas de bouton dans la barre des taches
            f.setResizable(false);
            f.setAlwaysOnTop(true);
            f.setBackground(Color.BLACK);
            f.setBounds(b);
            f.setFocusTraversalKeysEnabled(false);   // Tab et Maj+Tab sont transmis
            f.enableInputMethods(false);
            f.setCursor(Toolkit.getDefaultToolkit().createCustomCursor(
                    new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), new Point(0, 0), "masque"));
            if (dev.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.TRANSLUCENT)) {
                try { f.setOpacity(OPACITY); } catch (RuntimeException e) { /* fenetre opaque : le bandeau reste lisible */ }
            }
            f.addKeyListener(this);
            f.addMouseListener(this);
            f.addMouseMotionListener(this);
            f.addMouseWheelListener(this);
            f.addWindowFocusListener(this);
            frame = f;

            String text = bannerText(settings);
            BannerWindow w = new BannerWindow(f, text);
            w.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
            FontMetrics fm = w.getFontMetrics(w.getFont());
            int bw = Math.min(b.width, fm.stringWidth(text) + 40), bh = fm.getHeight() + 18;
            w.setFocusableWindowState(false);
            w.setAlwaysOnTop(true);
            w.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
            w.setBounds(b.x + (b.width - bw) / 2, b.y + 24, bw, bh);
            banner = w;

            robot.mouseMove(cx, cy);
            f.setVisible(true);
            w.setVisible(true);
            f.toFront();
            f.requestFocus();

            // Le focus clavier doit arriver vite ; sinon on le redemande, puis on renonce
            // (au bout d'une seconde). Voir FOCUS_CLICK pour l'essai du clic d'activation.
            focusTimer = new javax.swing.Timer(200, e -> focusTick());
            focusTimer.start();
        }

        private void focusTick() {
            if (over || frame == null) return;
            if (focused || frame.isFocused()) { focusTimer.stop(); return; }
            focusTicks++;
            if (focusTicks == 1) {
                frame.toFront();
                frame.requestFocus();
            } else if (focusTicks == 2 && FOCUS_CLICK && frame.isShowing()) {
                swallow = 2;
                robot.mouseMove(cx, cy);
                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            } else if (focusTicks >= 5) {
                end("la fenetre de capture n'a pas obtenu le clavier");
            }
        }

        /** Arret a l'initiative de la capture : le controleur fermera la fenetre. */
        void end(String why) {
            if (over) return;
            over = true;
            listener.ended(why);
        }

        void close(Point park) {
            over = true;
            if (focusTimer != null) focusTimer.stop();
            if (banner != null) banner.dispose();
            if (frame != null) frame.dispose();
            banner = null;
            frame = null;
            if (park != null && robot != null) {
                try { robot.mouseMove(park.x, park.y); } catch (RuntimeException e) { /* le pointeur reste au centre */ }
            }
        }

        private void send(KmProtocol.Event e) {
            if (!over) listener.event(e);
        }

        // --- souris ---

        private void moved(MouseEvent e) {
            if (over) return;
            int[] d = rel.feed(e.getXOnScreen(), e.getYOnScreen());
            if (d != null) send(KmProtocol.move(d[0], d[1]));
            if (rel.takeWarp()) robot.mouseMove(cx, cy);
        }

        @Override public void mouseMoved(MouseEvent e) { moved(e); }

        @Override public void mouseDragged(MouseEvent e) { moved(e); }

        @Override public void mousePressed(MouseEvent e) { button(e, true); }

        @Override public void mouseReleased(MouseEvent e) { button(e, false); }

        private void button(MouseEvent e, boolean down) {
            int b = e.getButton();
            if (over || b < 1 || b > KmProtocol.MAX_BUTTON) return;
            if (swallow > 0 && b == 1) { swallow--; return; }        // notre propre clic d'activation
            if (b == settings.returnButton) {
                if (down) end("bouton de retour");
                return;
            }
            send(KmProtocol.button(b, down));
        }

        @Override public void mouseWheelMoved(MouseWheelEvent e) {
            int n = e.getWheelRotation();
            if (n != 0) send(KmProtocol.wheel(n));
        }

        // --- clavier ---

        @Override public void keyPressed(KeyEvent e) {
            int vk = e.getKeyCode();
            KmSettings.Hotkey hk = settings.hotkey;
            if (hk != null && hk.matches(vk, e.isControlDown(), e.isAltDown(), e.isShiftDown())) {
                end("raccourci");
                return;
            }
            if (vk != KeyEvent.VK_UNDEFINED && vk != VK_WINDOWS) send(KmProtocol.key(vk, e.getKeyLocation(), true));
            e.consume();
        }

        @Override public void keyReleased(KeyEvent e) {
            int vk = e.getKeyCode();
            if (vk != KeyEvent.VK_UNDEFINED && vk != VK_WINDOWS) send(KmProtocol.key(vk, e.getKeyLocation(), false));
            e.consume();
        }

        @Override public void keyTyped(KeyEvent e) { e.consume(); }

        // --- focus ---

        @Override public void windowGainedFocus(WindowEvent e) { focused = true; }

        @Override public void windowLostFocus(WindowEvent e) {
            if (focused) end("focus perdu (touche reservee par Windows ou autre fenetre)");
        }
    }
}
