package cotransfert;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Partage clavier-souris : reglages de ce poste, immuables. Lus et ecrits sous
 * forme de Properties (cles prefixees "km."), pour cotransfert.conf.
 *
 *   km.enabled=true|false          fonction active (defaut : false)
 *   km.edge=gauche|droite|haut|bas|aucun
 *                                  bord de CE poste derriere lequel se trouve l'autre poste (defaut : aucun)
 *   km.edge.delay=250              duree en ms pendant laquelle le pointeur doit rester contre le bord (0..5000)
 *   km.hotkey=Ctrl+Alt+F12         raccourci de bascule ; "aucun" = pas de raccourci
 *   km.return.button=0|4|5         bouton lateral de la souris qui rend le controle (0 = aucun)
 *   km.clipboard=true|false        le texte copie suit le clavier d'un poste a l'autre (defaut : false)
 */
final class KmSettings {

    static final String K_ENABLED = "km.enabled", K_EDGE = "km.edge", K_DELAY = "km.edge.delay",
            K_HOTKEY = "km.hotkey", K_RETURN = "km.return.button", K_CLIPBOARD = "km.clipboard";

    static final int DEFAULT_DELAY_MS = 250, MAX_DELAY_MS = 5000;
    static final String DEFAULT_HOTKEY = "Ctrl+Alt+F12";

    /** Bord d'ecran. */
    enum Edge {
        NONE("aucun", "none", KmProtocol.EDGE_NONE), LEFT("gauche", "left", 0), RIGHT("droite", "right", 1),
        TOP("haut", "top", 2), BOTTOM("bas", "bottom", 3);

        final String id;
        private final String alias;
        private final int wire;

        Edge(String id, String alias, int wire) { this.id = id; this.alias = alias; this.wire = wire; }

        int wire() { return wire; }

        Edge opposite() {
            return switch (this) { case LEFT -> RIGHT; case RIGHT -> LEFT; case TOP -> BOTTOM; case BOTTOM -> TOP; default -> NONE; };
        }

        /** Valeur de fil inconnue : NONE. */
        static Edge fromWire(int w) {
            for (Edge e : values()) if (e.wire == w) return e;
            return NONE;
        }

        /** null si le texte n'est pas un bord connu. */
        static Edge parse(String v) {
            String s = v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
            for (Edge e : values()) if (e.id.equals(s) || e.alias.equals(s)) return e;
            return null;
        }
    }

    /**
     * Raccourci clavier : Ctrl et/ou Alt (au moins un), Maj facultatif, puis une
     * lettre, un chiffre ou F1..F12. vk est le code AWT (KeyEvent.VK_*), qui vaut
     * le caractere ASCII pour les lettres et les chiffres, et 0x70..0x7B pour F1..F12.
     */
    record Hotkey(boolean ctrl, boolean alt, boolean shift, int vk) {

        private static final int VK_F1 = 0x70;

        /** null si le texte n'est pas un raccourci accepte. */
        static Hotkey parse(String text) {
            if (text == null) return null;
            boolean ctrl = false, alt = false, shift = false;
            int vk = 0;
            for (String raw : text.trim().split("\\+", -1)) {
                String t = raw.trim().toUpperCase(Locale.ROOT);
                switch (t) {
                    case "CTRL", "CONTROL" -> ctrl = true;
                    case "ALT" -> alt = true;
                    case "SHIFT", "MAJ" -> shift = true;
                    default -> {
                        if (vk != 0) return null;                       // une seule touche
                        if (t.length() == 1 && (t.charAt(0) >= 'A' && t.charAt(0) <= 'Z' || t.charAt(0) >= '0' && t.charAt(0) <= '9')) {
                            vk = t.charAt(0);
                        } else if (t.matches("F([1-9]|1[0-2])")) {
                            vk = VK_F1 + Integer.parseInt(t.substring(1)) - 1;
                        } else {
                            return null;
                        }
                    }
                }
            }
            if (vk == 0 || !(ctrl || alt)) return null;
            if (alt && !ctrl && !shift && vk == VK_F1 + 3) return null;      // Alt+F4 : reserve par Windows
            return new Hotkey(ctrl, alt, shift, vk);
        }

        private String keyName() { return vk >= VK_F1 ? "F" + (vk - VK_F1 + 1) : String.valueOf((char) vk); }

        /** Forme affichee et enregistree : "Ctrl+Alt+F12". */
        String text() { return (ctrl ? "Ctrl+" : "") + (alt ? "Alt+" : "") + (shift ? "Maj+" : "") + keyName(); }

        /** Forme attendue par la propriete Hotkey d'un raccourci Windows : "CTRL+ALT+F12". */
        String lnk() { return (ctrl ? "CTRL+" : "") + (alt ? "ALT+" : "") + (shift ? "SHIFT+" : "") + keyName(); }

        boolean matches(int keyCode, boolean ctrlDown, boolean altDown, boolean shiftDown) {
            return keyCode == vk && ctrlDown == ctrl && altDown == alt && shiftDown == shift;
        }
    }

    static final KmSettings DEFAULT = new KmSettings(false, Edge.NONE, DEFAULT_DELAY_MS, Hotkey.parse(DEFAULT_HOTKEY), 0, false);

    final boolean enabled;
    final Edge edge;
    final int edgeDelayMs;
    /** null = pas de raccourci. */
    final Hotkey hotkey;
    /** 0 (aucun), 4 ou 5. */
    final int returnButton;
    /** Le texte du presse-papiers suit le clavier (a activer sur les deux postes). */
    final boolean clipboard;

    KmSettings(boolean enabled, Edge edge, int edgeDelayMs, Hotkey hotkey, int returnButton) {
        this(enabled, edge, edgeDelayMs, hotkey, returnButton, false);
    }

    KmSettings(boolean enabled, Edge edge, int edgeDelayMs, Hotkey hotkey, int returnButton, boolean clipboard) {
        if (edge == null) throw new IllegalArgumentException("bord absent");
        if (edgeDelayMs < 0 || edgeDelayMs > MAX_DELAY_MS) throw new IllegalArgumentException("delai hors 0.." + MAX_DELAY_MS);
        if (returnButton != 0 && returnButton != 4 && returnButton != 5) throw new IllegalArgumentException("bouton de retour : 0, 4 ou 5");
        this.enabled = enabled;
        this.edge = edge;
        this.edgeDelayMs = edgeDelayMs;
        this.hotkey = hotkey;
        this.returnButton = returnButton;
        this.clipboard = clipboard;
    }

    /**
     * Lit les cles "km.*". Une cle absente ou vide prend sa valeur par defaut ;
     * une valeur invalide prend aussi sa valeur par defaut et ajoute une phrase a
     * problems (si non null) : l'API de reglage refuse alors l'enregistrement, la
     * lecture de cotransfert.conf se contente de journaliser.
     */
    static KmSettings from(Properties p, List<String> problems) {
        List<String> pb = problems != null ? problems : new ArrayList<>();
        boolean enabled = false;
        String v = value(p, K_ENABLED);
        if (v.equalsIgnoreCase("true")) enabled = true;
        else if (!v.isEmpty() && !v.equalsIgnoreCase("false")) pb.add(K_ENABLED + " : true ou false attendu");

        Edge edge = Edge.NONE;
        v = value(p, K_EDGE);
        if (!v.isEmpty()) {
            Edge e = Edge.parse(v);
            if (e == null) pb.add(K_EDGE + " : gauche, droite, haut, bas ou aucun attendu");
            else edge = e;
        }

        int delay = DEFAULT_DELAY_MS;
        v = value(p, K_DELAY);
        if (!v.isEmpty()) {
            try {
                int d = Integer.parseInt(v);
                if (d < 0 || d > MAX_DELAY_MS) pb.add(K_DELAY + " : entre 0 et " + MAX_DELAY_MS + " ms");
                else delay = d;
            } catch (NumberFormatException e) {
                pb.add(K_DELAY + " : nombre de millisecondes attendu");
            }
        }

        Hotkey hotkey = DEFAULT.hotkey;
        v = value(p, K_HOTKEY);
        if (v.equalsIgnoreCase("aucun") || v.equalsIgnoreCase("none")) {
            hotkey = null;
        } else if (!v.isEmpty()) {
            Hotkey h = Hotkey.parse(v);
            if (h == null) pb.add(K_HOTKEY + " : Ctrl et/ou Alt, Maj facultatif, puis une lettre, un chiffre ou F1 a F12 (ex. " + DEFAULT_HOTKEY + "), ou aucun");
            else hotkey = h;
        }

        int button = 0;
        v = value(p, K_RETURN);
        if (v.equals("4")) button = 4;
        else if (v.equals("5")) button = 5;
        else if (!v.isEmpty() && !v.equals("0")) pb.add(K_RETURN + " : 0, 4 ou 5 attendu");

        boolean clipboard = false;
        v = value(p, K_CLIPBOARD);
        if (v.equalsIgnoreCase("true")) clipboard = true;
        else if (!v.isEmpty() && !v.equalsIgnoreCase("false")) pb.add(K_CLIPBOARD + " : true ou false attendu");

        return new KmSettings(enabled, edge, delay, hotkey, button, clipboard);
    }

    private static String value(Properties p, String key) {
        String v = p == null ? null : p.getProperty(key);
        return v == null ? "" : v.trim();
    }

    /** Toutes les cles, valeurs par defaut comprises. */
    Properties toProperties() {
        Properties p = new Properties();
        p.setProperty(K_ENABLED, String.valueOf(enabled));
        p.setProperty(K_EDGE, edge.id);
        p.setProperty(K_DELAY, String.valueOf(edgeDelayMs));
        p.setProperty(K_HOTKEY, hotkey == null ? "aucun" : hotkey.text());
        p.setProperty(K_RETURN, String.valueOf(returnButton));
        p.setProperty(K_CLIPBOARD, String.valueOf(clipboard));
        return p;
    }

    /** Lignes de cotransfert.conf : une cle a sa valeur par defaut est ecrite en commentaire. */
    List<String> confLines() {
        List<String> l = new ArrayList<>();
        l.add("# Partage clavier-souris (lien direct et secret partage requis) : desactive par defaut.");
        l.add("# km.edge : bord de CE poste derriere lequel se trouve l'autre (gauche, droite, haut, bas, aucun).");
        l.add("# km.edge.delay : ms contre le bord avant de passer ; km.hotkey : raccourci de bascule (ou aucun) ;");
        l.add("# km.return.button : bouton lateral de la souris qui rend le controle (4, 5, ou 0 = aucun).");
        l.add("# km.clipboard : le texte copie suit le clavier d'un poste a l'autre (a activer sur les deux postes).");
        Properties p = toProperties(), d = DEFAULT.toProperties();
        for (String k : new String[] { K_ENABLED, K_EDGE, K_DELAY, K_HOTKEY, K_RETURN, K_CLIPBOARD }) {
            String v = p.getProperty(k);
            l.add((v.equals(d.getProperty(k)) ? "# " : "") + k + "=" + v);
        }
        return l;
    }

    /** Pour GET /api/config : {"enabled":..,"edge":"..","delay":..,"hotkey":"..","returnButton":..}. */
    String json() {
        return "{\"enabled\":" + enabled + ",\"edge\":" + Status.q(edge.id) + ",\"delay\":" + edgeDelayMs
                + ",\"hotkey\":" + Status.q(hotkey == null ? "" : hotkey.text()) + ",\"returnButton\":" + returnButton + ",\"clipboard\":" + clipboard + "}";
    }

    @Override public boolean equals(Object o) {
        return o instanceof KmSettings s && s.enabled == enabled && s.edge == edge && s.edgeDelayMs == edgeDelayMs
                && s.returnButton == returnButton && s.clipboard == clipboard && java.util.Objects.equals(s.hotkey, hotkey);
    }

    @Override public int hashCode() { return java.util.Objects.hash(enabled, edge, edgeDelayMs, hotkey, returnButton, clipboard); }

    @Override public String toString() { return "KmSettings" + json(); }
}
