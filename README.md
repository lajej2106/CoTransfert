# CoTransfert

Synchronisation bidirectionnelle de répertoires entre deux postes Windows — un
**hub** et un **client** — reliés par un lien réseau direct (câble
**Thunderbolt / USB4**, Ethernet…), **sans droits admin, sans service, sans
pilote, sans IP fixe**.

Pensé pour les dépôts de développement (beaucoup de petits fichiers) et pour les
postes verrouillés : un simple `.jar` exécuté par un Java déjà présent, rien à
installer.

Quand le lien direct n'est pas possible, la synchronisation peut passer par un
autre **canal de transfert** : **WebDAV**, **Drime** ou un **disque relais**.
C'est **vous qui choisissez le canal**, dans la page, un seul à la fois et le
même sur les deux postes ; la page le teste avant de l'enregistrer. Aucun canal
n'est choisi par défaut et CoTransfert ne change jamais de canal tout seul
(voir §3.7).

- Feuille de route et pistes d'évolution : [`ROADMAP.md`](ROADMAP.md)
- Principes du produit (à respecter pour toute contribution) : [`CLAUDE.md`](CLAUDE.md)

---

## 1. Modèle hub / client et contraintes

CoTransfert vise le cas le plus contraint d'un parc Windows : aucun droit admin,
pare-feu en profil *Public* (`BlockInbound, AllowOutbound`), exécution de
binaires restreinte. Les deux rôles découlent de ce que chaque poste **a le droit
de faire sur le réseau** :

| Rôle | Ce que le poste doit pouvoir faire | Ce qu'il fait |
|---|---|---|
| **Hub** | Accepter une connexion entrante (l'utilisateur peut valider l'alerte pare-feu Windows au 1er lancement — règle locale, sans admin) | Écoute, diffuse le beacon de découverte, héberge l'IHM web |
| **Client** | Uniquement du **sortant** (aucune écoute, aucun entrant non sollicité, même pas la réponse à un broadcast) | Découvre le hub, ouvre la connexion |

Si les deux postes peuvent écouter, le choix du hub est libre. Si aucun ne le
peut, voir les modes dégradés dans [`ROADMAP.md`](ROADMAP.md).

Faits établis par POC sur ce modèle :

| Point | Résultat | Conséquence |
|---|---|---|
| Droits admin | Aucun des deux côtés | Pas de service, pas de règle pare-feu par admin |
| Client en écoute / réception de broadcast | **Impossible** en environnement verrouillé | Pas de découverte passive côté client |
| Sortant + trafic retour (flux initié par le client) | **OK** | Base de toute la communication |
| Push hub→client sur la socket du client | **OK** | Sync bidirectionnelle sur une seule connexion |
| Découverte par table ARP (beacons) + handshake unicast | **OK** | IP du hub résolue sans rien coder en dur |
| Binaire natif non signé (`.exe`) | Souvent **bloqué ou supprimé** (EDR, AppLocker) | Pas de binaire natif |
| `.jar` exécuté par un `java.exe` déjà présent | **OK** | Java retenu |
| Débit mesuré (lien Thunderbolt, mono-flux, buffers 4 Mio) | **4,39 Gbps (549 Mo/s)** | Le CPU devient le facteur limitant, pas le lien |

---

## 2. Choix technologique : **Java** (bytecode 21, pas de framework)

Un exécutable natif autonome serait plus simple à distribuer, mais les postes
verrouillés bloquent couramment les binaires non signés. Un `.jar`, lui, n'est
que de la donnée exécutée par un `java.exe` signé et déjà autorisé.

- **Cible bytecode : Java 21 (LTS)** — un seul `.jar` pour tout JDK/JRE ≥ 21.
- **Java SE pur, zéro dépendance externe** (démarrage instantané, jar ~190 Ko).
- **Pas de runtime à embarquer** : on utilise celui déjà présent sur le poste.
- `WatchService` → `ReadDirectoryChangesW` pour la surveillance.

---

## 3. Les quatre briques

### Brique 1 — Auto‑découverte (`Discovery.java`)

Le schéma « chacun broadcaste et écoute les réponses » ne marche pas : un client
verrouillé ne reçoit ni broadcast non sollicité, ni réponse unicast à un
broadcast. Mécanisme retenu :

1. Le **hub diffuse** un beacon UDP broadcast en continu.
2. Le **client** ne le reçoit pas applicativement, mais la trame remplit sa
   **table de voisinage ARP sous le pare-feu**. Sur un lien point‑à‑point, un seul
   voisin `169.254.x` : le hub.
3. Le client confirme par un **DISCOVER unicast → ACK** (trafic retour autorisé).

Robuste au multi‑interfaces : on teste chaque interface APIPA et on retient celle
dont le voisin répond `ACK`. Repli manuel : `--peer <ip>`.

### Brique 2 — Surveillance (`Watcher.java`)

`WatchService` récursif (→ `ReadDirectoryChangesW`), avec :

- **Debounce** ~250 ms par chemin (un compilateur réécrit un `.class` plusieurs
  fois en quelques ms) ; au flush, le type d'événement est déduit de l'existence
  du fichier (UPSERT / DELETE).
- Enregistrement dynamique des nouveaux dossiers (création d'arborescence d'un coup
  gérée, y compris les fichiers déjà présents non remontés par l'API).
- **Exclusions pilotées par `.syncignore`** (voir §3.5), pas en dur. Par défaut,
  synchronisation à 100% (dossiers iso, y compris `.git`, `.idea`, conf, compilés).

### Brique 3 — Transfert haut débit (`Transfer.java`)

- **Une** connexion TCP persistante, ouverte par le client, **full‑duplex**.
- `SO_SNDBUF`/`SO_RCVBUF` 8 Mio (réglés avant `bind` côté serveur), blocs
  applicatifs 4 Mio, `TCP_NODELAY`, keep‑alive, écoute forcée en IPv4.
- **Pipelining** : entête binaire + contenu à la suite, sans aller‑retour par
  fichier — essentiel pour les milliers de petits fichiers d'un dépôt.
- Mode `--bench` intégré.

### Brique 4 — Anti‑boucle et conflits (`SyncEngine.java`)

**Anti‑écho** : avant d'écrire un fichier reçu, on inscrit sa signature
`(chemin → hash)` dans une table « appliqué récemment » (purge 10 s) ; le watcher
ignore l'événement qu'il déclenchera sur ce fichier si le hash correspond.

**Conflits**, détectés à la réception d'un fichier :

- si le local n'a pas changé depuis la dernière sync → on accepte l'entrant
  (**dernière modification gagne**, cas normal) ;
- si le local ET l'entrant ont divergé de la référence → **conflit**, résolu selon
  `--on-conflict` :
  - **`keep-both`** (défaut, sûr) : on garde le local, on dépose l'entrant sous
    `fichier.conflict-peer-<date>`, on journalise une **alerte** `[CONFLIT]` et une
    ligne dans `.cotransfert/conflicts.log`. Rien n'est perdu ni écrasé en silence ;
    le choix se fait ensuite dans la page (voir « Conflits » ci-dessous).
  - **`master-wins`** : la version du poste **maître** (`--master`, le hub par
    défaut) l'emporte ; l'esclave accepte celle du maître.
  - **`newer-wins`** : la date de modification la plus récente gagne.
- **Index persistant** `(chemin → hash, mtime, taille)` dans `.cotransfert/index.tsv`
  pour détecter la divergence et survivre aux redémarrages.
- Le `.syncignore` ne produit jamais de copie `.conflict` : c'est un fichier de
  contrôle, le **maître** l'impose au pair.

#### Conflits : les voir et les régler depuis la page

**Ce qu'est un conflit.** Le même fichier a été modifié sur les deux postes avant
qu'ils aient pu se synchroniser. Avec la politique par défaut (`keep-both`),
**rien n'est perdu** : chaque poste garde sa version à sa place et range celle
de l'autre poste juste à côté, sous `fichier.conflict-peer-AAAAMMJJ-HHMMSS`.
Selon le moment où les envois se croisent, le conflit est vu par un seul poste
ou par les deux.

**L'écran « Conflits »** de la page (bouton « N conflits » de la carte du
dossier, ou bouton « Conflits » en haut) liste les conflits en attente **de ce
poste** : fichier, date du conflit, taille et date de chaque version. Pour
chacun, ou pour tous d'un coup :

| Choix | Sur ce poste | Sur l'autre poste |
|---|---|---|
| **Garder ma version** | mon fichier ne bouge pas, la version mise de côté est supprimée | reçoit mon fichier et l'adopte, sans nouveau conflit ; s'il avait lui aussi mis ma version de côté, cette copie devenue inutile est retirée |
| **Garder la version de l'autre poste** | la version mise de côté remplace mon fichier (déplacement atomique, date de modification conservée) | garde son fichier ; s'il avait mis ma version de côté, cette copie est retirée (je viens de l'écarter) |

Dans les deux cas les deux postes finissent avec **le même contenu**, et le
compteur de conflits redescend. Le choix se fait sur **un seul** poste : inutile
de le refaire sur l'autre.

À savoir :

- **Les copies ne sont jamais synchronisées.** Un fichier dont le nom porte
  `.conflict-peer-` ne quitte pas le poste qui doit trancher : il n'est ni
  envoyé, ni supprimé chez l'autre, ni accepté s'il arrive. Règle fixe, que le
  `.syncignore` ne peut pas lever.
- **Tant qu'un conflit attend sur un poste, son fichier est protégé** : aucun
  envoi ordinaire de l'autre poste ne l'écrase (une version déjà gardée en copie
  est ignorée, une version nouvelle devient une copie de plus) et une
  suppression venue de l'autre poste est ignorée.
- **Sans session en cours** (aucun canal choisi, autre poste absent) le choix
  est appliqué tout de suite sur ce poste et envoyé au début de la prochaine
  session. Le compteur vient de la liste des copies encore présentes : il
  survit aux redémarrages.
- **Choix contraires faits en même temps sur les deux postes** (« ma version »
  des deux côtés avant que l'un ait reçu le choix de l'autre) : aucun des deux
  n'est appliqué en silence. Chaque poste garde ce qu'il a choisi, retrouve la
  version de l'autre à côté, et le conflit se règle de nouveau, une seule fois.
- **Mon fichier a disparu depuis le conflit.** « Garder la version de l'autre
  poste » le restaure à partir de la copie. « Garder ma version » veut alors
  dire « pas de fichier » : la copie est supprimée et la suppression est
  **envoyée à l'autre poste**, qui supprime son fichier s'il est bien resté
  celui que je viens d'écarter (s'il l'a modifié depuis, il le garde). Si
  l'autre poste avait lui aussi mis mon ancienne version de côté, cette copie
  reste chez lui, à trancher depuis sa page : c'est la seule trace de ce
  fichier que j'ai supprimé à la main.
- **Supprimé d'un côté, modifié de l'autre** (sans copie) : ce n'est pas un
  conflit listé. La modification l'emporte : le fichier modifié est conservé et
  revient sur le poste qui l'avait supprimé à la synchronisation suivante.
- Le fichier `.cotransfert/conflicts.log` garde l'historique ; une ligne dont la
  copie n'existe plus est un conflit réglé. `.cotransfert/resolutions.tsv`
  retient les derniers choix tant que l'autre poste n'en a pas accusé réception.
- Avec `master-wins` ou `newer-wins`, il n'y a pas de copie donc rien à lister.

Comparer les deux versions côte à côte ou les fusionner depuis la page n'existe
pas encore : « Ouvrir l'emplacement » ouvre le dossier pour le faire à la main
avant de choisir.

### 3.5 — Configuration : `.syncignore` et `cotransfert.conf`

**`<dossier>/.cotransfert/.syncignore`** — exclusions façon `.gitignore`. Ce fichier
est **lui-même synchronisé** (identique des deux côtés), le maître faisant foi. Par
défaut il n'exclut rien (dossiers 100% iso) et contient des exemples commentés
(`# **/target`, `# **/node_modules`...) prêts à activer. Motifs gérés : `#` commentaire,
`**/x`, `x/` (dossier), `*`/`?`, `/x` (ancré). Les fichiers internes de `.cotransfert`
(index, `tmp/`, `conflicts.log`) sont toujours exclus ; `.syncignore`, toujours inclus.
Activer une exclusion **arrête** la synchro de ces chemins **sans supprimer** les
copies déjà présentes chez le pair.

**`cotransfert.conf`** (à côté du jar, **par poste**, non synchronisé, non versionné)
— mémorise le rôle, la politique de conflit et **le ou les dossiers** à
synchroniser. Les dossiers sont appariés par un **nom logique** identique des deux
côtés (le chemin local, lui, diffère) :

```
role=hub
master=true
conflict=keep-both
root.MonProjet=C:\work\MonProjet
# root.Autre=D:\work\autre
```

Plusieurs dossiers = plusieurs connexions, chacune annoncée par un handshake nommé
(`OP_HELLO`) que le hub route vers son chemin local correspondant.

**Canal de transfert** (voir §3.7) — `channel=` dit lequel est utilisé ; les
autres clés règlent les canaux. On peut en régler plusieurs, un seul sert :

```
# Canal choisi : direct, webdav, drime ou disque. Vide = aucun, rien n'est synchronisé.
# Normalement posé par la page (qui teste le canal avant), le même sur les deux postes.
channel=direct

# Canal WebDAV
webdav.url=https://cloud.example.org/remote.php/dav/files/utilisateur/CoTransfertRelais
webdav.user=utilisateur
webdav.password=mot-de-passe-d-application

# Canal Drime
drime.token=jeton-personnel
drime.folder=CoTransfertRelais
# drime.workspace=0
# drime.url=https://app.drime.cloud/api/v1

# Canal disque relais
relay=*:\CoTransfertRelais

# Réseau
# proxy=system
# tls.truststore=java

# Mise à jour par le canal (client) : installer tout seul la version publiée par le hub
# update.auto=false

# Clavier et souris partagés (lien direct + secret requis ; désactivé par défaut)
# km.enabled=false
# km.edge=aucun            # gauche, droite, haut, bas : bord de CE poste derrière lequel est l'autre
# km.edge.delay=250        # ms contre le bord avant de passer
# km.hotkey=Ctrl+Alt+F12   # raccourci de bascule, ou aucun
# km.return.button=0       # bouton latéral de la souris qui rend la main : 4, 5, ou 0
# km.clipboard=false       # le texte copié suit le clavier (à activer sur les deux postes)
```

- Un canal est **réglé** quand ses clés obligatoires sont renseignées :
  `webdav.url` + `webdav.user` + `webdav.password` ; `drime.token` ; `relay`
  (ou `relay.<Nom>` pour un dossier précis). Le lien direct n'a rien à régler.
  Régler un canal ne le choisit pas.
- L'ancienne clé `channels=` (ordre d'une bascule automatique) n'existe plus :
  elle est ignorée à la lecture, sans erreur, et n'est plus écrite.
- `webdav.url` désigne le **dossier de transit** et commence par `https://`
  (`http://` n'est accepté que vers la boucle locale, pour les tests) ; elle ne
  doit pas contenir d'identifiants. `drime.folder` vaut `CoTransfertRelais` par
  défaut, `drime.workspace` vaut `0` (espace personnel).
- Options de ligne de commande équivalentes, enregistrées dans
  `cotransfert.conf` comme les autres : `--channel`, `--webdav-url`,
  `--webdav-user`, `--webdav-password`, `--drime-token`, `--drime-folder`,
  `--proxy`, `--truststore`, `--update-auto`.
- `update.auto=true` (client, case « Installer les mises à jour
  automatiquement » de la page) : voir « Mise à jour de l'autre poste » (§4).
- Les **deux postes** choisissent le même canal et le règlent de la même façon :
  même compte, même dossier de transit, même `secret`.
- Les secrets (`secret`, `webdav.password`, `drime.token`) sont en clair dans ce
  fichier local, non versionné ; ils ne sont jamais journalisés ni exposés par
  `/api/status`. Préférer un **mot de passe d'application** ou un jeton dédié,
  révocable, plutôt que le mot de passe du compte.

**Proxy et certificats** (canaux en ligne uniquement) :

- `proxy=system` (défaut) reprend les réglages proxy du poste, `proxy=none` n'en
  utilise aucun, `proxy=hôte:port` en désigne un.
- `tls.truststore=java` (défaut) utilise les autorités de certification de Java ;
  `tls.truststore=windows` utilise le magasin de certificats de Windows, utile
  quand l'organisation inspecte le TLS avec sa propre autorité. On s'appuie sur
  la confiance déjà installée par l'organisation : la vérification des
  certificats n'est **jamais** désactivée.
- **Limites** : un proxy qui exige une authentification NTLM ou Kerberos n'est
  pas géré, et une configuration proxy par script PAC n'est pas lue par le JDK.
  Dans ces cas le test du canal répond « KO » avec la raison, et le canal reste
  « indisponible ». On ne contourne pas : si l'environnement refuse la
  connexion, ou si l'organisation interdit ces services, on ne les utilise pas ;
  à vous de choisir un autre canal — le disque relais et la valise restent.

**Écriture atomique** : tout fichier reçu est écrit dans `.cotransfert/tmp/` puis
déplacé (`ATOMIC_MOVE`) vers sa cible, avec restauration de la date de modif.

**Réconciliation initiale** : au démarrage, les deux agents échangent un manifeste
(`hash, mtime, taille` par chemin) et poussent ce qu'ils ont de plus récent ou que
le pair n'a pas ; les suppressions hors‑ligne (présentes dans l'index, absentes du
disque) sont propagées.

### 3.6 — IHM web : supervision et réglages

Chaque agent héberge une petite interface web (serveur HTTP du JDK, zéro
dépendance, page autonome hors-ligne), **toujours démarrée** et ouverte dans le
navigateur au lancement. Elle sert à trois choses : **régler** le poste (rôle,
dossiers, secret, canaux), **choisir le canal de transfert** et **suivre** la
synchronisation. Elle montre l'état
des **deux postes** dans une seule page : le client remonte son état au hub par
la connexion de sync (cadres `OP_STATUS`), le hub agrège et sert la page.

- Accès : `http://localhost:47790` sur chaque poste. Le **hub** réglé, une fois
  son canal choisi, écoute sur toutes les interfaces, pour que l'état reste
  lisible depuis le client (`http://<ip-hub>:47790`) ; le **client**, et tout
  poste pas encore réglé ou sans canal choisi, n'écoutent que sur la boucle
  locale : rien n'est exposé sur le réseau.
- Par dossier et par poste : icône d'état + libellé + compteur.
  - ✓ vert **À jour** · ↻ bleu **Synchronisation…** + « N fichiers restants » ·
    ✗ rouge **Conflit** + « N conflit(s) » (les conflits **en attente**, qui
    ouvrent l'écran « Conflits » — voir §3, brique 4) · ○ gris **Déconnecté** ·
    **En attente de l'autre poste** tant que le canal choisi n'est pas actif, ou
    — sur un canal par lots — tant que l'autre poste n'y a pas encore été vu :
    CoTransfert ne dit pas « À jour » alors que personne n'a rien reçu.
- En haut de la page, le panneau **« Canal de transfert »** : quatre cartes
  (Lien direct, WebDAV, Drime, Disque relais), une seule choisie — ou aucune.
  Chaque carte donne son état en toutes lettres (l'état ne repose pas sur la
  seule couleur) :
  - **actif** — choisi et en service (lien direct connecté, ou magasin joignable) ;
  - **en attente de l'autre poste** — lien direct choisi, pas encore connecté ;
  - **indisponible** — choisi mais inutilisable, avec la raison en clair
    (« identifiants refuses », « quota plein », « disque absent », « non
    configure », « secret partage manquant »…) ;
  - **prêt** — réglé, pas choisi (il n'est pas testé en continu) ;
  - **non réglé** — estompé.
- **Choisir un canal** : un clic sur sa carte le **teste**, puis affiche **OK** —
  le choix est enregistré et CoTransfert redémarre dessus — ou **KO** avec la
  raison, et rien ne change. « Choisir quand même » enregistre un canal qui ne
  répond pas encore (un disque pas encore branché, par exemple). Le lien direct
  ne se teste pas : le câble peut être branché plus tard. Un canal non réglé
  renvoie aux réglages. La carte choisie propose « Tester à nouveau » et « Ne
  plus utiliser ». Rappel permanent : **choisir le même canal sur les deux
  postes**.
- Par dossier, un badge rappelle le canal (`Lien direct`, `WebDAV`, `Drime`,
  `Disque relais`, `Valise`) et une ligne donne l'état du canal par lots
  (« 2 lots en attente du pair », « 1 lot a lire », « a jour »).
- Rafraîchissement automatique toutes les secondes (`GET /api/status`) ; thème
  clair ou sombre selon le système, mise en page téléphone.
- Options : `--web-port <n>` (défaut 47790), `--no-browser` pour ne pas ouvrir
  le navigateur, `--no-web` pour désactiver l'IHM (réglage par les options ou
  par `cotransfert.conf`, comme avant).

**Réglages depuis la page.** Tout ce que contient `cotransfert.conf` (§3.5) se
règle dans la page : rôle, dossiers (avec un bouton « Parcourir… » qui ouvre le
sélecteur de dossier du poste), secret partagé (générer, afficher pour le
recopier sur l'autre poste), réglage des canaux (WebDAV, Drime, disque relais)
avec un bouton **Tester** qui dit en clair ce qui ne va pas, politique de
conflit, hub forcé, découverte, proxy, certificats. Le **choix** du canal, lui,
se fait dans le panneau « Canal de transfert », pas dans les réglages. Tant que le poste n'a ni rôle ni dossier, la
page s'ouvre sur ce **premier réglage** ; l'agent ne synchronise rien et
n'écoute pas le réseau.

À l'enregistrement, l'agent valide tout lui-même, écrit `cotransfert.conf` de
façon atomique (fichier temporaire puis renommage : jamais de fichier tronqué),
répond, puis **redémarre tout seul** avec les nouveaux réglages ; la page se
reconnecte toute seule. Ce redémarrage ne passe par aucun script : l'agent
lance un petit processus de relance (`cotransfert.Relance`, depuis une copie
temporaire du jar), s'arrête, et la relance le redémarre avec les mêmes
arguments. Seul `CoTransfert.cmd --console` garde l'ancien fonctionnement :
l'agent sort avec le code 75 et c'est le script qui le relance.

**Modèle de sécurité de l'API de réglage.** `GET /api/status` reste lisible de
partout où la page l'est (lecture seule, aucun secret). Tout le reste
(`/api/config`, `/api/channel`, `/api/secret/new`, `/api/secret/reveal`,
`/api/test`, `/api/browse`, `/api/quit`, `/api/update/publish`,
`/api/update/check`, `/api/update/install`, `/api/log`) est **réservé au poste
lui-même** (seule exception : `/maj/liste` et `/maj/fichier`, que le hub réglé
sur le lien direct sert au client — deux fichiers scellés de la mise à jour,
rien d'autre, voir « Mise à jour de l'autre poste ») :

- adresse distante de boucle locale uniquement — sur le hub, un autre poste lit
  l'état mais reçoit `403` sur les réglages ;
- en-tête `Host` valant `localhost`, `127.0.0.1` ou `[::1]` (un nom étranger
  résolu vers le poste est refusé) ; `Origin`, s'il est présent, sur ce même
  hôte (une autre page ouverte dans le navigateur ne peut pas piloter l'agent) ;
- pour un `POST` : `Content-Type: application/json` et l'en-tête
  `X-CoTransfert: 1`, corps limité à 64 Kio ;
- les secrets ne sont jamais renvoyés par `GET /api/config` (seulement
  « enregistré » ou non) ni journalisés ; seul `/api/secret/reveal` rend le
  secret partagé, à la demande, pour le recopier sur l'autre poste.

Limite connue : il n'y a pas de mot de passe sur la page. Tout programme lancé
sous la même session Windows peut appeler cette API, comme il peut déjà lire
`cotransfert.conf`.

### 3.7 — Canal de transfert : un seul, choisi à la main

Le moteur ne connaît qu'une abstraction, `Transfer.Session` : un flux **ordonné
et fiable** de cadres dans chaque sens. Quatre canaux savent le porter ;
CoTransfert en utilise **un seul à la fois, celui que vous avez choisi** dans la
page (clé `channel=`) :

| Canal | Identifiant | Support | Nature |
|---|---|---|---|
| **Lien direct** | `direct` | Tout lien où le client peut ouvrir une connexion TCP vers le hub : câble USB-C / Thunderbolt, ou réseau local avec secret | flux, temps réel |
| **WebDAV** | `webdav` | Espace de stockage en ligne accessible en WebDAV (par exemple un Nextcloud hébergé) | lots, asynchrone |
| **Drime** | `drime` | Stockage en ligne Drime, par son API HTTP/REST (pas son WebDAV) | lots, asynchrone |
| **Disque relais** | `disque` | Disque ou clé USB déplacé d'un poste à l'autre, partage réseau, dossier d'un client de synchronisation (`relay=`) | lots, asynchrone |

S'y ajoute la **valise** (`--export` / `--import` d'un `.zip`), outil manuel
décrit plus bas.

**Les règles du choix.**

- **Aucun canal par défaut.** Tant que rien n'est choisi, CoTransfert ne
  synchronise rien, n'écoute pas le réseau et n'y cherche rien : il sert sa page
  (boucle locale) et vous invite à choisir.
- **Un clic teste le canal** et répond OK ou KO avec la raison (§3.6). Le choix
  est enregistré dans `cotransfert.conf` et repris à chaque démarrage.
- **Le même canal sur les deux postes.** Chaque poste dépose et lit directement
  dans le canal choisi, sans attendre l'autre : il n'y a plus de « le client
  décide, le hub suit » ni de fichiers marqueurs. Un hub seul, sur un canal par
  lots, dépose donc ses lots tout de suite, même si le client n'a jamais été
  lancé. Si les deux postes n'ont pas choisi le même canal, rien ne passe — et
  rien ne se perd : chacun garde ses lots.
- **Aucune bascule automatique, dans aucun sens.** Si le canal choisi tombe
  (réseau coupé, identifiants refusés, quota plein, disque débranché),
  CoTransfert **attend son retour** et le dit : état « indisponible » avec la
  raison, dossier « en attente », lots gardés dans le spool local. Il ne passe
  jamais à un autre canal ; changer de canal est votre décision.
- Canal choisi mais **non réglé**, ou canal en ligne **sans secret partagé** :
  aucune synchronisation, état « indisponible » avec la raison, la page reste là
  pour corriger.

| Canal choisi | Hub | Client |
|---|---|---|
| aucun | Page seule (boucle locale), aucune écoute, aucune synchro | Page seule, aucune synchro |
| `direct` | Écoute, beacon de découverte | Découvre le hub et s'y connecte ; il l'attend indéfiniment |
| `webdav` / `drime` / `disque` | Ni écoute de synchro ni beacon ; une session par lots sur le magasin choisi, tout de suite | Ni découverte ni connexion au hub ; une session par lots sur le magasin choisi |

**Un même format pour les trois canaux par lots.** Chaque agent découpe son
flux de cadres en **lots** numérotés (`NNNNNN.lot`, compressés, contrôlés par
CRC) qu'il dépose dans le **magasin** du canal ; l'autre les lit dans l'ordre et
publie un **acquittement** ; les lots acquittés sont purgés. Seul l'endroit où
l'on dépose change (interface `RelayStore`) ; l'arborescence est partout la même :

```
<NomLogique>/hub-vers-client/000042.lot
<NomLogique>/client-vers-hub/000017.lot
<NomLogique>/hub.ack        <NomLogique>/client.ack
```

- **Le stockage en ligne n'est qu'un transit.** On y dépose des lots, le pair les
  lit, les lots lus sont purgés ; rien n'y est conservé durablement, et tout ce
  qui y transite est chiffré (§3.8).
- Taille maximale d'un lot : 64 Mio sur disque et en WebDAV, 16 Mio sur Drime.
  Scrutation : 2 s sur disque, 10 s en ligne.
- Les lots restent dans le spool local (`<dossier>/.cotransfert/mailbox/`)
  jusqu'à leur acquittement ; magasin injoignable, la session réessaie
  indéfiniment.

**Changer de canal sans rien perdre.** Le dernier canal réellement utilisé par
chaque dossier est noté dans son spool
(`<dossier>/.cotransfert/mailbox/canal.properties`, clé `canal`). Rien n'est
jamais supprimé du dossier synchronisé lui-même.

- **D'un canal par lots à un autre** (WebDAV → Drime, par exemple) : le spool
  est gardé. Les lots non acquittés sont redéposés dans le nouveau magasin et la
  lecture reprend au lot attendu : le flux continue. Au démarrage, CoTransfert
  retire au mieux ses propres lots de l'ancien magasin s'il est encore réglé et
  joignable (quelques secondes au plus, sans jamais bloquer).
- **D'un canal par lots vers le lien direct** : la réconciliation du lien
  direct resynchronise tout, donc les lots non acquittés sont abandonnés —
  **mais seulement au moment où le lien direct réconcilie réellement le
  dossier** (à la réception du manifeste de l'autre poste), pas dès le
  démarrage. Choisir « Lien direct », ne jamais brancher le câble, puis revenir
  à un canal par lots ne fait donc rien perdre : les lots sont toujours là.
  L'ancien magasin est alors nettoyé au mieux. Une **suppression** qui se
  trouvait dans un lot abandonné peut ne pas être propagée : le fichier revient
  depuis l'autre poste (jamais l'inverse).
- **Du lien direct vers un canal par lots** : rien de particulier, le flux de
  lots repart de zéro.
- **Limite connue.** De vieux lots restés dans un magasin abandonné — parce
  qu'il était injoignable au moment du nettoyage — puis re-sélectionné plus
  tard peuvent, dans de rares cas, être relus. Le contrôle de la version de
  base (voir « Détection des croisements ») en fait alors des copies
  `.conflict-peer-…` avec la politique par défaut `keep-both` : **jamais une
  perte**, au pire un fichier en double à supprimer. Avec `master-wins` ou
  `newer-wins`, c'est la politique choisie qui tranche, comme pour tout conflit.

**WebDAV** (`webdav.url`, `webdav.user`, `webdav.password`). Protocole standard,
HTTPS sortant uniquement, authentification Basic. Écriture par `PUT` vers
`<nom>.part` puis `MOVE` : le lecteur ne voit jamais un fichier partiel. Erreurs
affichées en clair dans l'IHM : « identifiants refuses », « quota plein »,
« serveur occupe » (le délai `Retry-After` est respecté : aucune requête d'ici
là), certificat refusé, proxy. Certains serveurs gardent un temps les fichiers
supprimés dans une corbeille : c'est à surveiller côté quota, CoTransfert ne
cherche pas à la vider.

**Drime** (`drime.token`). API REST du service, jeton personnel créé dans les
réglages du compte ; le WebDAV de Drime n'est pas utilisé. Les suppressions sont
toujours **définitives** : le transit ne remplit pas la corbeille. Le jeton n'est
envoyé qu'à l'API elle-même, jamais à l'hôte de stockage d'une URL signée ou
d'une redirection. Les limites de débit du service ne sont pas documentées : sur
un refus pour excès de requêtes, le canal attend.

**Disque relais** (`relay=` dans `cotransfert.conf`, ou `--relay`).

- Le disque peut **apparaître et disparaître** (clé USB) : les lots attendent
  dans le spool et partent dès qu'il revient. L'IHM indique « a jour - support
  retirable » quand on peut le retirer ; cette mention n'existe que pour ce canal.
- `relay=*:\CoTransfertRelais` cherche ce dossier sur **tous les lecteurs** : la
  clé USB est retrouvée quelle que soit sa lettre. CoTransfert ne crée jamais ce
  dossier lui-même (un support absent ne doit pas devenir un dossier local).
- `relay.<Nom>=` donne un relais propre à un dossier synchronisé.
- Chaque poste dépose dès que le disque est là : on peut le brancher d'abord
  sur l'un ou sur l'autre.

Sur un canal par lots, la page du hub n'est en général pas joignable depuis le
client : chaque poste suit son canal sur sa propre page
(`http://localhost:47790`), démarrée dans tous les cas (§3.6).

**Valise.** Même mécanisme, à la main, sans agent en continu :

```powershell
java -jar cotransfert.jar --export aller.zip                      # sur le poste A
java -jar cotransfert.jar --import aller.zip --export retour.zip  # sur le poste B
java -jar cotransfert.jar --import retour.zip                     # sur le poste A
```

La toute première synchronisation demande trois trajets (échange des manifestes) ;
ensuite, les changements faits d'un côté arrivent en **un seul trajet**.

**Détection des croisements.** Chaque cadre porte le hash de la version que
l'émetteur croit présente chez le pair (`base`). Si le fichier local du récepteur
n'est pas cette version, les deux postes ont modifié le fichier chacun de leur
côté : c'est un **conflit** (copie `.conflict-peer-…`), jamais un écrasement
silencieux — indispensable dès que le transport a des heures de latence.

### 3.8 — Secret partagé et chiffrement

Sans secret, les échanges sont en clair et ne sont acceptés que sur le **lien
direct** (adresses `169.254.x.x`) ou la boucle locale. Dès qu'on sort du câble
(réseau local, dossier relais chez un tiers, valise), on configure un secret,
identique sur les deux postes. Pour les canaux **`webdav` et `drime`, le secret
est obligatoire** : sans lui, la page refuse de les choisir (« secret partage
manquant ») et, s'ils sont choisis quand même dans le fichier, rien ne part en
ligne. Le canal `disque` garde son
comportement : le secret y est recommandé dès que le support sort de vos mains.

```powershell
java -jar cotransfert.jar --new-secret     # génère (100 bits), enregistre et affiche la ligne secret=
```

- **Lien direct** : poignée de main authentifiée par le secret, clés éphémères
  X25519, puis flux chiffré en AES-256-GCM. Un mauvais secret est rejeté
  immédiatement des deux côtés.
- **Lots des canaux par lots (WebDAV, Drime, disque relais) et de la valise** :
  chaque lot est scellé en AES-256-GCM, lié à son dossier, à son sens et à son
  numéro. Le service en ligne ne voit que du chiffré.
- Clé dérivée par PBKDF2-HMAC-SHA256 (600 000 itérations). Secret de 16
  caractères minimum ; préférer un secret **généré**.
- `discovery=lan` étend la découverte du hub aux réseaux privés (désactivé par
  défaut) ; `peer=<nom ou ip>` désigne le hub sans découverte.

Limites connues : un seul secret pour tout (pas de rotation ni de révocation),
stocké en clair dans `cotransfert.conf` (comme `webdav.password` et
`drime.token`) ; les acquittements ne sont pas
authentifiés (un tiers ayant accès au magasin peut bloquer la synchronisation,
pas lire ni injecter de contenu) ; la taille approximative des fichiers et le nom
logique des dossiers restent observables.

### 3.9 — Clavier et souris partagés (optionnel)

Sur le **lien direct**, avec un **secret partagé**, on peut piloter l'autre poste
avec le clavier et la souris de celui-ci, dans un sens ou dans l'autre. La
fonction est **désactivée par défaut** et doit être activée sur les deux postes
(Réglages → Avancé → « Clavier et souris partagés »). Elle n'utilise que le JDK :
une fenêtre AWT plein écran capte les entrées (un bandeau dit en permanence où
vont les frappes), `java.awt.Robot` les rejoue en face — ni pilote, ni droit
administrateur, ni crochet clavier global. Les événements passent par une
connexion dédiée et chiffrée, ouverte par le client vers le hub sur le port de la
synchronisation : un gros fichier ne retarde pas la souris.

- **Partir** : pousser le pointeur contre le bord réglé, cliquer sur « Piloter
  l'autre poste » dans la page, ou utiliser la touche du raccourci Windows créé
  depuis les réglages.
- **Revenir** : le raccourci clavier (`Ctrl+Alt+F12` par défaut), le bord opposé
  de l'écran du poste piloté, un bouton latéral de la souris si réglé, ou tout
  simplement la touche Windows.
- **Limites** : même disposition de clavier sur les deux postes ; les touches que
  Windows se réserve (touche Windows, Alt+Tab, Ctrl+Alt+Suppr) restent sur le
  poste et rendent la main ; l'écran de verrouillage, les invites UAC et les
  fenêtres élevées ne se pilotent pas.
- **Texte copié** : en option (« Partager le texte copié », désactivée par
  défaut, à activer sur les deux postes), le texte du presse-papiers suit le
  clavier d'un poste à l'autre. Texte seulement, 512 Ko au plus ; ni fichier ni
  image.

Protocole, états, sécurité et points restant à valider sur de vrais postes :
[`SPEC-km.md`](SPEC-km.md).

---

## 4. Build & utilisation

**Prérequis** : un JDK ≥ 21 pour compiler, un Java ≥ 21 (JRE ou JDK) sur chaque
poste pour exécuter. Aucun droit admin nulle part.

**Compiler le jar** :

```bash
javac --release 21 -d build/classes $(find src/main/java -name '*.java')
cp -r src/main/resources/. build/classes/
jar --create --file cotransfert.jar --main-class cotransfert.Agent -C build/classes .
```

### Utilisation : un seul fichier à lancer

Sur chaque poste, placer `CoTransfert.cmd` et `cotransfert.jar` dans le même
dossier (n'importe où dans le profil utilisateur), puis **double-cliquer sur
`CoTransfert.cmd`**. C'est tout : aucune commande à taper, aucun fichier à
éditer.

1. Le script trouve tout seul un Java 21+ déjà présent (variable
   `COTRANSFERT_JAVA` si elle est définie, puis `JAVA_HOME`, le `PATH` et les
   emplacements d'installation usuels). S'il n'en trouve pas, il le dit et
   explique comment lui indiquer un `java.exe` ; il n'installe rien.
2. L'agent démarre **sans fenêtre** (`javaw.exe`, le même Java sans console) et
   **ouvre la page de CoTransfert** dans le navigateur
   (`http://localhost:47790`). La fenêtre noire du script se ferme aussitôt :
   **aucune fenêtre ne reste ouverte**, CoTransfert tourne en arrière-plan.
3. Premier lancement : la page propose le **premier réglage**.
   - **Rôle** — *hub* (ce poste accepte la connexion) sur l'un, *client* (ce
     poste se connecte à l'autre) sur l'autre.
   - **Dossiers** — un nom (le même sur les deux postes) et le dossier local.
   - **Secret** (facultatif sur le câble direct, obligatoire pour les canaux en
     ligne) — le générer sur un poste, l'afficher, le coller sur l'autre.
   - **Réglage des canaux** (WebDAV, Drime, disque relais) et réglages
     avancés, facultatifs (§3.7).
4. **Enregistrer** : CoTransfert redémarre tout seul avec ces réglages et la
   page revient.
5. **Choisir le canal de transfert**, sur chaque poste, dans le panneau « Canal
   de transfert » : un clic sur *Lien direct*, *WebDAV*, *Drime* ou *Disque
   relais*. La page teste le canal, l'enregistre et CoTransfert redémarre
   dessus. **Tant qu'aucun canal n'est choisi, rien n'est synchronisé** ; et il
   faut **le même canal sur les deux postes**. Les lancements suivants ne
   demandent plus rien : le choix est repris.

Sur le **hub**, au premier lancement avec un canal choisi, Windows affiche l'alerte pare-feu :
cocher *Réseaux publics* et *Autoriser* (crée la règle entrante locale, sans
admin). Cette alerte peut **réapparaître une fois** sur un hub qui avait déjà
autorisé CoTransfert : l'agent tourne maintenant avec `javaw.exe`, qui est pour
Windows un autre programme que `java.exe`.

**Arrêter** : bouton **Quitter CoTransfert** dans la page — il n'y a plus de
fenêtre à fermer. Si la page est fermée, relancer `CoTransfert.cmd` la rouvre
(sans démarrer de second agent). **Modifier un réglage** : bouton **Réglages**
de la page (visible seulement sur le poste lui-même).

**Journal.** Tout ce que l'agent écrivait dans la fenêtre va dans
`cotransfert.log`, à côté de `cotransfert.conf` (à défaut, dans le dossier
temporaire de l'utilisateur). Le bouton **Journal** de la page en montre les
dernières lignes et le chemin. Sa taille est bornée : au-delà de 2 Mio il
devient `cotransfert.log.1` (un seul fichier archivé) et un journal neuf
commence. Aucun secret n'y est écrit.

**Erreur au démarrage.** Sans fenêtre, une erreur qui empêche CoTransfert de
démarrer (port de la page pris par un autre programme, `cotransfert.conf`
illisible…) s'affiche dans une **petite boîte de dialogue**, et dans le journal.

**Diagnostic : `CoTransfert.cmd --console`** garde l'ancien comportement : la
fenêtre reste ouverte, les messages s'y affichent (et vont aussi dans le
journal), fermer la fenêtre arrête CoTransfert. Les lancements ponctuels qui
écrivent à l'écran (`--new-secret`, `--export`, `--import`, `--bench`,
`--no-web`) gardent eux aussi la fenêtre. Si `javaw.exe` est absent à côté du
`java.exe` trouvé, le script le dit et garde la fenêtre.

**Une seule instance.** Relancer `CoTransfert.cmd` alors que CoTransfert tourne
déjà ne démarre pas de second agent : la page existante est rouverte et le
script se termine (code 0). Si le port de l'IHM est pris par un autre programme,
l'agent le dit et s'arrête (code 2) ; choisir un autre port avec `--web-port`.

**Codes de sortie de l'agent** : `0` arrêt normal (bouton Quitter, instance
déjà lancée, ou redémarrage pris en charge par la relance), `2` erreur de
démarrage (boîte de dialogue et journal ; avec `--console`, le script garde la
fenêtre ouverte pour lire le message), `75` redémarrage demandé — seulement
avec `CoTransfert.cmd --console`, où c'est le script qui relance l'agent, ou
quand l'agent ne tourne pas depuis un jar.

### Mise à jour de l'autre poste

Quand le programme change, il n'est plus nécessaire de porter le jar à la main
sur l'autre poste : il voyage par le **canal de transfert** déjà choisi, quel
qu'il soit (lien direct, WebDAV, Drime ou disque relais), avec les mêmes boutons
et le même comportement.

1. **Sur le hub** (le poste qui a la nouvelle version) : *Réglages → Avancé →
   Mise à jour de l'autre poste → « Publier cette version pour l'autre
   poste »*. Le jar est déposé dans le dossier de transit, sous `_maj/`, à côté
   des dossiers synchronisés — ou, sur le lien direct, dans le dossier
   `cotransfert.maj` du hub (à côté de `cotransfert.conf`), que le client vient
   lire par le câble. Publier remplace la version publiée précédente.
2. **Sur le client** : il vérifie au démarrage, puis toutes les 10 minutes, et
   sur « Vérifier maintenant ». Quand une version plus récente que la sienne est
   publiée, un bandeau l'annonce avec un bouton **« Installer et redémarrer »**.
3. **Installer** : le client télécharge le jar, le vérifie, redémarre dessus.
   La page revient toute seule et affiche la nouvelle version.

**Automatique, si on le demande.** La case « Installer les mises à jour
automatiquement » (clé `update.auto=true`, option `--update-auto`) fait
installer et redémarrer le client tout seul dès qu'une version est publiée.
Elle est **décochée par défaut** : sans elle, rien ne s'installe sans un clic.

**Retour arrière.** Après l'installation, le démarrage de la nouvelle version
est surveillé pendant 30 secondes. Si elle s'arrête sur une erreur, la version
précédente est **remise en place et relancée toute seule**, la page affiche
« la mise à jour du … n'a pas démarré, version précédente rétablie », et cette
version n'est plus proposée sur ce poste (elle est notée dans
`cotransfert.maj-refusee` ; supprimer ce fichier la rend de nouveau
installable).

**Ce qui la protège.** Le jar et sa fiche (date, taille, empreinte SHA-256) sont
**scellés avec le secret partagé**, par le même chiffrement authentifié que les
lots. Le client refuse tout ce qui ne s'ouvre pas avec son secret — donc tout ce
qui ne vient pas de l'autre poste —, ainsi qu'une version plus ancienne ou
identique, une taille ou une empreinte qui ne correspond pas à la fiche, et un
fichier qui n'est pas un jar CoTransfert. Celui qui héberge le stockage en ligne
ne peut ni lire le jar ni en glisser un autre. En contrepartie, **quiconque
connaît le secret partagé peut publier une version** : c'est le même niveau de
confiance que pour les fichiers synchronisés.

**Par le lien direct.** Le client n'écoute toujours rien : il va chercher la
publication chez le hub, par des requêtes HTTP sortantes vers la page du hub
(même port sur les deux postes, `47790` par défaut), à l'adresse où il a trouvé
le hub. Le hub ne sert par ces routes (`/maj/liste`, `/maj/fichier`) que les
deux fichiers scellés de la publication : aucun autre fichier n'est accessible,
rien ne peut être effacé par le réseau, et un dépôt n'est accepté que s'il
s'ouvre avec le secret partagé du hub. Le lien direct synchronise sans secret,
mais **la mise à jour en demande un, le même sur les deux postes** (elle est
scellée avec lui) : sans secret, la page le dit.

**Quand ce n'est pas disponible** (la page dit pourquoi) : aucun canal choisi,
pas de secret partagé, ou agent lancé hors d'un jar ; sur le lien direct, tant
que le client n'a pas trouvé le hub, la vérification échoue et reprend toute
seule. Le hub publie et n'installe jamais ; le client installe et ne publie
jamais.

**La version** d'un jar est sa date de construction (celle de son manifeste),
affichée « version du 2026-11-16 16:41 », plus son empreinte. Il n'y a aucun
numéro à tenir à jour.

**Fichiers à côté du jar** (tous créés et retirés par CoTransfert) :

| Fichier | Rôle |
|---|---|
| `cotransfert.jar.nouveau` | version téléchargée et vérifiée, en attente du redémarrage |
| `cotransfert.jar.precedent` | version remplacée, gardée pour le retour arrière |
| `cotransfert.jar.essai` | témoin posé à l'échange, retiré dès que la nouvelle version a tenu 20 secondes |
| `cotransfert.maj-refusee` | versions qui n'ont pas démarré sur ce poste |

L'échange est fait par la relance — ou par `CoTransfert.cmd` au lancement
suivant si la relance n'a pas pu le faire. Chaque étape copie puis renomme : il
existe à tout instant un `cotransfert.jar` complet. Si le témoin est encore là
au lancement suivant (la nouvelle version n'a jamais tenu 20 secondes), le
script remet la version précédente avant de lancer.

**Limites.** `CoTransfert.cmd` lui-même n'est pas mis à jour par ce moyen. La
première fois, il faut encore porter à la main sur l'autre poste un jar et un
script qui connaissent cette fonction. Le client ne vérifie que toutes les
10 minutes : « Vérifier maintenant » évite d'attendre.

### Usage avancé

Tout ce qui suit est facultatif : `CoTransfert.cmd` transmet ses arguments à
l'agent, et l'agent se lance aussi directement.

```powershell
# Sans argument : lit cotransfert.conf ; pas encore réglé, ouvre la page de réglage.
java -jar cotransfert.jar
# Réglage par la ligne de commande (enregistré dans cotransfert.conf) :
java -jar cotransfert.jar --role hub --dir MonProjet=C:\work\MonProjet --channel direct
java -jar cotransfert.jar --role client --dir MonProjet=D:\dev\MonProjet --channel direct
# Choisir un autre canal (enregistré ; "aucun" pour ne plus en utiliser) :
java -jar cotransfert.jar --channel disque
# Sans ouvrir le navigateur, ou sans IHM du tout :
java -jar cotransfert.jar --no-browser
java -jar cotransfert.jar --no-web
```

Lancé ainsi dans un terminal, l'agent affiche ses messages à l'écran **et** les
écrit dans `cotransfert.log`. Après un enregistrement depuis la page il
redémarre comme avec le script : par la relance, sans fenêtre (le terminal est
alors libéré).
Avec `--no-web`, un poste sans rôle affiche l'usage et sort avec le code 2 ; un
poste sans canal choisi le dit et sort avec le code 2 (`--channel` le choisit,
sans test préalable).

**Anciens lanceurs** (dossier `outils/`, conservés pour l'usage avancé) :

```powershell
# Hub : sert aussi le jar au client pour son bootstrap.
powershell -ExecutionPolicy Bypass -File outils\run-hub-sync.ps1 -Dir C:\work\MonProjet
# Client : détecte Java, récupère et met à jour le jar depuis le hub.
powershell -ExecutionPolicy Bypass -File outils\run-client-sync.ps1 -Dir D:\dev\MonProjet -Name MonProjet
```

Le lanceur client de `outils/` **se met à jour tout seul** : le lanceur et le
jar sont re‑téléchargés depuis le hub au démarrage si une nouvelle version y est
publiée. Avec `CoTransfert.cmd`, le jar se met à jour par le canal de transfert
(« Mise à jour de l'autre poste » ci-dessus) ; le script lui-même, non.

**Mesure de débit** : `--role hub --bench` côté hub, puis `--role client --bench
--gib 8` côté client (ou `outils\cotransfert-client.ps1 -Hub <ip-hub>`). Ni la
mesure de débit, ni la valise (`--export` / `--import`), ni `--new-secret`
n'ouvrent la page.

> Astuce pare-feu : si un binaire se retrouve avec une règle *Block* créée par
> erreur (non supprimable sans admin), le **renommer** suffit — un nouveau chemin
> est réévalué et peut recevoir une règle *Allow*.

---

## 5. État

**Fait et validé** : les 4 briques (découverte, surveillance, transfert, anti‑boucle
+ conflits), la réconciliation initiale, la sync live bidirectionnelle, le
multi‑dossiers, le `.syncignore` synchronisé et l'IHM web. L'écran « Conflits »
(choisir une version, sur un poste, pour les deux) est fait et validé par tests
automatisés sur un seul poste — détail plus bas.

**Fait, validé par tests automatisés sur un seul poste** (pas encore éprouvé entre
deux machines réelles) : le disque relais et la valise, le choix manuel du
canal et les changements de canal, le chiffrement par secret partagé. La découverte
`discovery=lan` n'a pas été essayée sur un vrai réseau local.

**Fait, validé uniquement par tests automatisés contre de faux serveurs** : les
canaux en ligne `webdav` et `drime`. Les tests lancent
de faux serveurs WebDAV et Drime dans le même processus, sur la boucle locale.
Dit simplement :

- **jamais** essayé contre un vrai serveur WebDAV ;
- **jamais** essayé contre le vrai service Drime (les appels d'API sont écrits
  d'après sa documentation, pas vérifiés sur le service) ;
- **jamais** essayé entre deux machines réelles ;
- proxy d'organisation, inspection TLS et `tls.truststore=windows` : jamais
  essayés sur un poste verrouillé.

Un essai manuel sur de vrais comptes et deux postes reste à faire avant de s'y
fier.

**Choix manuel du canal** (`SPEC-cascade-canaux.md` §16, qui remplace la bascule
automatique) — ce qui a été vérifié, sur un seul poste :

- **test automatisé de bout en bout** (`ChannelsEndToEndTest`) : deux vrais
  dossiers, le moteur des deux côtés, lots chiffrés, et pour chaque canal par
  lots (faux serveurs WebDAV et Drime, dossier temporaire pour le disque) : le
  hub seul dépose ses lots sans qu'aucun client existe ; les deux côtés
  convergent ; magasin en panne, aucune bascule, les lots attendent, puis
  reprise ; changements de canal lots → lots et lots → direct → lots par le
  même code de démarrage que l'agent, sans changement perdu ni rejoué ;
- **test automatisé de l'API** (`SetupApiTest`) : chaque cas de
  `POST /api/channel` (canal inconnu, désélection, non réglé, secret manquant,
  direct non testé, test KO, « choisir quand même », test OK, re-test du canal
  déjà choisi sans redémarrage) ;
- **essai au niveau processus**, une fois : un hub lancé par `CoTransfert.cmd`
  avec un dossier, un secret et un disque relais réglés mais **aucun canal
  choisi** ne synchronise rien et n'écoute que sa page ; `POST /api/channel`
  `disque` → le script relance l'agent → un lot apparaît dans le dossier relais
  **sans aucun client lancé**, `/api/status` montre `disque` choisi et actif ;
  `/api/quit` arrête tout.

Non vérifié : tout cela entre deux machines réelles, et contre un vrai serveur
WebDAV ou le vrai service Drime.

**Point d'entrée unique et réglages depuis la page** (`SPEC-cascade-canaux.md`
§15) — ce qui a été vérifié, sur un seul poste :

- **test automatisé de l'API** (`SetupApiTest`) : serveur web et API dans le
  processus du test, sur la boucle locale ; lecture et enregistrement de chaque
  clé, secrets jamais renvoyés, chaque erreur de validation avec son champ,
  chaque garde (`403` sans `X-CoTransfert`, avec un `Host` ou un `Origin`
  étranger, depuis une adresse hors boucle locale ; `413`, `400`, `405`),
  essais de canal contre les faux serveurs WebDAV et Drime, demande de relance ;
- **essai au niveau processus**, une fois, à la main : `CoTransfert.cmd` et le
  jar dans un dossier vide, lancés avec `--no-browser` ; la page de réglage
  répond, une configuration client envoyée par `curl` est enregistrée, l'agent
  sort avec le code 75, le script le relance et il revient réglé ; une seconde
  instance sort avec le code 0 en disant que CoTransfert tourne déjà ;
  `/api/quit` arrête tout.

Non vérifié par ces essais : l'ouverture du navigateur et le sélecteur de
dossier (ils demandent un écran et une personne ; le test ne couvre que le cas
« indisponible »), et la partie réglages de la page elle-même, validée
séparément. Aucun essai sur un poste verrouillé.

**Lancement silencieux et mise à jour par le canal** (`SPEC-cascade-canaux.md`
§17) — ce qui a été vérifié, sur un seul poste :

- **tests automatisés** (`UpdateTest`, sans lancer de processus) : version lue
  dans un vrai jar ; publier → vérifier → installer sur un disque relais avec
  une vraie clé, puis par les faux serveurs WebDAV et Drime (le jar reçu est
  identique au jar publié, rien n'est déposé en clair) ; chaque refus (mauvais
  secret, fiche ou jar altérés, taille, empreinte ou date fausses, pas un jar,
  autre classe principale, version ancienne, égale ou refusée) ; l'échange, le
  retour arrière et le marqueur ; les états montrés par la page ;
  l'installation automatique ; l'archivage du journal ; le **lien direct** dans
  le même processus (le hub publie dans son dossier local, le client voit et
  installe par HTTP ; dépôt par le client ; refus d'un contenu non scellé par
  le bon secret, d'un nom hors liste, d'un parcours de chemin, d'un fichier
  trop gros, d'une version plus ancienne ; hub pas encore trouvé, arrêté ou
  sans ces routes). `SetupApiTest` couvre les nouvelles routes, `updateAuto` et
  leurs gardes. La mise à jour par le lien direct n'a **pas** été essayée entre
  deux agents réels reliés par un câble ;
- **essais au niveau processus**, une fois, sur un seul poste, canal disque,
  avec des jars construits pour l'essai dans des dossiers à part :
  1. un client lancé par `CoTransfert.cmd` : le script rend la main en moins
     d'une seconde, il ne reste qu'un `javaw.exe` (aucune console), la page
     répond, `cotransfert.log` est écrit ;
  2. réglages puis canal enregistrés par l'API : l'agent redémarre deux fois
     tout seul, sans script ; un hub en version 2 publie, le client en
     version 1 la voit, l'installe, redémarre tout seul en version 2
     (`cotransfert.jar.precedent` contient la version 1) ;
  3. une version authentique mais qui s'arrête au démarrage (code 3) est
     publiée puis installée : la version précédente revient toute seule en
     quelques secondes, `cotransfert.maj-refusee` est écrit, la page annonce le
     retour arrière, la version fautive n'est plus proposée ni installable ;
  4. `CoTransfert.cmd --console` : l'agent sort avec le code 75 après un
     enregistrement et le script le relance ; un second lancement pendant
     qu'il tourne ne démarre pas de second agent ;
  5. au lancement, le script remet la version précédente quand le témoin
     d'essai est resté, puis applique une version en attente.

Non vérifié : tout cela entre deux machines réelles et par un vrai serveur
WebDAV ou le vrai service Drime ; l'installation automatique au niveau
processus (seulement par test automatisé) ; la boîte de dialogue d'erreur (elle
demande un écran et une personne) ; l'alerte pare-feu de `javaw.exe` sur un
hub ; un poste verrouillé où `javaw.exe` ou le lancement d'un second processus
Java seraient interdits.

**Écran « Conflits »** (`SPEC-cascade-canaux.md` §18) — ce qui a été vérifié,
sur un seul poste :

- **test automatisé de bout en bout** (`ConflictsTest`) : deux vrais dossiers,
  deux moteurs, un disque relais dans un dossier temporaire que le test coupe
  et rétablit. Un vrai conflit (même fichier modifié des deux côtés pendant la
  coupure), sa liste et le compteur ; « ma version », « celle de l'autre
  poste », un par un et pour tous ; conflit vu d'un seul côté ; choix fait sans
  session puis envoyé à la session suivante ; choix croisés sur les deux
  postes ; fichier disparu ; copies jamais transmises ; refus des chemins
  arbitraires ;
- **test automatisé de l'API** (`SetupApiTest`) : les trois routes
  `/api/conflicts*`, leurs gardes et leurs réponses.

Non vérifié : entre deux machines réelles, sur le lien direct (le test passe
par un canal par lots), avec de très gros fichiers ou des milliers de conflits,
et l'ouverture de l'explorateur de fichiers (elle demande un écran). Un poste
resté sur une version antérieure reçoit bien le fichier choisi, mais ne retire
pas ses propres copies et n'a pas l'écran.

**Tests** (sans dépendance, simples `main` ; aucun n'appelle un service réel) :

```bash
javac --release 21 -cp build/classes -d build/test-classes $(find src/test/java -name '*.java')
java -cp "build/classes;build/test-classes" cotransfert.MailboxTest <dossier-de-travail>
java -cp "build/classes;build/test-classes" cotransfert.WebDavStoreTest <dossier-de-travail>
java -cp "build/classes;build/test-classes" cotransfert.DrimeStoreTest <dossier-de-travail>
java -cp "build/classes;build/test-classes" cotransfert.ChannelsEndToEndTest <dossier-de-travail>
java -cp "build/classes;build/test-classes" cotransfert.ConflictsTest <dossier-de-travail>
java -cp "build/classes;build/test-classes" cotransfert.SetupApiTest <dossier-de-travail>
java -cp "build/classes;build/test-classes" cotransfert.UpdateTest <dossier-de-travail>
java -cp "build/classes;build/test-classes" cotransfert.KmTest <dossier-de-travail>
java -cp "build/classes;build/test-classes" cotransfert.CryptoTest --no-bench
```

| Test | Ce qu'il couvre |
|---|---|
| `MailboxTest` | Transport par lots dans un dossier relais, valise |
| `WebDavStoreTest` | Magasin WebDAV contre un faux serveur WebDAV, puis une session hub ↔ client complète à travers lui |
| `DrimeStoreTest` | Magasin Drime contre un faux serveur Drime, puis une session hub ↔ client complète à travers lui |
| `ChannelsEndToEndTest` | Bout en bout du choix manuel : chaque canal par lots (hub seul, convergence, panne sans bascule, reprise), changements de canal, toujours contre les faux serveurs |
| `ConflictsTest` | Écran « Conflits » de bout en bout : vrai conflit sur un canal par lots, liste et compteur, « ma version » / « celle de l'autre poste », résolution groupée, conflit d'un seul côté, résolution sans session, choix croisés, fichier disparu, copies jamais transmises, refus |
| `SetupApiTest` | API de réglage de l'IHM : gardes de la boucle locale, validation, enregistrement atomique, secrets, essais de canal, choix du canal (`/api/channel`), mise à jour (`/api/update/*`), journal (`/api/log`), conflits (`/api/conflicts*`), relance |
| `UpdateTest` | Mise à jour par le canal : version d'un jar, publier / vérifier / installer (disque, faux WebDAV, faux Drime, lien direct par les routes `/maj/` du hub), refus, échange, retour arrière, états, journal |
| `KmTest` | Clavier et souris partagés, uniquement avec des doublures (ni `Robot`, ni fenêtre, ni raccourci Windows) : protocole, lien chiffré, accept du hub, machine à états, bord d'écran, coupure du lien, battement de cœur, réglages `km.*` |
| `CryptoTest` | Secret partagé, chiffrement des flux et des lots |

**Reste à faire** : voir [`ROADMAP.md`](ROADMAP.md).

---

## Structure

```
CoTransfert.cmd                 # POINT D'ENTREE UNIQUE : trouve Java, applique une mise a jour en attente, lance l'agent sans console (--console : fenetre + boucle sur le code 75)
src/main/java/cotransfert/
  Agent.java        # point d'entree, roles hub/client, canal choisi (une session par dossier), changement de canal, instance unique, redemarrage, erreurs fatales, options, bench
  Discovery.java    # BRIQUE 1 : decouverte ARP + handshake unicast
  Watcher.java      # BRIQUE 2 : WatchService recursif + debounce + exclusions
  Transfer.java     # BRIQUE 3 : cadres + interface Session + transport TCP (clair ou chiffre)
  MailboxSession.java # transport asynchrone par lots, depose dans un RelayStore
  RelayStore.java     # interface : magasin de fichiers d'un canal asynchrone
  DirRelayStore.java  # magasin "disque" : dossier, partage ou support amovible
  WebDavRelayStore.java # magasin "webdav" : serveur WebDAV (HTTPS sortant)
  DrimeRelayStore.java  # magasin "drime" : API REST Drime (HTTPS sortant)
  HttpSupport.java    # socle HTTP des canaux en ligne : client du JDK, proxy, certificats
  Valise.java       # export / import d'une archive de lots (--export / --import)
  Crypto.java       # secret partage : derivation, AES-GCM, poignee de main
  SyncEngine.java   # BRIQUE 4 + moteur : manifeste, anti-echo, conflits
  Conflicts.java    # conflits en attente : liste, resolution (par le moteur ou sans session), memoire des choix
  Index.java        # index de reference persistant + hachage CRC-32C
  SyncIgnore.java   # exclusions facon .gitignore (synchronisees, 100% iso par defaut)
  Config.java       # cotransfert.conf : role, conflit, dossiers (multi-root), canal choisi, reglage des canaux
  Status.java       # modele d'etat (self + pair) + serialisation JSON
  WebServer.java    # IHM web (serveur HTTP du JDK) : page, /api/status, routes de reglage, routes /maj/ du hub
  SetupApi.java     # API de reglage (boucle locale uniquement) : config, choix du canal, secret, essai de canal, parcourir, quitter, mise a jour, journal
  DesktopSupport.java # confort facultatif : ouvrir le navigateur, selecteur de dossier, boite d'erreur au demarrage
  Journal.java      # cotransfert.log : sortie de l'agent sans console, taille bornee, dernieres lignes pour la page
  Relance.java      # redemarrage sans script : attend la fin de l'agent, echange le jar, relance, surveille, revient en arriere
  Updater.java      # mise a jour par le canal : version d'un jar, publier / verifier / installer, echange, retour arriere
  MajRoutes.java    # mise a jour par le lien direct, cote hub : dossier cotransfert.maj servi par /maj/liste et /maj/fichier
  HubHttpRelayStore.java # mise a jour par le lien direct, cote client : magasin HTTP sortant vers ces routes
  Km.java           # clavier et souris partages (optionnel) : controleur, machine a etats, points d'entree de l'agent
  KmProtocol.java / KmLink.java   # evenements binaires ; connexion dediee chiffree, battement de coeur
  KmCapture.java / KmInject.java  # fenetre de capture AWT ; injection par java.awt.Robot
  KmEdge.java / KmShortcut.java / KmSettings.java  # bord d'ecran ; raccourci Windows .lnk ; reglages km.*
  KmDemo.java       # essai manuel du partage clavier-souris entre deux postes, sans l'agent
src/main/resources/cotransfert/web/index.html   # page de l'IHM (autonome) : etat, choix du canal et reglages
outils/                         # anciens lanceurs, usage avance
  run-hub-sync.ps1              #   lanceur HUB : sert le jar + lance l'agent maitre
  run-client-sync.ps1           #   lanceur CLIENT : auto-update + lance l'agent esclave
  cotransfert-client.ps1 / .cmd #   lanceurs client de mesure de debit (bench)
cotransfert.conf                # config locale par poste (non versionnee)
cotransfert.log                 # journal de l'agent (non versionne)
```

Options CLI : `--role hub|client`, `--dir [Nom=]<dossier>` (répétable),
`--peer <nom ou ip>` (sans découverte), `--discovery link|lan`, `--master|--slave`,
`--on-conflict keep-both|master-wins|newer-wins`, `--secret <secret>`, `--new-secret`,
`--relay <dossier | *:\Dossier>`, `--channel direct|webdav|drime|disque|aucun`,
`--webdav-url <url>`,
`--webdav-user <identifiant>`, `--webdav-password <mot de passe>`,
`--drime-token <jeton>`, `--drime-folder <dossier>`,
`--proxy system|none|<hôte:port>`, `--truststore java|windows`,
`--export <zip>`, `--import <zip>`,
`--config <fichier>`, `--web-port <n>`, `--no-web`, `--no-browser`,
`--update-auto`, `--bench [--gib N]`. Options du script seul :
`CoTransfert.cmd --console`, `CoTransfert.cmd --trouver-java`. Aucune n'est nécessaire : sans option, tout se règle dans
la page.

Clés de `cotransfert.conf` : `role`, `master`, `conflict`, `root.<Nom>`, `peer`,
`discovery`, `secret`, `channel`, `relay`, `relay.<Nom>`, `webdav.url`,
`webdav.user`, `webdav.password`, `drime.token`, `drime.folder`,
`drime.workspace`, `drime.url`, `proxy`, `tls.truststore`, `update.auto`.

## Licence et contributions

CoTransfert est distribué sous licence [GNU AGPL-3.0](LICENSE) : toute version
modifiée qui est distribuée, ou proposée par un réseau, doit l'être sous la même
licence, avec ses sources. Le titulaire des droits peut aussi proposer le projet
sous d'autres licences ; pour un usage incompatible avec l'AGPL, ouvrez une
issue.

Les contributions suivent des règles strictes : voir
[`CONTRIBUTING.md`](CONTRIBUTING.md) et l'[accord de contribution](CLA.md),
obligatoire avant toute fusion.
