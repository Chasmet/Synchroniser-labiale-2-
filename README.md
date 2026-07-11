# LipSync AI Studio

Application Android de synchronisation labiale locale. Elle associe une vidéo contenant un visage à un fichier MP3, analyse le visage et le signal audio directement sur le téléphone, anime la zone de la bouche, puis exporte un MP4 dans la galerie.

## Version 0.3.1

- écran obligatoire de choix du format juste avant la génération ;
- choix manuel entre **9:16 vertical** et **16:9 horizontal** ;
- export réel en 720 × 1280 ou 1280 × 720 ;
- suppression de la rotation héritée de la vidéo intermédiaire ;
- orientation finale imposée selon le format choisi ;
- vérification automatique du ratio avant l'enregistrement dans la galerie ;
- aucune déformation de l'image : la vidéo est centrée et conservée entière ;
- modèle personnel v2 entraîné sur 18 vidéos ;
- environ 286 secondes de données et 5 541 exemples audio–bouche ;
- réseau renforcé avec deux couches de 64 et 32 neurones ;
- poids quantifiés pour garder une application légère et rapide ;
- aucune vidéo personnelle publiée dans le dépôt ou intégrée à l'APK.

## Fonctionnalités

- import d'une vidéo depuis Android ;
- import d'un MP3 ;
- choix du point de départ du son ;
- choix 9:16 ou 16:9 avant chaque traitement ;
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

Le modèle 0.3.1 apprend une relation personnalisée entre le signal audio et les mouvements de bouche observés dans les vidéos d'entraînement. Il est plus grand et plus précis que le modèle précédent, mais ce n'est pas encore un modèle génératif photoréaliste lourd capable de recréer entièrement de nouvelles lèvres comme une infrastructure GPU de studio. Ajouter plusieurs gigaoctets artificiellement à l'APK n'améliorerait pas la qualité. Les profils complets, les mains devant la bouche, les mouvements rapides et plusieurs visages réduisent encore la précision.

## Construction automatique

Le workflow GitHub Actions exécute les tests unitaires, le contrôle Android Lint, compile l'APK signé et publie la version installable dans les Releases du dépôt.
