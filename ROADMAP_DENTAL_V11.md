# Feuille de route lèvres et dents v0.11

## Problèmes observés

La v0.10 utilisait la largeur et la hauteur complètes de la bouche comme des
rayons, puis les multipliait encore pour construire le support. Une prédiction
Wav2Lip pouvait donc recouvrir les joues, le nez ou la barbe. Le crop ne
redressait pas non plus le roulis du visage. Enfin, une bouche générée trop
blanche ou trop noire était mélangée presque à pleine force.

## Corrections livrées

1. Face Landmarker suit 478 points en mode vidéo ; ML Kit reste le secours.
2. L'angle des commissures produit un roulis lissé par le chemin le plus court.
3. Le crop et le remappage OpenGL compensent angle et ratio réel des pixels.
4. Les dimensions labiales deviennent des demi-axes avant tout calcul de masque.
5. La zone affectée de référence passe de 108 374 à 15 096 pixels, soit -86,07 %.
6. La position canonique réelle de la bouche est recalculée pour chaque image.
7. La déformation phonétique secondaire est limitée à 58 % pour éviter le flou.
8. La garde dentaire contrôle teinte, luminance, cavité, bord, détail et saut
   temporel avant le compositing.
9. Une mauvaise prédiction ne peut plus dominer l'image originale.
10. Le modèle personnel v4 améliore les six MAE cible/visuelles face à la v3.

## Données privées

Les trois nouvelles vidéos ont fourni 738 exemples de mouvement. Deux vidéos du
même sourire ont fourni 69 images dentaires exploitables ; la troisième n'est
utilisée que pour le mouvement. Les MP4 et les images ne sont jamais intégrés à
Git ou à l'APK.

## Limites honnêtes

Un petit modèle personnel ne remplace pas l'entraînement complet du générateur
audio-visuel et de son discriminateur de synchronisation. Le choix retenu est
donc un Wav2Lip général stable, complété par un traqueur plus précis, un masque
local, un modèle de mouvement personnel et une protection dentaire mesurable.
