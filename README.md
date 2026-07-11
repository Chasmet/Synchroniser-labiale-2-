# LipSync AI Studio

Application Android locale de synchronisation labiale. Elle associe une vidéo contenant un visage à un MP3, recrée la zone labiale avec un réseau audio-visuel et exporte le résultat dans la galerie sans envoyer les fichiers sur Internet.

## Version 0.7.0 générative

- véritable génération neuronale Wav2Lip 256 × 256 image par image ;
- spectrogramme compatible avec l’entraînement Wav2Lip : 16 kHz, STFT 800/200 et 80 bandes Mel ;
- accélération locale ONNX Runtime avec NNAPI, XNNPACK puis CPU de secours ;
- suivi verrouillé du même visage et interpolation temporelle des cadres ;
- masque progressif conservant les yeux, les cheveux, le contour du visage et les occlusions ;
- correction colorimétrique et stabilisation temporelle adaptative ;
- retour automatique au moteur Pro v4 si une image est incertaine ou si le modèle ne tient pas en mémoire ;
- choix obligatoire entre **9:16 vertical** et **16:9 horizontal** ;
- sortie contrôlée en 720 × 1280 ou 1280 × 720 sans rotation MP4 supplémentaire ;
- réseau personnel réentraîné après examen de 28 vidéos et 6 950 exemples audio–bouche ;
- aucune vidéo personnelle intégrée dans l’APK.

## Fonctionnalités

- import d’une vidéo et d’un MP3 ;
- choix du point de départ du son ;
- détection et suivi local du visage avec ML Kit ;
- génération Wav2Lip 256 avec ONNX Runtime Android ;
- rendu OpenGL avec fusion multi-masque du visage généré ;
- moteur neuronal personnel Pro v4 conservé comme secours ;
- progression par blocs de 30 secondes ;
- export dans `Movies/LipSync AI` ;
- aucun serveur et aucune API distante.

## Conseils

- utiliser une personne principale avec une bouche visible ;
- privilégier un visage de face ou légèrement de côté ;
- commencer par une séquence de 5 à 15 secondes pour mesurer la vitesse du téléphone ;
- laisser le téléphone branché pour les traitements longs.

## Performances et compatibilité

Le modèle de 205 Mio est inclus dans l’APK et fonctionne sans connexion. Le traitement est volontairement hors ligne : selon la puissance du téléphone, une seconde de vidéo peut demander plusieurs secondes de calcul. Si NNAPI n’accepte pas une partie du graphe, ONNX Runtime la traite avec XNNPACK ou le CPU. Si le moteur génératif échoue, l’export continue avec Pro v4 au lieu de perdre la vidéo.

## Entraînement et construction

Le pipeline reproductible du modèle personnel se trouve dans `tools/train_personal_lip_model.py`. Les vidéos privées ne sont pas publiées : seuls les poids quantifiés et un rapport sans image sont conservés. Elles servent également à calibrer le suivi, le cadrage et la fusion du moteur génératif. Le générateur généraliste n’est pas réentraîné sur quelques minutes d’un seul visage, ce qui réduirait sa capacité à fonctionner avec d’autres personnes.

GitHub Actions télécharge le graphe ONNX public, vérifie strictement son SHA-256, valide les modèles, exécute les tests et Android Lint, puis compile et publie l’APK signé.

## Conditions du modèle

Cette version embarque une conversion ONNX du modèle Wav2Lip. Les poids Wav2Lip publics, entraînés sur LRS2, sont réservés aux usages de recherche, académiques et personnels ; l’usage commercial est interdit sans licence adaptée. Voir `THIRD_PARTY_NOTICES.md`.
