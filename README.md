# LipSync AI Studio

Application Android de synchronisation labiale locale. Elle associe une vidéo contenant un visage à un fichier MP3, analyse le visage et le signal audio directement sur le téléphone, anime la zone de la bouche, puis exporte un MP4 dans la galerie.

## Version 0.2.0

- premier réseau neuronal personnalisé entraîné sur les vidéos fournies par l'utilisateur ;
- 887 exemples audio–bouche extraits de 8 vidéos, soit environ 170 secondes exploitables ;
- seuls les poids entraînés sont intégrés dans l'APK ;
- aucune vidéo personnelle n'est publiée dans le dépôt ni envoyée vers un serveur ;
- le format d'origine est conservé physiquement dans les pixels : vertical reste vertical et horizontal reste horizontal ;
- les rotations 90°, 180° et 270° sont appliquées pendant le réencodage au lieu de dépendre uniquement d'une métadonnée ;
- tests automatiques de géométrie vidéo.

## Fonctionnalités

- import d'une vidéo depuis Android ;
- import d'un MP3 ;
- choix du point de départ du son ;
- détection locale du visage avec un modèle embarqué ;
- analyse locale des sons et création d'une chronologie de visèmes ;
- combinaison du modèle neuronal personnel et du moteur audio de sécurité ;
- rendu GPU image par image ;
- suivi de progression par blocs de 30 secondes ;
- conversion du MP3 en AAC ;
- export MP4 dans `Movies/LipSync AI` ;
- aucun serveur et aucune API distante.

## Installation

1. Ouvrir l'onglet **Releases** du dépôt.
2. Télécharger le fichier APK de la dernière version.
3. Autoriser l'installation d'applications inconnues pour le navigateur utilisé.
4. Installer l'APK.

## Conseils pour un meilleur résultat

- utiliser une seule personne ;
- filmer le visage de face ;
- garder la bouche visible ;
- choisir une vidéo bien éclairée et stable ;
- commencer par une séquence courte de 10 à 30 secondes ;
- laisser le téléphone branché pendant les longs traitements.

## Limites actuelles

Le modèle 0.2.0 apprend une relation personnalisée entre le signal audio et les mouvements de bouche observés dans les vidéos d'entraînement. Il reste volontairement compact pour fonctionner dans un téléphone Android sans serveur. Ce n'est pas encore un modèle génératif photoréaliste lourd capable de recréer entièrement les lèvres comme une infrastructure GPU de studio. Les profils complets, les mains devant la bouche, les mouvements rapides et plusieurs visages réduisent la qualité.

## Construction automatique

Le workflow GitHub Actions exécute les tests unitaires, le contrôle Android Lint, compile l'APK signé et publie la version installable dans les Releases du dépôt.
