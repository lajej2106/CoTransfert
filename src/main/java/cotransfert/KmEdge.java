package cotransfert;

import java.awt.Point;
import java.awt.Rectangle;

/**
 * Partage clavier-souris : declencheur "bord d'ecran". Logique pure : la
 * position du pointeur, les ecrans et l'heure sont fournis par l'appelant.
 *
 * Le declencheur part quand le pointeur reste contre le bord choisi pendant le
 * delai regle. Un bord ne compte que s'il est un bord EXTERIEUR du bureau (aucun
 * autre ecran derriere, a cette hauteur). Apres un declenchement, ou si le
 * pointeur est deja contre le bord au depart, il faut d'abord quitter le bord :
 * pas de declenchement en rafale.
 */
final class KmEdge {

    /** Distance au bord, en pixels, a laquelle le pointeur est pose en entrant sur un poste. */
    static final int INSET = 12;

    private final KmSettings.Edge edge;
    private final long delayMs;
    private boolean armed;
    private long since = -1;
    private int fraction = KmProtocol.POS_NONE;

    KmEdge(KmSettings.Edge edge, long delayMs) {
        this.edge = edge;
        this.delayMs = Math.max(0, delayMs);
    }

    KmSettings.Edge edge() { return edge; }

    /** Position le long du bord au dernier contact (0..POS_MAX), ou POS_NONE. */
    int fraction() { return fraction; }

    /** Exige de quitter le bord avant le prochain declenchement. */
    void reset() {
        armed = false;
        since = -1;
    }

    /**
     * A appeler regulierement (~15 ms).
     * @return vrai une seule fois, quand le pointeur est reste contre le bord pendant le delai
     */
    boolean poll(Point p, Rectangle[] screens, long nowMs) {
        if (edge == KmSettings.Edge.NONE || p == null || screens == null) { since = -1; return false; }
        int f = contact(edge, p, screens);
        if (f < 0) {
            armed = true;
            since = -1;
            return false;
        }
        fraction = f;
        if (!armed) return false;
        if (since < 0) since = nowMs;
        if (nowMs - since < delayMs) return false;
        reset();
        return true;
    }

    /** Position le long du bord (0..POS_MAX) si p touche ce bord exterieur du bureau, sinon -1. */
    static int contact(KmSettings.Edge edge, Point p, Rectangle[] screens) {
        Rectangle s = screenOf(p, screens);
        if (s == null) return -1;
        int right = s.x + s.width - 1, bottom = s.y + s.height - 1;
        switch (edge) {
            case LEFT: if (p.x > s.x || inAny(s.x - 1, p.y, screens)) return -1; break;
            case RIGHT: if (p.x < right || inAny(right + 1, p.y, screens)) return -1; break;
            case TOP: if (p.y > s.y || inAny(p.x, s.y - 1, screens)) return -1; break;
            case BOTTOM: if (p.y < bottom || inAny(p.x, bottom + 1, screens)) return -1; break;
            default: return -1;
        }
        boolean vertical = edge == KmSettings.Edge.LEFT || edge == KmSettings.Edge.RIGHT;
        long along = vertical ? p.y - s.y : p.x - s.x;
        long span = Math.max(1, (vertical ? s.height : s.width) - 1);
        return (int) Math.max(0, Math.min(KmProtocol.POS_MAX, along * KmProtocol.POS_MAX / span));
    }

    /**
     * Point d'entree : juste a l'interieur du bord donne du bureau, a la position
     * pos le long de ce bord (POS_NONE = milieu). null sans ecran ou sans bord.
     */
    static Point entryPoint(KmSettings.Edge edge, int pos, Rectangle[] screens) {
        if (edge == KmSettings.Edge.NONE || screens == null || screens.length == 0) return null;
        Rectangle best = null;
        for (Rectangle s : screens) {
            if (s == null || s.isEmpty()) continue;
            if (best == null) { best = s; continue; }
            boolean better = switch (edge) {
                case LEFT -> s.x < best.x;
                case RIGHT -> s.x + s.width > best.x + best.width;
                case TOP -> s.y < best.y;
                default -> s.y + s.height > best.y + best.height;
            };
            if (better) best = s;
        }
        if (best == null) return null;
        long f = pos < 0 || pos > KmProtocol.POS_MAX ? KmProtocol.POS_MAX / 2 : pos;
        int ix = Math.min(INSET, Math.max(0, best.width / 2 - 1)), iy = Math.min(INSET, Math.max(0, best.height / 2 - 1));
        int alongY = best.y + (int) (f * Math.max(0, best.height - 1) / KmProtocol.POS_MAX);
        int alongX = best.x + (int) (f * Math.max(0, best.width - 1) / KmProtocol.POS_MAX);
        return switch (edge) {
            case LEFT -> new Point(best.x + ix, alongY);
            case RIGHT -> new Point(best.x + best.width - 1 - ix, alongY);
            case TOP -> new Point(alongX, best.y + iy);
            default -> new Point(alongX, best.y + best.height - 1 - iy);
        };
    }

    /** L'ecran qui contient p, ou null. */
    static Rectangle screenOf(Point p, Rectangle[] screens) {
        if (p == null || screens == null) return null;
        for (Rectangle s : screens) if (s != null && s.contains(p)) return s;
        return null;
    }

    private static boolean inAny(int x, int y, Rectangle[] screens) {
        for (Rectangle s : screens) if (s != null && s.contains(x, y)) return true;
        return false;
    }

    /** Ramene p dans le bureau : inchange s'il est sur un ecran, sinon borne a l'ecran de from (ou au premier). */
    static Point clamp(Point p, Point from, Rectangle[] screens) {
        if (screens == null || screens.length == 0 || screenOf(p, screens) != null) return p;
        Rectangle s = screenOf(from, screens);
        if (s == null) for (Rectangle r : screens) if (r != null && !r.isEmpty()) { s = r; break; }
        if (s == null) return p;
        return new Point(Math.max(s.x, Math.min(s.x + s.width - 1, p.x)), Math.max(s.y, Math.min(s.y + s.height - 1, p.y)));
    }
}
