#!/usr/bin/env python3
"""Preview-adjust only cataloged chess hit sprite sheets.

Reads live normalized assets from shared/chess-assets/images, compares hit
frames with good live actions, writes hit-only previews, reports metrics, and
generates contact sheets. It does not overwrite live assets.
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import sys
from collections import defaultdict
from pathlib import Path
from statistics import mean
from typing import Any

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:
    print("Pillow is required. Install it with: python -m pip install Pillow", file=sys.stderr)
    raise SystemExit(1)


CATALOG_KEY_RE = re.compile(r"^[^/]+/(?P<color>white|black)_(?P<piece>[a-z]+)_(?P<action>[a-z0-9]+)$")
GOOD_ACTIONS = ("move", "attack", "attack1", "dead")
ALPHA_THRESHOLD = 10
MIN_SCALE = 0.85
MAX_SCALE = 1.25


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate hit-only sprite adjustment previews.")
    parser.add_argument("--source-root", type=Path, default=Path("shared/chess-assets"))
    parser.add_argument("--output-root", type=Path, default=Path("shared/chess-assets-hit-adjust-preview"))
    parser.add_argument("--alpha-threshold", type=int, default=ALPHA_THRESHOLD)
    parser.add_argument("--min-scale", type=float, default=MIN_SCALE)
    parser.add_argument("--max-scale", type=float, default=MAX_SCALE)
    return parser.parse_args()


def load_catalog(source_root: Path) -> dict[str, Any]:
    return json.loads((source_root / "sprite_catalog.json").read_text(encoding="utf-8"))


def parse_key(key: str) -> dict[str, str]:
    match = CATALOG_KEY_RE.match(key)
    if not match:
        raise ValueError(f"Unsupported catalog key: {key}")
    return match.groupdict()


def source_path(source_root: Path, catalog_path: str) -> Path:
    path = source_root / catalog_path
    if path.exists():
        return path
    if catalog_path.startswith("assets/"):
        path = source_root / catalog_path.removeprefix("assets/")
        if path.exists():
            return path
    raise FileNotFoundError(catalog_path)


def split_frames(sheet: Image.Image, frame_width: int, frame_height: int, frame_count: int) -> list[Image.Image]:
    return [
        sheet.crop((i * frame_width, 0, (i + 1) * frame_width, frame_height)).convert("RGBA")
        for i in range(frame_count)
    ]


def visible_bbox(frame: Image.Image, alpha_threshold: int) -> tuple[int, int, int, int] | None:
    alpha = frame.getchannel("A").point(lambda value: 255 if value > alpha_threshold else 0)
    return alpha.getbbox()


def frame_metric(frame: Image.Image, alpha_threshold: int) -> dict[str, Any]:
    bbox = visible_bbox(frame, alpha_threshold)
    if bbox is None:
        return {
            "width": 0.0,
            "height": 0.0,
            "center_x": frame.width / 2,
            "center_y": frame.height / 2,
            "offset_x": 0.0,
            "offset_y": 0.0,
            "area_ratio": 0.0,
            "bbox": None,
        }
    left, top, right, bottom = bbox
    width = float(right - left)
    height = float(bottom - top)
    center_x = (left + right) / 2
    center_y = (top + bottom) / 2
    return {
        "width": width,
        "height": height,
        "center_x": center_x,
        "center_y": center_y,
        "offset_x": center_x - frame.width / 2,
        "offset_y": center_y - frame.height / 2,
        "area_ratio": (width * height) / (frame.width * frame.height),
        "bbox": bbox,
    }


def avg(metrics: list[dict[str, Any]]) -> dict[str, float]:
    return {
        "width": mean(m["width"] for m in metrics),
        "height": mean(m["height"] for m in metrics),
        "center_x": mean(m["center_x"] for m in metrics),
        "center_y": mean(m["center_y"] for m in metrics),
        "offset_x": mean(m["offset_x"] for m in metrics),
        "offset_y": mean(m["offset_y"] for m in metrics),
        "area_ratio": mean(m["area_ratio"] for m in metrics),
    }


def avg_for_sheet(path: Path, frame_width: int, frame_height: int, frame_count: int, alpha_threshold: int) -> dict[str, float]:
    with Image.open(path) as sheet:
        sheet = sheet.convert("RGBA")
        frames = split_frames(sheet, frame_width, frame_height, frame_count)
    return avg([frame_metric(frame, alpha_threshold) for frame in frames])


def fit_scale(scale: float, bbox_width: float, bbox_height: float, cx: float, cy: float, fw: int, fh: int) -> tuple[float, bool]:
    room_w = 2 * min(cx, fw - cx)
    room_h = 2 * min(cy, fh - cy)
    max_fit = min(room_w / bbox_width, room_h / bbox_height)
    if scale <= max_fit:
        return scale, False
    return max(0.01, max_fit), True


def adjust_frame(
    frame: Image.Image,
    metric: dict[str, Any],
    target: dict[str, float],
    min_scale: float,
    max_scale: float,
) -> tuple[Image.Image, float, bool, bool]:
    bbox = metric["bbox"]
    if bbox is None or metric["height"] <= 0 or metric["width"] <= 0:
        return frame.copy(), 1.0, False, True

    height_scale = target["height"] / metric["height"]
    width_scale = target["width"] / metric["width"]
    requested = (height_scale * 0.7) + (width_scale * 0.3)
    extreme = requested < min_scale or requested > max_scale
    scale = min(max(requested, min_scale), max_scale)
    scale, would_crop = fit_scale(scale, metric["width"], metric["height"], target["center_x"], target["center_y"], frame.width, frame.height)

    crop = frame.crop(bbox)
    resized = crop.resize((max(1, round(crop.width * scale)), max(1, round(crop.height * scale))), Image.Resampling.LANCZOS)
    output = Image.new("RGBA", frame.size, (0, 0, 0, 0))
    output.alpha_composite(resized, (round(target["center_x"] - resized.width / 2), round(target["center_y"] - resized.height / 2)))
    return output, scale, would_crop, extreme


def compose(frames: list[Image.Image], frame_width: int, frame_height: int) -> Image.Image:
    sheet = Image.new("RGBA", (frame_width * len(frames), frame_height), (0, 0, 0, 0))
    for i, frame in enumerate(frames):
        sheet.alpha_composite(frame, (i * frame_width, 0))
    return sheet


def checker(size: tuple[int, int], block: int = 12) -> Image.Image:
    image = Image.new("RGBA", size, (28, 30, 34, 255))
    draw = ImageDraw.Draw(image)
    for y in range(0, size[1], block):
        for x in range(0, size[0], block):
            fill = (45, 47, 52, 255) if ((x // block) + (y // block)) % 2 else (30, 32, 36, 255)
            draw.rectangle((x, y, x + block, y + block), fill=fill)
    return image


def font(size: int) -> ImageFont.ImageFont:
    for candidate in (Path("C:/Windows/Fonts/segoeui.ttf"), Path("C:/Windows/Fonts/arial.ttf")):
        if candidate.exists():
            return ImageFont.truetype(str(candidate), size)
    return ImageFont.load_default()


def draw_bbox(draw: ImageDraw.ImageDraw, bbox: tuple[int, int, int, int] | None, scale: int, x: int, y: int, color: tuple[int, int, int]) -> None:
    if bbox is None:
        return
    left, top, right, bottom = bbox
    draw.rectangle((x + left * scale, y + top * scale, x + right * scale - 1, y + bottom * scale - 1), outline=color, width=2)


def make_contact_sheet(row: dict[str, Any], live_frames: list[Image.Image], preview_frames: list[Image.Image], output_dir: Path, alpha_threshold: int) -> Path:
    fw = int(row["frameWidth"])
    fh = int(row["frameHeight"])
    scale = 2 if max(fw, fh) <= 128 else 1
    pad = 10
    cell_w = fw * scale + pad * 2
    cell_h = fh * scale + pad * 2
    label_w = 126
    gap = 12
    title_h = 104
    width = max(900, label_w + len(live_frames) * cell_w + (len(live_frames) - 1) * gap + 28)
    height = title_h + cell_h * 3 + 74
    sheet = Image.new("RGBA", (width, height), (16, 18, 22, 255))
    draw = ImageDraw.Draw(sheet)
    draw.text((16, 14), f"{row['piece']} / {row['color']} / hit", font=font(22), fill=(238, 230, 210))
    draw.text((16, 46), f"target: {row['targetActions']} | scale: {row['scaleAppliedAverage']} | flags: {row['flags']}", font=font(14), fill=(205, 196, 174))
    draw.text((16, 68), "Red bbox: current live hit. Green bbox: adjusted preview. Blue lines: frame center.", font=font(12), fill=(169, 176, 190))

    rows = [("CURRENT HIT", live_frames, (239, 68, 68)), ("ADJUSTED HIT", preview_frames, (34, 197, 94)), ("BBOX GUIDE", preview_frames, None)]
    y = title_h
    for label, frames, color in rows:
        draw.text((16, y + 14), label, font=font(15), fill=(238, 230, 210))
        x = label_w
        for i, frame in enumerate(frames):
            cell = checker((cell_w, cell_h))
            resized = frame.resize((fw * scale, fh * scale), Image.Resampling.NEAREST)
            cell.alpha_composite(resized, (pad, pad))
            cd = ImageDraw.Draw(cell)
            cd.line((pad + fw * scale // 2, pad, pad + fw * scale // 2, pad + fh * scale), fill=(99, 179, 237))
            cd.line((pad, pad + fh * scale // 2, pad + fw * scale, pad + fh * scale // 2), fill=(99, 179, 237))
            live_bbox = visible_bbox(live_frames[i], alpha_threshold)
            preview_bbox = visible_bbox(preview_frames[i], alpha_threshold)
            if label == "BBOX GUIDE":
                draw_bbox(cd, live_bbox, scale, pad, pad, (239, 68, 68))
                draw_bbox(cd, preview_bbox, scale, pad, pad, (34, 197, 94))
            else:
                draw_bbox(cd, live_bbox if label == "CURRENT HIT" else preview_bbox, scale, pad, pad, color)
            sheet.alpha_composite(cell, (x, y))
            x += cell_w + gap
        y += cell_h + 28

    output_dir.mkdir(parents=True, exist_ok=True)
    path = output_dir / f"{row['piece']}_{row['color']}_hit.png"
    sheet.save(path)
    return path


def main() -> int:
    args = parse_args()
    catalog = load_catalog(args.source_root)
    clip_specs = catalog["clipSpecs"]
    entries = []
    by_piece_color_action: dict[tuple[str, str, str], dict[str, Any]] = {}
    for key, sheet in catalog["spriteSheets"].items():
        parsed = parse_key(key)
        spec = clip_specs[sheet["clipSpec"]]
        entry = {
            **parsed,
            "key": key,
            "catalogPath": sheet["path"],
            "sourcePath": source_path(args.source_root, sheet["path"]),
            "frameWidth": int(spec["frameSize"]["width"]),
            "frameHeight": int(spec["frameSize"]["height"]),
            "frameCount": int(spec["frameCount"]),
        }
        entries.append(entry)
        by_piece_color_action[(entry["piece"], entry["color"], entry["action"])] = entry

    rows = []
    errors = []
    contact_paths = []
    for hit in [entry for entry in entries if entry["action"] == "hit"]:
        piece = hit["piece"]
        color = hit["color"]
        fw = hit["frameWidth"]
        fh = hit["frameHeight"]
        fc = hit["frameCount"]
        with Image.open(hit["sourcePath"]) as live_sheet:
            live_sheet = live_sheet.convert("RGBA")
            if live_sheet.size != (fw * fc, fh):
                raise ValueError(f"Bad hit dimensions: {hit['sourcePath']}")
            live_frames = split_frames(live_sheet, fw, fh, fc)
        before_metrics = [frame_metric(frame, args.alpha_threshold) for frame in live_frames]
        before = avg(before_metrics)

        target_metrics = []
        target_actions = []
        for action in GOOD_ACTIONS:
            entry = by_piece_color_action.get((piece, color, action))
            if not entry:
                continue
            target_metrics.append(avg_for_sheet(entry["sourcePath"], entry["frameWidth"], entry["frameHeight"], entry["frameCount"], args.alpha_threshold))
            target_actions.append(action)
        if not target_metrics:
            entry = by_piece_color_action[(piece, color, "idle")]
            target_metrics.append(avg_for_sheet(entry["sourcePath"], entry["frameWidth"], entry["frameHeight"], entry["frameCount"], args.alpha_threshold))
            target_actions.append("idle")
        target = avg(target_metrics)

        preview_frames = []
        scales = []
        would_crop = False
        extreme = False
        empty = False
        for frame, metric in zip(live_frames, before_metrics):
            adjusted, scale, cropped, is_extreme = adjust_frame(frame, metric, target, args.min_scale, args.max_scale)
            preview_frames.append(adjusted)
            scales.append(scale)
            would_crop = would_crop or cropped
            extreme = extreme or is_extreme
            empty = empty or metric["bbox"] is None

        rel = hit["sourcePath"].relative_to(args.source_root)
        preview_path = args.output_root / rel
        preview_path.parent.mkdir(parents=True, exist_ok=True)
        compose(preview_frames, fw, fh).save(preview_path)
        after = avg([frame_metric(frame, args.alpha_threshold) for frame in preview_frames])
        flags = []
        if would_crop:
            flags.append("would_crop")
        if extreme:
            flags.append("extreme_scale")
        if empty:
            flags.append("empty_frame")
        if flags:
            flags.append("needs_review")
        else:
            flags.append("safe")
        row = {
            "piece": piece,
            "color": color,
            "action": "hit",
            "frameWidth": fw,
            "frameHeight": fh,
            "frameCount": fc,
            "beforeAvgVisibleWidth": round(before["width"], 3),
            "beforeAvgVisibleHeight": round(before["height"], 3),
            "beforeCenterOffset": f"{before['offset_x']:.2f},{before['offset_y']:.2f}",
            "targetAvgVisibleWidth": round(target["width"], 3),
            "targetAvgVisibleHeight": round(target["height"], 3),
            "targetCenterOffset": f"{target['offset_x']:.2f},{target['offset_y']:.2f}",
            "afterAvgVisibleWidth": round(after["width"], 3),
            "afterAvgVisibleHeight": round(after["height"], 3),
            "afterCenterOffset": f"{after['offset_x']:.2f},{after['offset_y']:.2f}",
            "scaleAppliedAverage": round(mean(scales), 5),
            "would_crop": would_crop,
            "needs_review": "needs_review" in flags,
            "flags": ";".join(flags),
            "targetActions": "+".join(target_actions),
            "sourcePath": str(hit["sourcePath"]).replace("\\", "/"),
            "outputPath": str(preview_path).replace("\\", "/"),
        }
        rows.append(row)
        contact_paths.append(str(make_contact_sheet(row, live_frames, preview_frames, args.output_root / "contact-sheets", args.alpha_threshold)).replace("\\", "/"))

    args.output_root.mkdir(parents=True, exist_ok=True)
    csv_path = args.output_root / "hit-adjust-report.csv"
    json_path = args.output_root / "hit-adjust-report.json"
    fieldnames = list(rows[0].keys())
    with csv_path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)
    json_path.write_text(json.dumps({"rows": rows, "errors": errors, "contactSheets": contact_paths}, indent=2) + "\n", encoding="utf-8")

    print(f"Hit sheets processed: {len(rows)}")
    print(f"Errors: {len(errors)}")
    print(f"Preview output: {args.output_root}")
    print(f"CSV report: {csv_path}")
    print(f"JSON report: {json_path}")
    for row in rows:
        print(f"{row['color']} {row['piece']} hit: before {row['beforeAvgVisibleWidth']}x{row['beforeAvgVisibleHeight']} -> after {row['afterAvgVisibleWidth']}x{row['afterAvgVisibleHeight']} target {row['targetAvgVisibleWidth']}x{row['targetAvgVisibleHeight']} scale {row['scaleAppliedAverage']} {row['flags']}")
    return 0 if not errors else 2


if __name__ == "__main__":
    raise SystemExit(main())
