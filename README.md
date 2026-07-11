# LipSync AI Studio

Application Android locale de synchronisation labiale. Elle associe une vidéo contenant un visage à un MP3, recrée la zone labiale avec un réseau audio-visuel et exporte le résultat dans la galerie sans envoyer les fichiers sur Internet.

## Version 0.9.0 traqueur labial dédié

- véritable génération neuronale Wav2Lip 256 × 256 image par image ;
- reconnaissance vocale française locale Vosk avec mots et horodatages ;
- spectrogramme Mel, détection de parole, silences et pauses ;
- traqueur dédié utilisant les contours supérieur et inférieur des lèvres ;
- validation géométrique de la position, de la largeur, de la hauteur et de la symétrie ;
- mémoire temporelle à vitesse constante pendant les pertes très courtes ;
- rejet automatique d'une détection située sur le front, les joues ou hors du visage ;
- retour prudent sur la dernière position fiable au lieu de déplacer le masque ;
- analyse portée jusqu'à 10 repères par seconde, avec interpolation entre les repères ;
- verrouillage du même visage lorsqu'il y a plusieurs personnes ;
- repère OpenGL unique entre la vidéo, le visage et la bouche ;
- masque Wav2Lip centré directement sur la bouche suivie ;
- barrière de sécurité finale avant toute fusion générative ;
- transcription utilisée seulement lorsque sa confiance est suffisante ;
- accélération locale ONNX Runtime avec NNAPI, XNNPACK puis CPU de secours ;
- compensation de 560 ms conservée pour le moteur Pro v4 de secours ;
- choix obligatoire entre **9:16 vertical** et **16:9 horizontal** ;
- sortie contrôlée en 720 × 1280 ou 1280 × 720 ;
- aucune vidéo, piste audio ou transcription envoyée sur Internet.

## Fonctionnement du traqueur

Le suivi ne dépend plus seulement de trois points du visage. ML Kit fournit maintenant quatre contours détaillés : haut de la lèvre supérieure, bas de la lèvre supérieure, haut de la lèvre inférieure et bas de la lèvre inférieure.

Chaque observation est contrôlée avant utilisation :

1. les points doivent se trouver dans la partie basse du visage ;
2. la taille des lèvres doit être cohérente avec la taille du visage ;
3. la position doit rester proche de la trajectoire précédente ;
4. un saut ou une taille impossible est rejeté ;
5. pendant une perte courte, la trajectoire précédente est prédite ;
6. après plusieurs pertes, le moteur revient sur une zone prudente et désactive la fusion si la confiance est trop faible.

Cette architecture réduit fortement le risque qu'une animation soit appliquée ailleurs que sur les lèvres. Elle ne constitue pas une garantie absolue pour toutes les vidéos : une bouche entièrement cachée, un visage de profil extrême ou une image très floue peuvent toujours empêcher une détection fiable. Dans ce cas, l'application préfère ne pas appliquer Wav2Lip à cette image plutôt que modifier une mauvaise zone.

## Architecture de décision

Le moteur utilise quatre sources complémentaires :

1. le traqueur de contours détermine la position réelle des lèvres ;
2. le spectrogramme Mel pilote Wav2Lip ;
3. la détection de parole ferme la bouche pendant les vrais silences ;
4. le texte horodaté corrige seulement les phonèmes français les plus visibles.

Une transcription peu fiable ou une position labiale douteuse est rejetée automatiquement. L’application continue avec les sources encore fiables sans bloquer l’export.

## Fonctionnalités

- import d’une vidéo et d’un MP3 ;
- choix du point de départ du son ;
- détection du visage et contours labiaux ML Kit ;
- suivi temporel spécialisé des lèvres ;
- reconnaissance vocale française hors ligne avec Vosk ;
- génération Wav2Lip 256 avec ONNX Runtime Android ;
- rendu OpenGL avec fusion centrée sur la bouche ;
- micro-corrections phonétiques ;
- moteur neuronal personnel Pro v4 comme secours ;
- progression par blocs de 30 secondes ;
- export dans `Movies/LipSync AI` ;
- aucun serveur et aucune API distante.

## Conseils

- utiliser une personne principale avec la bouche visible ;
- privilégier un visage de face ou légèrement de côté ;
- éviter qu'une main ou un objet masque complètement la bouche ;
- commencer par une séquence de 5 à 15 secondes ;
- laisser le téléphone branché pour les traitements longs.

## Performances et stockage

Les modèles Wav2Lip et Vosk sont inclus dans l’APK. Au premier traitement, le modèle vocal français est extrait dans l’espace privé de l’application. Le traqueur labial n'ajoute aucun téléchargement : il utilise le moteur ML Kit déjà présent.

L'analyse des lèvres est plus fréquente que dans la version 0.8.1. Le prétraitement peut donc être légèrement plus long, mais la zone générative est mieux verrouillée. Si la reconnaissance vocale échoue, elle est ignorée. Si Wav2Lip échoue ou manque de mémoire, l’export continue avec le moteur Pro v4 recalibré.

## Entraînement, calibration et construction

Le pipeline reproductible du modèle personnel se trouve dans `tools/train_personal_lip_model.py`. Les vidéos privées ne sont pas publiées : seuls les poids quantifiés et des rapports sans image sont conservés. Les diagnostics sont documentés dans `training/` sans inclure les vidéos sources.

GitHub Actions télécharge Wav2Lip et Vosk, vérifie leurs empreintes SHA-256, valide le traqueur et les modèles, exécute les tests et Android Lint, puis compile et publie l’APK signé.

## Conditions des modèles

La conversion ONNX des poids Wav2Lip publics est réservée aux usages de recherche, académiques et personnels ; l’usage commercial est interdit sans licence adaptée. Vosk et son modèle français compact utilisent la licence Apache 2.0. Voir `THIRD_PARTY_NOTICES.md`.
