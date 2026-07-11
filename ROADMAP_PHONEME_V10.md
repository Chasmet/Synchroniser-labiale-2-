# Feuille de route phonème par phonème — version 0.10.0

## Objectif

Faire évoluer le moteur d'un mouvement global piloté par l'énergie audio vers une articulation française lisible mot par mot.

Exemple cible pour « bonjour » :

1. B : fermeture complète des deux lèvres ;
2. ON : ouverture arrondie ;
3. J : passage postalvéolaire plus étroit ;
4. OU : lèvres serrées et projetées ;
5. R : relâchement consonantique final.

## Architecture retenue

1. Vosk fournit les mots, leurs horaires et une confiance.
2. Le lexique phonétique local convertit chaque mot en phonèmes français.
3. L'aligneur acoustique analyse l'audio toutes les 10 ms.
4. Les attaques, baisses d'énergie, hautes fréquences et passages par zéro ajustent les frontières des phonèmes à l'intérieur de chaque mot.
5. La fusion phonétique donne plus de poids aux phonèmes bien alignés.
6. Le correcteur du visage généré accentue fermeture, ouverture, largeur et rondeur uniquement sur les lèvres.
7. Wav2Lip reste le générateur d'image principal et le traqueur labial 0.9.0 conserve le placement.

## Contre-arguments étudiés

### Ajouter Whisper complet

Avantage : meilleure transcription sur certains audios difficiles.

Limite : modèle lourd, consommation mémoire importante et absence d'horodatage phonème natif. Une meilleure phrase reconnue ne garantit pas une meilleure articulation. Cette option n'est pas ajoutée dans cette version Android.

### Ajouter Montreal Forced Aligner

Avantage : excellent alignement phonétique sur ordinateur avec dictionnaires et modèles acoustiques.

Limite : dépendances Python et Kaldi non adaptées à une APK Android autonome. L'application doit fonctionner uniquement sur téléphone.

### Ajouter eSpeak NG comme phonétiseur

Avantage : couverture lexicale étendue.

Limite : intégration native supplémentaire, poids, maintenance et contraintes de licence. Le lexique local couvre les règles utiles sans ajouter une bibliothèque native fragile.

### Piloter uniquement Wav2Lip

Avantage : simplicité.

Limite : Wav2Lip reçoit un spectrogramme, pas la séquence explicite B → ON → J → OU → R. Il peut suivre le rythme sans rendre chaque mot visuellement lisible.

### Forcer fortement toutes les transcriptions

Avantage : mouvement plus visible.

Limite : une mauvaise reconnaissance déformerait les lèvres avec le mauvais mot. La version 0.10.0 applique le guidage fort uniquement lorsque la reconnaissance et l'alignement acoustique ont une confiance suffisante.

## Limites honnêtes

- Une bouche très petite, floue ou cachée ne peut pas afficher des phonèmes précis.
- Une musique très forte peut réduire la qualité de Vosk et de l'alignement.
- Le français comporte des homographes et des liaisons qui demanderaient un modèle linguistique plus grand pour être parfaits.
- La validation finale doit être faite sur les vidéos exportées du téléphone réel.
