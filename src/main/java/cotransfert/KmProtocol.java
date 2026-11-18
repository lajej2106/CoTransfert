package cotransfert;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Partage clavier-souris : format de fil des evenements (voir SPEC-km.md).
 *
 * Cadre : [type:1][longueur:1][charge utile]. La longueur permet a une version
 * plus ancienne de sauter un type qu'elle ne connait pas, et d'ignorer les
 * octets ajoutes en fin de charge utile par une version plus recente.
 *
 *   MOVE        dx:2 dy:2 (signes)        deplacement relatif de la souris
 *   BUTTON      bouton:1 appui:1          bouton 1..5 (1 gauche, 2 milieu, 3 droit, 4 et 5 lateraux)
 *   WHEEL       crans:2 (signe)           molette (positif = vers l'utilisateur, comme AWT)
 *   KEY         vk:4 position:1 appui:1   code de touche AWT (KeyEvent.VK_*) et keyLocation
 *   GRAB        bord:1 position:2         "je prends le controle" (bord pousse et position le long du bord)
 *   RELEASE     -                         "je rends le controle" (vaut aussi tout relacher)
 *   RETURN      position:2                le poste pilote demande au pilote de rendre le controle
 *   RELEASE_ALL -                         relacher toutes les touches et tous les boutons
 *   HEARTBEAT   -                         battement de coeur
 *   HELLO       version:1 drapeaux:1      premier evenement de chaque sens
 *   REFUSE      raison:1                  prise de controle refusee
 *   CLIP_BEGIN  octets:4                  debut d'un texte de presse-papiers (taille totale en UTF-8)
 *   CLIP_DATA   1 a 255 octets            morceau du texte
 *   CLIP_END    -                         fin du texte : applique si la taille annoncee est atteinte
 */
final class KmProtocol {

    private KmProtocol() { }

    /** Version du protocole, annoncee dans HELLO et dans le cadre d'ouverture du lien. */
    static final int VERSION = 1;

    static final int MOVE = 1, BUTTON = 2, WHEEL = 3, KEY = 4, GRAB = 5, RELEASE = 6, RETURN = 7,
            RELEASE_ALL = 8, HEARTBEAT = 9, HELLO = 10, REFUSE = 11, CLIP_BEGIN = 12, CLIP_DATA = 13, CLIP_END = 14;

    /** HELLO : ce poste accepte d'etre pilote (fonction active, ecran present). */
    static final int FLAG_ACCEPTS = 1;
    /** HELLO : ce poste est le hub. */
    static final int FLAG_HUB = 2;
    /** HELLO : ce poste accepte de recevoir le texte du presse-papiers. */
    static final int FLAG_CLIPBOARD = 4;

    /** Taille maximale d'un texte de presse-papiers partage (octets UTF-8) et d'un morceau. */
    static final int CLIP_MAX_BYTES = 512 * 1024, CLIP_CHUNK = 255;

    /** Position le long d'un bord : 0..65534 ; 65535 = pas de position. */
    static final int POS_NONE = 0xFFFF;
    static final int POS_MAX = 0xFFFE;
    /** Bord absent (prise de controle par raccourci ou par la page). */
    static final int EDGE_NONE = 255;

    static final int REFUSE_DISABLED = 1, REFUSE_NO_SCREEN = 2, REFUSE_ERROR = 3;

    static final int MAX_BUTTON = 5;

    /**
     * Un evenement. Sens des champs selon le type : MOVE (a=dx, b=dy), BUTTON
     * (a=bouton, down), WHEEL (a=crans), KEY (a=vk, b=position, down), GRAB
     * (a=bord, b=position), RETURN (a=position), HELLO (a=version, b=drapeaux),
     * REFUSE (a=raison), CLIP_BEGIN (a=octets), CLIP_DATA (data).
     */
    record Event(int type, int a, int b, boolean down, byte[] data) {
        Event(int type, int a, int b, boolean down) { this(type, a, b, down, null); }

        @Override public String toString() {
            return switch (type) {
                case MOVE -> "MOVE " + a + " " + b;
                case BUTTON -> "BUTTON " + a + (down ? " down" : " up");
                case WHEEL -> "WHEEL " + a;
                case KEY -> "KEY " + a + " @" + b + (down ? " down" : " up");
                case GRAB -> "GRAB " + a + " " + b;
                case RELEASE -> "RELEASE";
                case RETURN -> "RETURN " + a;
                case RELEASE_ALL -> "RELEASE_ALL";
                case HEARTBEAT -> "HEARTBEAT";
                case HELLO -> "HELLO v" + a + " " + b;
                case REFUSE -> "REFUSE " + a;
                case CLIP_BEGIN -> "CLIP_BEGIN " + a;
                case CLIP_DATA -> "CLIP_DATA " + (data == null ? 0 : data.length);
                case CLIP_END -> "CLIP_END";
                default -> "? " + type;
            };
        }
    }

    private static final Event E_RELEASE = new Event(RELEASE, 0, 0, false);
    private static final Event E_RELEASE_ALL = new Event(RELEASE_ALL, 0, 0, false);
    private static final Event E_HEARTBEAT = new Event(HEARTBEAT, 0, 0, false);
    private static final Event E_CLIP_END = new Event(CLIP_END, 0, 0, false);

    /**
     * Les cadres d'un texte de presse-papiers : CLIP_BEGIN, des CLIP_DATA, CLIP_END.
     * Liste vide si le texte est vide ou depasse CLIP_MAX_BYTES.
     */
    static java.util.List<Event> clip(String text) {
        java.util.List<Event> l = new java.util.ArrayList<>();
        if (text == null || text.isEmpty() || text.length() > CLIP_MAX_BYTES) return l;
        byte[] b = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (b.length > CLIP_MAX_BYTES) return l;
        l.add(new Event(CLIP_BEGIN, b.length, 0, false));
        for (int off = 0; off < b.length; off += CLIP_CHUNK) {
            l.add(new Event(CLIP_DATA, 0, 0, false, java.util.Arrays.copyOfRange(b, off, Math.min(b.length, off + CLIP_CHUNK))));
        }
        l.add(E_CLIP_END);
        return l;
    }

    /** Deplacement relatif ; chaque axe est borne a un entier signe de 16 bits. */
    static Event move(int dx, int dy) { return new Event(MOVE, clamp16(dx), clamp16(dy), false); }

    static Event button(int button, boolean down) {
        if (button < 1 || button > MAX_BUTTON) throw new IllegalArgumentException("bouton hors 1.." + MAX_BUTTON + " : " + button);
        return new Event(BUTTON, button, 0, down);
    }

    static Event wheel(int notches) { return new Event(WHEEL, clamp16(notches), 0, false); }

    static Event key(int vk, int location, boolean down) { return new Event(KEY, vk, location & 0xFF, down); }

    /** edge : KmSettings.Edge.wire() ; pos : 0..POS_MAX ou POS_NONE. */
    static Event grab(int edge, int pos) { return new Event(GRAB, edge & 0xFF, pos(pos), false); }

    static Event release() { return E_RELEASE; }

    static Event askReturn(int pos) { return new Event(RETURN, pos(pos), 0, false); }

    static Event releaseAll() { return E_RELEASE_ALL; }

    static Event heartbeat() { return E_HEARTBEAT; }

    static Event hello(int flags) { return new Event(HELLO, VERSION, flags & 0xFF, false); }

    static Event refuse(int reason) { return new Event(REFUSE, reason & 0xFF, 0, false); }

    private static int clamp16(int v) { return Math.max(Short.MIN_VALUE + 1, Math.min(Short.MAX_VALUE, v)); }

    private static int pos(int p) { return p < 0 || p > POS_MAX ? POS_NONE : p; }

    /** Ecrit un cadre ; ne vide pas le tampon. */
    static void write(DataOutputStream out, Event e) throws IOException {
        out.writeByte(e.type());
        switch (e.type()) {
            case MOVE -> { out.writeByte(4); out.writeShort(e.a()); out.writeShort(e.b()); }
            case BUTTON -> { out.writeByte(2); out.writeByte(e.a()); out.writeByte(e.down() ? 1 : 0); }
            case WHEEL -> { out.writeByte(2); out.writeShort(e.a()); }
            case KEY -> { out.writeByte(6); out.writeInt(e.a()); out.writeByte(e.b()); out.writeByte(e.down() ? 1 : 0); }
            case GRAB -> { out.writeByte(3); out.writeByte(e.a()); out.writeShort(e.b()); }
            case RETURN -> { out.writeByte(2); out.writeShort(e.a()); }
            case HELLO -> { out.writeByte(2); out.writeByte(e.a()); out.writeByte(e.b()); }
            case REFUSE -> { out.writeByte(1); out.writeByte(e.a()); }
            case RELEASE, RELEASE_ALL, HEARTBEAT, CLIP_END -> out.writeByte(0);
            case CLIP_BEGIN -> { out.writeByte(4); out.writeInt(e.a()); }
            case CLIP_DATA -> {
                byte[] d = e.data();
                if (d == null || d.length == 0 || d.length > CLIP_CHUNK) throw new IOException("morceau de presse-papiers invalide");
                out.writeByte(d.length);
                out.write(d);
            }
            default -> throw new IOException("evenement de type inconnu : " + e.type());
        }
    }

    /**
     * Lit le prochain evenement connu (bloquant) ; les types inconnus sont sautes.
     * EOFException quand le flux se termine ; IOException sur un cadre trop court.
     */
    static Event read(DataInputStream in) throws IOException {
        while (true) {
            int type = in.readUnsignedByte();
            int len = in.readUnsignedByte();
            int need = switch (type) {
                case MOVE -> 4; case BUTTON, WHEEL, RETURN, HELLO -> 2; case KEY -> 6; case GRAB -> 3;
                case REFUSE -> 1; case RELEASE, RELEASE_ALL, HEARTBEAT, CLIP_END -> 0;
                case CLIP_BEGIN -> 4; case CLIP_DATA -> len;
                default -> -1;
            };
            if (need < 0) { skip(in, len); continue; }
            if (len < need) throw new IOException("cadre clavier-souris trop court (type " + type + ")");
            Event e = switch (type) {
                case MOVE -> new Event(MOVE, in.readShort(), in.readShort(), false);
                case BUTTON -> new Event(BUTTON, in.readUnsignedByte(), 0, in.readUnsignedByte() != 0);
                case WHEEL -> new Event(WHEEL, in.readShort(), 0, false);
                case KEY -> new Event(KEY, in.readInt(), in.readUnsignedByte(), in.readUnsignedByte() != 0);
                case GRAB -> new Event(GRAB, in.readUnsignedByte(), in.readUnsignedShort(), false);
                case RETURN -> new Event(RETURN, in.readUnsignedShort(), 0, false);
                case HELLO -> new Event(HELLO, in.readUnsignedByte(), in.readUnsignedByte(), false);
                case REFUSE -> new Event(REFUSE, in.readUnsignedByte(), 0, false);
                case RELEASE -> E_RELEASE;
                case RELEASE_ALL -> E_RELEASE_ALL;
                case CLIP_BEGIN -> new Event(CLIP_BEGIN, in.readInt(), 0, false);
                case CLIP_DATA -> { byte[] d = new byte[len]; in.readFully(d); yield new Event(CLIP_DATA, 0, 0, false, d); }
                case CLIP_END -> E_CLIP_END;
                default -> E_HEARTBEAT;
            };
            skip(in, len - need);
            return e;
        }
    }

    private static void skip(DataInputStream in, int n) throws IOException {
        for (int i = 0; i < n; i++) in.readUnsignedByte();
    }
}
