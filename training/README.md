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

Le script examine toutes les vidéos, ignore automatiquement les images sans
visage exploitable, extrait les mouvements de la bouche avec MediaPipe, les
associe au son par fenêtres de 40 ms, valide le réseau par vidéos séparées puis
réentraîne le modèle final avec toutes les séquences valides.
