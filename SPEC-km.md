# Partage clavier-souris (« KM ») — spécification du prototype

État : **intégré à l'agent** (accept du hub, connexion du client, réglages, page)
et **testé avec des doublures** (`KmTest`, `SetupApiTest`), mais **jamais essayé
sur de vrais postes**. Voir « Reste à valider ».

## Principe

Quand le hub et le client sont reliés par le **lien direct**, l'utilisateur pilote
l'autre poste avec le clavier et la souris d'un seul poste — celui du hub ou celui
du client, dans un sens ou dans l'autre. On travaille au niveau des événements
Windows vus par AWT, pas du matériel : peu importe que le clavier soit en USB ou
en Bluetooth.

- Fonction **optionnelle, désactivée par défaut**.
- Uniquement sur le canal `direct`, et uniquement avec un **secret partagé** :
  les frappes ne circulent jamais en clair.
- Java SE pur : `java.awt.Robot` pour rejouer, une fenêtre AWT pour capter. Ni
  pilote, ni droit administrateur, ni code natif, ni crochet clavier global.
- **Presse-papiers : texte seulement** (`km.clipboard`, activée par défaut dès
  que le partage clavier-souris l'est, décochable ; effective seulement si les
  deux postes l'ont). Voir « Presse-papiers » plus bas.

| Classe | Rôle |
|---|---|
| `KmProtocol` | format de fil des événements |
| `KmLink` | connexion dédiée, chiffrée, battement de cœur |
| `KmCapture` | poste qui envoie : fenêtre plein écran, bandeau, déplacement relatif |
| `KmInject` | poste piloté : `Robot` derrière `Sink` / `Device`, suivi de ce qui est enfoncé |
| `KmEdge` | déclencheur « bord d'écran » (logique pure) |
| `KmShortcut` | raccourci Windows `.lnk` portant la touche de raccourci |
| `KmSettings` | réglages immuables, clés `km.*` |
| `Km` | contrôleur : machine à états, points d'entrée de l'agent |
| `KmDemo` | essai manuel entre deux postes, sans l'agent |

## Lien

Une connexion TCP **dédiée**, distincte de celles de la synchronisation (un gros
fichier ne retarde jamais la souris), **ouverte par le client vers le hub** sur le
port de la synchronisation (47777). Le client n'écoute jamais.

1. Poignée de main chiffrée de `Crypto` (X25519 + AES-256-GCM), la même que pour
   la synchronisation. Sans secret : refus des deux côtés.
2. Le client envoie **un cadre `Transfer`** d'opération `OP_KM = 9` (1 à 6 sont à
   `Transfer`, 7 et 8 à `SyncEngine`), chemin `km`, version du protocole KM dans
   le champ `hash`. Le hub lit déjà le premier cadre de chaque connexion
   (`OP_HELLO` = un dossier) : il reconnaît ainsi le lien KM (`Agent.serveKm`).
3. Ensuite, dans les deux sens, une suite d'événements `KmProtocol`.

Émission par une file et un fil dédié (le fil graphique ne bloque jamais sur le
réseau) ; les déplacements en attente sont fusionnés. Battement de cœur toutes
les 500 ms ; **rien reçu pendant 2 s = lien fermé**.

## Intégration dans l'agent

- **Démarrage** (`Agent.configureKm`) : lit `km.*`, règle `Km`, lance la
  scrutation du bord si la fonction est utilisable.
- **Hub** : dans l'accept de la synchronisation (`Agent.hubDirect`), un premier
  cadre `OP_KM` confie la session à `Km.serveHub` ; sans secret, il est refusé.
- **Client** : un fil `cotp-km` ouvre le lien vers l'adresse du hub trouvée par la
  synchronisation (`Agent.knownHub`), et recommence s'il tombe.
- **API** (boucle locale, gardes de `SetupApi`) : `POST /api/km/toggle` (bascule),
  `POST /api/km/shortcut` `{"create":true|false}` (raccourci Windows),
  sous-objet `km` de `/api/config` (`enabled`, `edge`, `delay`, `hotkey`,
  `returnButton`).
- **État** : `self.km` dans `/api/status` (`enabled`, `available`, `problem`,
  `linked`, `peerReady`, `state`, `edge`, `delay`, `hotkey`, `returnButton`,
  `ignored`, `message`).
- **Page** : Réglages → Avancé → « Clavier et souris partagés » ; sur la vue
  principale, un panneau avec le bouton de bascule dès que la fonction est activée.

## Protocole (version 1)

Cadre : `[type:1][longueur:1][charge utile]`. Un type inconnu est sauté, des
octets en trop sont ignorés (compatibilité ascendante).

| Type | Charge utile | Sens |
|---|---|---|
| 1 `MOVE` | dx:2, dy:2 (signés) | déplacement relatif |
| 2 `BUTTON` | bouton:1 (1..5), appui:1 | 1 gauche, 2 milieu, 3 droit, 4 et 5 latéraux |
| 3 `WHEEL` | crans:2 (signé) | molette |
| 4 `KEY` | vk:4, position:1, appui:1 | code `KeyEvent.VK_*` et `keyLocation` |
| 5 `GRAB` | bord:1, position:2 | « je prends le contrôle » |
| 6 `RELEASE` | — | « je rends le contrôle » (vaut tout relâcher) |
| 7 `RETURN` | position:2 | le poste piloté demande le rendu |
| 8 `RELEASE_ALL` | — | tout relâcher |
| 9 `HEARTBEAT` | — | battement de cœur |
| 10 `HELLO` | version:1, drapeaux:1 | premier événement de chaque sens (1 = accepte d'être piloté, 2 = hub) |
| 11 `REFUSE` | raison:1 | prise de contrôle refusée |

## États

`IDLE` (inactif) → `DRIVING` (je pilote) ou `DRIVEN` (je suis piloté). Un seul
sens à la fois.

- Prise : `GRAB` envoyé, capture ouverte. L'autre poste passe `DRIVEN`, ou répond
  `REFUSE` (fonction désactivée, pas d'écran, `Robot` indisponible).
- **Deux prises croisées : le hub garde la main, le client cède.**
- Rendu : `RELEASE` ; le poste piloté relâche tout ce qui était enfoncé.
- **Lien coupé ou battement de cœur absent : retour à `IDLE` des deux côtés, tout
  est relâché** sur le poste piloté, la capture se ferme sur l'autre.
- Un hub où la fonction est désactivée garde le lien ouvert et refuse : le client
  affiche pourquoi, sans se reconnecter en boucle.

## Capture (poste qui envoie)

Fenêtre AWT plein écran, sans décoration, toujours au premier plan, **sombre et
translucide** (opacité uniforme de 30 % : pas de transparence par pixel), curseur
masqué, sur l'écran où se trouve le pointeur. Un **bandeau opaque** dit en
permanence : « Clavier et souris envoyés à l'autre poste — Ctrl+Alt+F12 pour
revenir ». Le déplacement relatif est obtenu en ramenant le pointeur au centre
avec `Robot`.

Si la fenêtre **perd le focus** (touche Windows, Alt+Tab, Ctrl+Alt+Suppr, Win+L,
autre fenêtre), ou si elle **n'obtient pas le clavier** en moins d'une seconde,
la capture s'arrête et le contrôle est rendu : jamais de touche restée enfoncée
en face, jamais de souris envoyée d'un côté et de frappes restées de l'autre.

## Déclencheurs

- **Bord d'écran.** Le pointeur est scruté toutes les 15 ms ; s'il reste contre
  le bord réglé pendant le délai réglé, on prend le contrôle, et le pointeur
  entre sur l'autre poste par le bord opposé, à la même hauteur. Retour : pousser
  contre ce bord opposé sur le poste piloté (sans bouton enfoncé). Seul un bord
  extérieur du bureau compte (pas la jonction entre deux écrans). Après un
  passage, il faut quitter le bord avant de pouvoir repartir.
- **Raccourci clavier** (défaut `Ctrl+Alt+F12`). Pendant la capture, la fenêtre
  le voit directement : retour. Pour **partir**, Java pur ne peut pas écouter un
  raccourci global : `KmShortcut` crée un raccourci Windows `.lnk` dans le Menu
  Démarrer de l'utilisateur, portant la touche de raccourci, dont la cible
  (`curl.exe`, fenêtre réduite) appelle `POST /api/km/toggle` sur la boucle
  locale. Création par PowerShell 5.1 (`WScript.Shell`), sans module ni droit
  administrateur. Si elle échoue, on le dit ; le reste marche sans.
- **Bouton de souris.** Les boutons latéraux 4 ou 5 peuvent **rendre** le
  contrôle. Pour partir, c'est le logiciel de la souris qui doit émettre le
  raccourci clavier.
- **Page web** : bouton qui appelle `POST /api/km/toggle`.

## Réglages (`cotransfert.conf`, propres à chaque poste)

| Clé | Valeurs | Défaut |
|---|---|---|
| `km.enabled` | `true`, `false` | `false` |
| `km.edge` | `gauche`, `droite`, `haut`, `bas`, `aucun` — bord de **ce** poste derrière lequel se trouve l'autre | `aucun` |
| `km.edge.delay` | 0 à 5000 ms | `250` |
| `km.hotkey` | Ctrl et/ou Alt, Maj facultatif, puis une lettre, un chiffre ou F1 à F12 ; `aucun` | `Ctrl+Alt+F12` |
| `km.return.button` | `0`, `4`, `5` | `0` |
| `km.clipboard` | `true`, `false` | `true` |

Exemple, hub à droite du client : `km.edge=droite` sur le client, `km.edge=gauche`
sur le hub.

## Sécurité

- Désactivé par défaut ; les **deux** postes doivent l'activer.
- Flux chiffré et authentifié par le secret partagé ; aucun repli en clair.
- La bascule n'est servie qu'à la boucle locale, derrière les gardes de
  `SetupApi` (adresse, `Host`, `Origin`, `X-CoTransfert: 1`, JSON).
- Le script PowerShell du raccourci est fixe ; les valeurs réglées passent par
  des variables d'environnement, jamais par le texte du script.
- L'état publié (`/api/status`) ne contient aucun secret ni aucune frappe ; les
  frappes ne sont jamais journalisées.
- On ne contourne rien : ni UAC, ni écran de verrouillage, ni restriction de
  PowerShell, ni verrou de premier plan de Windows.

## Limites connues

- **Touches réservées par Windows** (touche Windows, Alt+Tab, Ctrl+Alt+Suppr,
  Win+L, Ctrl+Maj+Échap) : jamais transmises ; elles arrêtent la capture.
- **UAC, écran de verrouillage, bureau sécurisé, fenêtres élevées** : `Robot`
  n'y injecte rien. Le poste piloté ne peut pas être déverrouillé à distance.
- **Disposition de clavier identique requise** sur les deux postes : on transmet
  des codes de touche, pas des caractères. Touches **AZERTY à risque** avec
  `Robot` : AltGr (`@ # { [ | \ €`), touches mortes (`^ ¨`), `²`, `ù`, `<` ; une
  touche refusée est ignorée et comptée (`ignored` dans l'état).
- `Robot` ne distingue pas gauche/droite pour Maj, Ctrl, Alt, ni le pavé numérique.
- Le raccourci réglé avec Ctrl+Alt+lettre entre en conflit avec AltGr : préférer
  une touche de fonction.
- Bord d'écran : un pointeur simplement posé contre le bord pendant le délai
  déclenche aussi ; un glisser-déposer vers le bord du poste qui a la souris
  déclenche (Java pur ne connaît pas l'état des boutons hors de sa fenêtre).
- Raccourci `.lnk` : l'Explorateur peut mettre une à deux secondes à réagir ; il
  est à recréer si le port de la page change.
- Écrans de densités différentes : les déplacements sont transmis en pixels
  logiques, la vitesse ressentie peut différer.
- Presse-papiers : texte seulement ; ni fichier, ni image, ni glisser-déposer
  entre postes.

## Presse-papiers

Le texte copié **suit le clavier**, et rien d'autre ne déclenche un envoi :

- prise de contrôle : le poste qui pilote envoie son texte au poste piloté ;
- contrôle rendu : le poste piloté envoie le sien au poste qui pilotait.

Règles :

- option `km.clipboard`, **activée par défaut** : elle suit l'activation du
  partage clavier-souris (lui-même désactivé par défaut) et se décoche dans les
  réglages (« Partager le texte copié »). Un poste n'envoie que si l'autre a
  annoncé qu'il accepte (drapeau `FLAG_CLIPBOARD` de `HELLO`), et un poste où
  l'option est coupée ignore les cadres reçus : il suffit de la décocher **d'un
  seul côté** pour que rien ne passe. À décocher si les règles de l'un des postes
  n'autorisent pas cet échange : on ne s'en sert pas pour contourner une
  politique de sécurité ;
- texte seulement (`DataFlavor.stringFlavor`), 512 Ko au plus en UTF-8 ; un
  texte plus long n'est pas envoyé et le journal le dit ;
- un texte déjà envoyé ou reçu n'est pas renvoyé tant qu'il n'a pas changé ;
- même lien chiffré que les frappes ; rien n'est écrit sur le disque ni dans le
  journal ;
- cadres `CLIP_BEGIN` (taille totale), `CLIP_DATA` (255 octets au plus),
  `CLIP_END` : le texte n'est appliqué que si la taille annoncée est atteinte.
  Une version plus ancienne saute ces cadres (types inconnus) ; la version du
  protocole ne change pas.

## Reste à valider sur deux vrais postes

Rien de ce qui touche au vrai `Robot`, à la vraie fenêtre ou au vrai `.lnk` n'a
été exécuté, et la page n'a pas été essayée dans un navigateur. À vérifier dans
l'agent (ou, sans synchronisation, avec `KmDemo`) :

1. La fenêtre de capture obtient bien le clavier quand la prise vient du bord
   d'écran ou du raccourci (verrou de premier plan de Windows), sinon elle renonce.
   C'est le risque principal. Un essai, désactivé par défaut
   (`-Dcotransfert.km.focusClick=true`), clique une fois dans notre propre fenêtre
   pour l'activer : à n'adopter que s'il est nécessaire et jugé acceptable.
2. Opacité uniforme et curseur masqué sur le poste d'entreprise ; bandeau lisible.
3. Fluidité et justesse du déplacement relatif (recentrage), multi-écrans, mise à
   l'échelle 125 % / 150 %.
4. Boutons 4/5 et molette vus par AWT ; rejoués par `Robot`.
5. Touches AZERTY : AltGr, touches mortes, pavé numérique, répétition automatique.
6. Arrêt propre sur touche Windows, Alt+Tab, Ctrl+Alt+Suppr, Win+L, mise en veille.
7. Création du `.lnk` et délai de réaction de la touche de raccourci ; comportement
   quand PowerShell ou `curl.exe` sont restreints.
8. Réaction d'un antivirus/EDR à `Robot` et à la fenêtre plein écran : si c'est
   bloqué, la fonction reste désactivée — on ne contourne pas.
9. Latence ressentie pendant un gros transfert de fichiers sur le même lien.
