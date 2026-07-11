#!/usr/bin/env python3
"""Compare deux modèles audio→lèvres sur une liste de vidéos locales."""

from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path

import numpy as np

from train_personal_lip_model import (
    extract_video,
    metrics,
    network_predictions_from_inputs,
)


def git_json(repository: Path, revision: str, path: str) -> dict:
    result = subprocess.run(
        ["git", "show", f"{revision}:{path}"],
        cwd=repository,
        check=True,
        capture_output=True,
        text=True,
    )
    return json.loads(result.stdout)


def improvement(previous: list[float], current: list[float]) -> list[float]:
    return [
        round((old - new) / max(old, 1e-9) * 100.0, 3)
        for old, new in zip(previous, current)
    ]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--videos", type=Path, required=True)
    parser.add_argument("--files", nargs="+", required=True)
    parser.add_argument("--repository", type=Path, default=Path.cwd())
    parser.add_argument("--old-revision", default="origin/main")
    parser.add_argument(
        "--model-path",
        default="app/src/main/assets/chk_personal_lip_model_v1.json",
    )
    parser.add_argument(
        "--new-model",
        type=Path,
        default=Path("app/src/main/assets/chk_personal_lip_model_v1.json"),
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("training/new_videos_v4_comparison.json"),
    )
    args = parser.parse_args()

    old = git_json(args.repository, args.old_revision, args.model_path)
    new = json.loads(args.new_model.read_text())
    videos = []
    for file_name in args.files:
        path = args.videos / file_name
        inputs, _, visual, extraction = extract_video(path, 640, old)
        if not len(inputs):
            videos.append(extraction)
            continue
        old_metrics = metrics(visual, network_predictions_from_inputs(inputs, old))
        new_metrics = metrics(visual, network_predictions_from_inputs(inputs, new))
        videos.append(
            {
                "file": file_name,
                "samples": int(len(inputs)),
                "old_visual_mae": old_metrics["mae"],
                "new_visual_mae": new_metrics["mae"],
                "mae_improvement_percent": improvement(
                    old_metrics["mae"], new_metrics["mae"]
                ),
            }
        )

    payload = {
        "old_model": old["name"],
        "new_model": new["name"],
        "evaluation_scope": "post-training fit check on newly supplied clips; not holdout validation",
        "source_files_embedded": False,
        "videos": videos,
        "all_outputs_improved": all(
            all(value >= 0.0 for value in item.get("mae_improvement_percent", []))
            for item in videos
        ),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(payload, ensure_ascii=False))


if __name__ == "__main__":
    main()
