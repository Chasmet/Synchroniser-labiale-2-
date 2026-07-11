# LipSync AI Studio

Application Android de synchronisation labiale entièrement locale. Elle associe
une vidéo et un MP3, reconstruit uniquement la zone des lèvres, puis exporte un
MP4 au ratio demandé sans envoyer la vidéo, l'audio ou les repères faciaux sur
Internet.

## Version 0.11.0 — lèvres propres et sourire personnel

- Wav2Lip 256 × 256 exécuté localement avec ONNX Runtime ;
- MediaPipe Face Landmarker comme traqueur principal à 478 points ;
- contours ML Kit et traqueur temporel dédié en secours ;
- estimation du roulis et crop du visage redressé avant l'inférence ;
- masque labial corrigé : les dimensions complètes sont converties en demi-axes ;
- masque évalué avec le vrai ratio des pixels et la rotation du visage ;
- zone modifiée réduite de 11,76 % à 1,64 % sur la géométrie de référence ;
- profil dentaire personnel construit sur 69 images où les dents sont visibles ;
- protection contre les dents brûlées, cavités trop noires, bruit et sauts temporels ;
- préservation prioritaire des dents supérieures du sourire de référence ;
- modèle personnel `CHK-Personal-LipMotion-v4-DentalGuarded` ;
- 6 655 exemples issus de 21 vidéos valides sur 32 examinées ;
- amélioration des trois sorties sur la validation séparée et sur les trois
  nouvelles vidéos ;
- correction phonème par phonème avec Vosk, lexique français et alignement
  acoustique toutes les 10 ms ;
- choix obligatoire **9:16 vertical** ou **16:9 horizontal** ;
- dimensions finales exactes : **720 × 1280** ou **1280 × 720** ;
- aucune permission Internet dans l'application.

## Chaîne de traitement

1. le maillage 478 points suit le visage, les lèvres et leur inclinaison ;
2. le crop facial est redressé et envoyé à Wav2Lip ;
3. le spectrogramme Mel et les phonèmes alignés pilotent l'articulation ;
4. le modèle personnel v4 stabilise ouverture, largeur et rondeur ;
5. le garde-fou compare chaque bouche générée à la peau et au profil dentaire ;
6. une prédiction douteuse est corrigée et son poids de fusion est limité ;
7. OpenGL remappe le résultat autour des vraies lèvres seulement ;
8. le cadre choisi est produit avec bandes noires si nécessaire, sans étirement.

## Contrôles qualité v0.11

Le fine-tuning repart mathématiquement des poids v3 avec une faible vitesse
d'apprentissage. Sur les cinq vidéos de validation, les MAE passent de
`[0.134036, 0.059905, 0.089238]` à `[0.122085, 0.052059, 0.058439]`.
Les erreurs visuelles passent de `[0.297858, 0.199683, 0.297460]` à
`[0.279141, 0.184934, 0.246716]`.

Les trois dernières vidéos améliorent chacune l'ouverture, la largeur et la
rondeur. Ces chiffres constituent un contrôle après entraînement ; la vraie
validation reste le groupe de cinq vidéos tenu à l'écart.

## Utilisation conseillée

- visage principal assez grand, net et éclairé ;
- bouche visible, sans main ni objet devant les lèvres ;
- séquence de test de 5 à 15 secondes avant un long export ;
- voix plus forte que la musique ;
- téléphone branché pendant un traitement long.

Un profil extrême, un visage minuscule ou une forte occlusion ne peuvent pas
produire une bouche parfaite. Dans ces cas, l'application réduit ou désactive
la génération pour protéger l'image source au lieu de forcer un mauvais visage.

## Entraînement et confidentialité

`tools/train_personal_lip_model.py` entraîne le petit réseau audio→mouvements.
`tools/build_dental_profile.py` extrait uniquement des statistiques numériques
de dents et de peau. Aucune image, vidéo ou piste audio d'entraînement n'est
suivie dans Git ou intégrée à l'APK.

GitHub Actions récupère Wav2Lip, Vosk et Face Landmarker, vérifie leurs tailles
et SHA-256, valide les rapports, exécute les tests et Android Lint, puis publie
l'APK signé.

## Licences

Les poids Wav2Lip publics sont limités aux usages de recherche, académiques et
personnels par leur projet d'origine. Un usage commercial demande une licence
adaptée. MediaPipe est sous licence Apache 2.0. Voir `THIRD_PARTY_NOTICES.md`.

Les choix techniques, mesures et limites sont détaillés dans
`ROADMAP_DENTAL_V11.md` et `ROADMAP_PHONEME_V10.md`.
