# LipSync AI Studio

Application Android locale de synchronisation labiale. Elle associe une vidéo contenant un visage à un MP3, recrée la zone labiale avec un réseau audio-visuel et exporte le résultat dans la galerie sans envoyer les fichiers sur Internet.

## Version 0.8.1 placement labial corrigé

- véritable génération neuronale Wav2Lip 256 × 256 image par image ;
- spectrogramme compatible Wav2Lip : 16 kHz, STFT 800/200 et 80 bandes Mel ;
- reconnaissance vocale française locale Vosk avec mots et horodatages ;
- détection locale de la parole, des silences et des pauses ;
- conversion des mots français en formes de lèvres ;
- corrections ciblées pour M/P/B, F/V, A/E/I, O/U et consonnes visibles ;
- transcription utilisée seulement lorsque sa confiance est suffisante ;
- Wav2Lip et le signal audio restent prioritaires sur le texte ;
- repère OpenGL unifié entre la vidéo, le visage et la bouche ;
- masque génératif centré directement sur la bouche détectée ;
- suppression des anciens masques basés sur une hauteur fixe du visage ;
- barrière de sécurité rejetant toute fusion labiale hors du cadre du visage ;
- accélération locale ONNX Runtime avec NNAPI, XNNPACK puis CPU de secours ;
- suivi verrouillé du même visage et interpolation temporelle des cadres ;
- correction colorimétrique et stabilisation temporelle adaptative ;
- compensation de 560 ms conservée pour le moteur Pro v4 de secours ;
- rapport final indiquant le moteur utilisé, le nombre de mots et la confiance ;
- choix obligatoire entre **9:16 vertical** et **16:9 horizontal** ;
- sortie contrôlée en 720 × 1280 ou 1280 × 720 sans rotation MP4 supplémentaire ;
- aucune vidéo, piste audio ou transcription envoyée sur Internet.

## Correction du placement

Le résultat `1000121033.mp4` montrait une variation générative sur le front alors que les lèvres restaient presque fixes. Le diagnostic a identifié un mélange entre les coordonnées logiques du visage et les coordonnées transformées par `SurfaceTexture`.

La version 0.8.1 conserve désormais le visage et la bouche dans le même repère logique. La matrice Android est appliquée uniquement lors de la lecture des pixels vidéo. La zone générative est une ellipse calculée autour de la bouche réellement détectée, et non autour d'une position verticale prédéfinie.

## Architecture de décision

Le moteur utilise trois sources complémentaires :

1. le spectrogramme Mel pilote Wav2Lip ;
2. la détection de parole ferme la bouche pendant les vrais silences ;
3. le texte horodaté corrige seulement les phonèmes français les plus visibles.

Une transcription peu fiable est rejetée automatiquement. L’application continue alors avec Wav2Lip et l’analyse audio, sans bloquer la génération.

La feuille de route complète, les alternatives étudiées et leurs contre-arguments sont documentés dans `ROADMAP_AI_V8.md`.

## Fonctionnalités

- import d’une vidéo et d’un MP3 ;
- choix du point de départ du son ;
- détection et suivi local du visage avec ML Kit ;
- reconnaissance vocale française hors ligne avec Vosk ;
- détection de parole et de silence ;
- génération Wav2Lip 256 avec ONNX Runtime Android ;
- rendu OpenGL avec fusion centrée sur la bouche ;
- micro-corrections phonétiques appliquées au visage généré ;
- moteur neuronal personnel Pro v4 recalibré comme secours ;
- progression par blocs de 30 secondes ;
- export dans `Movies/LipSync AI` ;
- aucun serveur et aucune API distante.

## Conseils

- utiliser une personne principale avec une bouche visible ;
- privilégier un visage de face ou légèrement de côté ;
- utiliser une voix assez claire ;
- commencer par une séquence de 5 à 15 secondes pour mesurer la vitesse du téléphone ;
- laisser le téléphone branché pour les traitements longs.

## Performances et stockage

Les modèles Wav2Lip et Vosk sont inclus dans l’APK. Au premier traitement, le modèle vocal français est extrait dans l’espace privé de l’application. Cela demande du stockage supplémentaire mais évite toute connexion Internet pendant l’utilisation.

Selon la puissance du téléphone, une seconde de vidéo peut demander plusieurs secondes de calcul. Si la reconnaissance vocale échoue, elle est ignorée. Si Wav2Lip échoue ou manque de mémoire, l’export continue avec le moteur Pro v4 recalibré.

## Entraînement, calibration et construction

Le pipeline reproductible du modèle personnel se trouve dans `tools/train_personal_lip_model.py`. Les vidéos privées ne sont pas publiées : seuls les poids quantifiés et des rapports sans image sont conservés. Les diagnostics sont documentés dans `training/diagnostic_audio_sync_1000121007.json` et `training/diagnostic_mouth_position_1000121033.json` sans inclure les vidéos sources.

GitHub Actions télécharge Wav2Lip et Vosk, vérifie strictement leurs empreintes SHA-256, contrôle les archives, valide les modèles et la calibration, exécute les tests et Android Lint, puis compile et publie l’APK signé.

## Conditions des modèles

La conversion ONNX des poids Wav2Lip publics est réservée aux usages de recherche, académiques et personnels ; l’usage commercial est interdit sans licence adaptée. Vosk et son modèle français compact utilisent la licence Apache 2.0. Voir `THIRD_PARTY_NOTICES.md`.
