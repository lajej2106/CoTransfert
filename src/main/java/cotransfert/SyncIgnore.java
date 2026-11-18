package cotransfert;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Regles d'exclusion de la synchronisation, facon .gitignore.
 *
 * Principe (demande utilisateur) : par defaut on synchronise 100% des fichiers
 * (code, .git, .idea, conf, fichiers compiles -> dossiers 100% iso). Les
 * exclusions sont pilotees par un fichier .cotransfert/.syncignore, LUI-MEME
 * synchronise pour rester identique des deux cotes.
 *
 * Regle dure, non desactivable : les fichiers de travail internes
 * (.cotransfert/index.tsv, .cotransfert/tmp/, .cotransfert/conflicts.log) sont
 * TOUJOURS exclus (ils sont propres a chaque poste), SAUF .cotransfert/.syncignore
 * qui, lui, est toujours synchronise. Meme regle dure pour les copies de conflit
 * "*.conflict-peer-AAAAMMJJ-HHMMSS" : elles sont propres au poste qui doit
 * trancher (voir {@link #conflictCopy}).
 *
 * Sous-ensemble gitignore gere :
 *   - lignes vides et commentaires (#) ignores ;
 *   - "nom/"        -> un dossier nomme "nom" a n'importe quelle profondeur ;
 *   - "**" + "/"    -> joker multi-niveaux (ex: **\/target, logs\/**) ;
 *   - "*" , "?"     -> jokers sur un segment ;
 *   - "/x"          -> ancre a la racine ;
 *   - "nom"         -> correspond au chemin complet ou a un segment "nom".
 */
public final class SyncIgnore {

    public static final String META = ".cotransfert";
    public static final String IGNORE_REL = META + "/.syncignore";

    private final Path root;
    private final Path ignoreFile;
    private volatile List<Rule> rules = new ArrayList<>();

    public SyncIgnore(Path root) {
        this.root = root;
        this.ignoreFile = root.resolve(META).resolve(".syncignore");
        reload();
    }

    public Path file() { return ignoreFile; }

    /** Relit le .syncignore ; cree un modele commente s'il est absent. */
    public synchronized void reload() {
        try {
            if (!Files.exists(ignoreFile)) {
                Files.createDirectories(ignoreFile.getParent());
                Files.write(ignoreFile, defaultContent().getBytes("UTF-8"));
            }
            List<Rule> parsed = new ArrayList<>();
            for (String line : Files.readAllLines(ignoreFile)) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) continue;
                parsed.add(new Rule(s));
            }
            this.rules = parsed;
        } catch (IOException e) {
            Agent.log("syncignore: lecture impossible (%s)", e.getMessage());
        }
    }

    /** rel en separateurs '/'. true => a exclure de la synchronisation. */
    public boolean ignored(String rel) {
        if (rel == null || rel.isEmpty()) return true;
        // Regle dure : les copies de conflit ne quittent jamais le poste qui doit trancher.
        if (conflictCopy(rel)) return true;
        // Regle dure sur le dossier meta.
        if (rel.equals(IGNORE_REL)) return false;             // .syncignore : toujours synchro
        if (rel.equals(META) || rel.startsWith(META + "/")) return true; // reste de .cotransfert : jamais
        for (Rule r : rules) if (r.matches(rel)) return true;
        return false;
    }

    /**
     * Vrai pour une copie de conflit ("fichier.conflict-peer-AAAAMMJJ-HHMMSS") et,
     * par prudence, pour tout chemin qui porte cette marque (copie renommee pour
     * l'ouvrir, par exemple) : jamais scannee, jamais envoyee, jamais supprimee
     * chez le pair, ignoree si elle est recue. Regle non desactivable.
     */
    public static boolean conflictCopy(String rel) {
        return rel != null && rel.contains(".conflict-peer-");
    }

    /**
     * Empreinte (meme calcul que {@link Index#hashFile}) du modele commente cree
     * d'office. Un .syncignore encore egal au modele n'exprime aucun choix de
     * l'utilisateur : il ne doit jamais l'emporter sur un .syncignore modifie.
     */
    public static long templateHash() {
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update(defaultContent().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return crc.getValue();
    }

    private static String defaultContent() {
        return String.join("\n",
            "# .syncignore de CoTransfert (style .gitignore).",
            "# Ce fichier est synchronise : il reste identique sur les deux postes.",
            "# Par defaut AUCUNE exclusion : les dossiers sont 100% iso (y compris",
            "# .git, .idea, fichiers de conf et fichiers compiles).",
            "#",
            "# Pour exclure des chemins (ex. projet info), decommente ou ajoute :",
            "# **/target",
            "# **/build",
            "# **/out",
            "# **/node_modules",
            "# **/.gradle",
            "# *.log",
            ""
        );
    }

    // --- Une regle compilee en regex ---------------------------------------

    private static final class Rule {
        private final Pattern regex;

        Rule(String pat) {
            boolean anchored = pat.startsWith("/");
            if (anchored) pat = pat.substring(1);
            if (pat.endsWith("/")) pat = pat.substring(0, pat.length() - 1); // "target/" == "target"
            this.regex = Pattern.compile(build(pat, anchored));
        }

        /** Construit une regex qui matche le chemin relatif complet. */
        private static String build(String glob, boolean anchored) {
            StringBuilder re = new StringBuilder();
            // non ancre : la regle peut commencer a n'importe quel segment
            re.append(anchored ? "^" : "^(?:.*/)?");
            for (int i = 0; i < glob.length(); i++) {
                char c = glob.charAt(i);
                switch (c) {
                    case '*':
                        if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                            i++;
                            if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') { i++; re.append("(?:.*/)?"); }
                            else re.append(".*");
                        } else {
                            re.append("[^/]*"); // * ne traverse pas un /
                        }
                        break;
                    case '?': re.append("[^/]"); break;
                    case '.': case '(': case ')': case '+': case '|':
                    case '^': case '$': case '{': case '}': case '[':
                    case ']': case '\\':
                        re.append('\\').append(c); break;
                    default: re.append(c);
                }
            }
            // matche le chemin exact OU tout ce qui est dessous (dossier)
            re.append("(?:/.*)?$");
            return re.toString();
        }

        boolean matches(String rel) {
            return regex.matcher(rel).matches();
        }
    }
}
