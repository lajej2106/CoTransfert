package cotransfert;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Journal de l'agent : cotransfert.log, a cote de cotransfert.conf (repli : le
 * dossier temporaire de l'utilisateur).
 *
 * L'agent tourne sans console (javaw) : tout ce qu'il ecrivait a l'ecran
 * (System.out et System.err) est redirige ici, ligne par ligne. Avec une console
 * (CoTransfert.cmd --console, ou java lance a la main), les lignes vont a l'ecran
 * ET dans le fichier.
 *
 * Taille bornee : au demarrage, puis en cours de route, au-dela de 2 Mio le
 * fichier est renomme cotransfert.log.1 (un seul fichier archive, le precedent
 * est remplace) et un fichier neuf commence.
 *
 * Aucun secret ne doit etre journalise (regle de tout le code) ; par precaution
 * les valeurs sensibles connues sont masquees ici aussi (voir {@link #setSecrets}).
 * Une erreur d'ecriture du journal n'arrete jamais l'agent.
 */
final class Journal {

    private Journal() { }

    static final String FILE_NAME = "cotransfert.log";
    /** Au-dela de cette taille le journal est archive (un seul fichier archive). */
    static final long MAX_BYTES = 2L << 20;
    /** Longueur maximale d'une ligne rendue par {@link #tail}. */
    private static final int MAX_LINE = 2000;

    private static final Object LOCK = new Object();
    private static volatile Path file;
    private static volatile List<String> secrets = List.of();
    private static OutputStream out;
    private static long written;
    private static PrintStream screen;

    /** Fichier journal en cours, ou null si le journal n'est pas demarre (modes ponctuels, essais). */
    static Path path() { return file; }

    /** Valeurs a ne jamais laisser sortir (secret partage, mot de passe, jeton) : masquees dans chaque ligne. */
    static void setSecrets(Collection<String> values) {
        List<String> l = new ArrayList<>();
        if (values != null) for (String v : values) if (v != null && v.trim().length() >= 4) l.add(v.trim());
        secrets = List.copyOf(l);
    }

    /**
     * Emplacement du journal : a cote du fichier de configuration s'il est
     * possible d'y ecrire, sinon dans le dossier temporaire de l'utilisateur.
     */
    static Path fileFor(Path conf) {
        try {
            Path dir = conf.toAbsolutePath().getParent();
            if (dir != null) {
                Files.createDirectories(dir);
                Path f = dir.resolve(FILE_NAME);
                Files.newOutputStream(f, StandardOpenOption.CREATE, StandardOpenOption.APPEND).close();
                return f;
            }
        } catch (IOException | RuntimeException e) {
            // repli ci-dessous
        }
        return Paths.get(System.getProperty("java.io.tmpdir", ".")).toAbsolutePath().resolve(FILE_NAME);
    }

    /**
     * Demarre le journal et y redirige System.out et System.err.
     * @param conf     fichier de configuration (le journal se place a cote)
     * @param toScreen vrai : les lignes restent aussi affichees a l'ecran
     */
    static void start(Path conf, boolean toScreen) {
        synchronized (LOCK) {
            if (file != null) return;
            Path f = fileFor(conf);
            rotate(f, MAX_BYTES);
            try {
                out = Files.newOutputStream(f, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                written = Files.size(f);
            } catch (IOException | RuntimeException e) {
                out = null;   // pas de fichier : l'ecran seul, s'il y en a un
            }
            screen = toScreen ? System.out : null;
            file = f;
            PrintStream ps = new PrintStream(new Lines(), true, StandardCharsets.UTF_8);
            System.setOut(ps);
            System.setErr(ps);
        }
    }

    /**
     * Archive le journal s'il depasse max octets : il devient "<nom>.1" (l'archive
     * precedente est remplacee). Sans effet sinon ; un echec laisse le journal tel quel.
     * @return vrai si le fichier a ete archive
     */
    static boolean rotate(Path log, long max) {
        try {
            if (!Files.isRegularFile(log) || Files.size(log) <= max) return false;
            Files.move(log, log.resolveSibling(log.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Ajoute une ligne horodatee a un journal sans passer par System.out : sert au
     * processus de relance, qui n'a ni console ni journal a lui.
     */
    static void append(Path log, String line) {
        if (log == null) return;
        String s = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " " + line + System.lineSeparator();
        try {
            Files.write(log, s.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            // journal indisponible : rien d'autre a faire
        }
    }

    /** Les n dernieres lignes du journal (1000 au plus), de la plus ancienne a la plus recente. */
    static List<String> tail(Path log, int n) {
        List<String> res = new ArrayList<>();
        int want = Math.max(1, Math.min(n, 1000));
        if (log == null) return res;
        try {
            if (!Files.isRegularFile(log)) return res;
            byte[] b;
            try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(log, StandardOpenOption.READ)) {
                long size = ch.size();
                long from = Math.max(0, size - (MAX_BYTES + (64 << 10)));
                java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate((int) (size - from));
                long at = from;
                while (bb.hasRemaining()) {
                    int k = ch.read(bb, at);
                    if (k < 0) break;
                    at += k;
                }
                b = java.util.Arrays.copyOf(bb.array(), bb.position());
            }
            String[] all = new String(b, StandardCharsets.UTF_8).split("\r?\n");
            for (int i = Math.max(0, all.length - want); i < all.length; i++) {
                String l = all[i];
                if (i == all.length - 1 && l.isEmpty()) continue;
                res.add(l.length() > MAX_LINE ? l.substring(0, MAX_LINE) : l);
            }
        } catch (IOException | RuntimeException e) {
            // journal illisible : liste vide
        }
        return res;
    }

    private static String scrub(String s) {
        String o = s;
        for (String v : secrets) o = o.replace(v, "***");
        return o;
    }

    /** Decoupe le flux en lignes ; chaque ligne complete part dans le fichier (et a l'ecran). */
    private static final class Lines extends OutputStream {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream(256);

        @Override public void write(int b) {
            synchronized (LOCK) {
                if (b == '\n') emit();
                else buf.write(b);
            }
        }

        @Override public void write(byte[] b, int off, int len) {
            synchronized (LOCK) {
                for (int i = off; i < off + len; i++) {
                    if (b[i] == '\n') emit();
                    else buf.write(b[i]);
                }
            }
        }

        @Override public void flush() {
            synchronized (LOCK) {
                try { if (out != null) out.flush(); } catch (IOException e) { /* journal indisponible */ }
            }
        }

        private void emit() {
            String line = buf.toString(StandardCharsets.UTF_8);
            buf.reset();
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            line = scrub(line);
            if (screen != null) screen.println(line);
            if (out == null) return;
            try {
                byte[] bytes = (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
                if (written + bytes.length > MAX_BYTES) reopen();
                // Archivage impossible : on continue dans le meme fichier et on reessaiera plus tard.
                if (written + bytes.length > MAX_BYTES) written = 0;
                if (out == null) return;
                out.write(bytes);
                written += bytes.length;
            } catch (IOException | RuntimeException e) {
                // journal indisponible : l'agent continue
            }
        }

        /** Journal plein en cours de route : archive puis fichier neuf. */
        private void reopen() {
            Path f = file;
            try { out.close(); } catch (IOException e) { /* on rouvre quand meme */ }
            rotate(f, 0);
            try {
                out = Files.newOutputStream(f, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                written = Files.size(f);
            } catch (IOException | RuntimeException e) {
                out = null;
            }
        }
    }
}
