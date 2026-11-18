package cotransfert;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Magasin de fichiers d'un canal asynchrone : l'endroit ou {@link MailboxSession}
 * depose ses lots et lit ceux du pair. Implementations : dossier local ou
 * support amovible ("disque"), serveur WebDAV ("webdav"), API REST Drime
 * ("drime"), et la cascade qui choisit parmi eux.
 *
 * Les chemins sont relatifs a la racine du magasin, separes par '/', sans '/'
 * initial : "MonProjet/hub-vers-client/000042.lot". Un segment peut contenir des
 * espaces ou des accents ; il ne contient jamais '/', '\' ni caractere de controle.
 *
 * Une implementation est sure pour plusieurs threads.
 */
public interface RelayStore extends AutoCloseable {

    /** Fichier d'un dossier du magasin. */
    record Entry(String name, long size) { }

    /** Identifiant du canal : "disque", "webdav" ou "drime". */
    String id();

    /** Description pour les journaux. Ne contient JAMAIS de secret (mot de passe, jeton). */
    String describe();

    /**
     * Vrai si le magasin est joignable maintenant. Ne leve jamais d'exception.
     * Le resultat est mis en cache par l'implementation : pas plus d'une requete
     * reseau par {@link #pollMs()}.
     */
    boolean available();

    /** Derniere cause d'indisponibilite ou d'echec, courte, en francais ; "" si aucune. */
    String lastError();

    /** Fichiers directs du dossier (pas les sous-dossiers). Dossier absent : liste vide. */
    List<Entry> list(String dir) throws IOException;

    /**
     * Contenu du fichier, ou null s'il est absent.
     * @param maxBytes &gt; 0 : au plus ce nombre d'octets, depuis le debut ; &lt;= 0 : tout.
     */
    byte[] read(String path, int maxBytes) throws IOException;

    /**
     * Ecrit le fichier (dossiers parents crees, existant remplace). Un lecteur ne
     * doit jamais confondre un fichier partiel avec un fichier complet : ecriture
     * sous un nom temporaire ".part" puis renommage quand le support le permet.
     */
    void write(String path, Path src) throws IOException;

    /** Comme {@link #write(String, Path)} pour un petit contenu en memoire. */
    void write(String path, byte[] data) throws IOException;

    /** Supprime le fichier. Absent : sans effet. Definitif quand le support a une corbeille parametrable. */
    void delete(String path) throws IOException;

    /** Periode de scrutation conseillee, en ms. */
    default long pollMs() { return 2000; }

    /** Taille maximale d'un fichier de lot acceptee par ce magasin, en octets. */
    default long maxLotBytes() { return 64L << 20; }

    /**
     * Change quand le magasin sous-jacent change (cascade). La session oublie
     * alors ce qu'elle croyait deja depose ou acquitte dans le magasin.
     */
    default int generation() { return 0; }

    /** Faux tant que les lots du pair ne doivent pas etre lus (cascade : pair pas encore sur ce magasin). */
    default boolean inboundReady() { return true; }

    @Override default void close() { }
}
