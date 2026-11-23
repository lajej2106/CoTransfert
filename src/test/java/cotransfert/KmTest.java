package cotransfert;

import java.awt.Point;
import java.awt.Rectangle;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Tests du partage clavier-souris (main simple, sans JUnit). Code de sortie non
 * nul au premier echec.
 *
 *   java -cp "build/classes;build/test-classes" cotransfert.KmTest <dossier-de-travail>
 *
 * Tout passe par des doublures : aucun java.awt.Robot, aucune fenetre, aucun
 * raccourci Windows cree, aucun PowerShell lance. Les liens utilisent des
 * sockets de boucle locale sur des ports libres choisis par le systeme.
 */
public final class KmTest {

    private static final AtomicInteger CHECKS = new AtomicInteger();
    private static final Rectangle[] ONE_SCREEN = { new Rectangle(0, 0, 1920, 1080) };

    public static void main(String[] args) {
        try {
            if (args.length > 0) Files.createDirectories(Paths.get(args[0]));
            testProtocol();
            testSettings();
            testEdge();
            testRelative();
            testInject();
            testShortcut();
            testAvailability();
            Crypto.Key good = Crypto.derive("secret-de-test-A");
            Crypto.Key bad = Crypto.derive("secret-de-test-B");
            testLinkRefusals(good, bad);
            testEndToEndEncrypted(good);
            testHubAccept(good);
            testToggle();
            testSimultaneous();
            testEdgeRoundTrip();
            testLinkCut();
            testHeartbeatLost();
            testPeerRefuses();
            testClipboard(good);
            System.out.println("OK - " + CHECKS.get() + " verifications");
            System.exit(0);
        } catch (Throwable t) {
            System.out.println("ECHEC: " + t);
            t.printStackTrace(System.out);
            System.exit(1);
        }
    }

    // =======================================================================
    //  Protocole
    // =======================================================================

    private static void testProtocol() throws Exception {
        List<KmProtocol.Event> all = List.of(
                KmProtocol.hello(KmProtocol.FLAG_ACCEPTS | KmProtocol.FLAG_HUB),
                KmProtocol.move(0, 0), KmProtocol.move(1, -1), KmProtocol.move(32767, -32767),
                KmProtocol.button(1, true), KmProtocol.button(1, false), KmProtocol.button(5, true), KmProtocol.button(4, false),
                KmProtocol.wheel(1), KmProtocol.wheel(-3), KmProtocol.wheel(32767), KmProtocol.wheel(-32767),
                KmProtocol.key(65, 1, true), KmProtocol.key(65, 1, false), KmProtocol.key(0xFF7E, 3, true),
                KmProtocol.key(0x1000000 + 0xE9, 4, true), KmProtocol.key(Integer.MAX_VALUE, 255, false), KmProtocol.key(0, 0, true),
                KmProtocol.grab(KmSettings.Edge.RIGHT.wire(), 0), KmProtocol.grab(KmSettings.Edge.BOTTOM.wire(), KmProtocol.POS_MAX),
                KmProtocol.grab(KmProtocol.EDGE_NONE, KmProtocol.POS_NONE),
                KmProtocol.release(), KmProtocol.askReturn(1234), KmProtocol.askReturn(KmProtocol.POS_NONE),
                KmProtocol.releaseAll(), KmProtocol.heartbeat(), KmProtocol.refuse(KmProtocol.REFUSE_NO_SCREEN));
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bo);
        for (KmProtocol.Event e : all) KmProtocol.write(out, e);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bo.toByteArray()));
        for (KmProtocol.Event e : all) check(e.equals(KmProtocol.read(in)), "protocole aller-retour : " + e);
        boolean eof = false;
        try { KmProtocol.read(in); } catch (EOFException e) { eof = true; }
        check(eof, "protocole : fin de flux = EOFException");
        check(bo.size() < all.size() * 8, "protocole compact (" + bo.size() + " octets pour " + all.size() + " evenements)");

        check(KmProtocol.move(100000, -100000).equals(KmProtocol.move(32767, -32767)), "MOVE borne a 16 bits");
        check(KmProtocol.wheel(1 << 20).a() == 32767, "WHEEL borne a 16 bits");
        check(KmProtocol.grab(1, -5).b() == KmProtocol.POS_NONE && KmProtocol.grab(1, 70000).b() == KmProtocol.POS_NONE, "position hors bornes = aucune");
        check(throwsIae(() -> KmProtocol.button(0, true)) && throwsIae(() -> KmProtocol.button(6, true)), "bouton hors 1..5 refuse");

        // Type inconnu saute, octets en trop ignores, cadre trop court refuse.
        bo.reset();
        out.writeByte(200); out.writeByte(3); out.write(new byte[] { 9, 9, 9 });
        out.writeByte(KmProtocol.MOVE); out.writeByte(6); out.writeShort(7); out.writeShort(-8); out.writeShort(99);
        KmProtocol.write(out, KmProtocol.release());
        in = new DataInputStream(new ByteArrayInputStream(bo.toByteArray()));
        check(KmProtocol.read(in).equals(KmProtocol.move(7, -8)), "type inconnu saute, charge utile plus longue acceptee");
        check(KmProtocol.read(in).equals(KmProtocol.release()), "le flux reste aligne apres un cadre plus long");
        bo.reset();
        out.writeByte(KmProtocol.KEY); out.writeByte(2); out.writeShort(1);
        boolean refused = false;
        try { KmProtocol.read(new DataInputStream(new ByteArrayInputStream(bo.toByteArray()))); } catch (IOException e) { refused = !(e instanceof EOFException); }
        check(refused, "cadre trop court refuse");
        check(KmProtocol.VERSION == 1, "version du protocole");
    }

    // =======================================================================
    //  Reglages
    // =======================================================================

    private static void testSettings() {
        List<String> pb = new ArrayList<>();
        KmSettings d = KmSettings.from(new Properties(), pb);
        check(pb.isEmpty() && d.equals(KmSettings.DEFAULT), "reglages par defaut sans cle");
        check(!d.enabled && d.edge == KmSettings.Edge.NONE && d.edgeDelayMs == 250 && d.returnButton == 0
                && d.hotkey != null && d.hotkey.text().equals("Ctrl+Alt+F12"), "valeurs par defaut : desactive, aucun bord, 250 ms, Ctrl+Alt+F12");
        check(KmSettings.from(null, null).equals(KmSettings.DEFAULT), "Properties nulles = defaut");

        KmSettings s = new KmSettings(true, KmSettings.Edge.RIGHT, 400, KmSettings.Hotkey.parse("ctrl+shift+k"), 5);
        Properties p = s.toProperties();
        check(p.getProperty("km.enabled").equals("true") && p.getProperty("km.edge").equals("droite")
                && p.getProperty("km.edge.delay").equals("400") && p.getProperty("km.hotkey").equals("Ctrl+Maj+K")
                && p.getProperty("km.return.button").equals("5"), "cles km.* ecrites : " + p);
        check(KmSettings.from(p, pb).equals(s) && pb.isEmpty(), "reglages aller-retour Properties");
        for (String k : p.stringPropertyNames()) check(k.startsWith("km."), "cle prefixee km. : " + k);

        KmSettings none = new KmSettings(true, KmSettings.Edge.TOP, 0, null, 4);
        check(none.toProperties().getProperty("km.hotkey").equals("aucun") && KmSettings.from(none.toProperties(), pb).equals(none) && pb.isEmpty(),
                "pas de raccourci : aller-retour");

        Properties en = new Properties();
        en.setProperty("km.edge", " LEFT ");
        en.setProperty("km.enabled", "TRUE");
        KmSettings e = KmSettings.from(en, pb);
        check(pb.isEmpty() && e.enabled && e.edge == KmSettings.Edge.LEFT, "alias et casse acceptes");

        Properties bad = new Properties();
        bad.setProperty("km.enabled", "oui");
        bad.setProperty("km.edge", "diagonale");
        bad.setProperty("km.edge.delay", "99999");
        bad.setProperty("km.hotkey", "F12");
        bad.setProperty("km.return.button", "3");
        KmSettings b = KmSettings.from(bad, pb);
        check(pb.size() == 5, "5 valeurs invalides signalees : " + pb);
        check(b.equals(KmSettings.DEFAULT), "valeurs invalides = valeurs par defaut");
        pb.clear();
        bad.setProperty("km.edge.delay", "vite");
        KmSettings.from(bad, pb);
        check(pb.size() == 5, "delai non numerique signale");
        check(KmSettings.from(bad, null).equals(KmSettings.DEFAULT), "valeurs invalides sans liste : pas d'exception");

        check(KmSettings.Hotkey.parse("Ctrl+Alt+F12").lnk().equals("CTRL+ALT+F12"), "raccourci au format .lnk");
        check(KmSettings.Hotkey.parse("alt + maj + 7").text().equals("Alt+Maj+7"), "raccourci avec chiffre");
        for (String h : new String[] { "", "F12", "Shift+F1", "Ctrl+Alt", "Ctrl+Alt+F13", "Ctrl+Alt+Suppr", "Ctrl+A+B", "Alt+F4", "Ctrl+Tab", "Win+L", "Ctrl++" })
            check(KmSettings.Hotkey.parse(h) == null, "raccourci refuse : '" + h + "'");
        KmSettings.Hotkey hk = KmSettings.Hotkey.parse("Ctrl+Alt+F12");
        check(hk.vk() == 0x7B && hk.matches(0x7B, true, true, false) && !hk.matches(0x7B, true, true, true)
                && !hk.matches(0x7B, true, false, false) && !hk.matches(0x7A, true, true, false), "raccourci : correspondance exacte");

        check(throwsIae(() -> new KmSettings(true, KmSettings.Edge.LEFT, -1, null, 0))
                && throwsIae(() -> new KmSettings(true, KmSettings.Edge.LEFT, 10, null, 2))
                && throwsIae(() -> new KmSettings(true, null, 10, null, 0)), "constructeur : valeurs invalides refusees");

        List<String> lines = KmSettings.DEFAULT.confLines();
        check(lines.contains("# km.enabled=false") && lines.contains("# km.hotkey=Ctrl+Alt+F12"), "conf : valeurs par defaut en commentaire");
        check(s.confLines().contains("km.enabled=true") && s.confLines().contains("km.edge=droite"), "conf : valeurs reglees ecrites");
        for (String l : s.confLines()) check(l.chars().allMatch(c -> c < 128), "conf sans accent : " + l);
        check(s.json().equals("{\"enabled\":true,\"edge\":\"droite\",\"delay\":400,\"hotkey\":\"Ctrl+Maj+K\",\"returnButton\":5,\"clipboard\":true}"), "reglages en JSON : " + s.json());
        Properties cp = s.toProperties();
        check("true".equals(cp.getProperty(KmSettings.K_CLIPBOARD)), "presse-papiers : actif par defaut dans les proprietes");
        check(KmSettings.DEFAULT.clipboard && lines.contains("# km.clipboard=true"), "presse-papiers : actif par defaut, en commentaire dans la conf");
        Properties en2 = new Properties();
        en2.setProperty("km.enabled", "true");
        check(KmSettings.from(en2, null).clipboard, "activer le partage clavier-souris active le presse-papiers");
        cp.setProperty(KmSettings.K_CLIPBOARD, "false");
        KmSettings noClip = KmSettings.from(cp, null);
        check(!noClip.clipboard && !noClip.equals(s) && noClip.confLines().contains("km.clipboard=false")
                && noClip.json().endsWith(",\"clipboard\":false}"), "presse-papiers : decoche, lu, ecrit et compare");
        cp.setProperty(KmSettings.K_CLIPBOARD, "oui");
        List<String> clipPb = new ArrayList<>();
        check(KmSettings.from(cp, clipPb).clipboard == KmSettings.DEFAULT_CLIPBOARD && clipPb.size() == 1 && clipPb.get(0).startsWith(KmSettings.K_CLIPBOARD), "presse-papiers : valeur invalide signalee");
        check(KmSettings.Edge.LEFT.opposite() == KmSettings.Edge.RIGHT && KmSettings.Edge.TOP.opposite() == KmSettings.Edge.BOTTOM
                && KmSettings.Edge.NONE.opposite() == KmSettings.Edge.NONE, "bord oppose");
        for (KmSettings.Edge ed : KmSettings.Edge.values()) check(KmSettings.Edge.fromWire(ed.wire()) == ed, "bord sur le fil : " + ed);
        check(KmSettings.Edge.fromWire(77) == KmSettings.Edge.NONE, "bord inconnu sur le fil = aucun");
    }

    // =======================================================================
    //  Bord d'ecran (logique pure)
    // =======================================================================

    private static void testEdge() {
        KmEdge right = new KmEdge(KmSettings.Edge.RIGHT, 250);
        check(!right.poll(new Point(1919, 500), ONE_SCREEN, 0) && !right.poll(new Point(1919, 500), ONE_SCREEN, 1000),
                "pointeur deja contre le bord au depart : pas de declenchement");
        check(!right.poll(new Point(1000, 500), ONE_SCREEN, 1100), "loin du bord : rien (et le bord est arme)");
        check(!right.poll(new Point(1919, 500), ONE_SCREEN, 1200), "contact : le delai commence");
        check(!right.poll(new Point(1919, 510), ONE_SCREEN, 1400), "200 ms : trop tot");
        check(right.poll(new Point(1919, 540), ONE_SCREEN, 1450), "250 ms contre le bord : declenchement");
        check(right.fraction() == 540L * KmProtocol.POS_MAX / 1079, "position le long du bord");
        check(!right.poll(new Point(1919, 540), ONE_SCREEN, 1500) && !right.poll(new Point(1919, 540), ONE_SCREEN, 5000),
                "un seul declenchement tant que le bord n'est pas quitte");
        check(!right.poll(new Point(1800, 540), ONE_SCREEN, 5100) && !right.poll(new Point(1919, 540), ONE_SCREEN, 5200)
                && right.poll(new Point(1919, 540), ONE_SCREEN, 5450), "bord quitte puis repousse : nouveau declenchement");

        // Contact interrompu : le delai repart de zero.
        KmEdge r2 = new KmEdge(KmSettings.Edge.RIGHT, 250);
        r2.poll(new Point(100, 100), ONE_SCREEN, 0);
        r2.poll(new Point(1919, 100), ONE_SCREEN, 10);
        r2.poll(new Point(1900, 100), ONE_SCREEN, 200);
        check(!r2.poll(new Point(1919, 100), ONE_SCREEN, 270) && !r2.poll(new Point(1919, 100), ONE_SCREEN, 500)
                && r2.poll(new Point(1919, 100), ONE_SCREEN, 520), "contact interrompu : le delai repart");

        // Mauvais bord.
        KmEdge wrong = new KmEdge(KmSettings.Edge.RIGHT, 100);
        wrong.poll(new Point(500, 500), ONE_SCREEN, 0);
        boolean fired = false;
        for (Point p : new Point[] { new Point(0, 500), new Point(500, 0), new Point(500, 1079) })
            for (long t = 10; t < 1000; t += 50) fired |= wrong.poll(p, ONE_SCREEN, t);
        check(!fired, "mauvais bord : jamais de declenchement");

        // Les quatre bords, delai nul.
        Object[][] cases = { { KmSettings.Edge.LEFT, new Point(0, 300) }, { KmSettings.Edge.RIGHT, new Point(1919, 300) },
                { KmSettings.Edge.TOP, new Point(300, 0) }, { KmSettings.Edge.BOTTOM, new Point(300, 1079) } };
        for (Object[] c : cases) {
            KmEdge e = new KmEdge((KmSettings.Edge) c[0], 0);
            e.poll(new Point(900, 500), ONE_SCREEN, 0);
            check(e.poll((Point) c[1], ONE_SCREEN, 1), "bord " + c[0] + " avec delai nul");
        }
        KmEdge off = new KmEdge(KmSettings.Edge.NONE, 0);
        off.poll(new Point(900, 500), ONE_SCREEN, 0);
        check(!off.poll(new Point(0, 0), ONE_SCREEN, 10) && !right.poll(null, ONE_SCREEN, 0) && !right.poll(new Point(1, 1), null, 0),
                "aucun bord, pointeur inconnu ou ecrans inconnus : rien");

        // Deux ecrans cote a cote : la jonction n'est pas un bord exterieur.
        Rectangle[] two = { new Rectangle(0, 0, 1920, 1080), new Rectangle(1920, 200, 1280, 720) };
        check(KmEdge.contact(KmSettings.Edge.RIGHT, new Point(1919, 500), two) < 0, "jonction entre deux ecrans : pas un bord");
        check(KmEdge.contact(KmSettings.Edge.RIGHT, new Point(1919, 100), two) >= 0, "bord droit du premier ecran, la ou le second ne le touche pas");
        check(KmEdge.contact(KmSettings.Edge.RIGHT, new Point(3199, 500), two) >= 0, "bord droit du second ecran");
        check(KmEdge.contact(KmSettings.Edge.LEFT, new Point(1920, 500), two) < 0 && KmEdge.contact(KmSettings.Edge.LEFT, new Point(0, 500), two) == 500L * KmProtocol.POS_MAX / 1079,
                "bord gauche : seulement celui du bureau");
        check(KmEdge.contact(KmSettings.Edge.LEFT, new Point(-50, 500), two) < 0, "pointeur hors de tout ecran : pas de contact");

        // Point d'entree.
        Point in = KmEdge.entryPoint(KmSettings.Edge.LEFT, KmProtocol.POS_MAX, ONE_SCREEN);
        check(in.x == KmEdge.INSET && in.y == 1079, "entree a gauche, en bas : " + in);
        in = KmEdge.entryPoint(KmSettings.Edge.RIGHT, 0, two);
        check(in.x == 3199 - KmEdge.INSET && in.y == 200, "entree a droite sur l'ecran le plus a droite : " + in);
        in = KmEdge.entryPoint(KmSettings.Edge.TOP, KmProtocol.POS_NONE, ONE_SCREEN);
        check(in.y == KmEdge.INSET && Math.abs(in.x - 959) <= 1, "entree en haut, au milieu sans position : " + in);
        check(KmEdge.contact(KmSettings.Edge.LEFT, KmEdge.entryPoint(KmSettings.Edge.LEFT, 100, ONE_SCREEN), ONE_SCREEN) < 0,
                "le point d'entree n'est pas contre le bord (pas de retour immediat)");
        check(KmEdge.entryPoint(KmSettings.Edge.NONE, 0, ONE_SCREEN) == null && KmEdge.entryPoint(KmSettings.Edge.LEFT, 0, new Rectangle[0]) == null,
                "pas de point d'entree sans bord ou sans ecran");

        check(KmEdge.clamp(new Point(2500, -40), new Point(1900, 10), ONE_SCREEN).equals(new Point(1919, 0)), "borne a l'ecran");
        check(KmEdge.clamp(new Point(2500, 500), new Point(1900, 500), two).equals(new Point(2500, 500)), "passage sur l'ecran voisin permis");
        check(KmEdge.clamp(new Point(5, 5), new Point(1, 1), new Rectangle[0]).equals(new Point(5, 5)), "ecrans inconnus : position inchangee");
    }

    // =======================================================================
    //  Deplacement relatif de la capture (logique pure, sans fenetre)
    // =======================================================================

    private static void testRelative() {
        KmCapture.Relative r = new KmCapture.Relative(500, 500, 100);
        check(r.feed(500, 500) == null && !r.takeWarp(), "au centre : rien");
        int[] d = r.feed(510, 495);
        check(d[0] == 10 && d[1] == -5 && !r.takeWarp(), "petit deplacement : relatif au precedent, pas de recentrage");
        d = r.feed(620, 495);
        check(d[0] == 110 && d[1] == 0 && r.takeWarp() && !r.takeWarp(), "loin du centre : recentrage demande une fois");
        d = r.feed(630, 500);
        check(d[0] == 10 && d[1] == 5 && !r.takeWarp(), "evenement deja en file avant le recentrage : compte par rapport au precedent");
        d = r.feed(503, 498);
        check(d[0] == 3 && d[1] == -2, "premier evenement apres recentrage : compte par rapport au centre");
        check(r.feed(503, 498) == null, "immobile : rien");
        d = r.feed(500, 500);
        check(d[0] == -3 && d[1] == 2, "retour au fonctionnement normal");
        // Somme exacte sur un long trajet vers la droite, avec recentrages.
        KmCapture.Relative s = new KmCapture.Relative(1000, 500, 200);
        int x = 1000, sum = 0, warps = 0;
        for (int i = 0; i < 500; i++) {
            x += 7;
            int[] m = s.feed(x, 500);
            if (m != null) sum += m[0];
            if (s.takeWarp()) { warps++; x = 1000; }       // le recentrage prend effet avant l'evenement suivant
        }
        check(sum == 3500 && warps > 10, "trajet long : somme exacte (" + sum + ") malgre " + warps + " recentrages");
        check(KmCapture.bannerText(KmSettings.DEFAULT).contains("Ctrl+Alt+F12") && KmCapture.bannerText(KmSettings.DEFAULT).contains("autre poste"),
                "bandeau : dit ou vont les frappes et comment revenir");
        check(KmCapture.bannerText(new KmSettings(true, KmSettings.Edge.LEFT, 0, null, 4)).contains("bouton 4"), "bandeau sans raccourci : bouton de retour");
    }

    // =======================================================================
    //  Injection (doublure du poste : jamais de Robot)
    // =======================================================================

    private static final class FakeDevice implements KmInject.Device {
        final List<String> log = new ArrayList<>();
        Point pointer = new Point(100, 100);
        Rectangle[] screens = { new Rectangle(0, 0, 200, 200) };
        @Override public Point pointer() { return pointer == null ? null : new Point(pointer); }
        @Override public Rectangle[] screens() { return screens; }
        @Override public void mouseMove(int x, int y) { pointer = new Point(x, y); log.add("move " + x + "," + y); }
        @Override public void button(int button, boolean down) {
            if (button > 3) throw new IllegalArgumentException("pas de bouton " + button);
            log.add("button " + button + (down ? " down" : " up"));
        }
        @Override public void wheel(int notches) { log.add("wheel " + notches); }
        @Override public void key(int vk, boolean down) {
            if (vk == 999) throw new IllegalArgumentException("Invalid key code");
            log.add("key " + vk + (down ? " down" : " up"));
        }
    }

    private static void testInject() {
        FakeDevice dev = new FakeDevice();
        KmInject.Sink s = KmInject.over(dev);
        s.move(50, -20);
        check(dev.log.equals(List.of("move 150,80")), "deplacement relatif a la position courante");
        s.move(500, 500);
        check(dev.pointer.equals(new Point(199, 199)), "deplacement borne a l'ecran");
        s.move(10, 10);
        s.move(0, 0);
        check(dev.log.size() == 2, "deplacement sans effet : rien d'injecte");
        dev.screens = new Rectangle[] { new Rectangle(0, 0, 200, 200), new Rectangle(200, 0, 200, 200) };
        s.move(100, 0);
        check(dev.pointer.equals(new Point(299, 199)), "deplacement vers le second ecran");
        dev.log.clear();

        s.key(65, 1, true);
        s.key(999, 1, true);
        s.key(999, 1, true);
        s.key(999, 1, false);
        check(s.ignored() == 2, "touche refusee par le poste : ignoree et comptee, jamais fatale");
        s.key(66, 1, true);
        s.key(66, 1, true);
        s.key(77, 1, false);
        s.button(1, true);
        s.button(4, true);
        s.button(2, false);
        s.wheel(-2);
        s.wheel(0);
        check(s.ignored() == 3, "bouton refuse par le poste : ignore et compte");
        check(dev.log.equals(List.of("key 65 down", "key 66 down", "key 66 down", "button 1 down", "wheel -2")),
                "injection : appuis, repetition, pas de relache sans appui : " + dev.log);
        dev.log.clear();
        s.releaseAll();
        check(dev.log.equals(List.of("key 66 up", "key 65 up", "button 1 up")), "tout relacher : touches puis boutons enfonces : " + dev.log);
        dev.log.clear();
        s.releaseAll();
        check(dev.log.isEmpty(), "tout relacher deux fois : rien la seconde fois");
        s.key(65, 1, true);
        s.key(65, 1, false);
        s.releaseAll();
        check(dev.log.equals(List.of("key 65 down", "key 65 up")), "touche deja relachee : pas relachee deux fois");
        dev.log.clear();

        dev.pointer = null;
        s.move(5, 5);
        check(dev.log.isEmpty(), "position du pointeur inconnue : deplacement ignore");
        dev.screens = new Rectangle[] { new Rectangle(0, 0, 200, 200) };
        s.enter(KmSettings.Edge.LEFT, KmProtocol.POS_MAX / 2);
        check(dev.log.equals(List.of("move " + KmEdge.INSET + ",99")), "entree : pointeur pose a l'interieur du bord : " + dev.log);
        dev.log.clear();
        s.enter(KmSettings.Edge.NONE, 0);
        check(dev.log.isEmpty(), "entree sans bord : pointeur laisse en place");
    }

    // =======================================================================
    //  Raccourci Windows (.lnk) : seulement ce qui SERAIT lance
    // =======================================================================

    private static void testShortcut() {
        KmSettings.Hotkey hk = KmSettings.Hotkey.parse("Ctrl+Alt+F12");
        KmShortcut.Plan p = KmShortcut.planCreate(47999, hk, KmShortcut.FOLDER_START_MENU, true);
        List<String> c = p.command();
        check(c.size() == 7 && c.get(0).toLowerCase().endsWith("\\system32\\windowspowershell\\v1.0\\powershell.exe"), "commande : Windows PowerShell 5.1 du systeme");
        check(c.subList(1, 6).equals(List.of("-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command")), "commande : options en tableau d'arguments");
        String script = c.get(6);
        check(script.equals(KmShortcut.CREATE_SCRIPT) && !script.contains("\"") && !script.contains("\n") && !script.contains("`"),
                "script fixe, sans guillemet double ni saut de ligne");
        check(script.contains("New-Object -ComObject WScript.Shell") && script.contains(".CreateShortcut(") && script.contains("$l.Hotkey = $env:COTRANSFERT_KM_TOUCHE")
                && script.contains("$l.WindowStyle = 7") && script.contains("$l.Save()") && script.contains("[Environment]::GetFolderPath($env:COTRANSFERT_KM_DOSSIER)"),
                "script : WScript.Shell, touche de raccourci, fenetre reduite");
        check(!script.contains("47999") && !script.contains("F12") && !script.contains("curl"), "aucune valeur reglee n'est inseree dans le script");
        check(!script.toLowerCase().contains("runas") && !script.contains("HKLM") && !script.contains("Import-Module") && !script.contains("Start-Process"),
                "script : ni elevation, ni registre machine, ni module");
        check(p.env().get(KmShortcut.ENV_FOLDER).equals("Programs") && p.env().get(KmShortcut.ENV_NAME).equals("CoTransfert - clavier et souris.lnk")
                && p.env().get(KmShortcut.ENV_HOTKEY).equals("CTRL+ALT+F12"), "valeurs passees par l'environnement : " + p.env().keySet());
        check(p.target().toLowerCase().endsWith("\\system32\\curl.exe") && p.env().get(KmShortcut.ENV_TARGET).equals(p.target()), "cible : curl.exe du systeme");
        String a = p.arguments();
        check(a.equals(p.env().get(KmShortcut.ENV_ARGS)) && a.endsWith("http://127.0.0.1:47999/api/km/toggle"), "cible : route de bascule sur la boucle locale");
        check(a.contains("-X POST") && a.contains("-H \"X-CoTransfert: 1\"") && a.contains("-H \"Content-Type: application/json\"") && a.contains("-d \"{}\""),
                "cible compatible avec les gardes de SetupApi (POST, X-CoTransfert, JSON) : " + a);
        check(!a.contains("localhost") && !a.contains("Origin"), "cible : Host 127.0.0.1, pas d'Origin");
        check(a.length() < 250, "arguments du raccourci assez courts (" + a.length() + ")");

        KmShortcut.Plan ps = KmShortcut.planCreate(8080, KmSettings.Hotkey.parse("Ctrl+Maj+K"), KmShortcut.FOLDER_DESKTOP, false);
        check(ps.target().toLowerCase().endsWith("powershell.exe") && ps.arguments().contains("Invoke-RestMethod -Method Post")
                && ps.arguments().contains("http://127.0.0.1:8080/api/km/toggle") && ps.arguments().contains("'X-CoTransfert'='1'")
                && ps.arguments().contains("-ContentType 'application/json'") && ps.arguments().contains("-WindowStyle Hidden"),
                "repli sans curl.exe : powershell.exe et Invoke-RestMethod");
        check(ps.env().get(KmShortcut.ENV_FOLDER).equals("Desktop") && ps.hotkey().equals("CTRL+SHIFT+K"), "Bureau et Ctrl+Maj+K");
        check(throwsIae(() -> KmShortcut.planCreate(47999, null, KmShortcut.FOLDER_DESKTOP, true))
                && throwsIae(() -> KmShortcut.planCreate(0, hk, KmShortcut.FOLDER_DESKTOP, true))
                && throwsIae(() -> KmShortcut.planCreate(47999, hk, "System", true)), "plan refuse : pas de raccourci, port ou dossier invalide");

        KmShortcut.Plan rm = KmShortcut.planRemove();
        check(rm.command().get(6).equals(KmShortcut.REMOVE_SCRIPT) && !KmShortcut.REMOVE_SCRIPT.contains("\"")
                && KmShortcut.REMOVE_SCRIPT.contains("Remove-Item -LiteralPath $p") && rm.env().size() == 1
                && rm.env().get(KmShortcut.ENV_NAME).equals(KmShortcut.LNK_NAME), "suppression : seul notre .lnk est vise");
        check(!KmShortcut.create(47999, null).ok(), "creation sans raccourci regle : refus clair, rien n'est lance");
    }

    // =======================================================================
    //  Doublures du poste
    // =======================================================================

    private static final class FakeSink implements KmInject.Sink {
        private final List<String> log = new ArrayList<>();
        private synchronized void add(String s) { log.add(s); }
        synchronized List<String> log() { return new ArrayList<>(log); }
        synchronized int count(String s) { int n = 0; for (String l : log) if (l.equals(s)) n++; return n; }
        synchronized String last() { return log.isEmpty() ? "" : log.get(log.size() - 1); }
        synchronized void clear() { log.clear(); }
        @Override public void enter(KmSettings.Edge edge, int pos) { add("enter " + edge + " " + pos); }
        @Override public void move(int dx, int dy) { add("move " + dx + " " + dy); }
        @Override public void button(int button, boolean down) { add("button " + button + (down ? " down" : " up")); }
        @Override public void wheel(int notches) { add("wheel " + notches); }
        @Override public void key(int vk, int location, boolean down) { add("key " + vk + " @" + location + (down ? " down" : " up")); }
        @Override public void releaseAll() { add("releaseAll"); }
        @Override public int ignored() { return 0; }
    }

    private static final class FakeGrabber implements KmCapture.Grabber {
        final AtomicInteger starts = new AtomicInteger(), stops = new AtomicInteger();
        final AtomicReference<KmCapture.Listener> listener = new AtomicReference<>();
        final AtomicReference<Point> park = new AtomicReference<>();
        volatile boolean open;
        @Override public void start(KmSettings settings, KmCapture.Listener l) { listener.set(l); open = true; starts.incrementAndGet(); }
        @Override public void stop(Point p) { open = false; park.set(p); stops.incrementAndGet(); }
        void feed(KmProtocol.Event e) { listener.get().event(e); }
    }

    private static class FakeEnv implements Km.Env {
        final FakeSink sink = new FakeSink();
        final FakeGrabber grabber = new FakeGrabber();
        final AtomicLong now = new AtomicLong(100_000);
        final AtomicInteger sinks = new AtomicInteger();
        volatile Point pointer = new Point(900, 500);
        volatile boolean headless;
        @Override public boolean headless() { return headless; }
        @Override public KmInject.Sink sink() { sinks.incrementAndGet(); return sink; }
        @Override public KmCapture.Grabber grabber() { return grabber; }
        @Override public Point pointer() { return pointer; }
        @Override public Rectangle[] screens() { return ONE_SCREEN; }
        @Override public long nowMs() { return now.get(); }
        final FakeClip clip = new FakeClip();
        @Override public Km.Clip clip() { return clip; }
    }

    private static final class FakeClip implements Km.Clip {
        volatile String text;
        final AtomicInteger writes = new AtomicInteger();
        @Override public String read() { return text; }
        @Override public void write(String t) { text = t; writes.incrementAndGet(); }
    }

    private static KmSettings onClip(boolean clipboard) {
        return new KmSettings(true, KmSettings.Edge.NONE, 250, KmSettings.Hotkey.parse("Ctrl+Alt+F12"), 0, clipboard);
    }

    /** Un aller (le client pilote le hub) puis le retour, en attendant chaque etat. */
    private static void roundTrip(Rig rig) throws Exception {
        rig.clientEnv.now.addAndGet(Km.TOGGLE_GUARD_MS + 1);
        check(rig.client.toggle().ok() && rig.client.state() == Km.State.DRIVING, "presse-papiers : le client pilote");
        await(() -> rig.hub.state() == Km.State.DRIVEN, 3000, "presse-papiers : le hub est pilote");
    }

    private static void back(Rig rig) throws Exception {
        check(rig.client.toggle().ok() && rig.client.state() == Km.State.IDLE, "presse-papiers : le client rend la main");
        await(() -> rig.hub.state() == Km.State.IDLE, 3000, "presse-papiers : le hub est libere");
    }

    // =======================================================================
    //  Presse-papiers : texte seulement, au passage du controle, option des deux cotes
    // =======================================================================

    private static void testClipboard(Crypto.Key key) throws Exception {
        // Cadres : aller-retour, et tailles hors limites.
        String accents = "ligne 1 \u00e9\u00e8\u20ac \ud83d\ude00\r\nligne 2\tfin";
        java.util.List<KmProtocol.Event> frames = KmProtocol.clip(accents);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bos);
        for (KmProtocol.Event f : frames) KmProtocol.write(dos, f);
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bos.toByteArray()));
        ByteArrayOutputStream got = new ByteArrayOutputStream();
        KmProtocol.Event first = KmProtocol.read(dis);
        check(first.type() == KmProtocol.CLIP_BEGIN && first.a() == accents.getBytes(StandardCharsets.UTF_8).length, "cadre CLIP_BEGIN : taille annoncee");
        KmProtocol.Event e;
        while ((e = KmProtocol.read(dis)).type() == KmProtocol.CLIP_DATA) got.write(e.data(), 0, e.data().length);
        check(e.type() == KmProtocol.CLIP_END && new String(got.toByteArray(), StandardCharsets.UTF_8).equals(accents), "cadres du presse-papiers : aller-retour exact");
        check(KmProtocol.clip("").isEmpty() && KmProtocol.clip(null).isEmpty(), "texte vide : aucun cadre");
        check(KmProtocol.clip("x".repeat(KmProtocol.CLIP_MAX_BYTES + 1)).isEmpty(), "texte trop long : aucun cadre");
        check(KmProtocol.clip("x".repeat(KmProtocol.CLIP_MAX_BYTES)).size() == 2 + (KmProtocol.CLIP_MAX_BYTES + 254) / 255, "texte a la limite : decoupe en morceaux de 255 octets");

        // Option active des deux cotes, lien chiffre.
        try (Rig rig = new Rig(key, onClip(true), onClip(true)).serve()) {
            rig.awaitReady();
            rig.clientEnv.clip.text = accents;
            rig.hubEnv.clip.text = "ancien texte du hub";
            roundTrip(rig);
            await(() -> accents.equals(rig.hubEnv.clip.text), 3000, "aller : le texte du client arrive sur le hub");
            check(rig.hubEnv.clip.writes.get() == 1 && rig.clientEnv.clip.writes.get() == 0, "aller : une seule ecriture, sur le poste pilote");
            String big = "0123456789abcdef\u00e9".repeat(3000);
            rig.hubEnv.clip.text = big;                                  // copie faite sur le hub pendant le pilotage
            back(rig);
            await(() -> big.equals(rig.clientEnv.clip.text), 3000, "retour : le texte copie sur le hub revient sur le client (plusieurs morceaux)");
            roundTrip(rig);
            back(rig);
            check(rig.hubEnv.clip.writes.get() == 1 && rig.clientEnv.clip.writes.get() == 1, "texte inchange : rien n'est renvoye, ni a l'aller ni au retour");
            rig.clientEnv.clip.text = "x".repeat(KmProtocol.CLIP_MAX_BYTES + 1);
            roundTrip(rig);
            back(rig);
            check(big.equals(rig.hubEnv.clip.text) && rig.hubEnv.clip.writes.get() == 1, "texte trop long : pas envoye, le presse-papiers de l'autre poste reste intact");
            rig.clientEnv.clip.text = null;                              // image, fichier ou presse-papiers vide
            roundTrip(rig);
            back(rig);
            check(rig.hubEnv.clip.writes.get() == 1, "pas de texte : rien n'est envoye");
            // Taille annoncee fausse : le texte recu est ecarte.
            rig.clientLink.send(new KmProtocol.Event(KmProtocol.CLIP_BEGIN, 10, 0, false));
            rig.clientLink.send(new KmProtocol.Event(KmProtocol.CLIP_DATA, 0, 0, false, "abc".getBytes(StandardCharsets.UTF_8)));
            for (KmProtocol.Event f : KmProtocol.clip("x")) if (f.type() == KmProtocol.CLIP_END) rig.clientLink.send(f);
            roundTrip(rig);
            back(rig);
            check(rig.hubEnv.clip.writes.get() == 1, "taille annoncee non atteinte : texte ecarte");
            check(rig.client.json().contains("\"clipboard\":true") && rig.client.json().contains("\"peerClipboard\":true"), "etat : presse-papiers actif des deux cotes");
        }

        // Option active d'un seul cote : rien ne passe, dans aucun sens.
        try (Rig rig = new Rig(null, onClip(false), onClip(true)).serve()) {
            rig.awaitReady();
            rig.clientEnv.clip.text = "texte du client";
            rig.hubEnv.clip.text = "texte du hub";
            roundTrip(rig);
            // Meme si l'autre poste envoie quand meme des cadres, un poste ou l'option est coupee les ignore.
            for (KmProtocol.Event f : KmProtocol.clip("force")) rig.clientLink.send(f);
            back(rig);
            roundTrip(rig);
            back(rig);
            check(rig.hubEnv.clip.writes.get() == 0 && "texte du hub".equals(rig.hubEnv.clip.text), "option coupee sur le hub : son presse-papiers n'est jamais modifie");
            check(rig.clientEnv.clip.writes.get() == 0, "option coupee sur le hub : il n'envoie rien non plus");
            check(rig.client.json().contains("\"peerClipboard\":false"), "etat : l'autre poste n'accepte pas le presse-papiers");
        }

        // Option decochee des deux cotes : aucun envoi.
        try (Rig rig = new Rig(null, onClip(false), onClip(false)).serve()) {
            rig.awaitReady();
            rig.clientEnv.clip.text = "texte du client";
            roundTrip(rig);
            back(rig);
            check(rig.hubEnv.clip.writes.get() == 0, "option decochee : presse-papiers non partage");
        }
    }

    private static KmSettings on(KmSettings.Edge edge) {
        return new KmSettings(true, edge, 250, KmSettings.Hotkey.parse("Ctrl+Alt+F12"), 0);
    }

    /** Deux controleurs relies par un lien de boucle locale (chiffre si key non null). */
    private static final class Rig implements AutoCloseable {
        final FakeEnv hubEnv = new FakeEnv(), clientEnv = new FakeEnv();
        final Km hub = new Km(hubEnv), client = new Km(clientEnv);
        Socket hubSock, clientSock;
        KmLink hubLink, clientLink;

        Rig(Crypto.Key key, KmSettings hubSettings, KmSettings clientSettings) throws Exception {
            hub.configure(hubSettings, true, "direct", true);
            client.configure(clientSettings, false, "direct", true);
            try (ServerSocket srv = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                if (key == null) {
                    clientSock = new Socket(InetAddress.getLoopbackAddress(), srv.getLocalPort());
                    hubSock = srv.accept();
                    clientSock.setTcpNoDelay(true);
                    hubSock.setTcpNoDelay(true);
                    hubLink = new KmLink(hubSock.getInputStream(), hubSock.getOutputStream(), hubSock, "client");
                    clientLink = new KmLink(clientSock.getInputStream(), clientSock.getOutputStream(), clientSock, "hub");
                } else {
                    AtomicReference<Object> accepted = new AtomicReference<>();
                    Thread t = new Thread(() -> {
                        try { hubSock = srv.accept(); accepted.set(KmLink.accept(hubSock, key)); } catch (Exception e) { accepted.set(e); }
                    });
                    t.start();
                    clientLink = KmLink.dial("127.0.0.1", srv.getLocalPort(), key, 3000);
                    t.join(10_000);
                    if (!(accepted.get() instanceof KmLink l)) throw new IllegalStateException("accept: " + accepted.get());
                    hubLink = l;
                }
            }
        }

        Rig serve() {
            daemon(() -> hub.serve(hubLink));
            daemon(() -> client.serve(clientLink));
            return this;
        }

        void awaitReady() throws Exception {
            await(() -> hub.ready() && client.ready(), 5000, "les deux postes se sont annonces prets");
        }

        @Override public void close() {
            hubLink.close();
            clientLink.close();
        }
    }

    // =======================================================================
    //  Disponibilite : desactive par defaut, lien direct, secret, ecran
    // =======================================================================

    private static void testAvailability() {
        FakeEnv env = new FakeEnv();
        Km km = new Km(env);
        check(!km.usable() && km.state() == Km.State.IDLE && !km.toggle().ok(), "desactive par defaut : rien ne part");
        check(km.json().contains("\"enabled\":false") && km.json().contains("\"available\":false") && km.json().contains("\"state\":\"idle\""), "etat JSON par defaut : " + km.json());
        km.configure(on(KmSettings.Edge.RIGHT), false, "webdav", true);
        check(!km.usable() && km.problem().contains("lien direct"), "hors canal direct : refus");
        km.configure(on(KmSettings.Edge.RIGHT), false, "", true);
        check(!km.usable(), "aucun canal : refus");
        km.configure(on(KmSettings.Edge.RIGHT), false, "direct", false);
        check(!km.usable() && km.problem().contains("secret"), "sans secret : refus");
        env.headless = true;
        km.configure(on(KmSettings.Edge.RIGHT), false, "direct", true);
        check(!km.usable() && km.problem().contains("ecran"), "sans ecran : refus");
        Km.Result r = km.take("essai", KmProtocol.POS_NONE);
        check(!r.ok() && r.message().contains("ecran") && r.json().contains("\"ok\":false"), "prise de controle refusee proprement : " + r.json());
        km.clientLoop(() -> "127.0.0.1", 1, null);   // rend la main tout de suite : fonction inutilisable
        env.headless = false;
        km.configure(on(KmSettings.Edge.RIGHT), false, "direct", true);
        check(km.usable() && km.problem().isEmpty(), "actif + direct + secret + ecran : utilisable");
        r = km.toggle();
        check(!r.ok() && r.message().contains("pas connecte") && km.state() == Km.State.IDLE, "sans lien : refus clair");
        km.tick();
        check(env.grabber.starts.get() == 0 && env.sinks.get() == 0, "rien n'a ete ouvert ni injecte");
        String j = km.json();
        check(j.contains("\"enabled\":true") && j.contains("\"available\":true") && j.contains("\"linked\":false") && j.contains("\"edge\":\"droite\"")
                && j.contains("\"hotkey\":\"Ctrl+Alt+F12\"") && !j.toLowerCase().contains("secret"), "etat JSON sans secret : " + j);
    }

    // =======================================================================
    //  Lien : refus
    // =======================================================================

    private static void testLinkRefusals(Crypto.Key good, Crypto.Key bad) throws Exception {
        boolean refused = false;
        try { KmLink.dial("127.0.0.1", 1, null, 500); } catch (IOException e) { refused = e.getMessage().contains("secret"); }
        check(refused, "client sans secret : refus avant toute connexion");

        try (ServerSocket srv = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            // Hub sans secret : la socket est fermee, rien n'est lu.
            AtomicReference<Object> got = new AtomicReference<>();
            Thread t = new Thread(() -> { try { got.set(KmLink.accept(srv.accept(), null)); } catch (Exception e) { got.set(e); } });
            t.start();
            Object clientSide;
            try { clientSide = KmLink.dial("127.0.0.1", srv.getLocalPort(), good, 2000); } catch (IOException e) { clientSide = e; }
            t.join(10_000);
            check(got.get() instanceof IOException e && e.getMessage().contains("secret"), "hub sans secret : refus (" + got.get() + ")");
            check(clientSide instanceof IOException, "client face a un hub sans secret : pas de lien");

            // Mauvais secret : refus des deux cotes.
            got.set(null);
            t = new Thread(() -> { try { got.set(KmLink.accept(srv.accept(), good)); } catch (Exception e) { got.set(e); } });
            t.start();
            try { clientSide = KmLink.dial("127.0.0.1", srv.getLocalPort(), bad, 2000); } catch (IOException e) { clientSide = e; }
            t.join(10_000);
            check(got.get() instanceof IOException, "mauvais secret : refuse par le hub (" + got.get() + ")");
            check(clientSide instanceof IOException e && e.getMessage().contains("secret"), "mauvais secret : refuse cote client (" + clientSide + ")");

            // Une connexion de synchronisation (OP_HELLO) n'est pas un lien clavier-souris.
            got.set(null);
            t = new Thread(() -> { try { got.set(KmLink.accept(srv.accept(), good)); } catch (Exception e) { got.set(e); } });
            t.start();
            try (Socket c = new Socket(InetAddress.getLoopbackAddress(), srv.getLocalPort())) {
                Crypto.Channel ch = Crypto.handshake(good, false, c.getInputStream(), c.getOutputStream());
                Transfer.Msg m = new Transfer.Msg();
                m.op = Transfer.OP_HELLO; m.relPath = "MonProjet"; m.size = 0; m.hash = Transfer.PROTO_VERSION;
                DataOutputStream d = new DataOutputStream(ch.out);
                Transfer.writeFrame(d, m, null);
                d.flush();
                t.join(10_000);
            }
            check(got.get() instanceof IOException, "premier cadre de synchronisation : pas un lien clavier-souris");
        }

        Transfer.Msg hello = new Transfer.Msg();
        hello.op = KmLink.OP_KM; hello.relPath = KmLink.HELLO_PATH; hello.size = 0; hello.hash = KmProtocol.VERSION + 1;
        check(KmLink.isHello(hello), "cadre d'ouverture reconnu a son operation");
        refused = false;
        try { KmLink.adopt(hello, new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), () -> { }, "x"); }
        catch (IOException e) { refused = e.getMessage().contains("protocole"); }
        check(refused, "autre version du protocole : refus explicite");
        check(KmLink.OP_KM == Transfer.OP_KM && KmLink.OP_KM != SyncEngine.OP_RESOLVED && KmLink.OP_KM != SyncEngine.OP_RESOLVED_ACK,
                "operation d'ouverture distincte de celles du moteur de synchronisation");
        check(KmLink.OP_KM != Transfer.OP_FILE && KmLink.OP_KM != Transfer.OP_DELETE && KmLink.OP_KM != Transfer.OP_PING
                && KmLink.OP_KM != Transfer.OP_MANIFEST && KmLink.OP_KM != Transfer.OP_HELLO && KmLink.OP_KM != Transfer.OP_STATUS,
                "operation d'ouverture distincte de celles de la synchronisation");

        // Hub : session non chiffree refusee par le controleur.
        Km km = new Km(new FakeEnv());
        km.configure(on(KmSettings.Edge.LEFT), true, "direct", true);
        hello.hash = KmProtocol.VERSION;
        AtomicInteger closed = new AtomicInteger();
        km.serveHub(hello, new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), closed::incrementAndGet, false);
        check(closed.get() >= 1 && !km.ready(), "hub : lien clavier-souris sur session en clair refuse et ferme");
    }

    // =======================================================================
    //  Bout en bout sur lien chiffre : la sequence recue est celle envoyee
    // =======================================================================

    private static void testEndToEndEncrypted(Crypto.Key key) throws Exception {
        try (Rig rig = new Rig(key, on(KmSettings.Edge.LEFT), on(KmSettings.Edge.RIGHT)).serve()) {
            rig.awaitReady();
            check(rig.hub.state() == Km.State.IDLE && rig.client.state() == Km.State.IDLE, "au repos apres connexion");
            Km.Result r = rig.client.toggle();
            check(r.ok() && rig.client.state() == Km.State.DRIVING && rig.clientEnv.grabber.open, "le client prend le controle : capture ouverte");
            await(() -> rig.hub.state() == Km.State.DRIVEN, 3000, "le hub est pilote");
            check(rig.hubEnv.sink.log().isEmpty(), "prise par la page : le pointeur du poste pilote n'est pas deplace");
            check(rig.client.json().contains("\"state\":\"driving\"") && rig.hub.json().contains("\"state\":\"driven\""), "etats publies en JSON");

            List<KmProtocol.Event> sent = List.of(
                    KmProtocol.key(17, 2, true), KmProtocol.move(5, -3), KmProtocol.key(65, 1, true), KmProtocol.key(65, 1, false),
                    KmProtocol.key(17, 2, false), KmProtocol.button(1, true), KmProtocol.move(-200, 40), KmProtocol.button(1, false),
                    KmProtocol.wheel(-1), KmProtocol.button(4, true), KmProtocol.button(4, false), KmProtocol.button(5, true),
                    KmProtocol.button(5, false), KmProtocol.wheel(3), KmProtocol.key(9, 1, true), KmProtocol.key(9, 1, false),
                    KmProtocol.move(32767, -32767), KmProtocol.key(0xFF7E, 3, true), KmProtocol.key(0xFF7E, 3, false));
            List<String> expected = List.of(
                    "key 17 @2 down", "move 5 -3", "key 65 @1 down", "key 65 @1 up", "key 17 @2 up", "button 1 down", "move -200 40",
                    "button 1 up", "wheel -1", "button 4 down", "button 4 up", "button 5 down", "button 5 up", "wheel 3",
                    "key 9 @1 down", "key 9 @1 up", "move 32767 -32767", "key 65406 @3 down", "key 65406 @3 up");
            for (KmProtocol.Event e : sent) rig.clientEnv.grabber.feed(e);
            await(() -> rig.hubEnv.sink.log().size() >= expected.size(), 3000, "tous les evenements sont arrives");
            check(rig.hubEnv.sink.log().equals(expected), "sequence recue identique a la sequence envoyee : " + rig.hubEnv.sink.log());
            check(rig.clientEnv.sink.log().isEmpty() && rig.clientEnv.sinks.get() == 0, "rien n'est injecte sur le poste qui pilote");

            // Beaucoup de petits deplacements : fusionnes en file ou non, la somme est exacte.
            rig.hubEnv.sink.clear();
            for (int i = 0; i < 2000; i++) rig.clientEnv.grabber.feed(KmProtocol.move(3, -1));
            rig.clientEnv.grabber.feed(KmProtocol.key(27, 1, true));
            await(() -> rig.hubEnv.sink.last().startsWith("key 27"), 5000, "rafale de deplacements arrivee");
            int sx = 0, sy = 0;
            for (String l : rig.hubEnv.sink.log()) if (l.startsWith("move ")) { String[] p = l.split(" "); sx += Integer.parseInt(p[1]); sy += Integer.parseInt(p[2]); }
            check(sx == 6000 && sy == -2000, "rafale de deplacements : somme exacte (" + sx + "," + sy + ")");

            // Evenement injecte par le poste pilote vers le pilote : ignore (un seul sens a la fois).
            rig.hubLink.send(KmProtocol.key(66, 1, true));
            // Retour par le raccourci vu par la fenetre de capture.
            rig.clientEnv.grabber.listener.get().ended("raccourci");
            check(rig.client.state() == Km.State.IDLE && !rig.clientEnv.grabber.open, "raccourci pendant la capture : controle rendu, capture fermee");
            await(() -> rig.hub.state() == Km.State.IDLE, 3000, "le hub n'est plus pilote");
            check(rig.hubEnv.sink.last().equals("releaseAll"), "rendu du controle : tout est relache sur le poste pilote");
            check(rig.clientEnv.sink.log().isEmpty(), "le poste qui pilotait n'a rien injecte");
            int before = rig.hubEnv.sink.log().size();
            rig.clientEnv.grabber.feed(KmProtocol.key(65, 1, true));    // evenement tardif de l'ancienne capture
            Thread.sleep(150);
            check(rig.hubEnv.sink.log().size() == before, "evenement d'une capture fermee : jamais transmis");

            // L'autre sens : le hub pilote le client.
            rig.hubEnv.now.addAndGet(5000);
            check(rig.hub.toggle().ok() && rig.hub.state() == Km.State.DRIVING, "le hub prend le controle a son tour");
            await(() -> rig.client.state() == Km.State.DRIVEN, 3000, "le client est pilote");
            rig.hubEnv.grabber.feed(KmProtocol.key(72, 1, true));
            await(() -> rig.clientEnv.sink.last().equals("key 72 @1 down"), 3000, "frappe du hub recue par le client");
            // Perte de focus de la fenetre de capture (touche reservee par Windows).
            rig.hubEnv.grabber.listener.get().ended("focus perdu");
            await(() -> rig.client.state() == Km.State.IDLE && rig.clientEnv.sink.last().equals("releaseAll"), 3000,
                    "focus perdu : capture arretee, aucune touche ne reste enfoncee sur l'autre poste");
            check(rig.hub.state() == Km.State.IDLE && rig.hubEnv.grabber.stops.get() == 1, "focus perdu : capture fermee une fois");
        }
    }

    // =======================================================================
    //  Accept du hub : la boucle d'acceptation de Transfer et la branche d'Agent
    // =======================================================================

    private static void testHubAccept(Crypto.Key key) throws Exception {
        FakeEnv henv = new FakeEnv(), cenv = new FakeEnv();
        Km hub = new Km(henv), client = new Km(cenv);
        hub.configure(on(KmSettings.Edge.LEFT), true, "direct", true);
        client.configure(on(KmSettings.Edge.RIGHT), false, "direct", true);
        AtomicInteger sync = new AtomicInteger();
        AtomicReference<String> syncName = new AtomicReference<>("");
        // Meme enchainement que Agent.hubDirect, sur un port libre : premier cadre, puis aiguillage.
        Transfer.SessionHandler handler = s -> {
            try {
                Transfer.Msg first = s.recvHeader();
                if (Agent.serveKm(hub, s, first, key != null)) return;
                if (first.op == Transfer.OP_HELLO) { syncName.set(first.relPath); sync.incrementAndGet(); }
                Thread.sleep(300);
            } catch (IOException | InterruptedException e) {
                // session terminee
            } finally {
                s.close();
            }
        };
        try (ServerSocket srv = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            daemon(() -> { try { Transfer.acceptLoop(srv, handler, key); } catch (IOException closed) { /* fin de l'essai */ } });
            KmLink link = KmLink.dial("127.0.0.1", srv.getLocalPort(), key, 3000);
            daemon(() -> client.serve(link));
            await(() -> hub.ready() && client.ready(), 5000, "accept du hub : le lien clavier-souris est reconnu sur le port de la synchronisation");
            check(client.toggle().ok(), "accept du hub : prise de controle");
            await(() -> hub.state() == Km.State.DRIVEN, 3000, "accept du hub : le hub est pilote");
            cenv.grabber.feed(KmProtocol.key(65, 1, true));
            await(() -> henv.sink.last().equals("key 65 @1 down"), 3000, "accept du hub : les frappes arrivent");

            // Une connexion de synchronisation sur le meme port n'est pas detournee, et ne gene pas le lien.
            try (Socket c = new Socket(InetAddress.getLoopbackAddress(), srv.getLocalPort())) {
                Crypto.Channel ch = Crypto.handshake(key, false, c.getInputStream(), c.getOutputStream());
                Transfer.Msg m = new Transfer.Msg();
                m.op = Transfer.OP_HELLO; m.relPath = "MonProjet"; m.size = 0; m.hash = Transfer.PROTO_VERSION;
                DataOutputStream d = new DataOutputStream(ch.out);
                Transfer.writeFrame(d, m, null);
                d.flush();
                await(() -> sync.get() == 1, 3000, "accept du hub : la synchronisation garde son premier cadre");
            }
            check(syncName.get().equals("MonProjet") && hub.state() == Km.State.DRIVEN && !link.closed(), "accept du hub : le lien clavier-souris n'est pas perturbe");
            link.close();
            await(() -> hub.state() == Km.State.IDLE && henv.sink.last().equals("releaseAll") && !hub.ready(), 3000,
                    "accept du hub : fin du lien, tout est relache");
        }
        Transfer.Msg other = new Transfer.Msg();
        other.op = Transfer.OP_HELLO;
        check(!Agent.serveKm(hub, null, other, true), "un cadre de synchronisation n'est jamais pris pour un lien clavier-souris");

        // Hub sans secret (sessions en clair du lien direct) : un cadre d'ouverture clavier-souris est refuse.
        Km plain = new Km(new FakeEnv());
        plain.configure(on(KmSettings.Edge.LEFT), true, "direct", false);
        AtomicInteger seen = new AtomicInteger();
        try (ServerSocket srv = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            daemon(() -> {
                try {
                    Transfer.acceptLoop(srv, s -> {
                        try { if (Agent.serveKm(plain, s, s.recvHeader(), false)) seen.incrementAndGet(); } catch (IOException e) { s.close(); }
                    }, null);
                } catch (IOException closed) { /* fin de l'essai */ }
            });
            try (Socket c = new Socket(InetAddress.getLoopbackAddress(), srv.getLocalPort())) {
                Transfer.Msg m = new Transfer.Msg();
                m.op = KmLink.OP_KM; m.relPath = KmLink.HELLO_PATH; m.size = 0; m.hash = KmProtocol.VERSION;
                DataOutputStream d = new DataOutputStream(c.getOutputStream());
                Transfer.writeFrame(d, m, null);
                d.flush();
                c.setSoTimeout(5000);
                int b;
                try { b = c.getInputStream().read(); } catch (IOException reset) { b = -1; }
                check(b == -1, "lien clavier-souris en clair : connexion fermee par le hub, rien n'est emis");
            }
            await(() -> seen.get() == 1, 3000, "lien clavier-souris en clair : reconnu puis refuse");
            check(!plain.ready() && plain.state() == Km.State.IDLE, "lien clavier-souris en clair : jamais etabli");
        }
    }

    // =======================================================================
    //  Bascule
    // =======================================================================

    private static void testToggle() throws Exception {
        try (Rig rig = new Rig(null, on(KmSettings.Edge.NONE), on(KmSettings.Edge.NONE)).serve()) {
            rig.awaitReady();
            check(rig.client.toggle().ok() && rig.client.state() == Km.State.DRIVING, "bascule : inactif -> je pilote");
            await(() -> rig.hub.state() == Km.State.DRIVEN, 3000, "bascule : l'autre est pilote");
            check(!rig.client.take("encore", KmProtocol.POS_NONE).ok() && rig.clientEnv.grabber.starts.get() == 1, "deja en train de piloter : seconde prise refusee");
            check(!rig.hub.take("croise", KmProtocol.POS_NONE).ok() && rig.hubEnv.grabber.starts.get() == 0, "poste pilote : ne peut pas prendre le controle");
            check(rig.client.toggle().ok() && rig.client.state() == Km.State.IDLE, "bascule : je pilote -> inactif");
            await(() -> rig.hub.state() == Km.State.IDLE, 3000, "bascule : l'autre est libere");
            check(rig.client.toggle().ok() && rig.client.state() == Km.State.IDLE, "bascule repetee juste apres un retour : ignoree (raccourci vu deux fois)");
            rig.clientEnv.now.addAndGet(Km.TOGGLE_GUARD_MS + 1);
            check(rig.client.toggle().ok() && rig.client.state() == Km.State.DRIVING, "bascule apres le delai de garde : je pilote de nouveau");
            await(() -> rig.hub.state() == Km.State.DRIVEN, 3000, "l'autre est pilote de nouveau");
            // Bascule depuis le poste pilote : il demande au pilote de rendre le controle.
            check(rig.hub.toggle().ok(), "bascule sur le poste pilote : retour demande");
            await(() -> rig.client.state() == Km.State.IDLE && rig.hub.state() == Km.State.IDLE, 3000, "retour demande par le poste pilote : tout le monde inactif");
            check(rig.clientEnv.grabber.stops.get() == 2 && rig.hubEnv.sink.count("releaseAll") == 2, "deux allers-retours : deux fermetures, deux relachements");
            check(rig.client.giveBack("rien").ok() && rig.client.state() == Km.State.IDLE, "rendre sans piloter : sans effet");
        }
    }

    // =======================================================================
    //  Deux prises de controle en meme temps : le hub garde la main
    // =======================================================================

    private static void testSimultaneous() throws Exception {
        for (int round = 0; round < 5; round++) {
            try (Rig rig = new Rig(null, on(KmSettings.Edge.NONE), on(KmSettings.Edge.NONE)).serve()) {
                rig.awaitReady();
                final boolean clientFirst = round % 2 == 0;
                Thread a = new Thread(() -> (clientFirst ? rig.client : rig.hub).take("ensemble", KmProtocol.POS_NONE));
                Thread b = new Thread(() -> (clientFirst ? rig.hub : rig.client).take("ensemble", KmProtocol.POS_NONE));
                a.start(); b.start();
                a.join(); b.join();
                await(() -> (rig.hub.state() == Km.State.DRIVING && rig.client.state() == Km.State.DRIVEN)
                        || (rig.client.state() == Km.State.DRIVING && rig.hub.state() == Km.State.DRIVEN), 3000,
                        "prises simultanees : un seul pilote (tour " + round + ")");
                Thread.sleep(100);
                boolean hubDrives = rig.hub.state() == Km.State.DRIVING;
                check(hubDrives ? rig.client.state() == Km.State.DRIVEN && !rig.clientEnv.grabber.open && rig.hubEnv.grabber.open
                                : rig.hub.state() == Km.State.DRIVEN && !rig.hubEnv.grabber.open && rig.clientEnv.grabber.open,
                        "prises simultanees : etat stable, une seule capture ouverte (tour " + round + ")");
                if (rig.hubEnv.grabber.starts.get() == 1 && rig.clientEnv.grabber.starts.get() == 1)
                    check(hubDrives, "prises croisees : le hub garde la main, le client cede");
                (hubDrives ? rig.hub : rig.client).giveBack("fin");
                await(() -> rig.hub.state() == Km.State.IDLE && rig.client.state() == Km.State.IDLE, 3000, "retour au repos");
            }
        }
        // Croisement force : chacun envoie GRAB avant d'avoir lu celui de l'autre.
        try (Rig rig = new Rig(null, on(KmSettings.Edge.NONE), on(KmSettings.Edge.NONE))) {
            Thread th = daemon(() -> rig.hub.serve(rig.hubLink));
            Thread tc = daemon(() -> rig.client.serve(rig.clientLink));
            rig.awaitReady();
            synchronized (rig.hub) { synchronized (rig.client) {
                // Les deux controleurs sont figes : aucun ne traite le GRAB de l'autre avant d'avoir emis le sien.
                check(rig.hub.take("croise", KmProtocol.POS_NONE).ok() && rig.client.take("croise", KmProtocol.POS_NONE).ok(), "les deux prises partent");
                check(rig.hub.state() == Km.State.DRIVING && rig.client.state() == Km.State.DRIVING, "un instant, les deux pilotent");
            } }
            await(() -> rig.hub.state() == Km.State.DRIVING && rig.client.state() == Km.State.DRIVEN, 3000, "croisement : le hub pilote, le client est pilote");
            check(rig.hubEnv.grabber.open && !rig.clientEnv.grabber.open && rig.clientEnv.grabber.stops.get() == 1, "croisement : le client a ferme sa capture");
            rig.hubEnv.grabber.feed(KmProtocol.key(65, 1, true));
            await(() -> rig.clientEnv.sink.last().equals("key 65 @1 down"), 3000, "croisement : les frappes du hub arrivent au client");
            check(rig.hubEnv.sink.log().isEmpty(), "croisement : rien n'a ete injecte sur le hub");
            check(th.isAlive() && tc.isAlive(), "croisement : le lien tient");
        }
    }

    // =======================================================================
    //  Bord d'ecran : aller par le bord, retour par le bord oppose du poste pilote
    // =======================================================================

    private static void testEdgeRoundTrip() throws Exception {
        // Le hub est a droite du client : bord droit du client, bord gauche du hub.
        try (Rig rig = new Rig(null, on(KmSettings.Edge.LEFT), on(KmSettings.Edge.RIGHT)).serve()) {
            rig.awaitReady();
            FakeEnv c = rig.clientEnv, h = rig.hubEnv;
            c.pointer = new Point(900, 500);
            rig.client.tick();
            c.pointer = new Point(0, 500);                      // mauvais bord
            for (int i = 0; i < 10; i++) { c.now.addAndGet(100); rig.client.tick(); }
            check(rig.client.state() == Km.State.IDLE, "mauvais bord : rien ne part");
            c.pointer = new Point(1919, 540);
            rig.client.tick();
            c.now.addAndGet(200);
            rig.client.tick();
            check(rig.client.state() == Km.State.IDLE, "bord pousse 200 ms : trop tot (delai 250 ms)");
            c.now.addAndGet(60);
            rig.client.tick();
            check(rig.client.state() == Km.State.DRIVING && c.grabber.open, "bord pousse 260 ms : prise de controle");
            await(() -> rig.hub.state() == Km.State.DRIVEN, 3000, "le hub est pilote");
            int pos = (int) (540L * KmProtocol.POS_MAX / 1079);
            check(h.sink.log().equals(List.of("enter LEFT " + pos)), "le pointeur entre par le bord oppose, a la meme hauteur : " + h.sink.log());
            rig.client.tick();
            check(c.grabber.starts.get() == 1, "pendant que je pilote, le bord ne declenche plus rien");

            // Sur le poste pilote : bouton enfonce = pas de retour (glisser-deposer vers le bord).
            c.grabber.feed(KmProtocol.button(1, true));
            await(() -> h.sink.last().equals("button 1 down"), 3000, "bouton enfonce sur le poste pilote");
            h.pointer = new Point(400, 300);
            rig.hub.tick();
            h.pointer = new Point(0, 300);
            for (int i = 0; i < 6; i++) { h.now.addAndGet(100); rig.hub.tick(); }
            Thread.sleep(150);
            check(rig.client.state() == Km.State.DRIVING, "bord oppose pousse avec un bouton enfonce : pas de retour");
            c.grabber.feed(KmProtocol.button(1, false));
            await(() -> h.sink.last().equals("button 1 up"), 3000, "bouton relache");
            for (int i = 0; i < 6; i++) { h.now.addAndGet(100); rig.hub.tick(); }
            Thread.sleep(100);
            check(rig.client.state() == Km.State.DRIVING, "bouton relache contre le bord : il faut quitter le bord puis le repousser");
            h.pointer = new Point(400, 300);
            rig.hub.tick();
            h.pointer = new Point(0, 300);
            rig.hub.tick();
            h.now.addAndGet(100);
            rig.hub.tick();
            Thread.sleep(100);
            check(rig.client.state() == Km.State.DRIVING, "bord oppose pousse 100 ms : trop tot");
            h.now.addAndGet(200);
            rig.hub.tick();
            await(() -> rig.client.state() == Km.State.IDLE, 3000, "bord oppose pousse sur le poste pilote : le pilote rend le controle");
            await(() -> rig.hub.state() == Km.State.IDLE && h.sink.last().equals("releaseAll"), 3000, "retour : le hub est libere, tout est relache");
            Point park = c.grabber.park.get();
            check(park != null && park.x == 1919 - KmEdge.INSET && Math.abs(park.y - 300) <= 1, "retour : le pointeur revient pres du bord, a la meme hauteur : " + park);
            // Le pointeur repose n'est pas contre le bord : pas de nouveau depart sans nouvelle poussee.
            c.pointer = park;
            for (int i = 0; i < 10; i++) { c.now.addAndGet(100); rig.client.tick(); }
            check(rig.client.state() == Km.State.IDLE && c.grabber.starts.get() == 1, "apres le retour : pas de nouveau depart sans nouvelle poussee");
            // Le hub, lui, a toujours son pointeur contre son bord gauche : il ne doit pas partir tout seul.
            for (int i = 0; i < 10; i++) { h.now.addAndGet(100); rig.hub.tick(); }
            check(rig.hub.state() == Km.State.IDLE && h.grabber.starts.get() == 0, "le poste libere ne prend pas le controle sans quitter puis repousser son bord");
            // Puis le hub pousse vraiment son bord gauche : il pilote le client, qui entre par la droite.
            h.pointer = new Point(300, 300);
            rig.hub.tick();
            h.pointer = new Point(0, 0);
            rig.hub.tick();
            h.now.addAndGet(300);
            rig.hub.tick();
            check(rig.hub.state() == Km.State.DRIVING, "le hub part par son bord gauche");
            await(() -> rig.client.state() == Km.State.DRIVEN && c.sink.last().equals("enter RIGHT 0"), 3000, "le client est pilote, entree par la droite en haut");
            rig.hub.giveBack("fin");
            await(() -> rig.client.state() == Km.State.IDLE, 3000, "fin");
            Point back = h.grabber.park.get();
            check(back != null && back.x == KmEdge.INSET && back.y == 0, "retour par le raccourci : pointeur repose a l'interieur du bord de depart : " + back);
        }
    }

    // =======================================================================
    //  Coupure du lien
    // =======================================================================

    private static void testLinkCut() throws Exception {
        try (Rig rig = new Rig(null, on(KmSettings.Edge.NONE), on(KmSettings.Edge.NONE)).serve()) {
            rig.awaitReady();
            rig.client.toggle();
            await(() -> rig.hub.state() == Km.State.DRIVEN, 3000, "le hub est pilote");
            rig.clientEnv.grabber.feed(KmProtocol.key(16, 2, true));
            rig.clientEnv.grabber.feed(KmProtocol.button(1, true));
            await(() -> rig.hubEnv.sink.last().equals("button 1 down"), 3000, "touche et bouton enfonces sur le hub");
            rig.clientSock.close();                               // coupure brutale, sans RELEASE
            await(() -> rig.hub.state() == Km.State.IDLE && rig.hubEnv.sink.last().equals("releaseAll"), 3000,
                    "lien coupe : tout est relache sur le poste pilote, retour a l'etat inactif");
            await(() -> rig.client.state() == Km.State.IDLE && !rig.clientEnv.grabber.open, 3000, "lien coupe : la capture est fermee, retour a l'etat inactif");
            check(!rig.hub.ready() && !rig.client.ready() && rig.hub.json().contains("\"linked\":false"), "lien coupe : plus de lien annonce");
            rig.clientEnv.now.addAndGet(5000);
            check(!rig.client.toggle().ok() && rig.client.state() == Km.State.IDLE, "sans lien : prise de controle refusee");
        }
        // Coupure cote poste pilote : le pilote ferme sa capture.
        try (Rig rig = new Rig(null, on(KmSettings.Edge.NONE), on(KmSettings.Edge.NONE)).serve()) {
            rig.awaitReady();
            rig.hub.toggle();
            await(() -> rig.client.state() == Km.State.DRIVEN, 3000, "le client est pilote");
            rig.clientSock.close();
            await(() -> rig.hub.state() == Km.State.IDLE && !rig.hubEnv.grabber.open, 3000, "poste pilote disparu : la capture se ferme");
            await(() -> rig.client.state() == Km.State.IDLE && rig.clientEnv.sink.last().equals("releaseAll"), 3000, "poste pilote : tout relache a la coupure");
        }
        // Une nouvelle connexion remplace l'ancienne (reconnexion du client).
        try (Rig a = new Rig(null, on(KmSettings.Edge.NONE), on(KmSettings.Edge.NONE)).serve()) {
            a.awaitReady();
            a.client.toggle();
            await(() -> a.hub.state() == Km.State.DRIVEN, 3000, "pilote sur le premier lien");
            try (ServerSocket srv = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                 Socket c2 = new Socket(InetAddress.getLoopbackAddress(), srv.getLocalPort()); Socket h2 = srv.accept()) {
                KmLink second = new KmLink(h2.getInputStream(), h2.getOutputStream(), h2, "client");
                daemon(() -> a.hub.serve(second));
                await(() -> a.hubLink.closed(), 3000, "nouveau lien : l'ancien est ferme");
                check(a.hub.state() == Km.State.IDLE && a.hubEnv.sink.last().equals("releaseAll"), "nouveau lien : l'ancien controle est relache");
                DataInputStream in = new DataInputStream(c2.getInputStream());
                c2.setSoTimeout(3000);
                check(KmProtocol.read(in).type() == KmProtocol.HELLO, "nouveau lien : le hub s'annonce");
                second.close();
            }
        }
    }

    // =======================================================================
    //  Battement de coeur absent
    // =======================================================================

    private static void testHeartbeatLost() throws Exception {
        FakeEnv env = new FakeEnv();
        Km hub = new Km(env);
        hub.configure(on(KmSettings.Edge.NONE), true, "direct", true);
        try (ServerSocket srv = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             Socket peer = new Socket(InetAddress.getLoopbackAddress(), srv.getLocalPort()); Socket mine = srv.accept()) {
            KmLink link = new KmLink(mine.getInputStream(), mine.getOutputStream(), mine, "client").timing(50, 400);
            AtomicReference<String> why = new AtomicReference<>();
            daemon(() -> why.set(hub.serve(link)));
            DataOutputStream out = new DataOutputStream(peer.getOutputStream());
            DataInputStream in = new DataInputStream(peer.getInputStream());
            peer.setSoTimeout(3000);
            check(KmProtocol.read(in).equals(KmProtocol.hello(KmProtocol.FLAG_ACCEPTS | KmProtocol.FLAG_HUB | KmProtocol.FLAG_CLIPBOARD)), "le hub s'annonce : version, accepte, hub, presse-papiers (defaut)");
            check(KmProtocol.read(in).type() == KmProtocol.HEARTBEAT, "le hub emet des battements de coeur");
            // Le pair reste vivant un moment grace a ses battements, au-dela du delai de silence.
            KmProtocol.write(out, KmProtocol.hello(KmProtocol.FLAG_ACCEPTS));
            KmProtocol.write(out, KmProtocol.grab(KmProtocol.EDGE_NONE, KmProtocol.POS_NONE));
            KmProtocol.write(out, KmProtocol.key(17, 2, true));
            out.flush();
            for (int i = 0; i < 8; i++) {
                Thread.sleep(100);
                KmProtocol.write(out, KmProtocol.heartbeat());
                out.flush();
            }
            check(hub.state() == Km.State.DRIVEN && env.sink.last().equals("key 17 @2 down") && !link.closed(),
                    "battements recus : le lien tient au-dela du delai de silence, la touche reste enfoncee");
            // Puis le pair se tait, sans fermer la connexion.
            long t0 = System.nanoTime();
            await(() -> hub.state() == Km.State.IDLE, 3000, "battement de coeur absent : retour a l'etat inactif");
            long ms = (System.nanoTime() - t0) / 1_000_000;
            check(env.sink.last().equals("releaseAll") && env.sink.count("releaseAll") == 1, "battement de coeur absent : tout est relache, une fois");
            await(() -> why.get() != null, 3000, "le lien est termine");
            check(why.get().contains("battement"), "raison de la fermeture : " + why.get());
            check(ms >= 250 && ms < 2000, "delai de silence respecte (" + ms + " ms pour 400 ms regles)");
            check(link.closed() && !link.send(KmProtocol.heartbeat()), "lien ferme : plus aucun envoi");
        }
        check(KmLink.HEARTBEAT_MS == 500 && KmLink.TIMEOUT_MS == 2000, "valeurs reelles : battement 500 ms, silence 2 s");
    }

    // =======================================================================
    //  L'autre poste refuse
    // =======================================================================

    private static void testPeerRefuses() throws Exception {
        // Hub ou la fonction est desactivee : il garde le lien mais refuse d'etre pilote.
        try (Rig rig = new Rig(null, KmSettings.DEFAULT, on(KmSettings.Edge.RIGHT)).serve()) {
            await(() -> rig.client.json().contains("\"linked\":true") && rig.hub.json().contains("\"linked\":true"), 3000, "lien etabli");
            Thread.sleep(200);
            check(!rig.client.ready() && rig.client.json().contains("\"peerReady\":false"), "l'autre poste n'accepte pas d'etre pilote");
            Km.Result r = rig.client.toggle();
            check(!r.ok() && r.message().contains("autre poste") && rig.clientEnv.grabber.starts.get() == 0, "prise refusee sans ouvrir la capture : " + r.message());
            rig.clientEnv.pointer = new Point(900, 500);
            rig.client.tick();
            rig.clientEnv.pointer = new Point(1919, 500);
            for (int i = 0; i < 10; i++) { rig.clientEnv.now.addAndGet(100); rig.client.tick(); }
            check(rig.client.state() == Km.State.IDLE && rig.clientEnv.grabber.starts.get() == 0, "bord d'ecran sans effet quand l'autre poste refuse");
            // Un pair qui insiste recoit un refus explicite, et rien n'est injecte.
            rig.clientLink.send(KmProtocol.grab(KmProtocol.EDGE_NONE, KmProtocol.POS_NONE));
            rig.clientLink.send(KmProtocol.key(65, 1, true));
            Thread.sleep(300);
            check(rig.hub.state() == Km.State.IDLE && rig.hubEnv.sinks.get() == 0, "poste desactive : jamais pilote, injection jamais creee");
        }
        // Le poste pilote ne peut pas creer son injection : il refuse, le pilote ferme sa capture.
        FakeEnv broken = new FakeEnv() {
            @Override public KmInject.Sink sink() { throw new IllegalStateException("java.awt.Robot indisponible"); }
        };
        Km hub = new Km(broken);
        hub.configure(on(KmSettings.Edge.NONE), true, "direct", true);
        FakeEnv cenv = new FakeEnv();
        Km client = new Km(cenv);
        client.configure(on(KmSettings.Edge.NONE), false, "direct", true);
        try (ServerSocket srv = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             Socket cs = new Socket(InetAddress.getLoopbackAddress(), srv.getLocalPort()); Socket hs = srv.accept()) {
            daemon(() -> hub.serve(new KmLink(hs.getInputStream(), hs.getOutputStream(), hs, "client")));
            daemon(() -> client.serve(new KmLink(cs.getInputStream(), cs.getOutputStream(), cs, "hub")));
            await(() -> client.ready() && hub.ready(), 3000, "pret");
            check(client.toggle().ok() && cenv.grabber.open, "le client tente la prise");
            await(() -> client.state() == Km.State.IDLE && !cenv.grabber.open, 3000, "refus de l'autre poste : la capture se ferme");
            check(hub.state() == Km.State.IDLE && client.json().contains("refuse par l'autre poste"), "refus explique dans l'etat : " + client.json());
        }
    }

    // =======================================================================
    //  Outils
    // =======================================================================

    private interface Risky { void run() throws Exception; }

    private static Thread daemon(Risky r) {
        Thread t = new Thread(() -> {
            try { r.run(); } catch (Exception e) { System.out.println("fil d'essai: " + e); }
        }, "km-test");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static boolean throwsIae(Runnable r) {
        try { r.run(); return false; } catch (IllegalArgumentException e) { return true; }
    }

    private static void await(BooleanSupplier cond, long ms, String what) throws InterruptedException {
        long end = System.nanoTime() + ms * 1_000_000;
        while (!cond.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        check(cond.getAsBoolean(), what);
    }

    private static void check(boolean cond, String what) {
        CHECKS.incrementAndGet();
        if (!cond) throw new AssertionError(what);
    }
}
