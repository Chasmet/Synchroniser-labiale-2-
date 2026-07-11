# LipSync AI Studio

Application Android locale de synchronisation labiale. Elle associe une vidéo contenant un visage à un MP3, recrée la zone labiale avec un réseau audio-visuel et exporte le résultat dans la galerie sans envoyer les fichiers sur Internet.

## Version 0.10.0 alignement phonème par phonème

- génération neuronale Wav2Lip 256 × 256 image par image ;
- reconnaissance vocale française locale Vosk avec mots, horaires et confiance ;
- conversion locale des mots en phonèmes français visibles ;
- alignement acoustique toutes les 10 ms à l'intérieur de chaque mot ;
- analyse de l'énergie, des attaques, des hautes fréquences et des passages par zéro ;
- fermeture anticipée des lèvres pour B, P et M ;
- formes renforcées pour F/V, A, E, I, O, OU, CH/J et consonnes ;
- exemple « bonjour » : B → ON → J → OU → R ;
- guidage fort uniquement lorsque la reconnaissance et l'alignement sont fiables ;
- traqueur dédié utilisant les contours supérieur et inférieur des lèvres ;
- validation géométrique et mémoire temporelle pendant les pertes courtes ;
- rejet automatique d'une détection située hors de la bouche ;
- repère OpenGL unique entre la vidéo, le visage et les lèvres ;
- accélération locale ONNX Runtime avec NNAPI, XNNPACK puis CPU de secours ;
- compensation de 560 ms conservée pour le moteur Pro v4 de secours ;
- choix obligatoire entre **9:16 vertical** et **16:9 horizontal** ;
- sortie contrôlée en 720 × 1280 ou 1280 × 720 ;
- aucune vidéo, piste audio ou transcription envoyée sur Internet.

## Chaîne de synchronisation

1. le traqueur de contours détermine la position réelle des lèvres ;
2. Vosk reconnaît les mots et leurs fenêtres temporelles ;
3. le lexique phonétique transforme les mots en sons français ;
4. l'aligneur acoustique déplace les frontières des phonèmes selon le signal réel ;
5. le spectrogramme Mel pilote Wav2Lip ;
6. les phonèmes bien alignés corrigent ouverture, largeur, rondeur et fermeture ;
7. la détection de parole ferme la bouche pendant les vrais silences.

Une transcription peu fiable ou une position labiale douteuse est rejetée automatiquement. L’application continue avec les sources encore fiables sans bloquer l’export.

## Exemple « bonjour »

- **B** : fermeture complète des deux lèvres ;
- **ON** : ouverture arrondie ;
- **J** : passage postalvéolaire plus étroit ;
- **OU** : lèvres serrées et projetées ;
- **R** : relâchement consonantique final.

Le minutage proportionnel reste disponible en secours si l'audio ne permet pas un alignement acoustique fiable.

## Fonctionnalités

- import d’une vidéo et d’un MP3 ;
- choix du point de départ du son ;
- détection du visage et contours labiaux ML Kit ;
- suivi temporel spécialisé des lèvres ;
- reconnaissance vocale française hors ligne avec Vosk ;
- phonétiseur français local sans serveur ;
- aligneur acoustique léger compatible Android ;
- génération Wav2Lip 256 avec ONNX Runtime Android ;
- rendu OpenGL avec fusion centrée sur la bouche ;
- correction phonétique renforcée du visage généré ;
- moteur neuronal personnel Pro v4 comme secours ;
- progression par blocs de 30 secondes ;
- export dans `Movies/LipSync AI` ;
- aucun serveur et aucune API distante.

## Conseils

- utiliser une personne principale avec la bouche visible ;
- privilégier un visage de face ou légèrement de côté ;
- conserver un visage assez grand pour que les lèvres restent détaillées ;
- éviter une musique beaucoup plus forte que la voix ;
- commencer par une séquence de 5 à 15 secondes ;
- laisser le téléphone branché pour les traitements longs.

## Limites

Un visage très petit, flou, masqué ou de profil extrême ne permet pas de rendre chaque phonème lisible. Le français comporte aussi des liaisons et des mots ambigus. La version 0.10.0 améliore l'articulation explicite, mais le résultat final doit être confirmé sur les exports réels du téléphone.

La feuille de route et les contre-arguments techniques sont documentés dans `ROADMAP_PHONEME_V10.md`.

## Performances et stockage

Les modèles Wav2Lip et Vosk sont inclus dans l’APK. Au premier traitement, le modèle vocal français est extrait dans l’espace privé de l’application. L'aligneur phonétique n'ajoute aucun gros modèle supplémentaire et fonctionne sur le processeur du téléphone.

Si la reconnaissance vocale échoue, elle est ignorée. Si Wav2Lip échoue ou manque de mémoire, l’export continue avec le moteur Pro v4 recalibré.

## Entraînement, calibration et construction

Le pipeline reproductible du modèle personnel se trouve dans `tools/train_personal_lip_model.py`. Les vidéos privées ne sont pas publiées : seuls les poids quantifiés et des rapports sans image sont conservés.

GitHub Actions télécharge Wav2Lip et Vosk, vérifie leurs empreintes SHA-256, valide le traqueur et l'aligneur phonétique, exécute les tests et Android Lint, puis compile et publie l’APK signé.

## Conditions des modèles

La conversion ONNX des poids Wav2Lip publics est réservée aux usages de recherche, académiques et personnels ; l’usage commercial est interdit sans licence adaptée. Vosk et son modèle français compact utilisent la licence Apache 2.0. Voir `THIRD_PARTY_NOTICES.md`.
