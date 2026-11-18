# CoTransfert — Possibilités d'évolution

Pistes d'évolution du produit. **Rien de ceci n'est implémenté**, sauf ce que
liste « Réalisé » au §3.5 ; pour l'état de ce qui est fait, voir `README.md`.

Toutes les pistes respectent l'esprit du produit (`CLAUDE.md`) : **pas de
pilote, pas de droit admin, rien à installer**, uniquement des mécanismes simples
et autorisés dans un maximum de contextes Windows. Une idée qui exige davantage
est signalée comme telle et reste optionnelle.

Sommaire :

1. [Robustesse et confort](#1-robustesse-et-confort)
2. [Configuration depuis l'IHM web](#2-configuration-depuis-lihm-web)
3. [Modes dégradés : fonctionner sans tunnel TCP/IP](#3-modes-dégradés--fonctionner-sans-tunnel-tcpip)
4. [Partage clavier-souris (KVM logiciel)](#4-partage-clavier-souris-kvm-logiciel)
5. [Ouverture à d'autres contextes](#5-ouverture-à-dautres-contextes)

---

## 1. Robustesse et confort

- **Secret partagé dans le handshake** : n'accepter que l'agent pair attendu
  (appairage par code affiché dans l'IHM, puis clé mémorisée dans la conf).
- **Chiffrement du flux** (TLS du JDK ou AEAD sur les cadres), au moins dès que le
  lien n'est plus un câble point‑à‑point.
- **Reprise incrémentale après coupure** : éviter le re‑manifeste complet sur les
  gros arbres (journal des changements depuis le dernier point de synchro).
- **Reprise des gros fichiers** interrompus (par blocs, avec hash par bloc).
- **Compression optionnelle** des petits fichiers texte (gain réseau vs CPU).
- **Transfert différentiel** (blocs modifiés seulement) pour les gros fichiers.
- **Démarrage automatique** des deux côtés sans admin (raccourci dans le dossier
  *Démarrage* de l'utilisateur, ou tâche planifiée de niveau utilisateur).
- **Surveillance à chaud du `.syncignore`** (aujourd'hui appliqué au redémarrage
  quand il est modifié localement, car `.cotransfert` n'est pas surveillé).
- **Lancement sans fenêtre** (fait) : `CoTransfert.cmd` lance l'agent avec
  `javaw` puis se termine ; journal dans `cotransfert.log`, erreur fatale dans
  une boîte de dialogue, redémarrage par un petit processus Java (`Relance`)
  sans aucun script. `CoTransfert.cmd --console` garde la fenêtre pour le
  diagnostic.
- **Versionnage explicite du jar** (fait, sans numéro) : la version est la date
  de construction du jar (celle de son manifeste) plus son empreinte SHA‑256 ;
  elle est affichée dans l'IHM et comparée pour la mise à jour. Aucune étape de
  build ni numéro à tenir. Reste possible : un numéro lisible en plus de la
  date.
- **Distribution du jar par l'agent lui‑même** (fait, quel que soit le
  canal) : le hub publie son jar dans le canal choisi (WebDAV, Drime, disque
  relais), scellé avec le secret partagé ; le client le voit, l'installe d'un
  clic — ou tout seul si `update.auto=true` — et redémarre, avec retour à la
  version précédente si la nouvelle ne démarre pas. Sur le **lien direct**, le
  hub range la publication dans un dossier local et la sert par son serveur
  HTTP embarqué ; le client la lit par des requêtes sortantes
  (`SPEC-cascade-canaux.md` §17.7). Reste à faire :
  - sur le lien direct, le client suppose que la page du hub écoute sur le
    même port que la sienne (`--web-port`, `47790` par défaut) : annoncer ce
    port dans le beacon du hub lèverait cette hypothèse ;
  - **mise à jour de `CoTransfert.cmd` lui‑même** : le script n'est pas
    distribué par le canal ; s'il change, il faut encore le porter à la main.
    L'ancienne mise à jour automatique du lanceur ne vit plus que dans
    `outils/` ;
  - mise à jour du hub depuis le client (aujourd'hui : le hub publie, le
    client installe, jamais l'inverse ; le transport, lui, sait déjà porter un
    dépôt du client vers le hub sur tous les canaux).
- **Synchronisation des dossiers vides** et des attributs (lecture seule…).
- **Résolution des conflits depuis l'IHM** :
  - **fait** (`SPEC-cascade-canaux.md` §18, `README.md` §3 brique 4) : écran
    « Conflits », liste des conflits en attente, « garder ma version » /
    « garder la version de l'autre poste », un par un ou pour tous ; le choix
    fait sur un poste met les deux postes d'accord ;
  - **à faire** : voir les deux versions côte à côte, fusionner, renommer la
    copie pour garder les deux fichiers ; régler depuis un poste les conflits
    de l'autre.
- **Tests automatisés** (JUnit, scénarios loopback hub + client).

## 2. Configuration depuis l'IHM web

**Fait** (`SPEC-cascade-canaux.md` §15, `README.md` §3.6 et §4) :

- Un seul fichier à lancer, `CoTransfert.cmd` ; l'agent sert toujours la page et
  l'ouvre dans le navigateur, même sans aucune configuration (premier réglage).
- Chaque poste se règle dans **sa** page : rôle, dossiers (ajouter, retirer,
  « Parcourir… »), secret partagé (générer, afficher), politique de conflit, hub
  forcé, découverte, proxy, certificats.
- Réglage des **canaux** (WebDAV, Drime, disque relais) avec un essai de
  connexion ; les secrets ne sont jamais renvoyés par l'API.
- **Choix du canal de transfert dans la page** (`SPEC-cascade-canaux.md` §16) :
  un seul canal à la fois, testé au clic (OK / KO avec la raison), aucun par
  défaut, enregistré et repris au redémarrage.
- API de réglage réservée à la boucle locale (adresse distante, `Host`,
  `Origin`, en-tête dédié), validation côté serveur, écriture atomique de
  `cotransfert.conf`, puis redémarrage de l'agent (par la relance ; par le script, code 75, avec `--console`).
- Instance unique : un second lancement rouvre la page existante.

**Reste à faire** :

- Ajout / retrait d'un dossier **à chaud** (une connexion par dossier existe
  déjà, via le handshake nommé `OP_HELLO`) : aujourd'hui tout enregistrement
  relance l'agent.
- Application **sur les deux postes** depuis une seule interface : le hub applique
  localement et envoie une commande de contrôle au client sur la connexion de
  sync. Aujourd'hui chaque poste se règle chez lui, et le secret se recopie à la
  main.
- Édition du `.syncignore` depuis la page (il est déjà synchronisé, le maître fait foi).
- Protection de la page par un mot de passe ou un jeton : aujourd'hui tout
  programme de la session locale peut appeler l'API de réglage.

---

## 3. Modes dégradés : fonctionner sans tunnel TCP/IP

### 3.1 Pourquoi

Le mode nominal suppose qu'une connexion TCP peut s'établir du client vers le hub
sur un lien direct. Ce n'est pas toujours possible :

- la mise en réseau sur Thunderbolt/USB4 est désactivée (BIOS, stratégie) ou le
  câble/port ne la supporte pas ;
- un agent de sécurité ou un VPN filtre **tout** le trafic vers les machines
  locales (le `ping` peut passer alors que TCP et UDP sont bloqués) ;
- aucun des deux postes n'a le droit d'accepter une connexion entrante ;
- il n'y a tout simplement pas de câble sous la main.

L'objectif des modes dégradés : **le produit continue de rendre le service**,
plus lentement, par un autre canal **autorisé** dans l'environnement.

> **Garde‑fou.** Un mode dégradé n'est pas un moyen de contourner une politique de
> sécurité. Si une organisation interdit un canal (clé USB, Bluetooth, cloud…),
> CoTransfert ne doit pas chercher à le rouvrir : on choisit un canal permis, ou on
> demande une exception. Chaque canal ci‑dessous est désactivé par défaut et
> s'active explicitement.

### 3.2 Prérequis d'architecture : abstraire le transport

Aujourd'hui `SyncEngine` dialogue avec `Transfer` (cadres `OP_HELLO`, `OP_FILE`,
`OP_DELETE`, `OP_MANIFEST`, `OP_STATUS`, `OP_PING` sur un flux TCP). Les modes
dégradés demandent de séparer **le protocole** de **son support** :

- une interface `Transport` (envoyer un cadre / recevoir un cadre / état du lien),
  avec deux familles d'implémentations :
  - **flux** (interactif, faible latence) : TCP aujourd'hui, port série demain ;
  - **boîte aux lettres** (asynchrone, *store‑and‑forward*) : les cadres sont
    regroupés en **lots** déposés dans un emplacement que l'autre poste lira plus
    tard ;
- un moteur tolérant à la latence : acquittements par lot, numéros de séquence,
  rejeu idempotent, aucun délai d'attente supposé court ;
- pour les canaux lents : **compression systématique**, **transfert différentiel**
  et **priorisation** (petits fichiers source d'abord, binaires lourds ensuite ou
  jamais, via un `.syncignore` propre au canal) ;
- **intégrité** (hash par lot) et **chiffrement authentifié** de bout en bout dès
  que le support sort du câble point‑à‑point (clé USB, dossier partagé, relais) ;
- un **choix du canal** visible dans l'IHM (« Lien direct », « Dossier relais »,
  « Série »…), avec changement de canal sans perte. La sélection automatique du
  meilleur canal a été réalisée puis écartée (voir §3.5).

L'index persistant (`.cotransfert/index.tsv`) et le manifeste existants servent
déjà de base : un lot n'est qu'un delta entre l'index local et le dernier état
connu du pair.

### 3.3 Échelle de repli

Du plus proche du nominal au plus dégradé. Les débits sont des **ordres de
grandeur estimés**, à mesurer.

| # | Canal | Sans IP entre les postes | Prérequis côté Windows | Débit (ordre de grandeur) | Nature |
|---|---|---|---|---|---|
| A | Autre lien IP (Ethernet direct, réseau local, Wi‑Fi) | non | Carte réseau existante | 100 Mb/s – 10 Gb/s | flux |
| B | Relais sortant (les deux postes sortent vers un tiers) | oui (pas de lien direct) | Accès sortant HTTPS | selon l'accès | flux |
| C | **Dossier relais** (partage réseau, dossier synchronisé cloud) | oui | Un dossier accessible aux deux | 1 – 100 Mo/s | boîte aux lettres |
| D | **Support amovible** (clé / disque USB, carte SD) | oui | Stockage de masse USB autorisé | 10 – 400 Mo/s, par passages | boîte aux lettres |
| E | **Valise manuelle** (archive export / import) | oui | N'importe quel moyen de porter un fichier | selon le moyen | boîte aux lettres |
| F | Port série (USB‑CDC, Bluetooth SPP) | oui | Port COM déjà présent, pilote de classe | 10 ko/s – 300 ko/s | flux |
| G | Optique (QR codes animés écran → webcam) | oui | Écran + webcam, navigateur | 1 – 20 ko/s | flux unidirectionnel |
| H | Acoustique (haut‑parleur → micro) | oui | Carte son | < 1 ko/s | flux unidirectionnel |

### 3.4 Détail des canaux

**A — Autre lien IP.** Pas un mode « sans IP », mais le premier repli, quasi
gratuit : le protocole actuel fonctionne sur tout lien où le client peut joindre
le hub. À faire : découverte étendue au‑delà de l'APIPA (sous‑réseau local),
`--peer <nom ou ip>`, et **chiffrement obligatoire** hors câble direct. Si c'est
l'autre poste qui a le droit d'écouter, il suffit d'**inverser les rôles**.

**B — Relais sortant.** Quand aucun des deux postes ne peut écouter, ou qu'il n'y
a pas de lien direct, les deux ouvrent une connexion **sortante** vers un point de
rendez‑vous qui relaie les cadres (WebSocket/HTTPS, port 443). Le relais ne voit
que du chiffré de bout en bout. Exige un serveur tiers (auto‑hébergeable, simple
jar) : à réserver aux contextes où c'est explicitement permis.

**C — Dossier relais.** Le canal dégradé **prioritaire** : meilleur rapport
service rendu / complexité. Les deux agents pointent sur un même dossier —
partage réseau (SMB), dossier d'un client de synchronisation déjà installé et
autorisé (OneDrive, SharePoint, Nextcloud…) — et y échangent des lots :

```
<relais>/cotransfert/<NomLogique>/
  hub-vers-client/000042.lot      # cadres compressés + chiffrés, numérotés
  client-vers-hub/000017.lot
  hub.ack   client.ack            # dernier lot appliqué par chacun
```

Chaque agent écrit ses lots (fichier temporaire puis renommage atomique),
surveille l'arrivée de ceux du pair, les applique, publie son acquittement ; les
lots acquittés sont purgés. Aucune connexion entre les postes, aucun port ouvert,
uniquement des entrées/sorties fichier. Latence = celle du support (quasi nulle
sur un partage, quelques secondes à minutes via un cloud). Points d'attention :
quota et taille maximale de fichier du support (découper les lots), fichiers
verrouillés par le client de synchronisation, données qui transitent chez un
tiers (d'où le chiffrement).

**D — Support amovible.** Même format de lots que C, mais le dossier relais est
sur une clé ou un disque que l'on **déplace** d'un poste à l'autre. L'agent
détecte l'arrivée du volume (scrutation des lecteurs, repérage par un fichier
marqueur), dépose ses lots sortants, applique les entrants, puis signale dans
l'IHM « Vous pouvez retirer le support ». Chaque passage fait avancer la synchro
d'un demi‑tour ; deux passages = convergence. Variante sans manipulation : un
**commutateur USB** partageant un même support entre les deux postes (matériel
passif, stockage de masse standard, aucun pilote). Exclus : les câbles USB « de
transfert » pont à pont, qui exigent un pilote ou un logiciel propriétaire.

**E — Valise manuelle.** Version ponctuelle et explicite de D, utile aussi comme
outil de secours : `--export <fichier>` produit une archive du delta depuis le
dernier état connu du pair (ou complète), `--import <fichier>` l'applique avec les
règles de conflit habituelles et produit en retour l'archive d'acquittement. Le
fichier voyage par n'importe quel moyen permis. C'est la première brique à écrire :
C et D ne sont que son automatisation.

**F — Port série.** Un flux bidirectionnel sans pile IP. Sans pilote à installer,
seuls comptent les ports COM **déjà fournis par un pilote de classe Windows** :
périphériques USB‑CDC/ACM (pilote `usbser` intégré à Windows 10/11) et profil
série Bluetooth (SPP) entre deux postes appairés par l'utilisateur. Le JDK n'a pas
d'API série, mais un port COM s'ouvre comme un fichier (`\\.\COMx`) et se règle
par la commande intégrée `mode` — donc sans JNI ni dépendance. Débit faible : à
réserver aux sources (texte compressé, différentiel), avec un protocole fenêtré
(séquence, CRC, retransmission). À valider par POC : fiabilité de la lecture
bloquante d'un port COM depuis le JDK, contrôle de flux, appairage Bluetooth
souvent restreint par stratégie.

**G — Optique.** Dernier recours quand rien ne relie les postes : l'IHM web d'un
poste affiche un flux de **QR codes animés**, la webcam de l'autre les lit depuis
une page du navigateur (API caméra, décodage en JavaScript embarqué dans la page,
toujours zéro installation). Codes fontaine pour tolérer les images perdues sans
voie de retour ; bidirectionnel si chaque poste filme l'écran de l'autre. Quelques
ko/s : adapté à un correctif, un fichier de configuration, une poignée de sources
— pas à un dépôt entier. Contrainte : l'accès caméra du navigateur demande un
contexte sécurisé (`localhost` convient).

**H — Acoustique.** Modem audio haut‑parleur → micro avec `javax.sound.sampled`
(inclus dans le JDK). Débit dérisoire ; mentionné pour mémoire, intérêt surtout
pour échanger un **code d'appairage** ou une clé plutôt que des fichiers.

### 3.5 Avancement

**Réalisé** (voir `README.md` §3.7 et §3.8) :

1. Interface de transport (`Transfer.Session`) + moteur tolérant à l'asynchrone.
2. **E — valise** `--export` / `--import`.
3. **C — dossier relais** et **D — support amovible** (`relay=*:\Dossier`).
4. Affichage du canal dans l'IHM.
5. **A — découverte hors APIPA** (`discovery=lan`) + chiffrement par secret partagé.
6. Abstraction de stockage `RelayStore` : un canal asynchrone n'est plus qu'un
   magasin de fichiers (`DirRelayStore` pour le disque) ; le format des lots et
   le moteur ne changent pas.
7. **Canal WebDAV** (`WebDavRelayStore`) et **canal Drime** par son API REST
   (`DrimeRelayStore`) : HTTPS sortant, lots chiffrés, secret obligatoire, purge
   après lecture. Variante en ligne de C qui ne demande aucun client de
   synchronisation installé.
8. **Choix manuel du canal** (`SPEC-cascade-canaux.md` §16) : un seul canal à la
   fois (`channel=`), choisi et testé dans la page, aucun par défaut ; chaque
   poste ouvre sa session par lots directement sur le magasin choisi, sans
   attendre l'autre ; canal en panne = on attend, sans changer de canal ;
   changement de canal sans perte (lots conservés dans le spool local).
9. Proxy du poste et magasin de certificats de Windows (`proxy=`,
   `tls.truststore=`), sans jamais affaiblir la vérification TLS.
10. Panneau **« Canal de transfert »** dans l'IHM : l'état de chaque canal et la
    raison d'une indisponibilité.

> Les points 6 à 10 ne sont validés que par des tests automatisés contre de faux
> serveurs lancés dans le même processus : jamais contre un vrai serveur WebDAV,
> jamais contre le vrai service Drime, jamais entre deux machines réelles.

> **Idée écartée pour l'instant : la bascule automatique entre canaux.** Une
> cascade `direct → webdav → drime → disque` a existé (`RelayCascade` :
> descente et remontée automatiques, anti-oscillation, « le client décide, le
> hub suit », marqueurs et époques dans les magasins). Elle fonctionnait dans
> les tests, mais à l'usage elle s'est révélée **trop difficile à comprendre et
> à dépanner** : un canal réglé sur un seul poste, et la page disait « À jour »
> alors que rien ne partait, le hub attendant en silence que le client
> choisisse. Elle a été retirée du code au profit du choix manuel. À ne
> reprendre que si l'on sait la rendre lisible : dire à tout moment quel canal
> sert, pourquoi, et ce que l'autre poste doit faire.

**Reste à faire sur ces canaux** :

- **Validation réelle du choix du canal et des canaux en ligne** : un vrai
  serveur WebDAV, le vrai service Drime (points d'API, doublons de nom, limites
  de débit et quota à confronter au service), deux machines réelles. À faire en
  premier sur un poste verrouillé : c'est le proxy qui décide si les canaux en
  ligne sont utilisables.
- **Proxy authentifiant** : un proxy exigeant NTLM ou Kerberos n'est pas géré,
  et une configuration par script PAC n'est pas lue par le JDK. Aujourd'hui le
  canal est « indisponible » (le test répond KO). À étudier sans rien
  contourner : uniquement ce que le JDK et le poste permettent déjà.
- **Vieux lots dans un magasin abandonné puis re-sélectionné.** À chaque
  changement de canal, chaque poste retire au mieux ses propres lots de l'ancien
  magasin ; s'il était injoignable à ce moment-là, ils y restent. Re-sélectionné
  plus tard, ce magasin peut faire relire d'anciens lots : copies
  `.conflict-peer-…` avec `keep-both` (jamais une perte), version ancienne
  réimposée avec `master-wins`. À fermer en datant le flux (refuser un lot d'un
  flux abandonné), sans réintroduire de marqueurs à comprendre.
- **Suppression dans un lot abandonné** : au passage d'un canal par lots au lien
  direct, une suppression qui n'avait pas encore été lue par l'autre poste n'est
  pas propagée (le fichier revient). À couvrir par la réconciliation directe.
- **Changement de canal coordonné** : aujourd'hui l'utilisateur choisit le même
  canal sur chaque poste, à la main. Une invitation affichée sur l'autre poste
  (« l'autre poste est passé sur Drime ») serait plus confortable, sans bascule
  automatique.
- **Volume de requêtes vers les magasins en ligne** : chaque dossier scrute son
  magasin toutes les 10 s (liste des lots, acquittements), même quand
  rien ne change. À mesurer, puis à réduire (scrutation qui s'espace au repos,
  requêtes conditionnelles) — les limites de débit de Drime ne sont pas
  documentées.
- Validation entre deux machines réelles des canaux plus anciens (relais sur
  partage, sur dossier cloud, sur clé USB ; `discovery=lan` sur un vrai réseau
  local).
- Un lot est tenu entièrement en mémoire (2 à 3 fois sa taille au pic), d'où des
  lots plus petits en ligne (16 Mio sur Drime). À remplacer
  par une lecture et une écriture en flux.
- Acquittements non authentifiés ; pas de rotation du
  secret ; secret, mot de passe WebDAV et jeton Drime en clair dans la
  configuration ; poignée de main qui n'est pas un PAKE (un secret court se casse
  hors ligne — d'où la longueur minimale imposée).
- Plusieurs comptes par canal, autres fournisseurs à API propriétaire : hors
  périmètre pour l'instant.
- Première synchronisation avec un même chemin et un contenu différent des deux
  côtés : copie `.conflict` systématique (pas d'ancêtre commun connu).
- Valise : chaque export porte un manifeste, il n'y a donc pas de « fin »
  naturelle au va-et-vient ; à remplacer par un état « rien à échanger ».

**Ensuite** :

11. **B — relais sortant** temps réel (les briques de chiffrement existent).
12. POC **F — série**, puis **G — optique** selon les retours d'usage.

---

## 4. Partage clavier-souris (KVM logiciel)

Partager clavier et souris entre le hub et le client en passant le curseur d'un
écran à l'autre (famille Synergy / Input Leap / Mouse Without Borders). **Pas de
partage d'écran**, seulement les entrées. Un lien direct est idéal (latence < 1 ms).

**Avancement** : un prototype est intégré à l'agent (désactivé par défaut, lien
direct et secret partagé requis ; voir [`SPEC-km.md`](SPEC-km.md)). Il reste en
Java pur : la capture se fait par une **fenêtre AWT plein écran** et non par des
crochets natifs ; le départ se fait par le bord d'écran, la page ou un raccourci
Windows `.lnk`. Testé avec des doublures seulement : **à valider sur deux vrais
postes** (focus de la fenêtre de capture, touches AZERTY avec `Robot`, réaction
des protections du poste). Le presse-papiers partagé est écarté (risque de fuite
de données). Les points ci-dessous sont la réflexion d'origine.

- **Injection** côté poste piloté : `java.awt.Robot` (JDK pur, sans admin, même
  modèle de déploiement que l'agent).
- **Capture** côté poste pilote : demande des crochets bas niveau Windows
  (`WH_MOUSE_LL` / `WH_KEYBOARD_LL`), donc du code natif (JNA/JNI) — **hors du
  cœur zéro dépendance** : module optionnel uniquement.
- **Transport** : seconde connexion de contrôle sur le lien existant (toujours
  ouverte par le client vers le hub).
- Bonus possibles : presse‑papiers partagé, glisser‑déposer de fichiers.
- **Limites** : sans droits admin, `Robot` ne pilote pas les fenêtres élevées, les
  invites UAC, l'écran de verrouillage ni le bureau sécurisé (isolation de
  privilèges Windows).
- **Point de vigilance** : l'injection d'entrées synthétiques ressemble à un outil
  de prise de contrôle à distance. Une protection comportementale peut alerter et
  une politique de sécurité peut l'interdire, même si Java est autorisé. À valider
  dans l'environnement visé avant d'investir ; désactivé par défaut.
- **Approche progressive** : v1 « prise de main par raccourci », puis v2
  franchissement de bord transparent (capture + mapping multi‑écrans + raccourci
  de secours).

## 5. Ouverture à d'autres contextes

- **Plus de deux postes** : un hub, plusieurs clients (le routage par `OP_HELLO`
  s'y prête ; reste la propagation client → hub → autres clients).
- **Autres systèmes** : le cœur Java est portable ; la découverte (`arp -a`) et
  les lanceurs sont à décliner pour Linux et macOS.
- **Lanceurs** : variante `.cmd` complète pour les postes où PowerShell est
  restreint ; raccourci de bureau généré sans admin.
- **Internationalisation** des messages et de l'IHM (français aujourd'hui).
- **Journal d'activité** consultable dans l'IHM (derniers fichiers transférés,
  débit, erreurs) et export de diagnostic pour les rapports d'anomalie.
