# Sécurité

## Signaler une faille

Ne publiez pas une faille dans une issue. Utilisez le signalement privé de
GitHub : onglet **Security**, puis **Report a vulnerability**.

Indiquez la version (date affichée dans la page), le rôle du poste concerné
(hub ou client), le canal utilisé et les étapes pour reproduire. Ne joignez
aucun secret ni aucune donnée réelle.

## Versions suivies

Seule la dernière version de la branche `main` reçoit des correctifs.

## Ce que le produit garantit, et ce qu'il ne garantit pas

- Avec un **secret partagé**, le lien direct est chiffré et authentifié, et les
  lots des canaux en ligne sont scellés. Sans secret, le lien direct n'est pas
  chiffré : ne l'utilisez alors que sur un câble reliant directement les deux
  postes.
- L'API de réglage n'est servie qu'à la boucle locale du poste.
- Le client n'écoute jamais sur le réseau.
- CoTransfert ne cherche pas à contourner un pare-feu, un VPN, un EDR ou un
  DLP. Un comportement qui y parviendrait est un défaut à signaler.
