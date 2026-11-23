# CoTransfert — guide pour les contributeurs (humains et agents)

CoTransfert synchronise des dossiers dans les deux sens entre deux postes
Windows : un **hub** et un **client**, reliés par un lien direct. Voir
`README.md` pour l'architecture et `ROADMAP.md` pour les évolutions envisagées.

## Esprit du produit (non négociable)

Le produit doit fonctionner dans **un maximum de contextes Windows**, y compris
les postes les plus verrouillés. Toute contribution respecte ces règles :

1. **Aucune installation de pilote.** Pas de WinUSB, libusb, Zadig, pilote
   filtre, carte réseau virtuelle (TAP/TUN/Wintun), pilote d'imprimante ou de port
   virtuel. On n'utilise que les pilotes de classe déjà fournis par Windows.
2. **Aucun droit administrateur, jamais.** Pas d'élévation UAC, pas de service
   Windows, pas d'écriture dans `HKLM` ni dans `Program Files`, pas de règle
   pare-feu créée par admin, pas de tâche planifiée système.
3. **Rien à installer.** Le livrable est un `.jar` unique exécuté par un Java
   déjà présent, plus des scripts lisibles (`.ps1`, `.cmd`). Pas d'installeur, pas
   d'exécutable natif (souvent bloqué ou supprimé quand il n'est pas signé).
4. **Uniquement des mécanismes simples et autorisés partout** : API standard du
   JDK, sockets sortantes, fichiers dans le profil utilisateur, commandes Windows
   intégrées (`arp`, PowerShell 5.1). Si une fonctionnalité exige plus, elle est
   optionnelle, désactivée par défaut, et le produit marche sans elle.
5. **Ne jamais contourner une politique de sécurité.** On s'appuie sur ce qui est
   permis ; on ne cherche pas à passer sous un pare-feu, un VPN, un EDR ou un DLP.
   Quand un environnement interdit un canal, on propose un autre canal autorisé
   (voir les modes dégradés de `ROADMAP.md`), pas une astuce d'évasion.
6. **Aucune perte silencieuse de données.** Écriture atomique, conflits
   conservés (`keep-both` par défaut), pas de suppression propagée sans certitude.

## Vocabulaire

On ne parle que de **hub** et de **client** :

- **hub** : le poste qui peut accepter une connexion entrante ; il écoute,
  diffuse le beacon et héberge l'IHM web.
- **client** : le poste qui ne fait que du sortant ; il découvre le hub et ouvre
  la connexion.
- **maître / esclave** : uniquement pour l'arbitrage des conflits (le hub est
  maître par défaut).

Le code, la doc, les scripts et les exemples restent **génériques** : pas de nom
de machine, d'entreprise, d'utilisateur, d'IP ou de chemin propre à un
environnement particulier. Exemples de chemins : `C:\work\MonProjet`.

## Contraintes techniques

- **Java SE pur, bytecode 21** (`javac --release 21`), **zéro dépendance
  externe**, pas de framework, pas de JNI/JNA dans le cœur.
- Le client **n'écoute jamais** : toute nouvelle communication est une connexion
  sortante du client vers le hub ; le hub répond et pousse sur cette connexion.
- Aucune IP codée en dur (adressage APIPA dynamique) ; écoute en IPv4
  (`0.0.0.0` + `-Djava.net.preferIPv4Stack=true`).
- Scripts PowerShell compatibles **Windows PowerShell 5.1**, lancés avec
  `-ExecutionPolicy Bypass -File`, sans module externe. Passer les arguments Java
  par un tableau splatté (`& $java @jargs`).
- Lancement **sans console** : `CoTransfert.cmd` (batch pur) lance `javaw` puis se
  termine ; le journal va dans `cotransfert.log`. Le redémarrage et la mise à
  jour du jar sont faits **en Java** (`Relance`, `Updater`), sans autre script,
  et ne laissent jamais le poste sans `cotransfert.jar` complet.
- `cotransfert.conf` est local à chaque poste et n'est jamais versionné.
- **Un seul point d'entrée pour l'utilisateur : `CoTransfert.cmd`.** Tout le
  reste se règle dans la page web (les options de ligne de commande restent,
  pour l'usage avancé). L'API de réglage n'est servie qu'à la boucle locale.
  Seules routes ouvertes au lien : `/api/status` (lecture seule) et, sur le hub
  réglé sur le lien direct avec un secret, `/maj/liste` et `/maj/fichier` (les
  deux fichiers scellés de la mise à jour, rien d'autre — voir `MajRoutes`).

## Build

```bash
javac --release 21 -d build/classes $(find src/main/java -name '*.java')
cp -r src/main/resources/. build/classes/
jar --create --file cotransfert.jar --main-class cotransfert.Agent -C build/classes .
```

## Tests

Sans dépendance (simples `main`, code de sortie non nul en cas d'échec), dans
`src/test/java` :

```bash
javac --release 21 -cp build/classes -d build/test-classes $(find src/test/java -name '*.java')
java -cp "build/classes;build/test-classes" cotransfert.MailboxTest <dossier-de-travail>
java -cp "build/classes;build/test-classes" cotransfert.WebDavStoreTest <dossier-de-travail>
java -cp "build/classes;build/test-classes" cotransfert.DrimeStoreTest <dossier-de-travail>
java -cp "build/classes;build/test-classes" cotransfert.ChannelsEndToEndTest <dossier-de-travail>
java -cp "build/classes;build/test-classes" cotransfert.CryptoTest --no-bench
java -cp "build/classes;build/test-classes" cotransfert.ConflictsTest <dossier-de-travail>
java -cp "build/classes;build/test-classes" cotransfert.ReconcileDeadlockTest <dossier-de-travail>
java -cp "build/classes;build/test-classes" cotransfert.SetupApiTest <dossier-de-travail>
java -cp "build/classes;build/test-classes" cotransfert.UpdateTest <dossier-de-travail>
java -cp "build/classes;build/test-classes" cotransfert.KmTest <dossier-de-travail>
```

`KmTest` (partage clavier-souris, voir `SPEC-km.md`) ne passe que par des
doublures : aucun test n'utilise le vrai `java.awt.Robot`, n'ouvre la fenêtre de
capture, ne lance PowerShell ni ne crée de raccourci Windows.

Les tests des canaux en ligne tournent contre de faux serveurs lancés dans le
même processus (`FakeWebDavServer`, `FakeDrimeServer`) : aucun test n'appelle un
service réel.

Pour un essai de bout en bout sur une seule machine : hub et client avec des
`--config` distincts, puis `--channel direct` + `--peer 127.0.0.1` (lien
direct), ou `--channel disque` + `--relay <dossier>` + `--secret` sur les deux
(canal par lots : aucun des deux n'attend l'autre).

## Architecture en une phrase

`SyncEngine` ne parle qu'à `Transfer.Session` (flux ordonné et fiable de cadres) ;
`TcpSession` (direct) et `MailboxSession` (canaux par lots, valise)
l'implémentent ; `MailboxSession` dépose ses lots directement dans le
`RelayStore` du canal (`DirRelayStore` disque, `WebDavRelayStore`,
`DrimeRelayStore`) ; `Agent` utilise **le seul canal choisi par l'utilisateur**
(`channel=` : `direct`, `webdav`, `drime`, `disque`, ou aucun — jamais de
bascule automatique), ouvre une session par dossier et garantit qu'un seul
moteur tourne par dossier. Un nouveau canal par lots est une nouvelle
implémentation de `RelayStore`, sans toucher au moteur ni à `MailboxSession` ;
un nouveau canal en flux est une nouvelle implémentation de `Session`, sans
toucher au moteur.
