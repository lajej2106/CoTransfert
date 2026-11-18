# CoTransfert — consignes pour les assistants de code (IA)

Ce fichier s'adresse aux agents et assistants de code, quel que soit l'outil.
La référence complète et à jour est [`CLAUDE.md`](CLAUDE.md) : lisez-la avant
toute modification. En cas de divergence, `CLAUDE.md` l'emporte.

## Le projet en bref

CoTransfert synchronise des dossiers dans les deux sens entre deux postes
Windows, un **hub** (peut accepter une connexion entrante) et un **client** (ne
fait que du sortant), par un lien direct (câble Thunderbolt/USB4, Ethernet) ou
par un canal par lots (WebDAV, Drime, disque relais). Il vise les postes les
plus verrouillés : un seul `.jar`, exécuté par un Java déjà présent.

## Interdits (non négociables)

- Aucun pilote, aucun droit administrateur, aucun service Windows, rien à
  installer, aucun exécutable natif.
- Java SE pur, bytecode 21, **aucune dépendance externe**, aucun framework, pas
  de JNI/JNA. Ne proposez ni Maven, ni Gradle, ni bibliothèque.
- Le client n'écoute jamais : toute communication est une connexion sortante du
  client vers le hub.
- Ne jamais contourner une politique de sécurité (pare-feu, VPN, EDR, DLP).
- Aucune perte silencieuse de données : écriture atomique, conflits conservés.
- Jamais de bascule automatique entre canaux : l'utilisateur choisit.
- Contenu générique : pas de nom de machine, d'entreprise, d'utilisateur,
  d'adresse IP ou de chemin propre à un environnement. Vocabulaire : « hub » et
  « client » (« maître / esclave » seulement pour l'arbitrage des conflits).
- Aucun secret, aucun binaire, aucun `cotransfert.conf` dans le dépôt.

## Conventions

- Commentaires des fichiers `.java` et `.cmd` : français **sans accent**.
- Documentation (`.md`) et textes de la page web : français **avec accents**.
- Scripts PowerShell compatibles Windows PowerShell 5.1.
- Un seul point d'entrée pour l'utilisateur : `CoTransfert.cmd` ; tout le reste
  se règle dans la page web.

## Construire et tester

```bash
javac --release 21 -Xlint:all -d build/classes $(find src/main/java -name '*.java')
cp -r src/main/resources/. build/classes/
javac --release 21 -cp build/classes -d build/test-classes $(find src/test/java -name '*.java')
java -cp "build/classes;build/test-classes" cotransfert.KmTest <dossier-de-travail>
```

Les tests sont de simples `main` (code de sortie non nul en cas d'échec) ; la
liste complète est dans `CLAUDE.md`. Ils n'appellent aucun service réel et
n'utilisent ni le vrai clavier, ni la vraie souris, ni le presse-papiers.
`CryptoTest` a besoin du port de synchronisation libre : ne le lancez pas
pendant qu'un agent CoTransfert tourne sur le poste.

Tout changement de comportement vient avec son test et la mise à jour de la
documentation concernée.

## Où regarder

| Sujet | Fichier |
|---|---|
| Architecture et usage | [`README.md`](README.md) |
| Canaux de transfert, mise à jour | [`SPEC-cascade-canaux.md`](SPEC-cascade-canaux.md) |
| Partage clavier-souris et presse-papiers | [`SPEC-km.md`](SPEC-km.md) |
| Évolutions envisagées | [`ROADMAP.md`](ROADMAP.md) |
| Règles de contribution, accord de contribution | [`CONTRIBUTING.md`](CONTRIBUTING.md), [`CLA.md`](CLA.md) |

## Contributions produites avec une IA

Elles suivent les mêmes règles que les autres : issue préalable, accord de
contribution accepté par la personne qui soumet, tests au vert. Cette personne
reste responsable du contenu soumis et déclare, dans sa *pull request*, l'usage
d'un assistant et tout code repris d'ailleurs.

## Licence

[GNU AGPL-3.0](LICENSE). Ne copiez pas dans ce dépôt du code sous une licence
incompatible.
