package cotransfert;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Magasin "disque" : un dossier local, un partage ou un support amovible deplace
 * d'un poste a l'autre.
 *
 * spec est un chemin de dossier, ou "*:\Dossier" : le dossier est alors cherche
 * a la racine de chaque lecteur (voir {@link MailboxSession#resolveRelay}). Le
 * dossier est re-resolu a chaque operation : un support peut apparaitre,
 * disparaitre ou changer de lettre a tout moment. Le magasin ne cree JAMAIS le
 * dossier relais lui-meme (un support absent ne doit pas devenir un dossier
 * local) ; il ne cree que les sous-dossiers d'un relais present.
 *
 * {@link #list} rend aussi les fichiers ".part" : ils ne correspondent a aucun
 * nom de lot, et la session s'en sert pour retirer de son sens les restes d'une
 * copie interrompue.
 */
public final class DirRelayStore implements RelayStore {

    /** Duree de vie du dossier resolu pour une spec "*:" (evite d'enumerer les lecteurs a chaque fichier). */
    private static final long ROOT_CACHE_MS = 1000;

    private final String spec;
    private final boolean wildcard;
    private volatile Path cached;
    private volatile long cachedAt;
    private volatile String lastError = "";

    public DirRelayStore(String spec) {
        this.spec = spec;
        this.wildcard = spec != null && spec.trim().startsWith("*:");
    }

    @Override public String id() { return "disque"; }

    @Override public String describe() { return String.valueOf(spec); }

    @Override public boolean available() {
        boolean up = root() != null;
        lastError = up ? "" : "support absent";
        return up;
    }

    @Override public String lastError() { return lastError; }

    /** Dossier relais tel qu'il est joignable maintenant, ou null. */
    public Path root() {
        if (!wildcard) return MailboxSession.resolveRelay(spec);
        Path c = cached;
        long now = System.nanoTime() / 1_000_000;
        try {
            if (c != null && now - cachedAt < ROOT_CACHE_MS && Files.isDirectory(c)) return c;
        } catch (SecurityException e) {
            // on re-resout
        }
        c = MailboxSession.resolveRelay(spec);
        cached = c;
        cachedAt = now;
        return c;
    }

    private Path resolve(String path) throws IOException {
        Path r = root();
        if (r == null) throw new IOException("relais absent");
        try {
            Path p = r;
            for (String seg : path.split("/", -1)) {
                if (seg.isEmpty() || seg.equals(".") || seg.equals("..") || seg.indexOf('\\') >= 0)
                    throw new IOException("chemin refuse: " + path);
                p = p.resolve(seg);
            }
            return p;
        } catch (InvalidPathException e) {
            throw new IOException("chemin refuse: " + path);
        }
    }

    @Override public List<Entry> list(String dir) throws IOException {
        Path d = resolve(dir);
        List<Entry> out = new ArrayList<>();
        if (!Files.isDirectory(d)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(d)) {
            for (Path p : ds) {
                try {
                    if (Files.isRegularFile(p)) out.add(new Entry(p.getFileName().toString(), Files.size(p)));
                } catch (IOException gone) {
                    // disparu entre la liste et la lecture de sa taille
                }
            }
        } catch (NoSuchFileException e) {
            return new ArrayList<>();
        } catch (DirectoryIteratorException e) {
            throw e.getCause();
        }
        return out;
    }

    @Override public byte[] read(String path, int maxBytes) throws IOException {
        Path f = resolve(path);
        try (FileChannel ch = FileChannel.open(f, StandardOpenOption.READ)) {
            long size = ch.size();
            long n = maxBytes > 0 ? Math.min(size, maxBytes) : size;
            if (n > Integer.MAX_VALUE - 64) throw new IOException("fichier trop gros: " + path);
            ByteBuffer bb = ByteBuffer.allocate((int) n);
            long at = 0;
            while (bb.hasRemaining()) {
                int k = ch.read(bb, at);
                if (k < 0) break;
                at += k;
            }
            return bb.hasRemaining() ? Arrays.copyOf(bb.array(), bb.position()) : bb.array();
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    /**
     * Cree les dossiers manquants SOUS la racine du relais, un niveau a la fois,
     * sans jamais recreer la racine elle-meme : si le support vient d'etre retire
     * (ou le dossier deplace), l'ecriture echoue au lieu de faire renaitre un
     * dossier relais vide a l'ancien emplacement.
     */
    private void mkdirsBelowRoot(Path dir) throws IOException {
        Path r = root();
        if (r == null || !Files.isDirectory(r)) throw new IOException("relais absent");
        if (!dir.startsWith(r)) throw new IOException("chemin refuse: " + dir);
        Path p = r;
        for (Path seg : r.relativize(dir)) {
            p = p.resolve(seg);
            if (Files.isDirectory(p)) continue;
            try {
                Files.createDirectory(p);
            } catch (java.nio.file.FileAlreadyExistsException e) {
                if (!Files.isDirectory(p)) throw e;      // cree entre-temps par l'autre fil : tres bien
            } catch (NoSuchFileException e) {
                throw new IOException("relais absent");  // le parent a disparu : support retire
            }
        }
    }

    @Override public void write(String path, Path src) throws IOException {
        Path dst = resolve(path);
        mkdirsBelowRoot(dst.getParent());
        Path tmp = dst.resolveSibling(dst.getFileName() + ".part");
        Files.copy(src, tmp, StandardCopyOption.REPLACE_EXISTING);
        MailboxSession.moveAtomic(tmp, dst);
    }

    @Override public void write(String path, byte[] data) throws IOException {
        Path dst = resolve(path);
        mkdirsBelowRoot(dst.getParent());
        Path tmp = dst.resolveSibling(dst.getFileName() + ".part");
        Files.write(tmp, data);
        MailboxSession.moveAtomic(tmp, dst);
    }

    @Override public void delete(String path) throws IOException {
        Files.deleteIfExists(resolve(path));
    }
}
