# Composants et modèles tiers

## Wav2Lip

- Projet original : https://github.com/Rudrabha/Wav2Lip
- Article : « A Lip Sync Expert Is All You Need for Speech to Lip Generation In The Wild », ACM Multimedia 2020.
- Le dépôt original indique que le code et les poids publics entraînés sur LRS2 sont destinés uniquement à la recherche, à l’enseignement et aux usages personnels. Toute utilisation commerciale nécessite une autorisation distincte des auteurs.

## Conversion ONNX 256 × 256

- Source du graphe converti : https://github.com/instant-high/wav2lip-onnx-256/releases/tag/v1.0.0
- Nom : `wav2lip_256.onnx`
- Taille : `214402122` octets
- SHA-256 : `bfeb0ab1ef3097f456f6fdcd506d3b32ee8a42f762a6722b42d0f1ca5b64e83c`

## ONNX Runtime

- Projet : https://github.com/microsoft/onnxruntime
- Paquet Android utilisé : `com.microsoft.onnxruntime:onnxruntime-android:1.27.0`
- Licence : MIT.

## Vosk API Android

- Projet : https://github.com/alphacep/vosk-api
- Paquet Android utilisé : `com.alphacephei:vosk-android:0.3.47`
- Fonction : reconnaissance vocale française locale avec mots et horodatages.
- Licence : Apache License 2.0.

## Modèle Vosk français compact

- Source : https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip
- Nom : `vosk-model-small-fr-0.22.zip`
- SHA-256 : `cabf6180e177eb9b3a9a9d43a437bd5e549f3a7d09525e5d69a3fed787be12ad`
- Licence déclarée par la liste officielle des modèles Vosk : Apache License 2.0.
- Le modèle est intégré sous forme d’archive vérifiée, puis extrait dans l’espace privé de l’application au premier traitement.

## Java Native Access

- Projet : https://github.com/java-native-access/jna
- Paquet Android utilisé : `net.java.dev.jna:jna:5.13.0`
- Fonction : liaison native nécessaire au moteur Vosk sur Android.
- Licence : LGPL 2.1 ou version ultérieure, avec option Apache License 2.0 selon les conditions publiées par le projet.

## Confidentialité

Les modèles fonctionnent localement. La vidéo, l’audio, la transcription et les repères de visage ne sont envoyés vers aucun service distant par l’application.