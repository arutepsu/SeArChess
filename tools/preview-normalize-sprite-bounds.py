#!/usr/bin/env python3
"""Generate preview-normalized chess sprite sheets without touching sources.

The script reads shared/chess-assets/sprite_catalog.json, computes visible bounds
using alpha > 10, and writes adjusted sprite sheets into a generated preview
folder. Catalog files, renderer code, and original sprites are left untouched.
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from statistics import mean
from typing import Any

try:
    from PIL import Image
except ImportError:
    print("Pillow is required. Install it with: python -m pip install Pillow", file=sys.stderr)
    raise SystemExit(1)


DEFAULT_SOURCE_ROOT = Path("shared/chess-assets")
DEFAULT_OUTPUT_ROOT = Path("shared/chess-assets-normalized-preview")
ALPHA_THRESHOLD = 10
MIN_SCALE = 0.75
MAX_SCALE = 1.35
CATALOG_KEY_RE = re.compile(r"^[^/]+/(?P<color>white|black)_(?P<piece>[a-z]+)_(?P<action>[a-z0-9]+)$")


@dataclass(frozen=True)
class SheetInfo:
    key: str
    piece: str
    color: str
    action: str
    catalog_path: str
    source_path: Path
    output_path: Path
    frame_width: int
    frame_height: int
    frame_count: int


@dataclass
class BoxMetric:
    width: float
    height: float
    area_ratio: float
    center_x: float
    center_y: float
    offset_x: float
    offset_y: float
    bbox: tuple[int, int, int, int] | None


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Create generated preview sprite sheets with normalized visible bounds."
    )
    parser.add_argument("--source-root", type=Path, default=DEFAULT_SOURCE_ROOT)
    parser.add_argument("--output-root", type=Path, default=DEFAULT_OUTPUT_ROOT)
    parser.add_argument("--alpha-threshold", type=int, default=ALPHA_THRESHOLD)
    parser.add_argument("--min-scale", type=float, default=MIN_SCALE)
    parser.add_argument("--max-scale", type=float, default=MAX_SCALE)
    return parser.parse_args()


def load_catalog(source_root: Path) -> dict[str, Any]:
    catalog_path = source_root / "sprite_catalog.json"
    with catalog_path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def resolve_source_path(source_root: Path, catalog_path: str) -> Path | None:
    candidates = [source_root / catalog_path]
    if catalog_path.startswith("assets/"):
        candidates.append(source_root / catalog_path.removeprefix("assets/"))

    for candidate in candidates:
        if candidate.exists():
            return candidate
    return None


def build_sheet_infos(catalog: dict[str, Any], source_root: Path, output_root: Path) -> tuple[list[SheetInfo], list[dict[str, str]]]:
    infos: list[SheetInfo] = []
    skipped: list[dict[str, str]] = []
    clip_specs = catalog["clipSpecs"]

    for key, sheet in sorted(catalog["spriteSheets"].items()):
        match = CATALOG_KEY_RE.match(key)
        if not match:
            skipped.append({"key": key, "reason": "unsupported_catalog_key"})
            continue

        clip_spec_name = sheet["clipSpec"]
        clip_spec = clip_specs[clip_spec_name]
        frame_width = int(clip_spec["frameSize"]["width"])
        frame_height = int(clip_spec["frameSize"]["height"])
        frame_count = int(clip_spec["frameCount"])
        catalog_path = sheet["path"]
        source_path = resolve_source_path(source_root, catalog_path)
        if source_path is None:
            skipped.append({"key": key, "reason": f"missing_source:{catalog_path}"})
            continue

        relative_output = source_path.relative_to(source_root)
        groups = match.groupdict()
        infos.append(
            SheetInfo(
                key=key,
                piece=groups["piece"],
                color=groups["color"],
                action=groups["action"],
                catalog_path=catalog_path,
                source_path=source_path,
                output_path=output_root / relative_output,
                frame_width=frame_width,
                frame_height=frame_height,
                frame_count=frame_count,
            )
        )

    return infos, skipped


def split_frames(image: Image.Image, info: SheetInfo) -> list[Image.Image]:
    frames = []
    for index in range(info.frame_count):
        left = index * info.frame_width
        box = (left, 0, left + info.frame_width, info.frame_height)
        frames.append(image.crop(box).convert("RGBA"))
    return frames


def visible_bbox(frame: Image.Image, alpha_threshold: int) -> tuple[int, int, int, int] | None:
    alpha = frame.getchannel("A").point(lambda value: 255 if value > alpha_threshold else 0)
    return alpha.getbbox()


def metric_for_frame(frame: Image.Image, alpha_threshold: int) -> BoxMetric:
    bbox = visible_bbox(frame, alpha_threshold)
    frame_width, frame_height = frame.size
    if bbox is None:
        return BoxMetric(0.0, 0.0, 0.0, frame_width / 2, frame_height / 2, 0.0, 0.0, None)

    left, top, right, bottom = bbox
    width = float(right - left)
    height = float(bottom - top)
    center_x = (left + right) / 2
    center_y = (top + bottom) / 2
    return BoxMetric(
        width=width,
        height=height,
        area_ratio=(width * height) / (frame_width * frame_height),
        center_x=center_x,
        center_y=center_y,
        offset_x=center_x - (frame_width / 2),
        offset_y=center_y - (frame_height / 2),
        bbox=bbox,
    )


def average_metric(metrics: list[BoxMetric]) -> dict[str, float]:
    if not metrics:
        return {
            "width": 0.0,
            "height": 0.0,
            "area_ratio": 0.0,
            "center_x": 0.0,
            "center_y": 0.0,
            "offset_x": 0.0,
            "offset_y": 0.0,
        }

    return {
        "width": mean(metric.width for metric in metrics),
        "height": mean(metric.height for metric in metrics),
        "area_ratio": mean(metric.area_ratio for metric in metrics),
        "center_x": mean(metric.center_x for metric in metrics),
        "center_y": mean(metric.center_y for metric in metrics),
        "offset_x": mean(metric.offset_x for metric in metrics),
        "offset_y": mean(metric.offset_y for metric in metrics),
    }


def compose_sheet(frames: list[Image.Image], info: SheetInfo) -> Image.Image:
    sheet = Image.new("RGBA", (info.frame_width * info.frame_count, info.frame_height), (0, 0, 0, 0))
    for index, frame in enumerate(frames):
        sheet.alpha_composite(frame, (index * info.frame_width, 0))
    return sheet


def fit_scale_to_canvas(
    target_scale: float,
    bbox_width: float,
    bbox_height: float,
    target_center_x: float,
    target_center_y: float,
    frame_width: int,
    frame_height: int,
) -> tuple[float, bool]:
    if bbox_width <= 0 or bbox_height <= 0:
        return 1.0, False

    horizontal_room = 2 * min(target_center_x, frame_width - target_center_x)
    vertical_room = 2 * min(target_center_y, frame_height - target_center_y)
    max_fit_scale = min(horizontal_room / bbox_width, vertical_room / bbox_height)
    if target_scale <= max_fit_scale:
        return target_scale, False
    return max(max_fit_scale, 0.01), True


def normalize_frame(
    frame: Image.Image,
    metric: BoxMetric,
    idle_average: dict[str, float],
    min_scale: float,
    max_scale: float,
    resampling_filter: int,
) -> tuple[Image.Image, float, bool, bool]:
    if metric.bbox is None or metric.height <= 0 or metric.width <= 0:
        return frame.copy(), 1.0, False, True

    requested_scale = idle_average["height"] / metric.height if metric.height else 1.0
    extreme_scale = requested_scale < min_scale or requested_scale > max_scale
    clamped_scale = min(max(requested_scale, min_scale), max_scale)

    final_scale, would_crop = fit_scale_to_canvas(
        clamped_scale,
        metric.width,
        metric.height,
        idle_average["center_x"],
        idle_average["center_y"],
        frame.width,
        frame.height,
    )

    crop = frame.crop(metric.bbox)
    scaled_width = max(1, round(crop.width * final_scale))
    scaled_height = max(1, round(crop.height * final_scale))
    scaled = crop.resize((scaled_width, scaled_height), resampling_filter)

    output = Image.new("RGBA", frame.size, (0, 0, 0, 0))
    paste_x = round(idle_average["center_x"] - (scaled_width / 2))
    paste_y = round(idle_average["center_y"] - (scaled_height / 2))
    output.alpha_composite(scaled, (paste_x, paste_y))
    return output, final_scale, would_crop, extreme_scale


def frame_metrics(frames: list[Image.Image], alpha_threshold: int) -> list[BoxMetric]:
    return [metric_for_frame(frame, alpha_threshold) for frame in frames]


def format_pair(offset_x: float, offset_y: float) -> str:
    return f"{offset_x:.2f},{offset_y:.2f}"


def make_row(
    info: SheetInfo,
    idle_average: dict[str, float],
    before_average: dict[str, float],
    after_average: dict[str, float],
    scale_average: float,
    flags: list[str],
) -> dict[str, Any]:
    return {
        "piece": info.piece,
        "color": info.color,
        "action": info.action,
        "frameWidth": info.frame_width,
        "frameHeight": info.frame_height,
        "frameCount": info.frame_count,
        "idleAvgVisibleWidth": round(idle_average["width"], 3),
        "idleAvgVisibleHeight": round(idle_average["height"], 3),
        "beforeAvgVisibleWidth": round(before_average["width"], 3),
        "beforeAvgVisibleHeight": round(before_average["height"], 3),
        "afterAvgVisibleWidth": round(after_average["width"], 3),
        "afterAvgVisibleHeight": round(after_average["height"], 3),
        "beforeAreaRatio": round(before_average["area_ratio"], 5),
        "afterAreaRatio": round(after_average["area_ratio"], 5),
        "scaleAppliedAverage": round(scale_average, 5),
        "centerOffsetBefore": format_pair(before_average["offset_x"], before_average["offset_y"]),
        "centerOffsetAfter": format_pair(after_average["offset_x"], after_average["offset_y"]),
        "flags": ";".join(flags),
        "sourcePath": str(info.source_path).replace("\\", "/"),
        "outputPath": str(info.output_path).replace("\\", "/"),
    }


def write_reports(output_root: Path, rows: list[dict[str, Any]], skipped: list[dict[str, str]]) -> tuple[Path, Path]:
    output_root.mkdir(parents=True, exist_ok=True)
    csv_path = output_root / "normalization-report.csv"
    json_path = output_root / "normalization-report.json"

    fieldnames = [
        "piece",
        "color",
        "action",
        "frameWidth",
        "frameHeight",
        "frameCount",
        "idleAvgVisibleWidth",
        "idleAvgVisibleHeight",
        "beforeAvgVisibleWidth",
        "beforeAvgVisibleHeight",
        "afterAvgVisibleWidth",
        "afterAvgVisibleHeight",
        "beforeAreaRatio",
        "afterAreaRatio",
        "scaleAppliedAverage",
        "centerOffsetBefore",
        "centerOffsetAfter",
        "flags",
        "sourcePath",
        "outputPath",
    ]

    with csv_path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)

    with json_path.open("w", encoding="utf-8") as handle:
        json.dump({"rows": rows, "skipped": skipped}, handle, indent=2)
        handle.write("\n")

    return csv_path, json_path


def validate_source_dimensions(image: Image.Image, info: SheetInfo) -> str | None:
    expected_width = info.frame_width * info.frame_count
    expected_height = info.frame_height
    if image.size != (expected_width, expected_height):
        return f"dimension_mismatch:expected={expected_width}x{expected_height}:actual={image.width}x{image.height}"
    return None


def main() -> int:
    args = parse_args()
    source_root = args.source_root
    output_root = args.output_root
    resampling_filter = getattr(Image.Resampling, "LANCZOS", Image.LANCZOS)

    catalog = load_catalog(source_root)
    infos, skipped = build_sheet_infos(catalog, source_root, output_root)

    metrics_by_key: dict[str, list[BoxMetric]] = {}
    idle_averages: dict[tuple[str, str], dict[str, float]] = {}
    images: dict[str, Image.Image] = {}

    for info in infos:
        image = Image.open(info.source_path).convert("RGBA")
        validation_error = validate_source_dimensions(image, info)
        if validation_error:
            skipped.append({"key": info.key, "reason": validation_error})
            continue

        images[info.key] = image
        metrics = frame_metrics(split_frames(image, info), args.alpha_threshold)
        metrics_by_key[info.key] = metrics
        if info.action == "idle":
            idle_averages[(info.piece, info.color)] = average_metric(metrics)

    rows: list[dict[str, Any]] = []
    processed = 0
    for info in infos:
        if info.key not in images:
            continue

        idle_average = idle_averages.get((info.piece, info.color))
        if idle_average is None:
            skipped.append({"key": info.key, "reason": "missing_idle_reference"})
            continue

        source_image = images[info.key]
        source_frames = split_frames(source_image, info)
        before_metrics = metrics_by_key[info.key]
        before_average = average_metric(before_metrics)

        flags: list[str] = []
        scales: list[float] = []
        normalized_frames: list[Image.Image] = []

        if info.action == "idle":
            normalized_frames = [frame.copy() for frame in source_frames]
            scales = [1.0 for _ in source_frames]
        else:
            for frame, metric in zip(source_frames, before_metrics):
                normalized_frame, scale, would_crop, extreme_scale = normalize_frame(
                    frame,
                    metric,
                    idle_average,
                    args.min_scale,
                    args.max_scale,
                    resampling_filter,
                )
                normalized_frames.append(normalized_frame)
                scales.append(scale)
                if would_crop:
                    flags.append("would_crop")
                if extreme_scale:
                    flags.append("extreme_scale")
                if metric.bbox is None:
                    flags.append("empty_frame")

        if info.action in {"dead", "fall"}:
            flags.append("dead_pose_warning")

        output_sheet = compose_sheet(normalized_frames, info)
        info.output_path.parent.mkdir(parents=True, exist_ok=True)
        output_sheet.save(info.output_path)

        after_metrics = frame_metrics(normalized_frames, args.alpha_threshold)
        after_average = average_metric(after_metrics)
        unique_flags = sorted(set(flags))
        if unique_flags:
            unique_flags.append("needs_review")
        else:
            unique_flags.append("safe")

        rows.append(
            make_row(
                info,
                idle_average,
                before_average,
                after_average,
                mean(scales) if scales else 1.0,
                unique_flags,
            )
        )
        processed += 1

    csv_path, json_path = write_reports(output_root, rows, skipped)
    flag_counts = Counter(flag for row in rows for flag in str(row["flags"]).split(";"))

    print(f"Processed cataloged sheets: {processed}")
    print(f"Skipped sheets: {len(skipped)}")
    if skipped:
        for item in skipped:
            print(f"  - {item['key']}: {item['reason']}")
    print(f"Preview output: {output_root}")
    print(f"CSV report: {csv_path}")
    print(f"JSON report: {json_path}")
    print("Flag counts:")
    for flag, count in sorted(flag_counts.items()):
        print(f"  {flag}: {count}")

    return 0 if not skipped else 2


if __name__ == "__main__":
    raise SystemExit(main())
