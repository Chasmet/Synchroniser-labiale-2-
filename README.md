# LipSync AI Studio

Application Android locale de synchronisation labiale. Elle associe une vidéo contenant un visage à un fichier MP3, suit la bouche pendant toute la vidéo, analyse le signal audio sur le téléphone puis exporte un MP4 dans la galerie.

## Version 0.5.0

- choix obligatoire entre **9:16 vertical** et **16:9 horizontal** avant le lancement ;
- sortie contrôlée en 720 × 1280 ou 1280 × 720 ;
- aucune rotation MP4 ajoutée après l'encodage : le format choisi reste stable ;
- suivi dynamique de la bouche et interpolation image par image ;
- interpolation des visèmes entre les fenêtres audio ;
- anticipation temporelle d'environ 80 ms ;
- détection renforcée des fermetures de lèvres M, P et B ;
- nouveau réseau personnel v3 à deux couches de 96 et 48 neurones ;
- les 28 vidéos reçues ont été examinées, 20 étaient exploitables ;
- 590,17 secondes de sources analysées, 274,76 secondes de lèvres mesurées et 6 869 exemples audio–bouche ;
- six caractéristiques audio, dont trois bandes fréquentielles ;
- poids quantifiés pour conserver une application légère ;
- aucune vidéo personnelle publiée dans le dépôt ou intégrée à l'APK.

## Fonctionnalités

- import d'une vidéo et d'un MP3 ;
- choix du point de départ du son ;
- détection locale du visage avec ML Kit ;
- suivi dynamique de la bouche avec rejet des mouvements incohérents ;
- réseau neuronal personnel et moteur fréquentiel temporel ;
- rendu GPU image par image ;
- progression par blocs de 30 secondes ;
- conversion du MP3 en AAC ;
- vérification des dimensions et de l'orientation du MP4 final ;
- export dans `Movies/LipSync AI` ;
- aucun serveur et aucune API distante.

## Installation

1. Ouvrir l'onglet **Releases** du dépôt.
2. Télécharger l'APK de la dernière version.
3. Autoriser l'installation d'applications inconnues pour le navigateur utilisé.
4. Installer l'APK.

## Conseils

- utiliser une seule personne ;
- filmer le visage de face ou légèrement de côté ;
- garder la bouche visible et bien éclairée ;
- commencer par une séquence de 10 à 30 secondes ;
- laisser le téléphone branché pendant les traitements longs.

## Limites actuelles

La version 0.5.0 est un moteur mobile local qui déforme intelligemment la zone de la bouche. Ce n'est pas encore un modèle génératif photoréaliste lourd recréant entièrement le visage. Une bouche cachée, un profil complet, plusieurs visages ou de grands mouvements rapides peuvent réduire la qualité.

## Entraînement et construction

Le pipeline reproductible se trouve dans `tools/train_personal_lip_model.py`. Il examine les vidéos privées, extrait les mouvements de bouche et n'enregistre dans le dépôt que les poids appris et un rapport sans image.

GitHub Actions valide les modèles, exécute les tests unitaires et Android Lint, compile l'APK signé puis publie la version installable dans les Releases.
