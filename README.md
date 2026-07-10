# LipSync AI Studio

Application Android de synchronisation labiale locale. Elle associe une vidéo contenant un visage à un fichier MP3, analyse le visage et le signal audio directement sur le téléphone, anime la zone de la bouche, puis exporte un MP4 dans la galerie.

## Fonctionnalités de la V1

- import d'une vidéo depuis Android ;
- import d'un MP3 ;
- choix du point de départ du son ;
- détection locale du visage avec un modèle embarqué ;
- analyse locale des sons et création d'une chronologie de visèmes ;
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

## Limites connues de la V1

Cette première version utilise une détection de visage locale et un moteur de visèmes léger adapté aux téléphones. Elle modifie réellement la bouche dans la vidéo, mais elle n'est pas encore un modèle génératif lourd de niveau studio. Les profils complets, les mains devant la bouche, les mouvements rapides et plusieurs visages réduisent la qualité.

## Construction automatique

Le workflow GitHub Actions compile l'application, exécute le contrôle Android Lint, génère l'APK et publie la version installable dans les Releases du dépôt.
