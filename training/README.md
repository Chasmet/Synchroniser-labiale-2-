# Entraînement du modèle personnel

Les vidéos privées ne sont jamais ajoutées au dépôt ni à l'APK. Seuls les poids
quantifiés du réseau et un rapport de qualité sans image sont conservés.

Préparation :

```bash
python -m pip install -r tools/requirements-training.txt
```

Entraînement :

```bash
python tools/train_personal_lip_model.py --videos /chemin/vers/les/videos
```

Profil dentaire :

```bash
python tools/build_dental_profile.py \
  --videos /chemin/vers/les/videos \
  --files sourire-1.mp4 sourire-2.mp4
```

Le script examine toutes les vidéos, ignore automatiquement les images sans
visage exploitable, extrait les mouvements de la bouche avec MediaPipe, les
associe au son par fenêtres de 40 ms, valide le réseau par vidéos séparées puis
réentraîne le modèle final avec toutes les séquences valides. La v4 recharge
les poids quantifiés v3, réexprime exactement leur première couche dans la
nouvelle normalisation, puis applique un fine-tuning à faible taux
d'apprentissage. Les rapports comparent toujours le candidat au réseau de base.

Le profil dentaire ne conserve que des percentiles numériques relatifs à la
peau et des hachages courts. Aucune image source n'est écrite dans le dépôt.
