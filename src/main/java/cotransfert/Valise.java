package cotransfert;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Valise manuelle : la synchronisation avance par des archives zip portees a la
 * main d'un poste a l'autre (n'importe quel moyen permis).
 *
 * C'est la meme boite aux lettres que le dossier relais ({@link MailboxSession}),
 * avec un relais LOCAL a chaque poste (<dossier>/.cotransfert/valise-relay) :
 *   - export : une session ponctuelle du moteur ecrit ses lots, puis l'archive
 *     recoit, pour chaque dossier, mes lots non acquittes et mon acquittement ;
 *   - import : les lots et l'acquittement du pair sont extraits dans le relais
 *     local, puis une session ponctuelle les applique ; les reactions (fichiers a
 *     renvoyer, acquittement) attendent le prochain export.
 * Une archive peut etre reimportee ou reexportee sans risque : les lots deja
 * consommes sont ignores, les lots non acquittes sont reexportes.
 */
public final class Valise {

    static final String RELAY_DIR = "valise-relay";
    static final String SPOOL_DIR = "valise-spool";

    private Valise() { }

    public static void exportTo(Map<String, String> roots, String side, boolean master,
                                SyncEngine.ConflictPolicy policy, Path zip, MailboxSession.Codec codec)
            throws IOException {
        checkSide(side);
        for (Map.Entry<String, String> e : roots.entrySet()) {
            runOnce(e.getKey(), e.getValue(), side, master, policy, codec);
        }
        String myDir = MailboxSession.direction(side);
        int lots = 0;
        Path target = zip.toAbsolutePath();
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        try (ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            z.setLevel(Deflater.NO_COMPRESSION);   // les lots sont deja compresses
            Set<String> seen = new HashSet<>();
            for (Map.Entry<String, String> e : roots.entrySet()) {
                String safe = MailboxSession.safeName(e.getKey());
                if (!seen.add(safe)) continue;
                Path base = relayOf(e.getValue()).resolve(safe);
                for (Path lot : lotsIn(base.resolve(myDir))) {
                    z.putNextEntry(new ZipEntry(safe + "/" + myDir + "/" + lot.getFileName()));
                    Files.copy(lot, z);
                    z.closeEntry();
                    lots++;
                }
                Path ack = base.resolve(side + ".ack");
                if (Files.isRegularFile(ack)) {
                    z.putNextEntry(new ZipEntry(safe + "/" + side + ".ack"));
                    Files.copy(ack, z);
                    z.closeEntry();
                }
            }
        }
        MailboxSession.moveAtomic(tmp, target);
        Agent.log("valise: %d lot(s) exporte(s) dans %s", lots, target);
        Agent.log("valise: porter ce fichier sur le poste %s et y lancer --import ; "
                + "les lots restent ici jusqu'a l'acquittement du pair (un nouvel export les reprendra)",
                MailboxSession.other(side));
    }

    public static void importFrom(Map<String, String> roots, String side, boolean master,
                                  SyncEngine.ConflictPolicy policy, Path zip, MailboxSession.Codec codec)
            throws IOException {
        checkSide(side);
        String peer = MailboxSession.other(side);
        String inDir = MailboxSession.direction(peer);
        String myDir = MailboxSession.direction(side);
        Map<String, String> bySafe = new HashMap<>();
        for (Map.Entry<String, String> e : roots.entrySet()) {
            bySafe.putIfAbsent(MailboxSession.safeName(e.getKey()), e.getValue());
        }

        int taken = 0, own = 0;
        Set<String> unknown = new HashSet<>();
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zip)))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String[] p = e.getName().replace('\\', '/').split("/", -1);
                // Seules deux formes sont admises ; tout le reste (dont les chemins
                // qui sortiraient du relais, "zip-slip") est refuse.
                boolean isAck = p.length == 2 && p[1].equals(peer + ".ack");
                boolean isLot = p.length == 3 && p[1].equals(inDir) && MailboxSession.lotSeq(p[2]) > 0;
                boolean isMine = (p.length == 2 && p[1].equals(side + ".ack"))
                        || (p.length == 3 && p[1].equals(myDir) && MailboxSession.lotSeq(p[2]) > 0);
                if (isMine) { own++; continue; }
                if (!isAck && !isLot) {
                    Agent.log("valise: entree refusee: %s", e.getName());
                    continue;
                }
                String dir = bySafe.get(p[0]);
                if (dir == null) {
                    if (unknown.add(p[0])) Agent.log("valise: dossier \"%s\" inconnu sur ce poste, ignore", p[0]);
                    continue;
                }
                Path base = relayOf(dir).resolve(p[0]).normalize();
                Path dst = (isAck ? base.resolve(p[1]) : base.resolve(p[1]).resolve(p[2])).normalize();
                if (!dst.startsWith(base)) {
                    Agent.log("valise: entree refusee (hors du dossier cible): %s", e.getName());
                    continue;
                }
                Files.createDirectories(dst.getParent());
                Path tmp = dst.resolveSibling(dst.getFileName() + ".part");
                Files.copy(zin, tmp, StandardCopyOption.REPLACE_EXISTING);
                MailboxSession.moveAtomic(tmp, dst);
                taken++;
            }
        }
        if (own > 0 && taken == 0) {
            Agent.log("valise: cette archive a ete produite par ce poste (%s) : elle est a importer sur le poste %s",
                    side, peer);
        }
        Agent.log("valise: %d fichier(s) du pair extrait(s) de %s", taken, zip);

        int waiting = 0;
        for (Map.Entry<String, String> e : roots.entrySet()) {
            runOnce(e.getKey(), e.getValue(), side, master, policy, codec);
            Path base = relayOf(e.getValue()).resolve(MailboxSession.safeName(e.getKey()));
            // Les lots du pair deja consommes ne servent plus : ce relais est une copie locale.
            long[] ack = MailboxSession.readAck(base.resolve(side + ".ack"));
            if (ack != null) {
                for (Path lot : lotsIn(base.resolve(inDir))) {
                    if (MailboxSession.lotSeq(lot.getFileName().toString()) < ack[1]) Files.deleteIfExists(lot);
                }
            }
            waiting += lotsIn(base.resolve(myDir)).size();
        }
        Agent.log("valise: import termine. %d lot(s) attendent d'etre renvoyes au poste %s : "
                + "lancer --export puis y importer l'archive (elle porte aussi l'acquittement)", waiting, peer);
    }

    // -----------------------------------------------------------------------

    private static void checkSide(String side) {
        if (!"hub".equals(side) && !"client".equals(side))
            throw new IllegalArgumentException("side doit valoir hub ou client: " + side);
    }

    private static Path relayOf(String dir) {
        return Paths.get(dir).toAbsolutePath().normalize().resolve(SyncIgnore.META).resolve(RELAY_DIR);
    }

    /** Une session ponctuelle du moteur sur le relais local de ce dossier. */
    private static void runOnce(String name, String dir, String side, boolean master,
                                SyncEngine.ConflictPolicy policy, MailboxSession.Codec codec) throws IOException {
        Path sync = Paths.get(dir).toAbsolutePath().normalize();
        Files.createDirectories(sync);
        Path relay = relayOf(dir);
        Files.createDirectories(relay);
        MailboxSession.Options o = new MailboxSession.Options();
        o.oneShot = true;
        o.channel = "valise";
        o.codec = codec;
        o.pollMs = 500;
        o.flushIdleMs = 60_000;   // pas de scellement sur inactivite : close() scelle
        MailboxSession s = new MailboxSession(name, side,
                sync.resolve(SyncIgnore.META).resolve(SPOOL_DIR), relay.toString(), o);
        try {
            new SyncEngine(sync, master, policy).run(s, side + "[" + name + "]", name);
        } finally {
            s.close();
        }
    }

    private static List<Path> lotsIn(Path dir) throws IOException {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) if (MailboxSession.lotSeq(p.getFileName().toString()) > 0) out.add(p);
        }
        Collections.sort(out);
        return out;
    }
}
