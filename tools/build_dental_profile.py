#!/usr/bin/env python3
"""Construit un profil dentaire numérique sans conserver d'image utilisateur."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import subprocess
from dataclasses import dataclass
from pathlib import Path

# MediaPipe 0.10 charge ses descripteurs protobuf pendant l'import.
import google.protobuf  # noqa: F401
import cv2
import mediapipe as mp
import numpy as np


LIPS = np.asarray(
    [61, 146, 91, 181, 84, 17, 314, 405, 321, 375, 291,
     308, 324, 318, 402, 317, 14, 87, 178, 88, 95, 78,
     191, 80, 81, 82, 13, 312, 311, 310, 415],
    dtype=np.int32,
)
INNER_MOUTH = np.asarray(
    [78, 191, 80, 81, 82, 13, 312, 311, 310, 415, 308,
     324, 318, 402, 317, 14, 87, 178, 88, 95],
    dtype=np.int32,
)


@dataclass
class Probe:
    duration: float
    width: int
    height: int
    rotation: int


def percentile(values: list[float], q: float, fallback: float) -> float:
    if not values:
        return fallback
    return float(np.percentile(np.asarray(values, dtype=np.float64), q))


def probe(path: Path) -> Probe:
    completed = subprocess.run(
        ["ffprobe", "-v", "error", "-show_streams", "-show_format", "-of", "json", str(path)],
        check=True,
        capture_output=True,
        text=True,
    )
    payload = json.loads(completed.stdout)
    video = next(stream for stream in payload["streams"] if stream["codec_type"] == "video")
    rotation = 0
    for side_data in video.get("side_data_list", []):
        if "rotation" in side_data:
            rotation = int(side_data["rotation"]) % 360
    return Probe(
        duration=float(payload.get("format", {}).get("duration") or video.get("duration") or 0.0),
        width=int(video["width"]),
        height=int(video["height"]),
        rotation=rotation,
    )


def rotate(frame: np.ndarray, degrees: int) -> np.ndarray:
    if degrees == 90:
        return cv2.rotate(frame, cv2.ROTATE_90_CLOCKWISE)
    if degrees == 180:
        return cv2.rotate(frame, cv2.ROTATE_180)
    if degrees == 270:
        return cv2.rotate(frame, cv2.ROTATE_90_COUNTERCLOCKWISE)
    return frame


def mouth_masks(shape: tuple[int, int], points: np.ndarray) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    height, width = shape
    lips = np.rint(points[LIPS] * np.asarray([width, height])).astype(np.int32)
    inner = np.rint(points[INNER_MOUTH] * np.asarray([width, height])).astype(np.int32)
    mouth = np.zeros((height, width), dtype=np.uint8)
    inner_mask = np.zeros_like(mouth)
    cv2.fillPoly(mouth, [cv2.convexHull(lips)], 255)
    cv2.fillPoly(inner_mask, [cv2.convexHull(inner)], 255)

    x, y, w, h = cv2.boundingRect(lips)
    radius = max(w, h * 2, 8)
    skin = np.zeros_like(mouth)
    cv2.ellipse(
        skin,
        (x + w // 2, y + h // 2),
        (max(radius, w), max(radius // 2, h * 2)),
        0,
        0,
        360,
        255,
        -1,
    )
    skin[mouth > 0] = 0
    return mouth > 0, inner_mask > 0, skin > 0


def frame_measurements(frame_bgr: np.ndarray, points: np.ndarray) -> dict[str, float] | None:
    mouth, inner, skin = mouth_masks(frame_bgr.shape[:2], points)
    if int(inner.sum()) < 24 or int(skin.sum()) < 80:
        return None

    rgb = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
    hsv = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2HSV).astype(np.float32)
    luma = rgb[..., 0] * 0.2126 + rgb[..., 1] * 0.7152 + rgb[..., 2] * 0.0722
    saturation = hsv[..., 1] / 255.0
    skin_luma = float(np.median(luma[skin]))
    skin_rgb = np.median(rgb[skin], axis=0)
    if skin_luma < 0.08:
        return None

    # Les seuils restent relatifs à la peau : le profil résiste à la lumière.
    # Les vidéos d'étalonnage peuvent être très sombres : un seuil absolu
    # rejetterait précisément les dents que l'on veut préserver. La sélection
    # reste donc principalement relative à la peau du même photogramme.
    candidate = inner & (luma > max(0.10, skin_luma * 1.12)) & (saturation < 0.52)
    candidate &= rgb[..., 2] < rgb[..., 0] * 1.13 + 0.08
    tooth_count = int(candidate.sum())
    inner_count = int(inner.sum())
    if tooth_count < max(8, int(inner_count * 0.025)):
        return {
            "skin_luma": skin_luma,
            "tooth_visible": 0.0,
            "tooth_area": tooth_count / max(inner_count, 1),
        }

    enamel_luma = luma[candidate]
    enamel_sat = saturation[candidate]
    enamel_rgb = np.median(rgb[candidate], axis=0)
    dark = inner & (luma < skin_luma * 0.38)
    ys = np.nonzero(candidate)[0]
    lip_center_y = float(np.mean(points[LIPS, 1]) * frame_bgr.shape[0])
    upper_share = float(np.mean(ys <= lip_center_y)) if len(ys) else 1.0

    gray = (luma * 255.0).astype(np.float32)
    laplacian = cv2.Laplacian(gray, cv2.CV_32F, ksize=3)
    detail = float(np.std(laplacian[candidate]) / 255.0)
    return {
        "skin_luma": skin_luma,
        "tooth_visible": 1.0,
        "tooth_area": tooth_count / max(inner_count, 1),
        "enamel_luma_ratio": float(np.median(enamel_luma) / skin_luma),
        "enamel_saturation": float(np.median(enamel_sat)),
        "enamel_red_ratio": float(enamel_rgb[0] / max(float(skin_rgb[0]), 0.03)),
        "enamel_green_ratio": float(enamel_rgb[1] / max(float(skin_rgb[1]), 0.03)),
        "enamel_blue_ratio": float(enamel_rgb[2] / max(float(skin_rgb[2]), 0.03)),
        "cavity_ratio": float(dark.sum() / max(inner_count, 1)),
        "cavity_luma_ratio": float(np.median(luma[dark]) / skin_luma) if dark.any() else 0.25,
        "detail": detail,
        "upper_teeth_share": upper_share,
    }


def analyze_video(path: Path, face_mesh, max_frames: int) -> tuple[dict, list[dict[str, float]]]:
    info = probe(path)
    capture = cv2.VideoCapture(str(path))
    capture.set(cv2.CAP_PROP_ORIENTATION_AUTO, 0)
    frame_count = int(capture.get(cv2.CAP_PROP_FRAME_COUNT))
    step = max(1, math.ceil(max(frame_count, 1) / max_frames))
    measurements: list[dict[str, float]] = []
    examined = 0
    mouth_frames = 0
    frame_index = 0
    try:
        while True:
            ok, frame = capture.read()
            if not ok:
                break
            if frame_index % step != 0:
                frame_index += 1
                continue
            frame_index += 1
            examined += 1
            frame = rotate(frame, info.rotation)
            scale = min(1.0, 720.0 / max(frame.shape[:2]))
            if scale < 1.0:
                frame = cv2.resize(frame, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
            result = face_mesh.process(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))
            if not result.multi_face_landmarks:
                continue
            mouth_frames += 1
            landmarks = result.multi_face_landmarks[0].landmark
            points = np.asarray([(point.x, point.y) for point in landmarks], dtype=np.float32)
            measured = frame_measurements(frame, points)
            if measured is not None:
                measurements.append(measured)
    finally:
        capture.release()

    report = {
        "file": path.name,
        "sha256_12": hashlib.sha256(path.read_bytes()).hexdigest()[:12],
        "duration_seconds": round(info.duration, 3),
        "source_width": info.width,
        "source_height": info.height,
        "source_rotation": info.rotation,
        "frames_examined": examined,
        "mouth_frames": mouth_frames,
        "tooth_frames": sum(item.get("tooth_visible", 0.0) > 0.5 for item in measurements),
        "accepted": bool(measurements),
    }
    return report, measurements


def build_profile(measurements: list[dict[str, float]], reports: list[dict]) -> dict:
    visible = [item for item in measurements if item.get("tooth_visible", 0.0) > 0.5]
    values = lambda key: [float(item[key]) for item in visible if key in item]
    return {
        "name": "CHK-Personal-DentalProfile-v1",
        "version": 1,
        "training": {
            "videos_examined": len(reports),
            "videos_readable": sum(bool(item.get("frames_examined")) for item in reports),
            "frames_examined": sum(int(item.get("frames_examined", 0)) for item in reports),
            "mouth_frames": sum(int(item.get("mouth_frames", 0)) for item in reports),
            "visible_teeth_frames": len(visible),
            "source_files_embedded": False,
        },
        "enamel_luma_to_skin_p10": round(percentile(values("enamel_luma_ratio"), 10, 0.92), 6),
        "enamel_luma_to_skin_p50": round(percentile(values("enamel_luma_ratio"), 50, 1.08), 6),
        "enamel_luma_to_skin_p90": round(percentile(values("enamel_luma_ratio"), 90, 1.36), 6),
        "enamel_saturation_p50": round(percentile(values("enamel_saturation"), 50, 0.19), 6),
        "enamel_rgb_to_skin_p50": [
            round(percentile(values("enamel_red_ratio"), 50, 1.08), 6),
            round(percentile(values("enamel_green_ratio"), 50, 1.05), 6),
            round(percentile(values("enamel_blue_ratio"), 50, 0.98), 6),
        ],
        "visible_tooth_area_p50": round(percentile(values("tooth_area"), 50, 0.28), 6),
        "visible_tooth_area_p90": round(percentile(values("tooth_area"), 90, 0.52), 6),
        "cavity_area_p50": round(percentile(values("cavity_ratio"), 50, 0.12), 6),
        "cavity_luma_to_skin_p50": round(percentile(values("cavity_luma_ratio"), 50, 0.24), 6),
        "detail_sigma_p50": round(percentile(values("detail"), 50, 0.10), 6),
        "upper_teeth_share_p50": round(percentile(values("upper_teeth_share"), 50, 0.90), 6),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--videos", type=Path, required=True)
    parser.add_argument("--files", nargs="*", default=[])
    parser.add_argument("--max-frames", type=int, default=100)
    parser.add_argument(
        "--motion-only",
        nargs="*",
        default=[],
        help="vidéos analysées pour le rapport mais exclues de la couleur dentaire",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("app/src/main/assets/chk_personal_dental_profile_v1.json"),
    )
    parser.add_argument(
        "--report",
        type=Path,
        default=Path("training/dental_profile_v11_report.json"),
    )
    args = parser.parse_args()
    paths = [args.videos / name for name in args.files] if args.files else sorted(args.videos.glob("*.mp4"))
    paths = [path for path in paths if path.exists()]
    if not paths:
        raise SystemExit("aucune vidéo trouvée")

    reports: list[dict] = []
    all_measurements: list[dict[str, float]] = []
    with mp.solutions.face_mesh.FaceMesh(
        static_image_mode=False,
        max_num_faces=1,
        refine_landmarks=True,
        min_detection_confidence=0.55,
        min_tracking_confidence=0.55,
    ) as face_mesh:
        for path in paths:
            try:
                report, measurements = analyze_video(path, face_mesh, args.max_frames)
            except Exception as error:
                report = {"file": path.name, "accepted": False, "reason": str(error)}
                measurements = []
            reports.append(report)
            if path.name not in set(args.motion_only):
                all_measurements.extend(measurements)
            else:
                report["profile_role"] = "motion_only"

    profile = build_profile(all_measurements, reports)
    report_payload = {
        "profile": profile["name"],
        "summary": profile["training"],
        "videos": reports,
        "privacy": "Only numeric statistics and short hashes are retained; no source frame is embedded.",
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(profile, ensure_ascii=False, indent=2) + "\n")
    args.report.write_text(json.dumps(report_payload, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(profile["training"], ensure_ascii=False))


if __name__ == "__main__":
    main()
