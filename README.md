# LipSync AI Studio

Application Android locale de synchronisation labiale. Elle associe une vidéo contenant un visage à un MP3, suit la bouche, analyse la voix sur le téléphone et exporte le résultat dans la galerie.

## Version 0.6.0 Pro

- suppression de la grande cavité noire et des fausses dents artificielles observées dans la sortie 0.5.0 ;
- déformation progressive utilisant la texture naturelle de la bouche ;
- fermeture réelle des lèvres pendant les silences et les consonnes M, P et B ;
- influence du réseau personnel réduite automatiquement lorsqu’il contredit le signal audio ;
- analyse supplémentaire du flux spectral, du centre spectral et du voisement ;
- anticipation réduite à 40 ms après mesure du décalage réel ;
- verrouillage du même visage dans les scènes contenant plusieurs personnages ;
- suivi du visage toutes les 200 ms au lieu de 400 ms ;
- zone de bouche limitée par la taille réelle du visage pour empêcher les déformations géantes ;
- choix obligatoire entre **9:16 vertical** et **16:9 horizontal** ;
- sortie contrôlée en 720 × 1280 ou 1280 × 720 sans rotation MP4 supplémentaire ;
- réseau personnel entraîné après examen de 28 vidéos et 6 869 exemples audio–bouche ;
- aucune vidéo personnelle intégrée dans l’APK.

## Fonctionnalités

- import d’une vidéo et d’un MP3 ;
- choix du point de départ du son ;
- détection locale du visage avec ML Kit ;
- suivi dynamique et verrouillé de la bouche ;
- moteur neuronal et fréquentiel adaptatif ;
- rendu GPU image par image ;
- progression par blocs de 30 secondes ;
- export dans `Movies/LipSync AI` ;
- aucun serveur et aucune API distante.

## Conseils

- utiliser une personne principale avec une bouche visible ;
- privilégier un visage de face ou légèrement de côté ;
- commencer par une séquence de 10 à 30 secondes ;
- laisser le téléphone branché pour les traitements longs.

## Limite technique

Le moteur reste entièrement local et transforme les pixels de la vidéo d’origine. Il améliore fortement le naturel et le rythme, mais il ne recrée pas tout le visage comme un lourd modèle génératif exécuté sur un serveur GPU.

## Entraînement et construction

Le pipeline reproductible se trouve dans `tools/train_personal_lip_model.py`. Les vidéos privées ne sont pas publiées : seuls les poids quantifiés et un rapport sans image sont conservés.

GitHub Actions valide les modèles, exécute les tests, Android Lint, compile l’APK signé puis publie la version installable dans les Releases.
