# Contribuer à CoTransfert

Merci de l'intérêt porté au projet. Les règles ci-dessous sont strictes et
s'appliquent à toute contribution, sans exception.

## Avant d'écrire du code

1. **Ouvrez d'abord une issue** qui décrit le besoin ou le défaut. Une *pull
   request* sans issue acceptée au préalable peut être fermée sans examen.
2. Attendez l'accord du mainteneur sur le principe et sur l'approche. Le
   mainteneur décide seul de ce qui entre dans le projet et peut refuser une
   contribution, même correcte, qui ne correspond pas à la direction du produit.
3. Les signalements de défaut et les idées sont bienvenus sans aucune formalité.

## Accord de contribution (obligatoire)

Aucun code, texte ou fichier d'un tiers n'est fusionné sans l'acceptation de
l'[accord de contribution](CLA.md). Pour l'accepter, écrivez dans la description
de votre *pull request* :

> J'ai lu et j'accepte l'accord de contribution de CoTransfert (CLA.md).

Cet accord vous laisse la propriété de votre contribution, mais donne au
titulaire du projet le droit de la distribuer sous la licence du projet et sous
d'autres licences, y compris commerciales. Si vous contribuez dans le cadre de
votre emploi, assurez-vous d'en avoir le droit.

## Règles du produit (non négociables)

Elles sont détaillées dans [`CLAUDE.md`](CLAUDE.md). En résumé :

- aucun pilote, aucun droit administrateur, rien à installer ;
- Java SE pur, bytecode 21, **zéro dépendance externe**, pas de JNI/JNA ;
- le client n'écoute jamais : il ne fait que des connexions sortantes ;
- on ne contourne jamais une politique de sécurité ;
- aucune perte silencieuse de données ;
- jamais de bascule automatique entre canaux ;
- contenu **générique** : pas de nom de machine, d'entreprise, d'utilisateur,
  d'adresse IP ou de chemin propre à un environnement ; on parle de « hub » et
  de « client ».

Une contribution qui enfreint l'une de ces règles est refusée.

## Exigences sur une pull request

- Un seul sujet par *pull request*, de taille raisonnable.
- Le projet compile sans avertissement (`javac --release 21 -Xlint:all`).
- Toutes les suites de tests listées dans `CLAUDE.md` passent, et tout
  changement de comportement est couvert par un test. Les tests n'appellent
  aucun service réel.
- Commentaires des fichiers `.java` et `.cmd` en français sans accent ;
  documentation en français avec accents.
- La documentation concernée (`README.md`, spécifications) est mise à jour dans
  la même *pull request*.
- Aucun binaire, aucun secret, aucun fichier de configuration local.
- Vous déclarez dans la *pull request* tout code repris d'ailleurs, avec sa
  licence. Le code dont vous n'êtes pas l'auteur n'est en principe pas accepté.

## Sécurité

Ne publiez pas une faille dans une issue. Utilisez le signalement privé de
GitHub (onglet « Security », « Report a vulnerability ») ou contactez le
mainteneur en privé.

## Licence

CoTransfert est distribué sous licence [GNU AGPL-3.0](LICENSE). Le titulaire des
droits peut aussi le proposer sous d'autres licences.
