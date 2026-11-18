package cotransfert;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.zip.CRC32C;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Tests du transport boite aux lettres (MailboxSession, Valise). Simple main,
 * sans dependance : code de sortie non nul en cas d'echec.
 *
 * Usage : java -cp <classes> cotransfert.MailboxTest <dossier de travail>
 */
public final class MailboxTest {

    private static Path work;
    private static final Random RND = new Random(42);

    public static void main(String[] args) throws Exception {
        if (args.length < 1) { System.err.println("usage: MailboxTest <dossier de travail>"); System.exit(2); }
        work = Paths.get(args[0]).toAbsolutePath().normalize();
        Thread dog = new Thread(() -> {
            try { Thread.sleep(240_000); } catch (InterruptedException e) { return; }
            System.err.println("ECHEC: delai global depasse (blocage)");
            Runtime.getRuntime().halt(3);
        }, "watchdog");
        dog.setDaemon(true);
        dog.start();
        try {
            wipe(work);
            Files.createDirectories(work);
            testFlux();
            testCodec();
            testRelaisAbsentEtReprises();
            testFermetureConcurrente();
            testAbandon();
            testResolution();
            testMoteur();
            testValise();
            wipe(work);
            System.out.println("MAILBOX TEST: OK");
            System.exit(0);
        } catch (Throwable t) {
            System.err.println("MAILBOX TEST: ECHEC");
            t.printStackTrace();
            System.exit(1);
        }
    }

    // =======================================================================
    //  1. Flux ordonne, cadre plus gros qu'un lot, compression
    // =======================================================================

    private static void testFlux() throws Exception {
        step("1. flux ordonne, gros cadre, compression");
        Path d = work.resolve("t1");
        Path relay = Files.createDirectories(d.resolve("relais"));
        MailboxSession hub = new MailboxSession("doc", "hub", d.resolve("spool-hub"), relay.toString(), opts(2048));

        List<byte[]> sent = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            byte[] b;
            if (i == 7) b = text(50_000);              // 25x la taille d'un lot, compressible
            else if (i == 15) b = random(9_000);       // incompressible, s'etale aussi
            else if (i % 5 == 0) b = new byte[0];
            else b = text(RND.nextInt(600));
            sent.add(b);
            send(hub, "f" + i, b);
        }
        Path hubOut = relay.resolve("doc").resolve("hub-vers-client");
        waitFor(() -> hub.outboundDrained() || (lots(hubOut) > 5 && lots(d.resolve("spool-hub/out")) == lots(hubOut)),
                10_000, "depot des lots");
        Thread.sleep(400);
        boolean compressed = false, plain = false;
        int n = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(hubOut, "*.lot")) {
            for (Path p : ds) {
                n++;
                check(Files.size(p) <= 2048, "lot trop gros: " + p + " = " + Files.size(p));
                int flags = Files.readAllBytes(p)[5];
                if ((flags & MailboxSession.F_COMPRESSED) != 0) compressed = true; else plain = true;
            }
        }
        check(n > 5, "le flux devait etre decoupe en plusieurs lots: " + n);
        check(compressed && plain, "attendu des lots compresses et des lots bruts");
        check(noPart(relay), "fichier .part oublie sur le relais");

        MailboxSession cli = new MailboxSession("doc", "client", d.resolve("spool-cli"), relay.toString(), opts(2048));
        // sens inverse en meme temps
        List<byte[]> back = new ArrayList<>();
        for (int i = 0; i < 5; i++) { byte[] b = text(300 + i); back.add(b); send(cli, "r" + i, b); }
        for (int i = 0; i < 30; i++) expect(cli, "f" + i, sent.get(i));
        for (int i = 0; i < 5; i++) expect(hub, "r" + i, back.get(i));

        // purge apres acquittement : l'ack part quand le cadre suivant est demande
        Thread drainC = blockedReader(cli), drainH = blockedReader(hub);
        waitFor(() -> hub.outboundDrained() && cli.outboundDrained(), 10_000, "acquittement et purge");
        waitFor(() -> lots(hubOut) == 0 && lots(d.resolve("spool-hub/out")) == 0, 5_000, "purge du relais et du spool");
        hub.close(); cli.close();
        hub.close();                                    // idempotent
        drainC.join(5000); drainH.join(5000);
        check(!drainC.isAlive() && !drainH.isAlive(), "recvHeader encore bloque apres close");
        try { send(hub, "x", new byte[1]); fail("sendFile apres close devait echouer"); } catch (IOException ok) { }

        // oneShot : fin de flux des qu'il n'y a plus de lot complet
        MailboxSession.Options o = opts(2048);
        o.oneShot = true;
        MailboxSession one = new MailboxSession("doc", "client", d.resolve("spool-cli"), relay.toString(), o);
        try { one.recvHeader(); fail("oneShot devait lever EOFException"); } catch (EOFException ok) { }
        check(!one.interactive() && "relais".equals(one.channel()), "interactive/channel");
        one.close();
    }

    // =======================================================================
    //  1b. Codec : aller-retour, mauvaise cle, lot en clair refuse
    // =======================================================================

    private static void testCodec() throws Exception {
        step("1b. codec");
        Path d = work.resolve("t1b");
        Path relay = Files.createDirectories(d.resolve("relais"));
        MailboxSession.Options oh = opts(4096); oh.codec = new XorCodec(0x5a);
        MailboxSession hub = new MailboxSession("doc", "hub", d.resolve("sh"), relay.toString(), oh);
        byte[] a = text(10_000), b = random(3000);
        send(hub, "a", a); send(hub, "b", b);
        hub.close();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(relay.resolve("doc/hub-vers-client"), "*.lot")) {
            for (Path p : ds) check(Files.size(p) <= 4096, "lot scelle trop gros: " + Files.size(p));
        }

        MailboxSession.Options bad = opts(4096); bad.codec = new XorCodec(0x11); bad.oneShot = true;
        MailboxSession wrong = new MailboxSession("doc", "client", d.resolve("s-bad"), relay.toString(), bad);
        try { wrong.recvHeader(); fail("mauvaise cle acceptee"); }
        catch (EOFException e) { fail("mauvaise cle prise pour une fin de flux"); }
        catch (IOException ok) { }
        check(wrong.fatalError().contains("secret"), "cause lisible attendue: " + wrong.fatalError());
        wrong.close();
        // Lots du hub encore dans son spool (non acquittes) : lisibles avec sa cle, pas avec une autre.
        check(!MailboxSession.outboundStale(d.resolve("sh"), new XorCodec(0x5a)), "spool scelle avec la bonne cle");
        check(MailboxSession.outboundStale(d.resolve("sh"), new XorCodec(0x11)), "spool scelle avec une autre cle");
        check(MailboxSession.outboundStale(d.resolve("sh"), null), "spool scelle alors qu'il n'y a plus de cle");
        check(!MailboxSession.outboundStale(d.resolve("absent"), new XorCodec(0x5a)), "spool absent");

        MailboxSession.Options none = opts(4096); none.oneShot = true;
        MailboxSession clear = new MailboxSession("doc", "client", d.resolve("s-none"), relay.toString(), none);
        try { clear.recvHeader(); fail("lot chiffre lu sans cle"); }
        catch (EOFException e) { fail("lot chiffre pris pour une fin de flux"); }
        catch (IOException ok) { }
        clear.close();

        MailboxSession.Options oc = opts(4096); oc.codec = new XorCodec(0x5a); oc.oneShot = true;
        MailboxSession cli = new MailboxSession("doc", "client", d.resolve("sc"), relay.toString(), oc);
        expect(cli, "a", a); expect(cli, "b", b);
        try { cli.recvHeader(); fail("EOF attendu"); } catch (EOFException ok) { }
        cli.close();
    }

    /** Codec de test : XOR + etiquette liee au numero de lot (detecte cle et alteration). */
    private static final class XorCodec implements MailboxSession.Codec {
        private final int key;
        XorCodec(int key) { this.key = key; }
        private int tag(long seq, byte[] plain, int off, int len) {
            CRC32C c = new CRC32C();
            c.update(plain, off, len);
            return (int) (c.getValue() ^ seq ^ (key * 0x9E3779B9L));
        }
        @Override public byte[] seal(long seq, byte[] plain) {
            byte[] out = new byte[plain.length + 4];
            int t = tag(seq, plain, 0, plain.length);
            out[0] = (byte) (t >>> 24); out[1] = (byte) (t >>> 16); out[2] = (byte) (t >>> 8); out[3] = (byte) t;
            for (int i = 0; i < plain.length; i++) out[i + 4] = (byte) (plain[i] ^ key);
            return out;
        }
        @Override public byte[] open(long seq, byte[] s) throws IOException {
            if (s.length < 4) throw new IOException("trop court");
            byte[] plain = new byte[s.length - 4];
            for (int i = 0; i < plain.length; i++) plain[i] = (byte) (s[i + 4] ^ key);
            int t = ((s[0] & 0xff) << 24) | ((s[1] & 0xff) << 16) | ((s[2] & 0xff) << 8) | (s[3] & 0xff);
            if (t != tag(seq, plain, 0, plain.length)) throw new IOException("etiquette invalide");
            return plain;
        }
    }

    // =======================================================================
    //  2. Relais absent puis present, redemarrages, lot tronque, purge
    // =======================================================================

    private static void testRelaisAbsentEtReprises() throws Exception {
        step("2. relais absent, redemarrages, lot tronque, purge");
        Path d = work.resolve("t2");
        Path relay = d.resolve("relais");               // n'existe pas encore
        Path spS = d.resolve("spool-hub"), spR = d.resolve("spool-cli");
        List<String> infos = new CopyOnWriteArrayList<>();
        MailboxSession.Options os = opts(1500);
        os.info = infos::add;
        MailboxSession s = new MailboxSession("doc", "hub", spS, relay.toString(), os);
        Map<Integer, byte[]> data = new TreeMap<>();
        for (int i = 1; i <= 10; i++) { data.put(i, i == 5 ? random(6000) : text(200 + i)); send(s, "f" + i, data.get(i)); }
        waitFor(() -> lots(spS.resolve("out")) >= 5, 5_000, "mise en spool sans relais");
        check(!Files.exists(relay), "le relais ne doit pas etre cree par l'agent");
        waitFor(() -> infos.stream().anyMatch(x -> x.startsWith("relais absent, ") && x.endsWith("lots en attente")),
                5_000, "info relais absent");
        check(!s.outboundDrained(), "outboundDrained sans relais");

        Files.createDirectories(relay);                 // le support arrive
        Path out = relay.resolve("doc/hub-vers-client");
        waitFor(() -> lots(out) >= 5 && lots(out) == lots(spS.resolve("out")), 5_000, "depot a l'arrivee du relais");
        waitFor(() -> infos.stream().anyMatch(x -> x.endsWith("en attente du pair - support retirable")),
                5_000, "info support retirable");

        // recepteur : 4 cadres, puis redemarrage AU MILIEU du 5e (entete lue, contenu non lu)
        MailboxSession r = new MailboxSession("doc", "client", spR, relay.toString(), opts(1500));
        for (int i = 1; i <= 4; i++) expect(r, "f" + i, data.get(i));
        Transfer.Msg m = r.recvHeader();
        check(m.relPath.equals("f5"), "attendu f5, recu " + m.relPath);
        r.close();
        r = new MailboxSession("doc", "client", spR, relay.toString(), opts(1500));
        for (int i = 5; i <= 10; i++) expect(r, "f" + i, data.get(i));

        // emetteur : arret propre puis nouvelle session (pas de perte, pas de doublon)
        for (int i = 11; i <= 13; i++) { data.put(i, text(100 + i)); send(s, "f" + i, data.get(i)); }
        s.close();
        s = new MailboxSession("doc", "hub", spS, relay.toString(), opts(1500));
        for (int i = 14; i <= 16; i++) { data.put(i, text(100 + i)); send(s, "f" + i, data.get(i)); }
        for (int i = 11; i <= 16; i++) expect(r, "f" + i, data.get(i));

        // emetteur : arret BRUTAL avec des cadres non scelles -> recuperes du journal
        s.close();
        MailboxSession.Options lazy = opts(1500);
        lazy.flushIdleMs = 600_000;
        s = new MailboxSession("doc", "hub", spS, relay.toString(), lazy);
        for (int i = 17; i <= 18; i++) { data.put(i, text(100 + i)); send(s, "f" + i, data.get(i)); }
        s.kill();
        s = new MailboxSession("doc", "hub", spS, relay.toString(), opts(1500));
        data.put(19, text(119)); send(s, "f19", data.get(19));
        for (int i = 17; i <= 19; i++) expect(r, "f" + i, data.get(i));

        // recepteur redemarre proprement (position = dernier cadre demande)
        Thread br = blockedReader(r);
        Thread.sleep(300);
        r.close();
        br.join(5000);

        // lot tronque : ignore tant qu'il n'est pas complet
        data.put(20, text(700)); data.put(21, text(50));
        send(s, "f20", data.get(20)); send(s, "f21", data.get(21));
        s.close();                                      // tout est scelle et depose, la pompe est arretee
        Path victim = lowestLot(out);
        byte[] full = Files.readAllBytes(victim);
        Files.write(victim, Arrays.copyOf(full, full.length / 2));
        MailboxSession r2 = new MailboxSession("doc", "client", spR, relay.toString(), opts(1500));
        AtomicReference<Object> got = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try { expect(r2, "f20", data.get(20)); got.set("ok"); } catch (Throwable e) { got.set(e); }
        });
        t.setDaemon(true);
        t.start();
        Thread.sleep(800);
        check(got.get() == null, "un lot tronque a ete consomme ou a provoque une erreur: " + got.get());
        Path tmp = victim.resolveSibling(victim.getFileName() + ".part");
        Files.write(tmp, full);
        MailboxSession.moveAtomic(tmp, victim);
        t.join(10_000);
        check("ok".equals(got.get()), "lot complete non lu: " + got.get());
        expect(r2, "f21", data.get(21));

        // purge apres acquittement (nouvelle session emettrice, spool et relais vides ensuite)
        Thread br2 = blockedReader(r2);
        MailboxSession s2 = new MailboxSession("doc", "hub", spS, relay.toString(), opts(1500));
        waitFor(() -> s2.outboundDrained() && lots(out) == 0 && lots(spS.resolve("out")) == 0, 10_000, "purge finale");
        s2.close(); r2.close();
        br2.join(5000);
        check(noPart(relay), "fichier .part oublie sur le relais");
    }

    // =======================================================================
    //  2b. close() depuis un autre thread pendant recvHeader()
    // =======================================================================

    private static void testFermetureConcurrente() throws Exception {
        step("2b. close() concurrent");
        Path d = work.resolve("t2b");
        Path relay = Files.createDirectories(d.resolve("relais"));
        MailboxSession.Options o = opts(4096);
        o.pollMs = 30_000;                              // la sortie ne doit pas attendre une periode
        MailboxSession r = new MailboxSession("doc", "client", d.resolve("sc"), relay.toString(), o);
        AtomicReference<Object> res = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try { r.recvHeader(); res.set("cadre"); }
            catch (EOFException e) { res.set("eof"); }
            catch (IOException e) { res.set("io"); }
        });
        t.start();
        Thread.sleep(500);
        check(t.isAlive(), "recvHeader devait bloquer");
        long t0 = System.nanoTime();
        r.close();
        t.join(5000);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        check(!t.isAlive() && "io".equals(res.get()), "recvHeader non libere par close: " + res.get());
        check(ms < 2000, "liberation trop lente: " + ms + " ms");

        // meme chose au milieu d'un cadre : copyContentTo doit lever IOException
        MailboxSession s = new MailboxSession("doc", "hub", d.resolve("sh"), relay.toString(), opts(4096));
        MailboxSession r2 = new MailboxSession("doc", "client", d.resolve("sc"), relay.toString(), o);
        // cadre annonce plus long que ce qui sera jamais depose : seul le 1er lot arrive
        Thread sender = new Thread(() -> {
            try {
                Transfer.Msg m = msg("gros", 1_000_000);
                s.sendFile(m, new java.io.InputStream() {
                    long n;
                    @Override public int read() { return 'x'; }
                    @Override public int read(byte[] b, int off, int len) throws IOException {
                        int k = Math.min(len, 1000);
                        n += k;
                        if (n > 10_000) { try { Thread.sleep(50); } catch (InterruptedException e) { throw new IOException(e); } }
                        Arrays.fill(b, off, off + k, (byte) 'x');
                        return k;
                    }
                });
            } catch (IOException expected) { }
        });
        sender.setDaemon(true);
        sender.start();
        waitFor(() -> lots(relay.resolve("doc/hub-vers-client")) >= 1, 5_000, "1er lot du gros cadre");
        Transfer.Msg m = r2.recvHeader();
        check(m.relPath.equals("gros"), "entete du gros cadre");
        AtomicReference<Object> res2 = new AtomicReference<>();
        Thread t2 = new Thread(() -> {
            try { r2.copyContentTo(m, null); res2.set("fini"); } catch (IOException e) { res2.set("io"); }
        });
        t2.start();
        Thread.sleep(500);
        check(t2.isAlive(), "copyContentTo devait bloquer");
        r2.close();
        t2.join(5000);
        check("io".equals(res2.get()), "copyContentTo non libere par close: " + res2.get());
        s.close();                                      // interrompt aussi l'envoi en cours
        sender.join(5000);
        check(!sender.isAlive(), "sendFile non interrompu par close");
    }

    // =======================================================================
    //  2c. discardOutbound : abandon des lots non acquittes
    // =======================================================================

    private static void testAbandon() throws Exception {
        step("2c. discardOutbound");
        Path d = work.resolve("t2c");
        Path relay = Files.createDirectories(d.resolve("relais"));
        Path spS = d.resolve("sh"), spR = d.resolve("sc");
        Path out = relay.resolve("doc/hub-vers-client");

        // a) recepteur a une frontiere de cadre
        MailboxSession s = new MailboxSession("doc", "hub", spS, relay.toString(), opts(1500));
        MailboxSession r = new MailboxSession("doc", "client", spR, relay.toString(), opts(1500));
        for (int i = 1; i <= 3; i++) send(s, "f" + i, text(100 + i));
        for (int i = 1; i <= 3; i++) expect(r, "f" + i, text(100 + i));
        send(s, "perdu1", text(3000)); send(s, "perdu2", text(10));
        final MailboxSession s0 = s;
        waitFor(() -> lots(out) >= 2 && lots(out) == lots(spS.resolve("out")), 5_000, "depot avant abandon");
        s.discardOutbound();
        check(lots(out) == 0 && lots(spS.resolve("out")) == 0, "lots non abandonnes");
        check(s0.outboundDrained(), "outboundDrained apres abandon");
        try { send(s, "apres", text(5)); fail("sendFile apres discardOutbound devait echouer"); } catch (IOException ok) { }
        s.close();
        check(lots(out) == 0 && lots(spS.resolve("out")) == 0, "close a recree des lots abandonnes");
        s = new MailboxSession("doc", "hub", spS, relay.toString(), opts(1500));
        send(s, "f4", text(104));
        expect(r, "f4", text(104));                     // ni attente infinie, ni cadres abandonnes

        // b) recepteur au milieu d'un cadre etale sur plusieurs lots
        byte[] big = random(6000);
        send(s, "gros", big); send(s, "perdu3", text(10));
        waitFor(() -> lots(out) >= 4, 5_000, "depot du gros cadre");
        Transfer.Msg m = r.recvHeader();
        check(m.relPath.equals("gros"), "attendu gros, recu " + m.relPath);
        s.discardOutbound();
        s.close();
        s = new MailboxSession("doc", "hub", spS, relay.toString(), opts(1500));
        send(s, "f5", text(105));
        try { r.copyContentTo(m, null); fail("cadre abandonne lu comme complet"); }
        catch (IOException ok) { }
        r.close();
        r = new MailboxSession("doc", "client", spR, relay.toString(), opts(1500));
        expect(r, "f5", text(105));

        // c) relais absent au moment de l'abandon : menage au prochain contact
        send(s, "perdu4", text(2000));
        waitFor(() -> lots(out) >= 1, 5_000, "depot");
        Path off = d.resolve("relais-debranche");
        moveDir(relay, off);
        s.discardOutbound();
        s.close();
        moveDir(off, relay);
        s = new MailboxSession("doc", "hub", spS, relay.toString(), opts(1500));
        send(s, "f6", text(106));
        waitFor(() -> lots(out) == 1, 5_000, "menage des lots abandonnes au retour du relais");
        expect(r, "f6", text(106));
        s.close(); r.close();
    }

    // =======================================================================
    //  3. Resolution "*:\Dossier"
    // =======================================================================

    private static void testResolution() throws Exception {
        step("3. resolution du relais");
        Path d = work.resolve("t3");
        Path a = Files.createDirectories(d.resolve("lecteurA"));
        Path b = Files.createDirectories(d.resolve("lecteurB"));
        Path c = Files.createDirectories(d.resolve("lecteurC"));
        Path absent = d.resolve("lecteurZ");            // lecteur sans support
        Files.createDirectories(b.resolve("CoTransfertRelais"));
        Files.createDirectories(c.resolve("Autre").resolve("Sous"));
        Files.write(a.resolve("Fichier"), new byte[1]);
        List<Path> roots = List.of(absent, a, b, c);
        check(b.resolve("CoTransfertRelais").equals(MailboxSession.resolveRelay("*:\\CoTransfertRelais", roots)), "*:\\Dossier");
        check(b.resolve("CoTransfertRelais").equals(MailboxSession.resolveRelay("*:/CoTransfertRelais", roots)), "*:/Dossier");
        check(c.resolve("Autre/Sous").equals(MailboxSession.resolveRelay("*:\\Autre\\Sous", roots)), "sous-dossier");
        check(MailboxSession.resolveRelay("*:\\Absent", roots) == null, "dossier absent");
        check(MailboxSession.resolveRelay("*:\\Fichier", roots) == null, "un fichier n'est pas un relais");
        check(MailboxSession.resolveRelay("*:\\", roots) == null, "racine de lecteur refusee");
        check(MailboxSession.resolveRelay("*:\\CoTransfertRelais", List.of()) == null, "aucun lecteur");
        check(a.equals(MailboxSession.resolveRelay(a.toString())), "chemin simple");
        check(MailboxSession.resolveRelay(absent.toString()) == null, "chemin simple absent");
        check(MailboxSession.resolveRelay("") == null && MailboxSession.resolveRelay(null) == null, "vide");
        check(MailboxSession.resolveRelay("*:\\CoTransfert-dossier-qui-n-existe-nulle-part") == null, "vrais lecteurs");
        // le support change de lettre : le dossier est retrouve
        moveDir(b.resolve("CoTransfertRelais"), a.resolve("CoTransfertRelais"));
        check(a.resolve("CoTransfertRelais").equals(MailboxSession.resolveRelay("*:\\CoTransfertRelais", roots)), "changement de lecteur");
    }

    // =======================================================================
    //  4. Deux moteurs sur un relais partage
    // =======================================================================

    private static void testMoteur() throws Exception {
        step("4. deux moteurs sur un relais partage");
        Path d = work.resolve("t4");
        Path relay = Files.createDirectories(d.resolve("relais"));
        Path a = Files.createDirectories(d.resolve("A")), b = Files.createDirectories(d.resolve("B"));
        write(a.resolve("a1.txt"), "a1"); write(a.resolve("a2.txt"), "a2");
        write(a.resolve("sub/deep/a3.txt"), "a3 " + new String(text(20_000), StandardCharsets.US_ASCII));
        write(b.resolve("b1.txt"), "b1"); write(b.resolve("dir/b2.txt"), "b2");
        Files.write(b.resolve("b3.bin"), random(30_000));

        MailboxSession.Options o = opts(8192);
        MailboxSession sa = new MailboxSession("proj", "hub", a.resolve(".cotransfert/mailbox"), relay.toString(), o);
        MailboxSession sb = new MailboxSession("proj", "client", b.resolve(".cotransfert/mailbox"), relay.toString(), o);
        Thread ta = new Thread(() -> new SyncEngine(a, true, SyncEngine.ConflictPolicy.KEEP_BOTH).run(sa, "hub[proj]", "proj"), "moteur-A");
        Thread tb = new Thread(() -> new SyncEngine(b, false, SyncEngine.ConflictPolicy.KEEP_BOTH).run(sb, "client[proj]", "proj"), "moteur-B");
        ta.setDaemon(true); tb.setDaemon(true);
        ta.start(); tb.start();

        waitFor(() -> tree(a).size() == 6 && tree(a).equals(tree(b)), 30_000, "convergence initiale");
        check(conflicts(a) == 0 && conflicts(b) == 0, "conflit inattendu a la convergence initiale");

        // modification et suppression en direct
        Thread.sleep(500);
        write(a.resolve("a1.txt"), "a1 modifie");
        waitFor(() -> "a1 modifie".equals(read(b.resolve("a1.txt"))), 20_000, "propagation d'une modification");
        Files.delete(b.resolve("b1.txt"));
        waitFor(() -> !Files.exists(a.resolve("b1.txt")), 20_000, "propagation d'une suppression");
        write(b.resolve("neuf/n.txt"), "nouveau");
        waitFor(() -> tree(a).equals(tree(b)) && tree(a).size() == 6, 20_000, "convergence apres modifications");
        check(conflicts(a) == 0 && conflicts(b) == 0, "conflit inattendu en sync live");

        // modifications croisees du meme fichier : le relais est indisponible pendant
        // que chacun modifie, puis revient -> les deux envois se croisent.
        waitFor(() -> sa.outboundDrained() && sb.outboundDrained(), 30_000, "canal au repos");
        Path off = d.resolve("relais-debranche");
        moveDir(relay, off);
        write(a.resolve("a2.txt"), "version de A");
        write(b.resolve("a2.txt"), "version de B");
        waitFor(() -> lots(a.resolve(".cotransfert/mailbox/out")) > 0 && lots(b.resolve(".cotransfert/mailbox/out")) > 0,
                10_000, "mise en spool des deux modifications");
        Thread.sleep(1200);
        moveDir(off, relay);
        waitFor(() -> conflicts(a) == 1 && conflicts(b) == 1, 30_000, "copies de conflit des deux cotes");
        check("version de A".equals(read(a.resolve("a2.txt"))), "A a perdu sa version: " + read(a.resolve("a2.txt")));
        check("version de B".equals(read(b.resolve("a2.txt"))), "B a perdu sa version: " + read(b.resolve("a2.txt")));
        check("version de B".equals(read(conflictFile(a))), "copie de conflit de A");
        check("version de A".equals(read(conflictFile(b))), "copie de conflit de B");

        // fermeture depuis un autre thread : les deux moteurs rendent la main
        sa.close(); sb.close();
        ta.join(15_000); tb.join(15_000);
        check(!ta.isAlive() && !tb.isAlive(), "SyncEngine.run n'a pas rendu la main apres close()");
    }

    // =======================================================================
    //  5. Valise : allers-retours jusqu'a convergence
    // =======================================================================

    private static void testValise() throws Exception {
        step("5. valise");
        Path d = work.resolve("t5");
        Path a = Files.createDirectories(d.resolve("A")), b = Files.createDirectories(d.resolve("B"));
        write(a.resolve("x.txt"), "x"); write(a.resolve("sub/y.txt"), "y " + new String(text(5000), StandardCharsets.US_ASCII));
        write(b.resolve("z.txt"), "z");
        Files.createDirectories(b.resolve("bin"));
        Files.write(b.resolve("bin/w.bin"), random(4000));
        Map<String, String> ra = new LinkedHashMap<>(), rb = new LinkedHashMap<>();
        ra.put("proj", a.toString()); rb.put("proj", b.toString());
        ra.put("seulementA", Files.createDirectories(d.resolve("A-seul")).toString());   // inconnu de B
        MailboxSession.Codec codec = new XorCodec(0x33);
        Path zip = d.resolve("valise.zip");

        int rounds = rounds(ra, rb, a, b, zip, codec, 5);
        check(tree(a).size() == 4, "arbre final inattendu: " + tree(a).keySet());
        check(conflicts(a) == 0 && conflicts(b) == 0, "conflit inattendu (valise)");
        System.out.println("   convergence initiale en " + rounds + " aller(s)-retour(s)");

        write(a.resolve("x.txt"), "x modifie");
        write(b.resolve("nouveau.txt"), "cree sur B");
        rounds = rounds(ra, rb, a, b, zip, codec, 5);
        check("x modifie".equals(read(b.resolve("x.txt"))) && "cree sur B".equals(read(a.resolve("nouveau.txt"))),
                "modifications non propagees");
        check(conflicts(a) == 0 && conflicts(b) == 0, "conflit inattendu (valise, 2e passe)");
        System.out.println("   modifications propagees en " + rounds + " aller(s)-retour(s)");

        // reimporter une vieille archive ne change rien
        Map<String, Long> before = tree(b);
        Valise.importFrom(rb, "client", false, SyncEngine.ConflictPolicy.KEEP_BOTH, zip, codec);
        check(before.equals(tree(b)) && conflicts(b) == 0, "reimport non idempotent");

        // archive hostile : aucune ecriture hors du relais local
        Path evil = d.resolve("hostile.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(evil))) {
            for (String n : new String[] { "../evade1.txt", "proj/../../evade2.txt", "proj/hub-vers-client/../../../evade3.lot",
                    "proj/hub-vers-client/..\\..\\evade4.lot", "/evade5.txt", "proj/hub.ack/../../evade6", "proj/autre/000001.lot",
                    "..\\evade7.txt", "proj/hub-vers-client/000001.lot/../../../../evade8" }) {
                z.putNextEntry(new ZipEntry(n));
                z.write("hostile".getBytes(StandardCharsets.US_ASCII));
                z.closeEntry();
            }
        }
        Map<String, Long> all = everything(d);
        before = tree(b);
        Valise.importFrom(rb, "client", false, SyncEngine.ConflictPolicy.KEEP_BOTH, evil, codec);
        for (String k : everything(d).keySet()) {
            check(!k.contains("evade") && !k.contains("autre/"), "archive hostile: fichier ecrit: " + k);
            check(all.containsKey(k) || k.contains(".cotransfert/"), "archive hostile: fichier inattendu " + k);
        }
        check(before.equals(tree(b)), "archive hostile: arbre modifie");
    }

    private static int rounds(Map<String, String> ra, Map<String, String> rb, Path a, Path b, Path zip,
                              MailboxSession.Codec codec, int max) throws Exception {
        SyncEngine.ConflictPolicy pol = SyncEngine.ConflictPolicy.KEEP_BOTH;
        for (int i = 1; i <= max; i++) {
            Valise.exportTo(ra, "hub", true, pol, zip, codec);
            Valise.importFrom(rb, "client", false, pol, zip, codec);
            Valise.exportTo(rb, "client", false, pol, zip, codec);
            Valise.importFrom(ra, "hub", true, pol, zip, codec);
            if (tree(a).equals(tree(b))) return i;
        }
        throw new AssertionError("valise: pas de convergence en " + max + " allers-retours\nA=" + tree(a) + "\nB=" + tree(b));
    }

    // =======================================================================
    //  Outils
    // =======================================================================

    private static MailboxSession.Options opts(long maxSegment) {
        MailboxSession.Options o = new MailboxSession.Options();
        o.maxSegmentBytes = maxSegment;
        o.flushIdleMs = 100;
        o.pollMs = 100;
        return o;
    }

    private static Transfer.Msg msg(String path, long size) {
        Transfer.Msg m = new Transfer.Msg();
        m.op = Transfer.OP_FILE; m.relPath = path; m.size = size; m.modTime = 1234; m.hash = size * 31;
        return m;
    }

    private static void send(MailboxSession s, String path, byte[] content) throws IOException {
        s.sendFile(msg(path, content.length), new ByteArrayInputStream(content));
    }

    private static void expect(MailboxSession s, String path, byte[] content) throws IOException {
        Transfer.Msg m = s.recvHeader();
        if (!path.equals(m.relPath)) throw new AssertionError("attendu " + path + ", recu " + m.relPath);
        check(m.size == content.length && m.modTime == 1234 && m.hash == m.size * 31 && m.op == Transfer.OP_FILE,
                "entete alteree pour " + path);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        s.copyContentTo(m, bos);
        check(Arrays.equals(content, bos.toByteArray()), "contenu altere pour " + path);
    }

    /** Lecteur bloque sur le cadre suivant : c'est ce qui acquitte le dernier cadre lu. */
    private static Thread blockedReader(MailboxSession s) {
        Thread t = new Thread(() -> {
            try {
                Transfer.Msg m = s.recvHeader();
                System.err.println("cadre inattendu: " + m.relPath);
                Runtime.getRuntime().halt(4);
            } catch (IOException expected) { }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static byte[] text(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) ("abcdefgh ".charAt(i % 9));
        return b;
    }

    private static byte[] random(int n) {
        byte[] b = new byte[n];
        RND.nextBytes(b);
        return b;
    }

    private static int lots(Path dir) {
        if (!Files.isDirectory(dir)) return 0;
        int n = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.lot")) {
            for (Path p : ds) if (p != null) n++;
        } catch (IOException | RuntimeException e) {
            return -1;
        }
        return n;
    }

    private static Path lowestLot(Path dir) throws IOException {
        Path best = null;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.lot")) {
            for (Path p : ds) if (best == null || p.getFileName().toString().compareTo(best.getFileName().toString()) < 0) best = p;
        }
        if (best == null) throw new AssertionError("aucun lot dans " + dir);
        return best;
    }

    private static boolean noPart(Path root) throws IOException {
        for (String k : everything(root).keySet()) if (k.endsWith(".part")) return false;
        return true;
    }

    private static void write(Path p, String s) throws IOException {
        Files.createDirectories(p.getParent());
        Files.write(p, s.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(Path p) {
        try { return new String(Files.readAllBytes(p), StandardCharsets.UTF_8); } catch (IOException e) { return null; }
    }

    /** Arbre synchronise : rel -> hash, hors .cotransfert et copies de conflit. */
    private static Map<String, Long> tree(Path root) {
        Map<String, Long> m = new TreeMap<>();
        for (Map.Entry<String, Long> e : everything(root).entrySet()) {
            String k = e.getKey();
            if (k.startsWith(".cotransfert/") || k.contains(".conflict-peer-")) continue;
            m.put(k, e.getValue());
        }
        return m;
    }

    private static int conflicts(Path root) {
        int n = 0;
        for (String k : everything(root).keySet()) if (k.contains(".conflict-peer-")) n++;
        return n;
    }

    private static Path conflictFile(Path root) {
        for (String k : everything(root).keySet()) if (k.contains(".conflict-peer-")) return root.resolve(k);
        throw new AssertionError("pas de copie de conflit dans " + root);
    }

    private static Map<String, Long> everything(Path root) {
        Map<String, Long> m = new TreeMap<>();
        if (!Files.isDirectory(root)) return m;
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult visitFile(Path p, BasicFileAttributes at) {
                    long h;
                    try { h = Index.hashFile(p); } catch (IOException e) { h = -1; }
                    m.put(root.relativize(p).toString().replace('\\', '/'), h);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path p, IOException e) { return FileVisitResult.CONTINUE; }
            });
        } catch (IOException e) {
            m.put("?erreur", -2L);
        }
        return m;
    }

    private static void moveDir(Path from, Path to) throws Exception {
        IOException last = null;
        for (int i = 0; i < 200; i++) {
            try { Files.move(from, to); return; } catch (IOException e) { last = e; Thread.sleep(25); }
        }
        throw new IOException("deplacement de " + from + " impossible", last);
    }

    private static void wipe(Path root) throws Exception {
        if (!Files.exists(root)) return;
        for (int i = 0; i < 20; i++) {
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                    @Override public FileVisitResult visitFile(Path p, BasicFileAttributes a) throws IOException {
                        Files.delete(p); return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult postVisitDirectory(Path p, IOException e) throws IOException {
                        Files.delete(p); return FileVisitResult.CONTINUE;
                    }
                });
                return;
            } catch (IOException e) {
                Thread.sleep(200);
            }
        }
        throw new IOException("nettoyage impossible: " + root);
    }

    private static void waitFor(BooleanSupplier c, long timeoutMs, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (true) {
            boolean ok;
            try { ok = c.getAsBoolean(); } catch (RuntimeException e) { ok = false; }
            if (ok) return;
            if (System.currentTimeMillis() > end) throw new AssertionError("delai depasse: " + what);
            Thread.sleep(40);
        }
    }

    private static void step(String s) { System.out.println("=== " + s); }

    private static void check(boolean ok, String msg) { if (!ok) throw new AssertionError(msg); }

    private static void fail(String msg) { throw new AssertionError(msg); }
}
