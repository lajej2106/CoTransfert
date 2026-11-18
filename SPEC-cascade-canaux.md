> **État : la bascule automatique décrite ci-dessous a été retirée du code.** Le §16 (choix manuel du canal) annule et remplace la cascade automatique (§1 R1/R2/R6, §4, clé `channels`) ; le reste du document vaut toujours.

# CoTransfert — Spécification : cascade de canaux (direct → WebDAV → Drime → disque)

État : **implémenté**, validé **uniquement par des tests automatisés contre de
faux serveurs** lancés dans le même processus — jamais encore contre un vrai
serveur WebDAV, jamais contre le vrai service Drime, jamais entre deux machines
réelles (la recette du §13 reste à faire). Ce document décrit le besoin métier,
le comportement attendu et le découpage technique. Il respecte `CLAUDE.md` : pas de pilote, pas
d'admin, rien à installer, Java SE pur sans dépendance, le client ne fait que du
sortant, aucune perte silencieuse de données, aucun contournement de politique.

---

## 1. Besoin métier

Pour chaque dossier synchronisé, CoTransfert utilise **le meilleur canal
disponible**, dans cet ordre, et redescend ou remonte tout seul :

| Rang | Canal | Identifiant | Support | Statut |
|---|---|---|---|---|
| 1 | **Lien direct** | `direct` | Câble USB-C / Thunderbolt (ou tout lien où le client joint le hub en TCP) | existant |
| 2 | **WebDAV** | `webdav` | Espace de stockage en ligne accessible en WebDAV (par exemple un Nextcloud hébergé) | **à faire** |
| 3 | **Drime** | `drime` | Stockage en ligne Drime, par son **API HTTP/REST** (pas son WebDAV) | **à faire** |
| 4 | **Disque** | `disque` | Disque dur ou clé USB déplacé d'un poste à l'autre, ou dossier partagé | existant (`relay=`) |

Règles métier :

- **R1 — Priorité.** On utilise toujours le canal configuré et disponible de plus
  petit rang. Un canal **non configuré** est ignoré sans erreur ; un canal
  configuré mais **indisponible** (réseau coupé, identifiants refusés, proxy qui
  bloque, quota plein, disque absent) est sauté.
- **R2 — Remontée automatique.** Dès qu'un canal de meilleur rang redevient
  disponible de façon stable, on y revient.
- **R3 — Aucune perte, aucun doublon.** Un changement de canal ne perd aucun
  fichier, n'en rejoue aucun et ne crée pas de faux conflit.
- **R4 — Le cloud n'est qu'un transit.** On y dépose des lots, le pair les lit,
  les lots lus sont purgés. Rien n'y est stocké durablement, et tout ce qui y
  transite est chiffré.
- **R5 — Secret obligatoire hors du câble.** `webdav` et `drime` exigent un
  secret partagé (`secret=`). Sans secret, ces canaux sont désactivés avec un
  message clair. (Le canal `disque` garde son comportement actuel.)
- **R6 — Le client décide, le hub suit.** Le client est le poste contraint :
  c'est lui qui choisit le canal asynchrone. Le hub utilise celui que le client
  a annoncé.
- **R7 — Visible.** L'IHM web montre la cascade par dossier : canal actif,
  canaux prêts, canaux indisponibles avec la raison, canaux non configurés.
- **R8 — Optionnel.** Les canaux en ligne sont désactivés par défaut ; le produit
  fonctionne sans eux, exactement comme aujourd'hui.

Hors périmètre de cette version : configuration des canaux depuis l'IHM,
plusieurs comptes par canal, relais sortant temps réel, autres fournisseurs à API
propriétaire.

---

## 2. Principe technique

Les trois canaux asynchrones (`webdav`, `drime`, `disque`) partagent **le même
format** : celui du relais actuel (`MailboxSession` : lots numérotés
`NNNNNN.lot`, acquittements `hub.ack` / `client.ack`). Seul l'endroit où l'on
dépose les fichiers change. On introduit donc une abstraction de stockage,
`RelayStore`, et on ne touche ni à `SyncEngine` ni au format des lots.

```
SyncEngine ── Transfer.Session ──┬─ TcpSession                      (direct)
                                 └─ MailboxSession ── RelayStore ──┬─ RelayCascade ──┬─ WebDavRelayStore
                                                                   │                 ├─ DrimeRelayStore
                                                                   │                 └─ DirRelayStore
                                                                   └─ DirRelayStore (valise, tests)
```

Point clé : **le flux de lots est indépendant du magasin**. Le spool local
(`.cotransfert/mailbox/`) garde les lots scellés jusqu'à leur acquittement et la
position de lecture dans le flux du pair. Changer de magasin revient à déposer
les lots non acquittés dans le nouveau magasin et à y chercher le prochain lot
attendu. Une seule `MailboxSession` vit donc à travers les bascules entre
`webdav`, `drime` et `disque`. La bascule entre `direct` et l'asynchrone garde le
mécanisme actuel (`Agent` : sonde du lien direct, vidage, `discardOutbound`).

### 2.1 Arborescence dans un magasin

Identique partout (chemins relatifs, séparateur `/`) :

```
<NomLogique>/hub-vers-client/000042.lot
<NomLogique>/client-vers-hub/000017.lot
<NomLogique>/hub.ack        <NomLogique>/client.ack
<NomLogique>/client.canal   <NomLogique>/hub.canal      (nouveau, §4)
```

---

## 3. Interface `RelayStore`

Fichier `src/main/java/cotransfert/RelayStore.java` (fourni, fait foi).

| Méthode | Contrat |
|---|---|
| `id()` | `disque`, `webdav` ou `drime` |
| `describe()` | Description pour les journaux, **sans aucun secret** |
| `available()` | Joignable maintenant. Ne lève jamais d'exception. Résultat mis en cache par l'implémentation (pas plus d'une requête par période de scrutation) |
| `lastError()` | Dernière cause d'indisponibilité, en français, courte ; `""` sinon |
| `list(dir)` | Fichiers directs du dossier (nom, taille). Dossier absent → liste vide. Les fichiers temporaires `.part` ne sont pas listés par les magasins en ligne |
| `read(path, maxBytes)` | Contenu, ou `null` si absent. `maxBytes > 0` : au plus ce préfixe |
| `write(path, src)` / `write(path, bytes)` | Crée les dossiers parents, remplace l'existant. Le lecteur ne doit jamais prendre un fichier partiel pour un fichier complet (renommage atomique quand le support le permet ; sinon la taille et le CRC du lot protègent) |
| `delete(path)` | Supprime ; absent → sans effet. **Suppression définitive** quand le support a une corbeille paramétrable |
| `pollMs()` | Période de scrutation conseillée (disque 2 s, en ligne 10 s) |
| `maxLotBytes()` | Taille maximale d'un lot acceptée |
| `generation()` | Change quand le magasin sous-jacent change (cascade) |
| `inboundReady()` | Faux tant que les lots du pair ne doivent pas être lus (cascade, §4) |

Les erreurs réseau lèvent `IOException` avec un message court en français ; la
session réessaie à la passe suivante (comportement actuel du relais).

### 3.1 Adaptation de `MailboxSession`

- Nouveau constructeur prenant un `RelayStore` ; l'ancien (chemin de dossier)
  construit un `DirRelayStore` et garde **exactement** le comportement actuel
  (`MailboxTest` et `Valise` passent sans modification de leur logique).
- Toutes les opérations sur le relais (`resolveRelay`, `Files.*` sur le dossier
  relais, `readAck`, `writeAck`, `peekSid`, `readVerified`, dépôt, ménage,
  comptage des lots entrants, `discardOutbound`) passent par le magasin.
- Quand `generation()` change : remise à zéro de `deposited`, `ackWritten`,
  `ackedSeq` côté cache, et relecture complète à la passe suivante (les lots non
  acquittés du spool sont redéposés, l'acquittement est réécrit).
- Tant que `inboundReady()` est faux : aucun lot entrant n'est lu.
- Scrutation : `pollMs()` du magasin ; taille des lots : `min(option, maxLotBytes())`.
- `channel()` renvoie l'`id()` courant du magasin quand la session est construite
  sur un magasin (sinon l'option actuelle, `relais` ou `valise`).
- Messages d'état pour l'IHM : « relais » devient le nom du canal ; « support
  retirable » n'est affiché que pour le canal `disque`.

---

## 4. Cascade `RelayCascade` (choix du magasin asynchrone)

`RelayCascade` est un `RelayStore` qui délègue au magasin choisi parmi une liste
ordonnée. État local persistant dans `.cotransfert/mailbox/canal.properties`
(`magasin`, `epoque`).

### 4.1 Marqueurs

Petit fichier texte, même forme que les acquittements :
`CTCANAL1 <époque décimale> <crc32c hexadécimal>`.

- `client.canal` : écrit par le client dans le magasin qu'il a choisi.
- `hub.canal` : écho du hub dans le magasin qu'il a adopté, avec la même époque.

L'**époque** est un compteur du client, incrémenté à chaque changement de magasin.

### 4.2 Côté client (décide)

1. Candidat = premier magasin de la liste dont `available()` est vrai.
2. **Descente** (magasin courant indisponible) : après 20 s d'indisponibilité
   continue, ou tout de suite s'il n'y a pas de magasin courant.
3. **Remontée** (un magasin de meilleur rang redevient disponible) : après 30 s
   de disponibilité continue (anti-oscillation).
4. À chaque changement : époque + 1 ; dans le nouveau magasin, **vider son propre
   sens** (`client-vers-hub/`) et supprimer `client.ack`, enregistrer l'état
   local, puis écrire `client.canal` ; `generation()` change. (L'état est
   enregistré **avant** le marqueur : un arrêt entre les deux donne une adoption
   complète au redémarrage, jamais une reprise sur un magasin à moitié préparé.)
5. `inboundReady()` n'est vrai que si `hub.canal` du magasin courant porte
   **la même époque** : le client ne lit rien du hub avant que le hub ait adopté
   ce magasin.
6. Au redémarrage, si le magasin enregistré est disponible et que son
   `client.canal` porte l'époque enregistrée, on le reprend sans rien vider.

### 4.3 Côté hub (suit)

1. À chaque passe, lire `client.canal` dans tous les magasins disponibles ;
   retenir celui de **plus grande époque**.
2. S'il diffère de l'adoption courante (magasin ou époque) : dans ce magasin,
   **vider son propre sens** (`hub-vers-client/`) et supprimer `hub.ack`, écrire
   `hub.canal` avec cette époque, enregistrer, `generation()` change.
3. Aucun marqueur nulle part : aucun magasin adopté, état « en attente du
   client ». Les lots restent dans le spool.
4. Le magasin choisi par le client n'est pas joignable depuis le hub (le hub ne
   voit que des époques plus anciennes) : le hub reste sur ce qu'il voit ; l'IHM
   du client affiche « en attente du hub ».

### 4.4 Pourquoi vider son sens avant de publier le marqueur

Un magasin retrouvé après des jours peut contenir de vieux lots. Sans
précaution, le lecteur pourrait les prendre pour un flux réinitialisé et rejouer
d'anciennes versions (faux conflits, ou écrasement en `master-wins`). Chaque côté
ne lit donc que ce que l'autre a déposé **après** avoir adopté le magasin pour
l'époque courante. Les lots non acquittés ne sont pas perdus : ils sont dans le
spool local et redéposés.

### 4.5 États remontés à l'IHM

Pour chaque magasin configuré : `actif`, `pret` (disponible, pas choisi),
`attente` (choisi, pair pas encore là), `indisponible` (+ `lastError()`).
`RelayCascade` les publie par un rappel `Consumer<List<RelayCascade.State>>`.

---

## 5. Canal WebDAV (`WebDavRelayStore`)

Protocole standard, HTTPS sortant uniquement, `java.net.http.HttpClient`.

- **Configuration** : URL du dossier de transit, identifiant, mot de passe
  d'application. `https://` obligatoire (`http://` accepté seulement vers la
  boucle locale, pour les tests).
- **Authentification** : `Authorization: Basic`, envoyée d'emblée.
- **Opérations** :
  - disponibilité : `PROPFIND` profondeur 0 sur le dossier de base ; s'il est
    absent (`404`), on le crée par `MKCOL` ;
  - liste : `PROPFIND` profondeur 1 (`getcontentlength`, `resourcetype`),
    réponse XML lue avec l'analyseur du JDK, DTD et entités externes désactivées ;
  - lecture : `GET` (`404` → absent) ; préfixe par en-tête `Range`, en acceptant
    une réponse `200` complète que l'on tronque ;
  - écriture : `PUT` vers `<nom>.part` puis `MOVE` avec `Overwrite: T` ;
    dossiers parents créés par `MKCOL` (à la demande, sur `409`, puis mémorisés) ;
  - suppression : `DELETE` (`404` → sans effet).
- **Erreurs** traduites : `401`/`403` « identifiants refusés », `507` « quota
  plein », `423` « fichier verrouillé », `429`/`503` « serveur occupé » (on
  respecte `Retry-After`), échec TLS, proxy.
- **Limites** : `pollMs()` = 10 s ; `maxLotBytes()` = 64 Mio.
- **Corbeille** : certains serveurs gardent les fichiers supprimés un temps ;
  c'est à surveiller côté quota, on ne cherche pas à la vider.

## 6. Canal Drime (`DrimeRelayStore`)

API REST propriétaire, base `https://app.drime.cloud/api/v1`, jeton personnel
créé dans les réglages du compte (`Authorization: Bearer <jeton>`). **On
n'utilise pas le WebDAV de Drime.** Points d'API utilisés (à revérifier dans la
documentation officielle `https://docs.drime.cloud/` au moment du développement) :

| Besoin | Appel |
|---|---|
| Vérifier le jeton | `GET /users/me` |
| Lister un dossier | `GET /drive/file-entries?parentIds=<id>&perPage=…&page=…&workspaceId=…` (pagination par `current_page` / `last_page`) |
| Créer un dossier | `POST /folders` `{name, parentId}` (un appel par niveau) |
| Déposer (≤ 5 Mo) | `POST /uploads` en `multipart/form-data` (`file`, `parentId`, `workspaceId`) |
| Déposer (> 5 Mo) | `POST /s3/multipart/create` → `batch-sign-part-urls` → `PUT` des parts → `complete` → `POST /s3/entries` |
| Lire | `GET /file-entries/download/{hash}` (suivre les redirections) |
| Supprimer | `POST /file-entries/delete` `{entryIds:[…], deleteForever:true}` |

Particularités à traiter :

- L'API adresse les fichiers par **identifiant**, pas par chemin : le magasin
  tient un cache `chemin → (id, hash, taille)` alimenté par les listes, et
  invalide le cache d'un dossier après chaque écriture ou suppression.
- **Deux fichiers de même nom peuvent coexister** dans un dossier : avant de
  déposer, on supprime définitivement l'entrée existante de même nom ; à la
  liste, s'il reste des doublons, on garde le plus récent.
- Pas de renommage atomique garanti : le lecteur s'appuie sur la taille et le
  CRC du lot (déjà le cas).
- Toujours `deleteForever:true` : le transit ne doit pas remplir la corbeille.
- Limites de débit non documentées : sur `429`, attendre (`Retry-After` ou
  repli progressif) ; `pollMs()` = 10 s ; `maxLotBytes()` = 16 Mio.
- **Configuration** : jeton, nom du dossier de transit (défaut
  `CoTransfertRelais`), espace de travail (défaut `0` = personnel), URL de l'API
  (modifiable pour les tests).

## 7. Réseau, proxy, certificats (`HttpSupport`)

Fichier `src/main/java/cotransfert/HttpSupport.java` (fourni).

- **Proxy** : `proxy=system` (défaut : réglages proxy du poste, via
  `java.net.useSystemProxies`), `none`, ou `hôte:port`.
- **Certificats** : `tls.truststore=java` (défaut) ou `windows` (magasin de
  certificats de Windows, utile quand l'organisation inspecte le TLS avec sa
  propre autorité — on s'appuie sur la confiance déjà installée par
  l'organisation, on ne désactive jamais la vérification).
- **Limites connues, à valider sur un poste verrouillé** : proxy exigeant une
  authentification NTLM/Kerberos (non géré par `HttpClient`), configuration
  proxy par script PAC (non lue par le JDK). Dans ces cas le canal est
  « indisponible » et la cascade passe au suivant. On ne contourne pas.

---

## 8. Configuration (`cotransfert.conf`)

Nouvelles clés, toutes optionnelles :

```
# Ordre de la cascade (défaut ci-dessous). Retirer un nom désactive le canal.
channels=direct,webdav,drime,disque

# Canal WebDAV (rang 2)
webdav.url=https://cloud.example.org/remote.php/dav/files/utilisateur/CoTransfertRelais
webdav.user=utilisateur
webdav.password=mot-de-passe-d-application

# Canal Drime (rang 3)
drime.token=jeton-personnel
drime.folder=CoTransfertRelais
# drime.workspace=0
# drime.url=https://app.drime.cloud/api/v1

# Canal disque (rang 4) : clé existante, inchangée
relay=*:\CoTransfertRelais

# Réseau
# proxy=system
# tls.truststore=java
```

- Un canal est **configuré** quand ses clés obligatoires sont renseignées
  (`webdav.url` + `webdav.user` + `webdav.password` ; `drime.token` ; `relay`).
- Options de ligne de commande équivalentes : `--channels`, `--webdav-url`,
  `--webdav-user`, `--webdav-password`, `--drime-token`, `--drime-folder`,
  `--proxy`, `--truststore` (persistées comme les options actuelles).
- Les secrets (`secret`, `webdav.password`, `drime.token`) restent en clair dans
  ce fichier local, non versionné (limite déjà connue) ; ils ne sont **jamais**
  journalisés ni exposés par `/api/status`.
- Les deux postes doivent déclarer les mêmes canaux en ligne (même compte, même
  dossier de transit). `relay.<Nom>` reste possible pour le disque.

## 9. Orchestration (`Agent`)

- Au démarrage : `HttpSupport.init(...)` avant toute activité réseau ;
  construction, par dossier, de la liste ordonnée des magasins configurés selon
  `channels` ; sans `secret`, `webdav` et `drime` sont écartés avec un message.
- Client : lien direct d'abord (comportement actuel, délai 8 s) si `direct` est
  dans `channels` ; sinon, ou en cas d'échec, session asynchrone sur la cascade.
  La sonde du lien direct et le retour au direct restent inchangés.
- Hub : la boucle de relais actuelle utilise la cascade.
- L'IHM locale du client démarre dès qu'un canal asynchrone est configuré.
- `Status` reçoit, par dossier, l'état de chaque canal de la cascade (y compris
  `direct`).

## 10. IHM web

Lecture seule, toujours une page autonome sans ressource externe.

- `/api/status` : chaque dossier de `self` gagne
  `"channels":[{"id":"direct","label":"Lien direct","state":"down","detail":"hub introuvable"},…]`
  dans l'ordre de la cascade. États : `active`, `ready`, `waiting`, `down`,
  `off` (non configuré).
- Carte d'un dossier : sous le titre, une **frise de la cascade** — une pastille
  par canal dans l'ordre (Lien direct → WebDAV → Drime → Disque), colorée selon
  l'état (actif : vert ; prêt : gris-bleu ; en attente du pair : ambre ;
  indisponible : rouge avec la raison en infobulle et en clair sous la frise ;
  non configuré : estompé).
- Le badge de canal existant affiche le canal actif (`WebDAV`, `Drime`,
  `Disque relais`, `Lien direct`, `Valise`).
- La ligne d'information du canal (lots en attente, lots à lire, « à jour »)
  reste ; « support retirable » seulement pour le disque.
- Utilisable au clavier et lisible par lecteur d'écran (texte, pas seulement la
  couleur), thème clair et sombre, mise en page téléphone.

## 11. Tests (sans dépendance, simples `main`)

| Test | Contenu |
|---|---|
| `MailboxTest` (existant) | Doit passer sans changement de logique après l'introduction de `RelayStore` |
| `RelayCascadeTest` | Deux ou trois `DirRelayStore` dont on coupe la disponibilité : descente, remontée, anti-oscillation, marqueurs et époques, magasin contenant de vieux lots (aucun rejeu), hub qui suit, reprise après redémarrage, aucun fichier perdu ni dupliqué à travers trois bascules |
| `WebDavStoreTest` | Mini-serveur WebDAV de test (`com.sun.net.httpserver`, dans `src/test`) : toutes les opérations du contrat, `401`, `404`, `507`, `429` avec `Retry-After`, noms avec espaces et accents, puis une session `MailboxSession` complète hub ↔ client à travers ce serveur |
| `DrimeStoreTest` | Faux serveur Drime de test : liste paginée, doublons de nom, dépôt simple et multipart, suppression définitive, `401`, `429`, puis une session complète hub ↔ client |

Les tests n'appellent **aucun** service réel. Un essai manuel de bout en bout
sur de vrais comptes reste à faire par l'utilisateur (voir §13).

## 12. Découpage du développement

| Lot | Fichiers | Dépend de |
|---|---|---|
| 0 — socle | `RelayStore.java`, `HttpSupport.java` (fournis) | — |
| A — cœur | `DirRelayStore.java`, `RelayCascade.java`, `MailboxSession.java`, `Valise.java` si nécessaire, `MailboxTest.java`, `RelayCascadeTest.java` | 0 |
| B — WebDAV | `WebDavRelayStore.java`, `WebDavStoreTest.java` | 0 |
| C — Drime | `DrimeRelayStore.java`, `DrimeStoreTest.java` | 0 |
| D — intégration | `Config.java`, `Agent.java`, `Status.java`, `WebServer.java`, `index.html`, `README.md`, `ROADMAP.md`, `CLAUDE.md`, lanceurs | A, B, C |

A, B et C sont indépendants et se développent en parallèle ; D vient ensuite.

## 13. Recette manuelle (deux postes réels)

1. Câble branché : le dossier est en `Lien direct`.
2. Câble débranché, WebDAV configuré des deux côtés : passage en `WebDAV` en
   moins d'une minute, un fichier modifié d'un côté arrive de l'autre, le dossier
   de transit se vide après lecture.
3. Mot de passe WebDAV faussé sur le client : `WebDAV` rouge « identifiants
   refusés », passage en `Drime`, la synchro continue.
4. Réseau coupé sur le client, disque branché : passage en `Disque`, « support
   retirable », déplacement du disque vers le hub puis retour : convergence.
5. Rebrancher le câble : retour en `Lien direct`, aucun conflit parasite.
6. À chaque étape : aucun fichier perdu, aucun `.conflict-peer-…` inattendu.

## 14. Risques et points ouverts

- Proxy authentifiant NTLM/Kerberos ou PAC sur le poste client (§7) : à tester
  en premier, c'est ce qui décide si les canaux en ligne sont utilisables.
- API Drime : limites de débit et comportement exact des doublons non
  documentés ; quota de l'offre gratuite à vérifier.
- Marqueurs et acquittements non authentifiés (un tiers ayant accès au magasin
  peut bloquer la synchro, pas lire ni injecter de contenu).
- Un lot est tenu en mémoire (limite existante), d'où des lots plus petits en
  ligne.
- Politique de l'organisation : si elle interdit ces services, on ne les utilise
  pas ; le canal `disque` et la valise restent.

---

## 15. Point d'entrée unique et réglages depuis l'IHM

### 15.1 Besoin

Un utilisateur standard n'a **qu'un fichier à lancer** : `CoTransfert.cmd`
(double-clic). Il ne tape aucune commande et n'édite aucun fichier.

- Le script trouve tout seul un Java 21+ déjà présent (choix forcé
  `COTRANSFERT_JAVA`, `JAVA_HOME`, `PATH`, puis emplacements usuels), lance
  `cotransfert.jar` sans argument et le relance quand l'agent sort avec le
  **code 75** (réglages modifiés).
- L'agent démarre **toujours** l'IHM web et ouvre la page dans le navigateur,
  même sans aucune configuration (mode « premier réglage »).
- Tout se règle dans la page : rôle, dossiers, secret, canaux.
- Les anciens lanceurs sont rangés dans `outils/` (usage avancé, mesure de débit).

### 15.2 Comportement de l'agent

| Situation | Comportement |
|---|---|
| `java -jar cotransfert.jar` sans rôle configuré | IHM sur la boucle locale, page ouverte sur les réglages, aucune synchro, aucune écoute réseau |
| Rôle configuré, aucun dossier | Idem, la page invite à ajouter un dossier |
| Hub configuré | Comme aujourd'hui, IHM sur toutes les interfaces (l'état reste lisible depuis le client), **réglages réservés à la boucle locale** |
| Client configuré | IHM **toujours** démarrée, boucle locale uniquement |
| Port de l'IHM déjà pris par un CoTransfert | Ouvre la page existante et sort avec le code 0 (pas de second agent) |
| Réglages enregistrés depuis la page | Écrit `cotransfert.conf`, répond, puis sort avec le code 75 ; la page attend le retour de l'agent |
| Lancé sans le script (pas de relance) | Le code 75 arrête l'agent ; le journal dit de le relancer |

Options : `--no-browser` (ne pas ouvrir le navigateur), `--no-web` et
`--web-port` inchangées. `--no-web` sans configuration : message d'usage, code 2.
Les options de ligne de commande existantes restent valables.

### 15.3 API de réglage (boucle locale uniquement)

Toutes ces routes exigent : adresse distante de boucle locale ; en-tête `Host`
valant `localhost`, `127.0.0.1` ou `[::1]` (avec ou sans port) ; s'il est
présent, `Origin` sur le même hôte. Les `POST` exigent en plus
`Content-Type: application/json` et l'en-tête `X-CoTransfert: 1`. Sinon `403`
`{"ok":false,"message":"reserve a ce poste"}`. Corps limité à 64 Kio.

**`GET /api/config`** — jamais de secret en clair :

```json
{"configured":true,"role":"hub","master":true,"conflict":"keep-both",
 "roots":[{"name":"MonProjet","path":"C:\work\MonProjet","exists":true}],
 "secretSet":true,
 "channels":["direct","webdav","drime","disque"],
 "webdav":{"url":"https://cloud.example.org/remote.php/dav/files/u/CoTransfertRelais","user":"u","passwordSet":true},
 "drime":{"tokenSet":false,"folder":"CoTransfertRelais"},
 "relay":"*:\CoTransfertRelais",
 "peer":"","discovery":"link","proxy":"system","truststore":"java",
 "configPath":"C:\outils\cotransfert.conf","host":"NOM-DU-POSTE"}
```

`configured` = rôle valide et au moins un dossier. `master` vaut toujours un
booléen (valeur effective).

**`POST /api/config`** — même forme. Champs absents = inchangés. Secrets :
`"secret"`, `"webdav":{"password":…}`, `"drime":{"token":…}` ; absent ou vide =
inchangé ; `"clear":["secret","webdav.password","drime.token"]` pour effacer.
`roots` remplace la liste entière. Validation côté serveur (rôle `hub|client`,
nom logique non vide et unique sans `=` ni saut de ligne, chemin absolu qui
existe ou que l'on peut créer, secret ≥ 16 caractères, `webdav.url` en `https`,
`channels` parmi les quatre). Réponses :

```json
{"ok":true,"restart":true}
{"ok":false,"errors":[{"field":"roots[0].path","message":"dossier introuvable"}]}
```

Après `ok`, l'agent sort avec le code 75 environ une seconde plus tard.

**`POST /api/secret/new`** → `{"secret":"…"}` : génère un secret, **sans
l'enregistrer** (la page le place dans le formulaire).
**`POST /api/secret/reveal`** → `{"secret":"…"}` : secret actuel, pour le
recopier sur l'autre poste (`""` s'il n'y en a pas).

**`POST /api/test`** — essaie un canal avec les valeurs du formulaire (un secret
absent = celui déjà enregistré), sans rien enregistrer :

```json
{"channel":"webdav","webdav":{"url":"…","user":"…","password":"…"}}
{"channel":"drime","drime":{"token":"…","folder":"…"}}
{"channel":"disque","relay":"*:\CoTransfertRelais"}
```

→ `{"ok":true,"message":"joignable"}` ou `{"ok":false,"message":"identifiants refuses"}`.
Délai maximal 20 s.

**`POST /api/browse`** `{"start":"C:\work"}` → ouvre le sélecteur de dossier
du poste (Swing, JDK pur) et renvoie `{"path":"C:\work\MonProjet"}`, `{"path":""}`
si annulé, `{"error":"indisponible"}` sinon (la saisie au clavier reste possible).

**`POST /api/quit`** → `{"ok":true}` puis arrêt de l'agent (code 0).

`GET /api/status` reste lisible de partout (lecture seule, aucun secret) et
gagne `"self":{…,"configured":true|false}`.

### 15.4 Page

- Bouton **Réglages** visible seulement si `GET /api/config` répond (donc sur le
  poste lui-même) ; ouvert d'office tant que `configured` est faux.
- Premier réglage guidé, en langage courant :
  1. **Rôle** — « Ce poste accepte la connexion (hub) » / « Ce poste se connecte
     à l'autre (client) », avec une phrase d'aide pour choisir.
  2. **Dossiers** — nom + chemin, bouton « Parcourir… », ajouter / retirer.
  3. **Secret** — hub : « Générer » puis « Afficher » pour le recopier ; client :
     coller celui du hub.
  4. **Canaux en ligne** (facultatif, replié) — WebDAV, Drime, disque relais,
     chacun avec un bouton **Tester** et son résultat en clair.
  5. **Avancé** (replié) — ordre des canaux, politique de conflit, hub forcé,
     découverte, proxy, certificats.
- **Enregistrer** : erreurs affichées à côté des champs ; en cas de succès,
  message « CoTransfert redémarre… » jusqu'au retour de `/api/status`.
- Les champs secrets sont de type mot de passe, vides, avec la mention
  « déjà enregistré » quand c'est le cas.
- Bouton **Quitter CoTransfert** (avec confirmation dans la page, sans boîte de
  dialogue du navigateur).

---

## 16. Choix manuel du canal (remplace la bascule automatique)

**Cette section annule et remplace** la cascade automatique : §1 (R1, R2, R6),
§2 (rôle de `RelayCascade`), §4 en entier, la clé `channels` du §8, l'ordre des
canaux du §15.4 et la frise par dossier du §10. Le reste (magasins `RelayStore`,
format des lots, WebDAV, Drime, secret obligatoire en ligne, point d'entrée
unique, API de réglage) reste valable.

### 16.1 Besoin

La bascule automatique entre canaux est trop compliquée à comprendre et à
dépanner. Nouveau principe :

- **Un seul canal à la fois, choisi par l'utilisateur** dans la page :
  Lien direct, WebDAV, Drime ou Disque relais.
- **Aucun canal sélectionné par défaut** : tant que l'utilisateur n'a rien
  choisi, CoTransfert ne synchronise rien et la page l'invite à choisir.
- Un clic sur un canal le **teste** et affiche **OK** ou **KO** avec la raison.
- Le choix est **enregistré** dans `cotransfert.conf` et repris au redémarrage.
- **Aucune bascule automatique**, dans aucun sens : si le canal choisi tombe,
  CoTransfert attend son retour et le dit ; il ne passe pas à un autre.
- Les deux postes doivent choisir **le même canal**. Il n'y a plus de « le client
  décide, le hub suit » ni de fichiers marqueurs : chaque poste dépose et lit
  directement dans le canal choisi.

### 16.2 Configuration

- Nouvelle clé `channel=` : `direct`, `webdav`, `drime`, `disque`, ou vide
  (aucun). Option de ligne de commande `--channel`.
- La clé `channels` (liste ordonnée) disparaît : ignorée à la lecture, plus
  écrite. `--channels` disparaît aussi.
- Les réglages des canaux (`webdav.*`, `drime.*`, `relay`) restent inchangés :
  on peut régler plusieurs canaux, un seul est utilisé.

### 16.3 Comportement de l'agent

| `channel` | Hub | Client |
|---|---|---|
| vide | Page seule (boucle locale), aucune écoute réseau, aucune synchro | Page seule, aucune synchro |
| `direct` | Écoute, beacon, comme le mode nominal d'origine | Découverte et connexion, sans délai de repli : il attend le hub indéfiniment |
| `webdav` / `drime` / `disque` | Pas d'écoute de synchro ni de beacon ; une session par lots sur le magasin choisi, tout de suite | Pas de découverte ni de connexion ; une session par lots sur le magasin choisi |

- La session par lots est une `MailboxSession` construite **directement** sur le
  magasin du canal (`RelayStore`), sans `RelayCascade`. `RelayCascade` et son
  test sont retirés du code.
- Canal choisi mais non réglé, ou en ligne sans secret : aucune synchro, état
  `down` avec la raison, la page reste disponible.
- Canal choisi indisponible (réseau, identifiants, disque absent) : la session
  réessaie indéfiniment ; les lots attendent dans le spool local.
- **Changement de canal** (aucune perte, aucun rejeu) : le dernier canal utilisé
  par dossier est noté dans le spool (`.cotransfert/mailbox/canal.properties`,
  clé `canal`).
  - Vers `direct` : la réconciliation du lien direct resynchronise tout, donc
    les lots non acquittés du spool sont **abandonnés** au démarrage
    (`discardOutbound`, qui réinitialise le flux), avec nettoyage au mieux de
    son propre sens dans l'ancien magasin s'il est joignable.
  - D'un canal par lots à un autre : le spool est gardé, les lots non acquittés
    sont redéposés dans le nouveau magasin (le flux continue).
  - Limite connue, à documenter : de vieux lots restés dans un magasin
    abandonné puis re-sélectionné plus tard peuvent, dans de rares cas, être
    relus et produire des copies `.conflict-peer-…` (jamais une perte).

### 16.4 API

Mêmes protections que les autres routes de réglage (§15.3).

**`POST /api/channel`** `{"channel":"drime","force":false}`

1. Nom inconnu → `{"ok":false,"message":"canal inconnu"}`.
2. `""` → désélection : enregistre, `{"ok":true,"restart":true}`.
3. Canal non réglé → `{"ok":false,"needsConfig":true,"message":"canal non regle"}`.
4. Canal en ligne sans secret → `{"ok":false,"needsConfig":true,"message":"secret partage manquant"}`.
5. Test : `direct` n'est pas testable (le câble peut être branché plus tard) →
   considéré OK, `"tested":false`. Les autres : même essai que `/api/test` avec
   les valeurs enregistrées, 20 s au plus.
6. Test KO et `force` faux → `{"ok":false,"canForce":true,"message":"<raison>"}`, rien n'est enregistré.
7. Test OK, ou `force` vrai → enregistre `channel=`, répond
   `{"ok":true,"restart":true,"tested":true,"message":"joignable"}` puis sort
   avec le code 75. Si ce canal était déjà sélectionné : `"restart":false`, pas
   de redémarrage (simple re-test).

**`GET /api/config`** : `"channel":"drime"` (ou `""`) remplace `"channels"`.
`POST /api/config` ignore `channels` et `channel` (le choix passe par
`/api/channel`). `configured` ne dépend pas du canal.

**`GET /api/status`** : dans chaque dossier de `self`, `"channel"` vaut le canal
sélectionné (ou `""`) et `"channels"` liste toujours les quatre canaux dans
l'ordre fixe `direct`, `webdav`, `drime`, `disque` :

```json
{"id":"drime","label":"Drime","selected":true,"state":"active","detail":""}
```

| `state` | Sens |
|---|---|
| `active` | sélectionné, session en cours (lien direct connecté, ou magasin joignable) |
| `waiting` | sélectionné, en attente de l'autre poste (lien direct non connecté) |
| `down` | sélectionné mais indisponible ; `detail` donne la raison |
| `ready` | réglé, non sélectionné (pas testé en continu) |
| `off` | non réglé ; `detail` : `non configure` ou `secret partage manquant` |

`self` gagne `"channel":"drime"` (le choix global). L'état d'un dossier vaut
`WAITING` (« En attente de l'autre poste ») à la place de `UP_TO_DATE` tant
qu'aucun canal n'est `active`.

### 16.5 Page

- En haut de la vue principale (poste local seulement), un panneau
  **« Canal de transfert »** : quatre cartes, une par canal, avec son état en
  toutes lettres. C'est un groupe de choix exclusif (une seule sélectionnée, ou
  aucune).
- Clic sur une carte : « Test en cours… », puis
  - OK → « OK », le choix est enregistré, CoTransfert redémarre (état d'attente
    déjà existant) ;
  - KO → « KO : <raison> » en clair, la sélection ne change pas ; un bouton
    « Choisir quand même » (utile pour un disque pas encore branché) ;
  - non réglé → ouvre les réglages sur la rubrique de ce canal.
- La carte sélectionnée a un bouton « Tester à nouveau » et une action « Ne plus
  utiliser » (désélection).
- Aucun canal sélectionné : message « Choisissez un canal de transfert » ;
  rappel permanent : « Choisissez le même canal sur les deux postes ».
- La frise par dossier disparaît ; chaque dossier garde son badge de canal et sa
  ligne d'information. Vue en lecture seule (depuis l'autre poste) : le panneau
  montre les états sans être cliquable.
- Dans les réglages : la rubrique « Avancé » perd l'ordre des canaux ; la
  rubrique des canaux s'appelle « Réglage des canaux » (WebDAV, Drime, Disque
  relais), toujours avec les boutons « Tester ».

---

## 17. Lancement silencieux et mise à jour par le canal

### 17.1 Besoin

- **Plus de fenêtre noire en permanence.** Un double-clic sur `CoTransfert.cmd`
  lance l'agent sans console ; la fenêtre du script se ferme aussitôt. L'agent
  s'arrête par le bouton « Quitter CoTransfert » de la page.
- **Mise à jour de l'autre poste par le canal de transfert**, quel qu'il soit
  (lien direct, Drime, WebDAV, disque) : le hub publie sa version d'un clic, le
  client la voit, l'installe d'un clic (ou tout seul si l'utilisateur l'a
  demandé) et redémarre. Mêmes boutons, mêmes fichiers scellés, même
  comportement sur tous les canaux.
- Toujours : rien à installer, pas d'admin, Java SE pur, aucun script autre que
  `CoTransfert.cmd`, pas de PowerShell ni de VBScript.

### 17.2 Lancement sans console

- `CoTransfert.cmd` : trouve Java comme aujourd'hui, prend le `javaw.exe` voisin
  du `java.exe` retenu, applique une mise à jour en attente (§17.4), lance
  `javaw -jar cotransfert.jar` détaché (`start ""`) puis se termine. Erreur avant
  le lancement (pas de Java, pas de jar) : message dans la console et `pause`,
  comme aujourd'hui.
- `CoTransfert.cmd --console` : ancien comportement (console visible, `java.exe`,
  boucle sur le code 75), pour le diagnostic. La variable `COTRANSFERT_CONSOLE=1`
  est posée dans ce mode.
- **Journal** : sans console, tout ce que l'agent écrivait à l'écran va dans
  `cotransfert.log`, à côté de `cotransfert.conf` (repli : dossier temporaire de
  l'utilisateur). Taille bornée : au démarrage, au-delà de 2 Mio le fichier est
  renommé `cotransfert.log.1` (un seul archivé). En mode console, écran **et**
  fichier. Jamais de secret dans le journal (règle existante).
- **Erreur fatale au démarrage sans console** (port pris par un autre programme,
  configuration illisible…) : petite boîte de dialogue (Swing, JDK pur) avec le
  message, puis arrêt. Sans affichage possible : journal seulement.
- **Redémarrage sans script** (réglages enregistrés, changement de canal, mise à
  jour) : hors mode console, l'agent ne sort plus avec le code 75. Il copie son
  jar dans un fichier temporaire, lance depuis cette copie un petit processus
  « relance » (`javaw -cp <copie> cotransfert.Relance …`), puis s'arrête (code 0).
  La relance attend la fin de l'agent, applique une mise à jour en attente,
  relance `javaw -jar cotransfert.jar` avec les mêmes arguments et
  `COTRANSFERT_RELANCE=1` (ne pas rouvrir le navigateur), surveille le démarrage
  (§17.4) puis se termine et supprime sa copie quand c'est possible.
- Second double-clic alors que l'agent tourne : inchangé (ouvre la page, pas de
  second agent).

### 17.3 Version

- La **version** d'un jar est sa date de construction : l'horodatage de l'entrée
  `META-INF/MANIFEST.MF` du jar en cours d'exécution, en millisecondes, plus son
  empreinte SHA-256. Aucune étape de build en plus. Affichée
  « version du 2026-11-16 16:41 ».
- Agent lancé hors d'un jar (classes, tests) : version inconnue, mise à jour
  désactivée, la page le dit.

### 17.4 Mise à jour par le canal

Disponible quand un canal est choisi et utilisable, **avec un secret
partagé** : canal par lots (webdav, drime, disque) ou lien direct (§17.7).

**Dans le magasin**, à la racine du dossier de transit (hors des dossiers
synchronisés) — sur le lien direct, dans le dossier `cotransfert.maj` du hub :

```
_maj/version.scelle          fiche : "CTMAJ1 <dateMs> <taille> <sha256 hex>"
_maj/cotransfert.jar.scelle  le jar
```

Les deux sont scellés avec le secret partagé (même chiffrement authentifié que
les lots, donnée associée distincte de celle des lots : `maj|fiche` et
`maj|jar`). Un fichier qui ne s'ouvre pas avec le secret est **refusé** : il ne
vient pas de l'autre poste.

**Publier (hub uniquement, sur clic).** Dépose le jar puis la fiche (dans cet
ordre). Remplace la version publiée précédente.

**Vérifier (client).** Au démarrage de la session, puis toutes les 10 minutes,
et sur clic : lit la fiche (une requête). Quand le canal n'a pas pu être lu
(serveur en panne, hub pas encore trouvé sur le lien direct), nouvel essai
après 30 s au lieu de 10 minutes. Une mise à jour est **disponible** si
la date publiée est postérieure à la sienne et l'empreinte différente, et que
cette empreinte n'est pas marquée « refusée » (voir retour arrière).

**Installer (client, sur clic ; automatique si `update.auto=true`).** Télécharge
le jar, l'ouvre avec le secret, vérifie taille et SHA-256 de la fiche, vérifie
que c'est un jar dont la classe principale est `cotransfert.Agent`, l'écrit sous
`cotransfert.jar.nouveau` à côté du jar (écriture atomique), puis redémarre.

**Échange** (par la relance, ou par `CoTransfert.cmd` au lancement) : si
`cotransfert.jar.nouveau` existe, `cotransfert.jar` devient
`cotransfert.jar.precedent` et le nouveau prend sa place.

**Retour arrière.** Après un échange, la relance surveille le nouvel agent
pendant 30 s. S'il s'arrête avec une erreur dans ce délai, elle remet
`cotransfert.jar.precedent` en place, note l'empreinte fautive dans
`cotransfert.maj-refusee`, relance l'ancienne version, et la page affiche « la
mise à jour du … n'a pas démarré, version précédente rétablie ».

Clé de configuration : `update.auto=false` (défaut). Option `--update-auto`.

### 17.5 API (mêmes protections que §15.3)

- `POST /api/update/publish` (hub) → `{"ok":true,"message":"version du … publiee"}` ou `{"ok":false,"message":"…"}`.
- `POST /api/update/check` → `{"ok":true,"available":true|false,"version":"2026-11-16 16:41","message":"…"}`.
- `POST /api/update/install` → `{"ok":true,"restart":true}` ou `{"ok":false,"message":"…"}`.
- `GET /api/log?lines=200` → `{"path":"…\cotransfert.log","lines":["…"]}` (au plus 1000 lignes).
- `GET /api/config` et `POST /api/config` : champ booléen `updateAuto`.
- `GET /api/status`, dans `self` :

```json
"update":{"current":"2026-11-16 16:41","supported":true,"canPublish":true,
          "available":false,"version":"","state":"","message":""}
```

`supported` : faux hors jar, sans canal utilisable ou sans secret partagé
(`message` dit pourquoi) ; vrai sur le lien direct dès qu'un secret est défini. `canPublish` : vrai sur le hub quand `supported`. `state` : `""`,
`checking`, `downloading`, `ready` (redémarrage en cours), `failed`,
`rolled-back`. `message` : phrase courte en français.

### 17.6 Page

- **Bandeau** en haut de la vue principale quand `update.available` : « Une
  mise à jour de CoTransfert est disponible (version du …) » + bouton
  « Installer et redémarrer ». Pendant l'installation : état d'attente existant.
  Après un retour arrière : bandeau d'avertissement avec le message.
- **Sur le hub** : dans les réglages, rubrique « Avancé », bloc « Mise à jour de
  l'autre poste » : version en cours, bouton « Publier cette version pour
  l'autre poste », résultat en clair.
- **Sur le client** : même rubrique, version en cours, bouton « Vérifier
  maintenant », case « Installer les mises à jour automatiquement » (décochée
  par défaut, avec une phrase : l'installation manuelle reste visible et
  volontaire).
- **Journal** : bouton « Journal » (poste local) qui affiche les dernières
  lignes de `cotransfert.log`, avec « Actualiser » et le chemin du fichier.
- Rappel dans la page d'arrêt : l'agent tourne sans fenêtre ; « Quitter
  CoTransfert » est la façon de l'arrêter.

### 17.7 Mise à jour par le lien direct

Même `Updater`, mêmes fichiers scellés, même page : seul le magasin change.

- **Hub** : `DirRelayStore` sur le dossier `cotransfert.maj`, à côté de
  `cotransfert.conf` (créé à la première publication ; fichiers sous
  `cotransfert.maj/_maj/`). Le hub le sert par le serveur HTTP de la page
  (`MajRoutes`), déjà à l'écoute sur toutes les interfaces quand le hub est
  réglé. Ces routes ne sont **pas** réservées à la boucle locale :
  - `GET /maj/liste` → `text/plain` : ligne `CTMAJ-LISTE 1`, puis une ligne
    `<taille> <nom>` par fichier publié ;
  - `GET /maj/fichier?nom=<nom>[&max=<n>]` → le fichier (au plus `n` octets),
    `404` s'il est absent ;
  - `PUT /maj/fichier?nom=<nom>` → dépôt par le client (le jar, puis la fiche).
- **Client** : `HubHttpRelayStore` (id `direct`), requêtes HTTP **sortantes**
  vers ces routes, sans proxy ni redirection, à l'adresse du hub que la
  session directe utilise (découverte, ou `peer=` / `--peer`), sur le port de
  la page (`--web-port`, supposé identique sur les deux postes). Tant que le
  hub n'est pas localisé ou ne répond pas : magasin indisponible, raison
  affichée, nouvel essai automatique. Le client n'écoute jamais.
- **Garde-fous des routes** :
  - `nom` vaut exactement `version.scelle` ou `cotransfert.jar.scelle`
    (comparaison stricte, aucun chemin construit depuis la requête) ; sinon
    `400` ; autre chemin sous `/maj/` : `404` ; autre méthode : `405` ;
  - taille : fiche ≤ 4096 octets, jar ≤ 12 Mio + l'enveloppe du scellement ;
    au-delà `413`, rien n'est écrit ;
  - `PUT` : le contenu doit s'ouvrir avec le secret partagé du hub (bonne
    donnée associée comprise), sinon `403` et rien n'est écrit. Le jar reçu
    est mis de côté (`cotransfert.jar.scelle.recu`) sans toucher à la
    publication ; la fiche valide l'ensemble : elle doit décrire ce jar
    (taille, empreinte) et ne pas être plus ancienne que la version publiée
    (pas de rejeu d'une ancienne publication capturée sur le lien), sinon
    `409`. Alors seulement le jar puis la fiche remplacent la publication ;
  - aucune suppression par le réseau (pas de `DELETE`) ;
  - sans secret partagé, ou sur un poste qui n'est pas un hub réglé sur le
    lien direct : `/maj/…` répond `404`.
- **Secret obligatoire** : le lien direct synchronise sans secret, la mise à
  jour non (elle est scellée avec lui). Sans secret : `supported:false`,
  message « secret partagé manquant : sur le lien direct, la mise à jour
  demande le même secret partagé sur les deux postes ».
- Hub lancé avec `--no-web` : pas de serveur, donc pas de mise à jour par le
  lien direct (`message` le dit).
- Inchangés : `CoTransfert.cmd`, `Relance`, l'échange de jar, le retour
  arrière, « le hub publie, le client installe ».

---

## 18. Écran « Conflits »

### 18.1 Besoin

Avec la politique par défaut (`keep-both`), un fichier modifié des deux côtés
n'est jamais écrasé : le poste garde sa version et dépose celle de l'autre poste
à côté, sous `<fichier>.conflict-peer-<date>`. Aujourd'hui l'utilisateur doit
chercher ces copies à la main. Il lui faut, dans la page :

- **voir** la liste des conflits de ce poste (fichier, date, taille et date de
  chaque version) ;
- **choisir** pour chacun « Garder ma version » ou « Garder la version de
  l'autre poste », ou le faire **pour tous** d'un coup ;
- que le choix **converge** : après résolution, les deux postes ont le même
  contenu, sans nouveau conflit en retour.

### 18.2 Règles

- **Source** : le fichier `<dossier>/.cotransfert/conflicts.log` du poste
  (lignes `date<TAB>chemin<TAB>-> copie`), filtré aux copies qui existent
  encore. Un conflit n'existe que sur le poste qui l'a détecté.
- Les copies `*.conflict-peer-AAAAMMJJ-HHMMSS` ne sont **jamais synchronisées**
  (exclusion dure, comme les fichiers internes) : elles sont propres au poste
  qui doit trancher. Les copies déjà présentes ne sont pas supprimées chez le
  pair.
- **Garder ma version** : l'index de référence du fichier prend l'empreinte de
  la copie (la version que l'autre poste possède), la copie est supprimée, puis
  mon fichier est renvoyé au pair avec cette base : le pair l'accepte sans
  conflit. Sans session active, l'envoi part à la prochaine session (même base).
- **Garder la version de l'autre poste** : la copie remplace mon fichier
  (déplacement atomique), l'index prend son empreinte ; rien n'est renvoyé, le
  pair a déjà ce contenu.
- Mon fichier a disparu entre-temps : « garder la mienne » supprime la copie et
  ne renvoie rien ; « garder l'autre » restaure le fichier depuis la copie.
- La résolution passe par le moteur du dossier (un seul moteur par dossier) :
  jamais d'écriture concurrente hors du moteur.
- Le **compteur** de conflits d'un dossier (`conflicts` dans `/api/status`) est
  le nombre de conflits **en attente** selon cette liste : il redescend à chaque
  résolution et survit aux redémarrages.
- Les conflits « supprimé chez le pair mais modifié ici » n'ont pas de copie :
  le fichier local est simplement conservé ; ils ne figurent pas dans la liste.

### 18.3 API (mêmes protections que §15.3)

**`GET /api/conflicts`**

```json
{"count":2,"conflicts":[
  {"root":"MonProjet","path":"src/a.txt","copy":"src/a.txt.conflict-peer-20261116-173000",
   "when":"2026-11-16 17:30:00","mineExists":true,
   "mineSize":1200,"mineTime":1794840000000,"theirsSize":1180,"theirsTime":1794839000000}]}
```

**`POST /api/conflicts/resolve`**

```json
{"root":"MonProjet","copy":"src/a.txt.conflict-peer-20261116-173000","keep":"mine"}
{"all":true,"keep":"theirs"}
{"all":true,"root":"MonProjet","keep":"mine"}
```

→ `{"ok":true,"resolved":2,"failed":[]}` ; un échec partiel répond
`{"ok":false,"resolved":1,"failed":[{"copy":"…","message":"fichier verrouille"}]}`.
`keep` vaut `mine` ou `theirs`. `copy` doit être une copie listée (aucun chemin
arbitraire, aucun `..`).

**`POST /api/conflicts/open`** `{"root":"MonProjet","copy":"…"}` → ouvre
l'explorateur de fichiers du poste sur le dossier du fichier (JDK pur) ;
`{"ok":true}` ou `{"ok":false,"message":"indisponible"}`.

### 18.4 Page

- Quand un dossier de ce poste a des conflits : le compteur rouge de la carte
  devient un bouton « N conflits » qui ouvre l'écran ; un bouton « Conflits »
  apparaît aussi en haut tant qu'il en reste.
- L'écran explique en une phrase ce qu'est un conflit et que rien n'a été perdu.
- Liste groupée par dossier synchronisé. Par ligne : chemin du fichier, date du
  conflit, « Ma version : taille, date » / « Version de l'autre poste : taille,
  date » (la plus récente signalée), boutons « Garder ma version », « Garder
  celle de l'autre poste », « Ouvrir l'emplacement ».
- Actions groupées : « Garder ma version pour tous », « Garder celle de l'autre
  poste pour tous », avec confirmation dans la page.
- Résultat en clair après chaque action ; un échec reste affiché sur sa ligne.
- Si le « Conflit » affiché vient de l'autre poste (état du pair) : la page dit
  que le conflit se règle sur l'autre poste, depuis sa propre page.
- Vue en lecture seule (page vue depuis l'autre poste) : aucun bouton.
