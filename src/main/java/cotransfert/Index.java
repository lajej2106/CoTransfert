package cotransfert;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32C;

/**
 * Index de reference : pour chaque chemin relatif, l'etat du contenu que l'on
 * considere "en phase" avec le pair (dernier envoye / recu / reconcilie).
 *
 * Sert a :
 *   - detecter un vrai changement local (hash different de la reference) ;
 *   - detecter une divergence des deux cotes (conflit) ;
 *   - survivre aux redemarrages (persistance TSV dans .cotransfert/index.tsv).
 *
 * Le hash est un CRC-32C du contenu (rapide, dans le JDK). Suffisant pour la
 * detection de changement et l'anti-echo ; a remplacer par un hash 64/128 bits
 * si la robustesse anti-collision devient critique.
 */
public final class Index {

    public static final class State {
        public final long hash;
        public final long mtime; // epoch millis
        public final long size;
        public State(long hash, long mtime, long size) {
            this.hash = hash; this.mtime = mtime; this.size = size;
        }
    }

    private final Map<String, State> map = new ConcurrentHashMap<>();
    private final Path file;

    public Index(Path syncDir) {
        this.file = syncDir.resolve(".cotransfert").resolve("index.tsv");
    }

    public State get(String rel) { return map.get(rel); }
    public void put(String rel, State s) { map.put(rel, s); dirty = true; }
    public void remove(String rel) { if (map.remove(rel) != null) dirty = true; }
    public boolean isEmpty() { return map.isEmpty(); }

    /** Modifie depuis la derniere sauvegarde. */
    private volatile boolean dirty = false;
    /** Sauvegarde periodique bon marche : n'ecrit que s'il y a eu des changements. */
    public void saveIfDirty() { if (dirty) save(); }
    public Iterable<Map.Entry<String, State>> entries() { return map.entrySet(); }
    public boolean contains(String rel) { return map.containsKey(rel); }

    // --- hachage -----------------------------------------------------------

    public static long hashFile(Path p) throws IOException {
        CRC32C crc = new CRC32C();
        byte[] buf = new byte[1 << 20];
        try (InputStream in = Files.newInputStream(p)) {
            int n;
            while ((n = in.read(buf)) >= 0) crc.update(buf, 0, n);
        }
        return crc.getValue();
    }

    // --- persistance -------------------------------------------------------

    public synchronized void load() {
        try {
            if (!Files.exists(file)) return;
            try (BufferedReader r = Files.newBufferedReader(file)) {
                String line;
                while ((line = r.readLine()) != null) {
                    String[] f = line.split("\t", 4);
                    if (f.length < 4) continue;
                    map.put(f[3], new State(Long.parseLong(f[0]), Long.parseLong(f[1]), Long.parseLong(f[2])));
                }
            }
        } catch (Exception e) {
            Agent.log("index: lecture impossible (%s), on repart a vide", e.getMessage());
        }
    }

    public synchronized void save() {
        dirty = false;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("index.tsv.tmp");
            try (BufferedWriter w = Files.newBufferedWriter(tmp)) {
                for (Map.Entry<String, State> e : map.entrySet()) {
                    State s = e.getValue();
                    w.write(s.hash + "\t" + s.mtime + "\t" + s.size + "\t" + e.getKey());
                    w.newLine();
                }
            }
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            Agent.log("index: sauvegarde impossible (%s)", e.getMessage());
        }
    }
}
