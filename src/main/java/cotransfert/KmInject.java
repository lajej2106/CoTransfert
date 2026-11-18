package cotransfert;

import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Partage clavier-souris : injection sur le poste pilote, avec java.awt.Robot
 * (API standard du JDK : ni pilote, ni droit administrateur, ni code natif).
 *
 * Deux interfaces pour que les essais n'utilisent jamais le vrai Robot :
 *   - {@link Sink}   : ce que le controleur ({@link Km}) demande ;
 *   - {@link Device} : les gestes elementaires (Robot en vrai, doublure en essai).
 * {@link #over(Device)} fait le lien : deplacement relatif borne aux ecrans,
 * suivi des touches et boutons enfonces (pour tout relacher), touches refusees
 * par Robot ignorees et comptees.
 *
 * Limites de Robot : pas de distinction gauche/droite des modificateurs ni du
 * pave numerique (keyLocation est transporte mais pas rejoue) ; rien n'est
 * injecte dans une fenetre elevee (UAC), l'ecran de verrouillage ou le bureau
 * securise : c'est voulu par Windows et on ne le contourne pas.
 */
final class KmInject {

    private KmInject() { }

    /** Ce que le poste pilote execute. */
    interface Sink {
        /** Prise de controle : pose le pointeur a l'interieur de ce bord (NONE = ne pas le deplacer). */
        void enter(KmSettings.Edge edge, int pos);
        void move(int dx, int dy);
        void button(int button, boolean down);
        void wheel(int notches);
        void key(int vk, int location, boolean down);
        /** Relache toutes les touches et tous les boutons encore enfonces. */
        void releaseAll();
        /** Nombre de touches ou boutons refuses par le poste depuis le demarrage. */
        int ignored();
    }

    /** Gestes elementaires. button() et key() lancent IllegalArgumentException sur un code refuse. */
    interface Device {
        /** Position du pointeur, ou null si elle est inconnue (bureau securise). */
        Point pointer();
        Rectangle[] screens();
        void mouseMove(int x, int y);
        void button(int button, boolean down);
        void wheel(int notches);
        void key(int vk, boolean down);
    }

    /** Injecteur sur ce Device. */
    static Sink over(Device device) { return new Injector(device); }

    /** Injecteur reel. IllegalStateException si Robot est indisponible (pas d'ecran, politique du poste). */
    static Sink robot() { return new Injector(new RobotDevice()); }

    /** Position du pointeur de ce poste, ou null. Lecture seule, sans Robot. */
    static Point pointer() {
        try {
            PointerInfo pi = MouseInfo.getPointerInfo();
            return pi == null ? null : pi.getLocation();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** Rectangles des ecrans de ce poste (bureau virtuel), tableau vide si inconnus. */
    static Rectangle[] screens() {
        try {
            List<Rectangle> l = new ArrayList<>();
            for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                l.add(d.getDefaultConfiguration().getBounds());
            }
            return l.toArray(new Rectangle[0]);
        } catch (RuntimeException | LinkageError e) {
            return new Rectangle[0];
        }
    }

    private static final class Injector implements Sink {
        private final Device dev;
        private final LinkedHashSet<Integer> keys = new LinkedHashSet<>();
        private final LinkedHashSet<Integer> buttons = new LinkedHashSet<>();
        private final Set<String> refused = new HashSet<>();
        private int ignored;

        Injector(Device dev) { this.dev = dev; }

        @Override public synchronized void enter(KmSettings.Edge edge, int pos) {
            Point p = KmEdge.entryPoint(edge, pos, dev.screens());
            if (p != null) dev.mouseMove(p.x, p.y);
        }

        @Override public synchronized void move(int dx, int dy) {
            if (dx == 0 && dy == 0) return;
            Point from = dev.pointer();
            if (from == null) return;
            Point to = KmEdge.clamp(new Point(from.x + dx, from.y + dy), from, dev.screens());
            if (!to.equals(from)) dev.mouseMove(to.x, to.y);
        }

        @Override public synchronized void button(int button, boolean down) {
            if (!down && !buttons.contains(button)) return;
            try {
                dev.button(button, down);
                if (down) buttons.add(button); else buttons.remove(button);
            } catch (IllegalArgumentException e) {
                refuse("bouton " + button);
            }
        }

        @Override public synchronized void wheel(int notches) {
            if (notches != 0) dev.wheel(notches);
        }

        @Override public synchronized void key(int vk, int location, boolean down) {
            if (!down && !keys.contains(vk)) return;       // jamais de relache sans appui
            try {
                dev.key(vk, down);
                if (down) keys.add(vk); else keys.remove(vk);
            } catch (IllegalArgumentException e) {
                refuse("touche 0x" + Integer.toHexString(vk));
            }
        }

        private void refuse(String what) {
            ignored++;
            if (refused.add(what)) Agent.log("km: %s refusee par ce poste, ignoree", what);
        }

        @Override public synchronized void releaseAll() {
            List<Integer> k = new ArrayList<>(keys), b = new ArrayList<>(buttons);
            keys.clear();
            buttons.clear();
            for (int i = k.size() - 1; i >= 0; i--) {
                try { dev.key(k.get(i), false); } catch (RuntimeException e) { /* on relache les autres */ }
            }
            for (int i = b.size() - 1; i >= 0; i--) {
                try { dev.button(b.get(i), false); } catch (RuntimeException e) { /* on relache les autres */ }
            }
        }

        @Override public synchronized int ignored() { return ignored; }
    }

    /** Le vrai poste. Jamais instancie par les essais. */
    private static final class RobotDevice implements Device {
        private final Robot robot;

        RobotDevice() {
            try {
                robot = new Robot();
                robot.setAutoDelay(0);
                robot.setAutoWaitForIdle(false);
            } catch (Exception | LinkageError e) {
                throw new IllegalStateException("java.awt.Robot indisponible sur ce poste (" + e.getClass().getSimpleName() + ")");
            }
        }

        @Override public Point pointer() { return KmInject.pointer(); }

        @Override public Rectangle[] screens() { return KmInject.screens(); }

        @Override public void mouseMove(int x, int y) { robot.mouseMove(x, y); }

        @Override public void button(int button, boolean down) {
            int mask = InputEvent.getMaskForButton(button);   // IllegalArgumentException si la souris n'a pas ce bouton
            if (down) robot.mousePress(mask); else robot.mouseRelease(mask);
        }

        @Override public void wheel(int notches) { robot.mouseWheel(notches); }

        @Override public void key(int vk, boolean down) {
            if (down) robot.keyPress(vk); else robot.keyRelease(vk);
        }
    }
}
