package cotransfert;

import java.io.File;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Confort du poste, facultatif : ouvrir la page dans le navigateur, choisir
 * un dossier dans une boite de dialogue, ouvrir un dossier dans l'explorateur
 * de fichiers, afficher une erreur fatale quand
 * l'agent tourne sans console. Uniquement le JDK (java.desktop) et,
 * en repli sous Windows, une commande integree. Rien ici n'est indispensable :
 * toute erreur (session sans ecran, JRE sans module graphique, politique du
 * poste) est absorbee et l'agent continue ; l'adresse de la page reste dans le
 * journal et la saisie au clavier reste possible.
 */
final class DesktopSupport {

    private DesktopSupport() { }

    /** Une seule boite de dialogue a la fois. */
    private static final AtomicBoolean CHOOSING = new AtomicBoolean();

    /** Ouvre l'URL dans le navigateur par defaut. @return faux si rien n'a pu etre tente. */
    static boolean openBrowser(String url) {
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop d = java.awt.Desktop.getDesktop();
                if (d.isSupported(java.awt.Desktop.Action.BROWSE)) {
                    d.browse(URI.create(url));
                    return true;
                }
            }
        } catch (Throwable t) {
            // repli ci-dessous
        }
        try {
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url)
                        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                return true;
            }
        } catch (Throwable t) {
            // pas de navigateur : l'adresse est dans le journal
        }
        return false;
    }

    /**
     * Ouvre ce dossier dans l'explorateur de fichiers du poste.
     * @return faux si rien n'a pu etre tente (pas d'ecran, JRE sans module graphique, dossier absent)
     */
    static boolean openFolder(java.nio.file.Path dir) {
        try {
            if (dir == null || !java.nio.file.Files.isDirectory(dir)) return false;
            if (java.awt.GraphicsEnvironment.isHeadless()) return false;
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop d = java.awt.Desktop.getDesktop();
                if (d.isSupported(java.awt.Desktop.Action.OPEN)) {
                    d.open(dir.toFile());
                    return true;
                }
            }
        } catch (Throwable t) {
            // repli ci-dessous
        }
        try {
            if (!java.awt.GraphicsEnvironment.isHeadless() && System.getProperty("os.name", "").toLowerCase().contains("win")) {
                new ProcessBuilder("explorer.exe", dir.toString())
                        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                return true;
            }
        } catch (Throwable t) {
            // pas d'explorateur : le chemin est affiche dans la page
        }
        return false;
    }

    /**
     * Selecteur de dossier du poste (bloquant jusqu'au choix).
     * @param start dossier de depart, ou vide
     * @return le chemin choisi, "" si l'utilisateur annule, null si le selecteur est indisponible
     *         (pas d'ecran, erreur, ou une boite deja ouverte)
     */
    static String chooseFolder(String start) {
        if (!CHOOSING.compareAndSet(false, true)) return null;
        try {
            if (java.awt.GraphicsEnvironment.isHeadless()) return null;
            final String[] out = { null };
            javax.swing.SwingUtilities.invokeAndWait(() -> out[0] = dialog(start));
            return out[0];
        } catch (Throwable t) {
            return null;
        } finally {
            CHOOSING.set(false);
        }
    }

    /**
     * Boite de dialogue d'erreur (bloquante jusqu'a sa fermeture) : sert quand
     * l'agent s'arrete au demarrage sans console pour le dire.
     * @return faux si rien n'a pu etre affiche (pas d'ecran, JRE sans module graphique) :
     *         le message n'est alors que dans le journal
     */
    static boolean error(String message) {
        try {
            if (java.awt.GraphicsEnvironment.isHeadless()) return false;
            final boolean[] shown = { false };
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                javax.swing.JFrame owner = null;
                try {
                    try { javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName()); }
                    catch (Exception e) { /* apparence par defaut */ }
                    owner = owner();
                    javax.swing.JTextArea text = new javax.swing.JTextArea(message == null ? "" : message);
                    text.setEditable(false);
                    text.setLineWrap(true);
                    text.setWrapStyleWord(true);
                    text.setOpaque(false);
                    text.setColumns(48);
                    text.setSize(text.getPreferredSize().width, 1);
                    javax.swing.JOptionPane.showMessageDialog(owner, text, "CoTransfert", javax.swing.JOptionPane.ERROR_MESSAGE);
                    shown[0] = true;
                } catch (Throwable t) {
                    // pas de boite : le journal suffit
                } finally {
                    if (owner != null) owner.dispose();
                }
            });
            return shown[0];
        } catch (Throwable t) {
            return false;
        }
    }

    /** Fenetre porteuse invisible, toujours au premier plan : une boite ne reste pas derriere le navigateur. */
    private static javax.swing.JFrame owner() {
        javax.swing.JFrame owner = new javax.swing.JFrame("CoTransfert");
        owner.setUndecorated(true);
        owner.setType(java.awt.Window.Type.UTILITY);
        owner.setAlwaysOnTop(true);
        owner.setSize(1, 1);
        owner.setLocationRelativeTo(null);
        owner.setVisible(true);
        owner.toFront();
        owner.requestFocus();
        return owner;
    }

    /** Sur le fil graphique : apparence du systeme, boite au premier plan. */
    private static String dialog(String start) {
        javax.swing.JFrame owner = null;
        try {
            try { javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception e) { /* apparence par defaut */ }
            javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
            fc.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
            fc.setMultiSelectionEnabled(false);
            fc.setDialogTitle("CoTransfert - choisir un dossier");
            fc.setApproveButtonText("Choisir");
            if (start != null && !start.isBlank()) {
                // Dossier de depart : le premier parent qui existe.
                File d = new File(start.trim());
                while (d != null && !d.isDirectory()) d = d.getParentFile();
                if (d != null) fc.setCurrentDirectory(d);
            }
            owner = owner();
            int r = fc.showOpenDialog(owner);
            File f = fc.getSelectedFile();
            return r == javax.swing.JFileChooser.APPROVE_OPTION && f != null ? f.getAbsolutePath() : "";
        } catch (Throwable t) {
            return null;
        } finally {
            if (owner != null) owner.dispose();
        }
    }
}
