#!/usr/bin/env python3
"""Entraîne le petit réseau audio→mouvements de lèvres embarqué dans l'APK.

Les vidéos restent locales. Le script publie uniquement les poids quantifiés et
un rapport anonyme de qualité. Il exige ffmpeg/ffprobe, MediaPipe et scikit-learn.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import math
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

import cv2
import mediapipe as mp
import numpy as np
from sklearn.metrics import mean_absolute_error, r2_score
from sklearn.model_selection import GroupShuffleSplit
from sklearn.neural_network import MLPRegressor


FRAME_SECONDS = 0.040
TARGET_FPS = 25
OFFSETS = (-5, -2, 0, 2, 5)
BASE_FEATURES = 6
INPUT_SIZE = len(OFFSETS) * BASE_FEATURES + BASE_FEATURES + 2
OUTPUT_LABELS = ("openness", "width", "roundness")
HIDDEN_LAYERS = (96, 48)


@dataclass
class VideoProbe:
    duration: float
    width: int
    height: int
    rotation: int
    sample_rate: int


@dataclass
class MouthMeasurement:
    openness: float
    width: float
    roundness: float


def run(command: list[str], *, binary: bool = False) -> bytes | str:
    completed = subprocess.run(
        command,
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    return completed.stdout if binary else completed.stdout.decode("utf-8")


def ratio(text: str) -> float:
    numerator, denominator = text.split("/", 1)
    return float(numerator) / max(float(denominator), 1.0)


def probe_video(path: Path) -> VideoProbe:
    payload = json.loads(
        run(
            [
                "ffprobe",
                "-v",
                "error",
                "-show_streams",
                "-show_format",
                "-of",
                "json",
                str(path),
            ]
        )
    )
    video = next(stream for stream in payload["streams"] if stream["codec_type"] == "video")
    audio = next(
        (stream for stream in payload["streams"] if stream["codec_type"] == "audio"),
        None,
    )
    rotation = 0
    for side_data in video.get("side_data_list", []):
        if "rotation" in side_data:
            rotation = int(side_data["rotation"]) % 360
    duration = float(payload.get("format", {}).get("duration") or video.get("duration") or 0)
    return VideoProbe(
        duration=duration,
        width=int(video["width"]),
        height=int(video["height"]),
        rotation=rotation,
        sample_rate=int(audio.get("sample_rate", 48_000)) if audio else 0,
    )


def decode_audio(path: Path, sample_rate: int) -> np.ndarray:
    if sample_rate <= 0:
        raise ValueError("piste audio absente")
    raw = run(
        [
            "ffmpeg",
            "-v",
            "error",
            "-i",
            str(path),
            "-map",
            "0:a:0",
            "-ac",
            "1",
            "-ar",
            str(sample_rate),
            "-f",
            "f32le",
            "pipe:1",
        ],
        binary=True,
    )
    return np.frombuffer(raw, dtype="<f4").astype(np.float32, copy=False)


def audio_features(samples: np.ndarray, sample_rate: int) -> np.ndarray:
    """Réplique les six entrées de WindowCollector, bandes de Goertzel incluses."""
    window_size = max(1, int(sample_rate * FRAME_SECONDS))
    frequencies = (280.0, 560.0, 950.0, 1_550.0, 2_700.0, 4_200.0)
    basis_cache: dict[int, tuple[np.ndarray, np.ndarray]] = {}
    rows: list[list[float]] = []

    def powers(window: np.ndarray) -> np.ndarray:
        count = len(window)
        cached = basis_cache.get(count)
        if cached is None:
            positions = np.arange(count, dtype=np.float64)
            hann = 0.5 - 0.5 * np.cos(
                2.0 * np.pi * positions / max(count - 1, 1)
            )
            safe_frequencies = np.minimum(np.asarray(frequencies), sample_rate * 0.45)
            angles = 2.0 * np.pi * safe_frequencies[:, None] * positions / sample_rate
            basis = np.exp(-1j * angles)
            cached = (hann, basis)
            basis_cache[count] = cached
        hann, basis = cached
        projection = basis @ (window.astype(np.float64) * hann)
        return np.square(np.abs(projection))

    for start in range(0, len(samples), window_size):
        window = samples[start : start + window_size]
        if len(window) == 0:
            continue
        count = max(len(window), 1)
        rms = float(np.sqrt(np.mean(np.square(window, dtype=np.float64))))
        if start > 0:
            comparison = np.concatenate((samples[start - 1 : start], window))
        else:
            comparison = window
        zero_crossings = int(
            np.count_nonzero((comparison[1:] >= 0.0) != (comparison[:-1] >= 0.0))
        )
        transient_sum = float(np.abs(np.diff(comparison.astype(np.float64))).sum())
        frequency_powers = powers(window)
        low_power = float(frequency_powers[0] + frequency_powers[1])
        mid_power = float(frequency_powers[2] + frequency_powers[3])
        high_power = float(frequency_powers[4] + frequency_powers[5])
        selected_power = max(low_power + mid_power + high_power, 1.0e-12)
        rows.append(
            [
                rms,
                zero_crossings / count,
                transient_sum / count,
                min(max(low_power / selected_power, 0.0), 1.0),
                min(max(mid_power / selected_power, 0.0), 1.0),
                min(max(high_power / selected_power, 0.0), 1.0),
            ]
        )
    return np.asarray(rows, dtype=np.float32)


def scaled_dimensions(probe: VideoProbe, max_side: int) -> tuple[int, int]:
    width, height = probe.width, probe.height
    if probe.rotation in (90, 270):
        width, height = height, width
    scale = min(1.0, max_side / max(width, height))
    output_width = max(2, int(round(width * scale)) // 2 * 2)
    output_height = max(2, int(round(height * scale)) // 2 * 2)
    return output_width, output_height


def read_exact(stream, size: int) -> bytes:
    chunks: list[bytes] = []
    remaining = size
    while remaining:
        chunk = stream.read(remaining)
        if not chunk:
            break
        chunks.append(chunk)
        remaining -= len(chunk)
    return b"".join(chunks)


def iter_video_frames(path: Path, probe: VideoProbe, max_side: int):
    width, height = scaled_dimensions(probe, max_side)
    command = [
        "ffmpeg",
        "-v",
        "error",
        "-i",
        str(path),
        "-an",
        "-vf",
        f"fps={TARGET_FPS},scale={width}:{height}",
        "-pix_fmt",
        "rgb24",
        "-f",
        "rawvideo",
        "pipe:1",
    ]
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    assert process.stdout is not None
    frame_size = width * height * 3
    try:
        while True:
            raw = read_exact(process.stdout, frame_size)
            if len(raw) != frame_size:
                break
            yield np.frombuffer(raw, dtype=np.uint8).reshape(height, width, 3)
    finally:
        process.stdout.close()
        stderr = process.stderr.read().decode("utf-8") if process.stderr else ""
        status = process.wait()
        if status != 0:
            raise RuntimeError(f"ffmpeg vidéo a échoué pour {path.name}: {stderr[-400:]}")


def point(landmarks, index: int, width: int, height: int) -> np.ndarray:
    landmark = landmarks[index]
    return np.asarray((landmark.x * width, landmark.y * height), dtype=np.float32)


def distance(a: np.ndarray, b: np.ndarray) -> float:
    return float(np.linalg.norm(a - b))


def measure_mouth(frame: np.ndarray, face_mesh) -> MouthMeasurement | None:
    height, width = frame.shape[:2]
    result = face_mesh.process(frame)
    if not result.multi_face_landmarks:
        return None
    landmarks = result.multi_face_landmarks[0].landmark
    xs = np.asarray([item.x for item in landmarks])
    ys = np.asarray([item.y for item in landmarks])
    box_width = float(xs.max() - xs.min())
    box_height = float(ys.max() - ys.min())
    if box_width < 0.10 or box_height < 0.12:
        return None

    face_left = point(landmarks, 234, width, height)
    face_right = point(landmarks, 454, width, height)
    nose = point(landmarks, 1, width, height)
    left_distance = distance(face_left, nose)
    right_distance = distance(nose, face_right)
    yaw_ratio = left_distance / max(right_distance, 0.0001)
    if not 0.32 <= yaw_ratio <= 3.2:
        return None

    mouth_left = point(landmarks, 61, width, height)
    mouth_right = point(landmarks, 291, width, height)
    inner_top = point(landmarks, 13, width, height)
    inner_bottom = point(landmarks, 14, width, height)
    outer_top = point(landmarks, 0, width, height)
    outer_bottom = point(landmarks, 17, width, height)
    mouth_width = distance(mouth_left, mouth_right)
    face_width = distance(face_left, face_right)
    if mouth_width < 8.0 or face_width < 24.0:
        return None

    return MouthMeasurement(
        openness=distance(inner_top, inner_bottom) / mouth_width,
        width=mouth_width / face_width,
        roundness=distance(outer_top, outer_bottom) / mouth_width,
    )


def robust_unit(values: np.ndarray, low: float, high: float, minimum_range: float) -> np.ndarray:
    lo = float(np.quantile(values, low))
    hi = float(np.quantile(values, high))
    return np.clip((values - lo) / max(hi - lo, minimum_range), 0.0, 1.0)


def normalize_targets(measurements: np.ndarray, rms: np.ndarray) -> np.ndarray:
    relative_open = robust_unit(measurements[:, 0], 0.08, 0.95, 0.045)
    absolute_open = np.clip((measurements[:, 0] - 0.01) / 0.34, 0.0, 1.0)
    relative_width = robust_unit(measurements[:, 1], 0.08, 0.95, 0.035)
    absolute_width = np.clip((measurements[:, 1] - 0.30) / 0.24, 0.0, 1.0)
    relative_round = robust_unit(measurements[:, 2], 0.08, 0.95, 0.10)
    absolute_round = np.clip((measurements[:, 2] - 0.16) / 0.58, 0.0, 1.0)

    peak = max(float(np.quantile(rms, 0.95)), 0.0001)
    energy = np.sqrt(np.clip(rms / peak, 0.0, 1.4)).clip(0.0, 1.0)
    gate = np.clip((energy - 0.035) / 0.965, 0.0, 1.0)
    targets = np.column_stack(
        (
            0.55 * absolute_open + 0.45 * relative_open,
            0.55 * absolute_width + 0.45 * relative_width,
            0.55 * absolute_round + 0.45 * relative_round,
        )
    )
    targets[gate < 0.025] = 0.0
    return np.clip(targets, 0.0, 1.0).astype(np.float32)


def model_inputs(
    features: np.ndarray,
    base_feature_count: int = BASE_FEATURES,
    offsets: tuple[int, ...] = OFFSETS,
) -> np.ndarray:
    input_size = len(offsets) * base_feature_count + base_feature_count + 2
    rows = np.empty((len(features), input_size), dtype=np.float32)
    for index in range(len(features)):
        cursor = 0
        for offset in offsets:
            source = features[min(max(index + offset, 0), len(features) - 1)]
            rows[index, cursor : cursor + base_feature_count] = source[:base_feature_count]
            cursor += base_feature_count
        previous = features[max(index - 1, 0)]
        following = features[min(index + 1, len(features) - 1)]
        rows[index, cursor : cursor + base_feature_count] = (
            following[:base_feature_count] - previous[:base_feature_count]
        ) / 2.0
        cursor += base_feature_count
        current = features[index]
        rows[index, cursor] = math.log1p(float(current[0]) * 100.0)
        rows[index, cursor + 1] = float(current[2]) / (float(current[0]) + 0.0001)
    return rows


def network_predictions(features: np.ndarray, payload: dict) -> np.ndarray:
    offsets = tuple(int(value) for value in payload["feature_context_offsets"])
    input_size = int(payload["input_size"])
    base_feature_count = (input_size - 2) // (len(offsets) + 1)
    inputs = model_inputs(features, base_feature_count, offsets)
    mean = np.asarray(payload["input_mean"], dtype=np.float32)
    scale = np.asarray(payload["input_scale"], dtype=np.float32).clip(1e-6)
    activations = (inputs - mean) / scale
    for layer in payload["layers"]:
        if "weights_q8_base64" in layer:
            raw = base64.b64decode(layer["weights_q8_base64"])
            weights = np.frombuffer(raw, dtype=np.int8).astype(np.float32)
            weights = weights.reshape(int(layer["rows"]), int(layer["cols"]))
            weights *= float(layer["weight_scale"])
        else:
            weights = np.asarray(layer["weights"], dtype=np.float32)
        activations = activations @ weights + np.asarray(layer["bias"], dtype=np.float32)
        if layer["activation"] == "tanh":
            activations = np.tanh(activations)
    return np.clip(activations[:, :3], 0.0, 1.0)


def extract_video(
    path: Path,
    max_side: int,
    teacher: dict | None,
) -> tuple[np.ndarray, np.ndarray, np.ndarray, dict]:
    probe = probe_video(path)
    samples = decode_audio(path, probe.sample_rate)
    audio = audio_features(samples, probe.sample_rate)
    measured: list[MouthMeasurement | None] = []
    face_mesh = mp.solutions.face_mesh.FaceMesh(
        static_image_mode=False,
        max_num_faces=1,
        refine_landmarks=True,
        min_detection_confidence=0.55,
        min_tracking_confidence=0.55,
    )
    try:
        for frame in iter_video_frames(path, probe, max_side):
            measured.append(measure_mouth(frame, face_mesh))
    finally:
        face_mesh.close()

    usable = min(len(audio), len(measured))
    valid_indices = np.asarray(
        [index for index, item in enumerate(measured[:usable]) if item is not None],
        dtype=np.int32,
    )
    report = {
        "file": path.name,
        "sha256_12": hashlib.sha256(path.read_bytes()).hexdigest()[:12],
        "duration_seconds": round(probe.duration, 3),
        "source_width": probe.width,
        "source_height": probe.height,
        "source_rotation": probe.rotation,
        "frames_examined": usable,
        "faces_detected": int(len(valid_indices)),
    }
    minimum_valid = max(25, int(usable * 0.12))
    if len(valid_indices) < minimum_valid:
        report.update(accepted=False, samples=0, reason="pas assez de visages exploitables")
        empty_x = np.empty((0, INPUT_SIZE), np.float32)
        empty_y = np.empty((0, 3), np.float32)
        return empty_x, empty_y, empty_y, report

    raw_measurements = np.asarray(
        [
            [measured[index].openness, measured[index].width, measured[index].roundness]
            for index in valid_indices
        ],
        dtype=np.float32,
    )
    movement = float(np.quantile(raw_measurements[:, 0], 0.95) - np.quantile(raw_measurements[:, 0], 0.08))
    if movement < 0.012:
        report.update(accepted=False, samples=0, reason="mouvement de bouche insuffisant")
        empty_x = np.empty((0, INPUT_SIZE), np.float32)
        empty_y = np.empty((0, 3), np.float32)
        return empty_x, empty_y, empty_y, report

    all_inputs = model_inputs(audio[:usable])
    visual_targets = normalize_targets(raw_measurements, audio[valid_indices, 0])
    if teacher is not None:
        teacher_targets = network_predictions(audio[:usable], teacher)[valid_indices]
        visual_weight = np.asarray((0.45, 0.30, 0.30), dtype=np.float32)
        targets = teacher_targets * (1.0 - visual_weight) + visual_targets * visual_weight
    else:
        targets = visual_targets
    report.update(
        accepted=True,
        samples=int(len(valid_indices)),
        labeled_seconds=round(len(valid_indices) * FRAME_SECONDS, 3),
        mouth_movement=round(movement, 6),
    )
    return all_inputs[valid_indices], targets.astype(np.float32), visual_targets, report


def new_model(seed: int) -> MLPRegressor:
    return MLPRegressor(
        hidden_layer_sizes=HIDDEN_LAYERS,
        activation="tanh",
        solver="adam",
        alpha=0.0008,
        batch_size=256,
        learning_rate_init=0.001,
        max_iter=850,
        early_stopping=True,
        validation_fraction=0.12,
        n_iter_no_change=35,
        random_state=seed,
    )


def metrics(actual: np.ndarray, predicted: np.ndarray) -> dict:
    return {
        "mae": [round(float(value), 6) for value in mean_absolute_error(actual, predicted, multioutput="raw_values")],
        "r2": [round(float(value), 6) for value in r2_score(actual, predicted, multioutput="raw_values")],
    }


def quantized_layers(model: MLPRegressor) -> list[dict]:
    layers: list[dict] = []
    for index, (weights, bias) in enumerate(zip(model.coefs_, model.intercepts_)):
        maximum = max(float(np.max(np.abs(weights))), 1e-9)
        scale = maximum / 127.0
        quantized = np.clip(np.rint(weights / scale), -127, 127).astype(np.int8)
        layers.append(
            {
                "activation": "linear" if index == len(model.coefs_) - 1 else "tanh",
                "rows": int(weights.shape[0]),
                "cols": int(weights.shape[1]),
                "weight_scale": round(scale, 10),
                "weights_q8_base64": base64.b64encode(quantized.tobytes(order="C")).decode("ascii"),
                "bias": [round(float(value), 7) for value in bias],
            }
        )
    return layers


def train(args: argparse.Namespace) -> None:
    video_paths = sorted(args.videos.glob("*.mp4"))
    if not video_paths:
        raise SystemExit(f"Aucune vidéo MP4 dans {args.videos}")

    input_parts: list[np.ndarray] = []
    target_parts: list[np.ndarray] = []
    visual_target_parts: list[np.ndarray] = []
    group_parts: list[np.ndarray] = []
    reports: list[dict] = []
    teacher = json.loads(args.teacher.read_text()) if args.teacher and args.teacher.exists() else None
    selection = json.loads(args.selection.read_text()) if args.selection and args.selection.exists() else {}
    for group, path in enumerate(video_paths):
        print(f"[{group + 1:02d}/{len(video_paths):02d}] Analyse de {path.name}", flush=True)
        rule = selection.get(path.name, {})
        if rule.get("include") is False:
            probe = probe_video(path)
            reports.append(
                {
                    "file": path.name,
                    "duration_seconds": round(probe.duration, 3),
                    "source_width": probe.width,
                    "source_height": probe.height,
                    "source_rotation": probe.rotation,
                    "accepted": False,
                    "samples": 0,
                    "reason": rule.get("reason", "écartée lors du contrôle visuel"),
                }
            )
            print(f"  ignorée : {reports[-1]['reason']}", flush=True)
            continue
        try:
            inputs, targets, visual_targets, report = extract_video(
                path, args.max_frame_side, teacher
            )
        except Exception as error:
            report = {"file": path.name, "accepted": False, "samples": 0, "reason": str(error)}
            inputs = np.empty((0, INPUT_SIZE), np.float32)
            targets = np.empty((0, 3), np.float32)
            visual_targets = np.empty((0, 3), np.float32)
        reports.append(report)
        if len(inputs):
            input_parts.append(inputs)
            target_parts.append(targets)
            visual_target_parts.append(visual_targets)
            group_parts.append(np.full(len(inputs), group, dtype=np.int32))
            print(f"  {len(inputs)} exemples retenus", flush=True)
        else:
            print(f"  ignorée : {report.get('reason', 'inexploitable')}", flush=True)

    if len(input_parts) < 4:
        raise SystemExit("Il faut au moins quatre vidéos exploitables pour entraîner et valider le modèle")

    x = np.concatenate(input_parts)
    y = np.concatenate(target_parts)
    visual_y = np.concatenate(visual_target_parts)
    groups = np.concatenate(group_parts)
    splitter = GroupShuffleSplit(n_splits=1, test_size=0.20, random_state=args.seed)
    train_indices, validation_indices = next(splitter.split(x, y, groups))
    input_mean = x[train_indices].mean(axis=0)
    input_scale = x[train_indices].std(axis=0).clip(1e-6)
    x_scaled = (x - input_mean) / input_scale

    evaluation_model = new_model(args.seed)
    evaluation_model.fit(x_scaled[train_indices], y[train_indices])
    validation_prediction = np.clip(evaluation_model.predict(x_scaled[validation_indices]), 0.0, 1.0)
    validation_metrics = metrics(y[validation_indices], validation_prediction)
    visual_validation_metrics = metrics(
        visual_y[validation_indices], validation_prediction
    )

    # Le modèle livré est ensuite réentraîné sur tous les exemples valides.
    final_mean = x.mean(axis=0)
    final_scale = x.std(axis=0).clip(1e-6)
    final_model = new_model(args.seed)
    final_model.fit((x - final_mean) / final_scale, y)

    used_groups = sorted(set(int(value) for value in groups))
    validation_groups = sorted(set(int(groups[index]) for index in validation_indices))
    total_duration = sum(float(item.get("duration_seconds", 0)) for item in reports)
    labeled_seconds = sum(float(item.get("labeled_seconds", 0)) for item in reports)
    model_payload = {
        "name": "CHK-Personal-LipMotion-v3",
        "version": 3,
        "input_size": INPUT_SIZE,
        "training": {
            "videos_seen": len(video_paths),
            "videos_used": len(used_groups),
            "videos_rejected": len(video_paths) - len(used_groups),
            "samples": int(len(x)),
            "source_duration_seconds": round(total_duration, 3),
            "labeled_duration_seconds": round(labeled_seconds, 3),
            "source_files_embedded": False,
            "hidden_layers": list(HIDDEN_LAYERS),
            "validation_videos": len(validation_groups),
            "validation_mae": validation_metrics["mae"],
            "validation_r2": validation_metrics["r2"],
            "validation_visual_mae": visual_validation_metrics["mae"],
            "validation_visual_r2": visual_validation_metrics["r2"],
            "seed": args.seed,
            "teacher_model": teacher.get("name") if teacher else None,
            "visual_target_weight": [0.45, 0.30, 0.30] if teacher else [1.0, 1.0, 1.0],
        },
        "feature_context_offsets": list(OFFSETS),
        "audio_features": ["rms", "zero_crossing", "transient", "low_band", "mid_band", "high_band"],
        "input_mean": [round(float(value), 7) for value in final_mean],
        "input_scale": [round(float(value), 7) for value in final_scale],
        "layers": quantized_layers(final_model),
        "output_labels": list(OUTPUT_LABELS),
        "blend_with_signal_model": 0.42,
        "quantization": "symmetric_int8_weights",
    }
    report_payload = {
        "model": model_payload["name"],
        "generated_from_personal_videos": True,
        "source_files_embedded": False,
        "summary": model_payload["training"],
        "videos": reports,
    }

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(model_payload, ensure_ascii=False, separators=(",", ":")) + "\n")
    args.report.write_text(json.dumps(report_payload, ensure_ascii=False, indent=2) + "\n")
    print(
        f"Modèle v3 écrit : {len(x)} exemples, {len(used_groups)}/{len(video_paths)} vidéos, "
        f"MAE validation={validation_metrics['mae']}",
        flush=True,
    )


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--videos", type=Path, required=True, help="dossier local des MP4")
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("app/src/main/assets/chk_personal_lip_model_v1.json"),
    )
    parser.add_argument(
        "--report",
        type=Path,
        default=Path("training/model_v3_report.json"),
    )
    parser.add_argument("--seed", type=int, default=20260711)
    parser.add_argument("--max-frame-side", type=int, default=640)
    parser.add_argument(
        "--teacher",
        type=Path,
        default=Path("training/chk_personal_lip_model_v2.json"),
        help="modèle stable précédent utilisé pour éviter une régression",
    )
    parser.add_argument(
        "--selection",
        type=Path,
        default=Path("training/video_selection.json"),
        help="contrôle visuel des vidéos manifestement non humaines ou sans bouche",
    )
    return parser.parse_args()


if __name__ == "__main__":
    try:
        train(arguments())
    except subprocess.CalledProcessError as error:
        print(error.stderr.decode("utf-8", errors="replace"), file=sys.stderr)
        raise
