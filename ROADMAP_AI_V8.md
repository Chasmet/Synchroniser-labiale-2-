# Feuille de route IA v8

## Objectif

Améliorer la synchronisation labiale sans dépendre d’un serveur, tout en gardant une application utilisable depuis un téléphone Android.

## Première feuille de route envisagée

1. Ajouter Whisper pour transcrire tout l’audio.
2. Ajouter plusieurs modèles de synchronisation en parallèle.
3. Ajouter une super-résolution du visage.
4. Ajouter une séparation voix/musique lourde.
5. Augmenter fortement la taille du modèle.

## Contre-arguments

- **Whisper complet** : très lourd, lent sur téléphone et ses horodatages ne sont pas assez fins pour piloter seuls les lèvres, surtout avec du rap ou de la musique.
- **Plusieurs générateurs en parallèle** : multiplie la mémoire et le temps de traitement sans garantir un meilleur résultat.
- **Super-résolution** : améliore éventuellement la netteté, mais pas le timing audio–lèvres.
- **Séparation musicale lourde** : consomme beaucoup de mémoire et peut déformer les consonnes utiles à Wav2Lip.
- **Taille de l’APK** : un fichier plus gros n’est utile que si le modèle supplémentaire apporte une information réellement exploitable.

## Feuille de route retenue

1. Conserver **Wav2Lip 256** comme moteur génératif principal.
2. Ajouter une **reconnaissance vocale française locale Vosk** avec mots et horodatages.
3. Ajouter une **détection de parole locale** pour distinguer voix, silences et bruit.
4. Convertir les mots reconnus en **formes de lèvres françaises** : M/P/B, F/V, A, E, I, O, U et consonnes.
5. N’appliquer les corrections textuelles que si la confiance est suffisante.
6. Fermer les lèvres pendant les silences et renforcer les fermetures M/P/B.
7. Utiliser le texte comme guide léger, jamais comme remplacement du spectrogramme audio.
8. Ajouter un rapport qualité : mots reconnus, confiance moyenne, moteur utilisé et nombre d’images générées.
9. Garder un repli automatique vers le moteur Pro v4 si Wav2Lip ou la reconnaissance vocale échoue.
10. Tester automatiquement les visèmes français, la détection de parole, les formats 9:16/16:9 et la compilation Android.

## Décision finale

La version v8 combine trois sources complémentaires :

- le signal audio brut pour Wav2Lip ;
- la détection de parole pour les silences et les attaques ;
- le texte horodaté uniquement pour corriger les phonèmes les plus visibles.

Cette architecture est plus robuste qu’un empilement de modèles lourds et reste entièrement locale.