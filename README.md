# LipSync AI Studio

Application Android de synchronisation labiale locale. Elle associe une vidéo contenant un visage à un fichier MP3, analyse le visage et le signal audio directement sur le téléphone, anime la zone de la bouche, puis exporte un MP4 dans la galerie.

## Version 0.4.0

- écran obligatoire de choix entre **9:16 vertical** et **16:9 horizontal** ;
- sortie contrôlée en 720 × 1280 ou 1280 × 720 ;
- suivi de la bouche sur toute la vidéo au lieu d'une position unique ;
- interpolation de la position de la bouche image par image ;
- interpolation des visèmes entre deux fenêtres audio pour supprimer les sauts ;
- analyse fréquentielle locale sur six zones de la voix ;
- anticipation temporelle d'environ 80 ms ;
- détection renforcée des fermetures de lèvres de type M, P et B ;
- lissage différent pour l'ouverture et la fermeture de la bouche ;
- conservation du réseau neuronal personnel v2 validé ;
- ajout d'un profil temporel v3 calibré sur trois nouvelles vidéos ;
- 21 vidéos et 6 695 exemples audio–bouche cumulés dans les données de calibration ;
- aucun fichier vidéo personnel publié dans le dépôt ou intégré à l'APK.

Un second réseau expérimental entraîné uniquement sur les trois nouvelles vidéos a été évalué puis rejeté, car sa validation était inférieure au modèle existant. Il n'est pas inclus dans l'application.

## Fonctionnalités

- import d'une vidéo depuis Android ;
- import d'un MP3 ;
- choix du point de départ du son ;
- choix 9:16 ou 16:9 avant chaque traitement ;
- détection locale du visage avec ML Kit ;
- suivi dynamique de la bouche avec filtrage des mouvements incohérents ;
- réseau neuronal personnel et moteur fréquentiel temporel ;
- rendu GPU image par image ;
- suivi de progression par blocs de 30 secondes ;
- conversion du MP3 en AAC ;
- vérification de l'orientation du MP4 final ;
- export dans `Movies/LipSync AI` ;
- aucun serveur et aucune API distante.

## Installation

1. Ouvrir l'onglet **Releases** du dépôt.
2. Télécharger le fichier APK de la dernière version.
3. Autoriser l'installation d'applications inconnues pour le navigateur utilisé.
4. Installer l'APK.

## Conseils pour un meilleur résultat

- utiliser une seule personne ;
- filmer le visage de face ou légèrement de côté ;
- garder la bouche visible ;
- choisir une vidéo bien éclairée ;
- commencer par une séquence courte de 10 à 30 secondes ;
- laisser le téléphone branché pendant les longs traitements.

## Limites actuelles

La version 0.4.0 améliore surtout le suivi du visage, la compréhension temporelle de la voix et la stabilité du mouvement. Elle reste un moteur mobile local qui déforme intelligemment la zone de la bouche. Ce n'est pas encore un modèle génératif photoréaliste lourd recréant chaque image du visage. Plusieurs visages, une bouche cachée, un profil complet ou de grands mouvements rapides peuvent encore réduire la qualité.

## Construction automatique

Le workflow GitHub Actions valide les deux fichiers de modèle, exécute les tests unitaires, Android Lint, compile l'APK signé et publie la version installable dans les Releases du dépôt.
