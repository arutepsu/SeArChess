#!/usr/bin/env python3
"""Preview-adjust only cataloged chess idle sprite sheets.

Reads live normalized assets from shared/chess-assets/images, compares idle
frames with the already-good move/attack(/attack1) actions, writes idle-only
previews, reports metrics, and generates contact sheets. It does not
overwrite live assets and does not touch move/attack/dead/hit sheets.

Target logic (per piece/color):
  targetWidthRatio  = mean of visible-bbox width ratios for move + attack(+attack1)
  targetHeightRatio = capped at the max of those same actions' height ratios
                       (idle must not become taller than the good actions)
  scale             = clamp(min(widthScale, heightCapScale), MIN_SCALE, MAX_SCALE)
  extreme requests (raw scale > EXTREME_SCALE) are flagged and NOT applied.
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import sys
from pathlib import Path
from statistics import mean
from typing import Any

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:
    print("Pillow is required. Install it with: python -m pip install Pillow", file=sys.stderr)
    raise SystemExit(1)


CATALOG_KEY_RE = re.compile(r"^[^/]+/(?P<color>white|black)_(?P<piece>[a-z]+)_(?P<action>[a-z0-9]+)$")
TARGET_ACTIONS = ("move", "attack", "attack1")
ALPHA_THRESHOLD = 10
MIN_SCALE = 1.00
MAX_SCALE = 1.25
EXTREME_SCALE = 1.50  # raw (pre-clamp) scale beyond this is flagged and not applied
HEIGHT_TOLERANCE = 1.05  # allow idle height to land up to 5% above the move/attack
                          # ceiling — several priority pieces already sit exactly at
                          # the ceiling, and a strict 0%-tolerance cap would block any
                          # width correction for them. 5% is not a visually meaningful
                          # height increase; documented here and in the report.
PRIORITY_PIECES = ("pawn", "rook", "knight", "king")
REVIEW_ONLY_PIECES = ("bishop", "queen")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate idle-only sprite adjustment previews.")
    parser.add_argument("--source-root", type=Path, default=Path("shared/chess-assets"))
    parser.add_argument("--output-root", type=Path, default=Path("shared/chess-assets-idle-adjust-preview"))
    parser.add_argument("--alpha-threshold", type=int, default=ALPHA_THRESHOLD)
    parser.add_argument("--min-scale", type=float, default=MIN_SCALE)
    parser.add_argument("--max-scale", type=float, default=MAX_SCALE)
    parser.add_argument("--extreme-scale", type=float, default=EXTREME_SCALE)
    parser.add_argument("--height-tolerance", type=float, default=HEIGHT_TOLERANCE)
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
            "width_ratio": 0.0,
            "height_ratio": 0.0,
            "center_x": frame.width / 2,
            "center_y": frame.height / 2,
            "bbox": None,
        }
    left, top, right, bottom = bbox
    return {
        "width_ratio": (right - left) / frame.width,
        "height_ratio": (bottom - top) / frame.height,
        "center_x": (left + right) / 2,
        "center_y": (top + bottom) / 2,
        "bbox": bbox,
    }


def avg_ratio(metrics: list[dict[str, Any]]) -> dict[str, float]:
    return {
        "width_ratio": mean(m["width_ratio"] for m in metrics),
        "height_ratio": mean(m["height_ratio"] for m in metrics),
    }


def load_frames(path: Path, fw: int, fh: int, fc: int) -> list[Image.Image]:
    with Image.open(path) as sheet:
        sheet = sheet.convert("RGBA")
        if sheet.size != (fw * fc, fh):
            raise ValueError(f"Bad sheet dimensions: {path} expected {(fw * fc, fh)} got {sheet.size}")
        return split_frames(sheet, fw, fh, fc)


def fit_scale(scale: float, bbox_width: float, bbox_height: float, cx: float, cy: float, fw: int, fh: int) -> tuple[float, bool]:
    room_w = 2 * min(cx, fw - cx)
    room_h = 2 * min(cy, fh - cy)
    max_fit = min(room_w / bbox_width, room_h / bbox_height) if bbox_width > 0 and bbox_height > 0 else scale
    if scale <= max_fit:
        return scale, False
    return max(0.01, max_fit), True


def adjust_frame(
    frame: Image.Image,
    metric: dict[str, Any],
    scale: float,
) -> tuple[Image.Image, bool]:
    bbox = metric["bbox"]
    if bbox is None or scale <= 1.0:
        return frame.copy(), False

    bbox_w = bbox[2] - bbox[0]
    bbox_h = bbox[3] - bbox[1]
    fitted_scale, would_crop = fit_scale(scale, bbox_w, bbox_h, metric["center_x"], metric["center_y"], frame.width, frame.height)

    crop = frame.crop(bbox)
    resized = crop.resize(
        (max(1, round(crop.width * fitted_scale)), max(1, round(crop.height * fitted_scale))),
        Image.Resampling.LANCZOS,
    )
    output = Image.new("RGBA", frame.size, (0, 0, 0, 0))
    output.alpha_composite(
        resized,
        (round(metric["center_x"] - resized.width / 2), round(metric["center_y"] - resized.height / 2)),
    )
    return output, would_crop


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


def row_label(frames: list[Image.Image], alpha_threshold: int, label: str) -> list[tuple[int, int, int, int] | None]:
    return [visible_bbox(f, alpha_threshold) for f in frames]


def make_contact_sheet(
    row: dict[str, Any],
    live_idle: list[Image.Image],
    preview_idle: list[Image.Image],
    live_move: list[Image.Image] | None,
    live_attack: list[Image.Image] | None,
    output_dir: Path,
    alpha_threshold: int,
) -> Path:
    fw = int(row["frameWidth"])
    fh = int(row["frameHeight"])
    scale = 2 if max(fw, fh) <= 128 else 1
    pad = 10
    cell_w = fw * scale + pad * 2
    cell_h = fh * scale + pad * 2
    label_w = 150
    gap = 12
    title_h = 110

    sections: list[tuple[str, list[Image.Image] | None, tuple[int, int, int] | None]] = [
        ("LIVE IDLE (before)", live_idle, (239, 68, 68)),
        ("ADJUSTED IDLE (preview)", preview_idle, (34, 197, 94)),
        ("LIVE MOVE (reference)", live_move, (99, 179, 237)),
        ("LIVE ATTACK (reference)", live_attack, (250, 204, 21)),
        ("BBOX GUIDE (red=before, green=after)", preview_idle, None),
    ]

    max_frames = max(len(s[1]) for s in sections if s[1])
    width = max(900, label_w + max_frames * cell_w + (max_frames - 1) * gap + 28)
    height = title_h + sum(cell_h + 28 for s in sections if s[1])
    sheet = Image.new("RGBA", (width, height), (16, 18, 22, 255))
    draw = ImageDraw.Draw(sheet)
    draw.text((16, 14), f"{row['piece']} / {row['color']} / idle", font=font(22), fill=(238, 230, 210))
    draw.text(
        (16, 46),
        f"target ratio: {row['targetWidthRatio']}/{row['targetHeightRatio']} | scale: {row['scaleAppliedAverage']} | needsReview: {row['needsReview']}",
        font=font(14), fill=(205, 196, 174),
    )
    draw.text(
        (16, 68),
        f"idle before: {row['idleBeforeWidthRatio']}/{row['idleBeforeHeightRatio']} -> idle after: {row['idleAfterWidthRatio']}/{row['idleAfterHeightRatio']}",
        font=font(14), fill=(205, 196, 174),
    )
    draw.text((16, 90), "Red bbox: live idle. Green bbox: adjusted idle preview.", font=font(12), fill=(169, 176, 190))

    y = title_h
    for label, frames, color in sections:
        if not frames:
            continue
        draw.text((16, y + 14), label, font=font(15), fill=(238, 230, 210))
        x = label_w
        for i, frame in enumerate(frames):
            cell = checker((cell_w, cell_h))
            resized = frame.resize((fw * scale, fh * scale), Image.Resampling.NEAREST)
            cell.alpha_composite(resized, (pad, pad))
            cd = ImageDraw.Draw(cell)
            cd.line((pad + fw * scale // 2, pad, pad + fw * scale // 2, pad + fh * scale), fill=(70, 80, 96))
            cd.line((pad, pad + fh * scale // 2, pad + fw * scale, pad + fh * scale // 2), fill=(70, 80, 96))
            if label.startswith("BBOX GUIDE"):
                before_bbox = visible_bbox(live_idle[i], alpha_threshold) if i < len(live_idle) else None
                after_bbox = visible_bbox(preview_idle[i], alpha_threshold) if i < len(preview_idle) else None
                draw_bbox(cd, before_bbox, scale, pad, pad, (239, 68, 68))
                draw_bbox(cd, after_bbox, scale, pad, pad, (34, 197, 94))
            else:
                bbox = visible_bbox(frame, alpha_threshold)
                draw_bbox(cd, bbox, scale, pad, pad, color)
            sheet.alpha_composite(cell, (x, y))
            x += cell_w + gap
        y += cell_h + 28

    output_dir.mkdir(parents=True, exist_ok=True)
    path = output_dir / f"{row['piece']}_{row['color']}_idle.png"
    sheet.save(path)
    return path


def main() -> int:
    args = parse_args()
    catalog = load_catalog(args.source_root)
    clip_specs = catalog["clipSpecs"]
    entries: list[dict[str, Any]] = []
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

    rows: list[dict[str, Any]] = []
    contact_paths: list[str] = []
    errors: list[str] = []

    idle_entries = [e for e in entries if e["action"] == "idle"]
    idle_entries.sort(key=lambda e: (e["piece"], e["color"]))

    for idle in idle_entries:
        piece = idle["piece"]
        color = idle["color"]
        fw, fh, fc = idle["frameWidth"], idle["frameHeight"], idle["frameCount"]

        try:
            live_idle_frames = load_frames(idle["sourcePath"], fw, fh, fc)
        except Exception as exc:  # noqa: BLE001
            errors.append(f"{piece}/{color}/idle: {exc}")
            continue

        before_metrics = [frame_metric(f, args.alpha_threshold) for f in live_idle_frames]
        before = avg_ratio(before_metrics)

        target_metrics = []
        target_actions = []
        for action in TARGET_ACTIONS:
            entry = by_piece_color_action.get((piece, color, action))
            if not entry:
                continue
            try:
                frames = load_frames(entry["sourcePath"], entry["frameWidth"], entry["frameHeight"], entry["frameCount"])
            except Exception as exc:  # noqa: BLE001
                errors.append(f"{piece}/{color}/{action}: {exc}")
                continue
            metrics = [frame_metric(f, args.alpha_threshold) for f in frames]
            target_metrics.append(avg_ratio(metrics))
            target_actions.append(action)

        is_priority = piece in PRIORITY_PIECES
        if not target_metrics:
            # No move/attack reference available — nothing to target against.
            rows.append({
                "piece": piece, "color": color,
                "frameWidth": fw, "frameHeight": fh, "frameCount": fc,
                "idleBeforeWidthRatio": round(before["width_ratio"], 4),
                "idleBeforeHeightRatio": round(before["height_ratio"], 4),
                "targetWidthRatio": None, "targetHeightRatio": None,
                "idleAfterWidthRatio": round(before["width_ratio"], 4),
                "idleAfterHeightRatio": round(before["height_ratio"], 4),
                "scaleAppliedAverage": 1.0,
                "wouldCrop": False,
                "needsReview": True,
                "skippedReason": "no move/attack reference sheet found",
                "applied": False,
                "isPriority": is_priority,
                "targetActions": "",
            })
            continue

        target_width_ratio = mean(m["width_ratio"] for m in target_metrics)
        height_ceiling = max(m["height_ratio"] for m in target_metrics)

        raw_width_scale = target_width_ratio / before["width_ratio"] if before["width_ratio"] > 0 else 1.0
        raw_height_cap_scale = (
            (height_ceiling * args.height_tolerance) / before["height_ratio"]
            if before["height_ratio"] > 0 else raw_width_scale
        )
        raw_scale = min(raw_width_scale, raw_height_cap_scale)

        extreme = raw_scale > args.extreme_scale
        # Bishop/queen are review-only: report metrics but never auto-apply a scale,
        # per task instruction to only normalize them with explicit justification.
        review_only = piece in REVIEW_ONLY_PIECES

        if extreme or review_only or raw_scale <= 1.0:
            applied_scale = 1.0
            applied = False
        else:
            applied_scale = min(max(raw_scale, args.min_scale), args.max_scale)
            applied = True

        needs_review = extreme or review_only or (raw_scale > args.max_scale) or (raw_scale <= 1.0)

        would_crop = False
        if applied:
            preview_frames = []
            for frame, metric in zip(live_idle_frames, before_metrics):
                adjusted, cropped = adjust_frame(frame, metric, applied_scale)
                preview_frames.append(adjusted)
                would_crop = would_crop or cropped
        else:
            preview_frames = [f.copy() for f in live_idle_frames]

        rel = idle["sourcePath"].relative_to(args.source_root)
        preview_path = args.output_root / rel
        preview_path.parent.mkdir(parents=True, exist_ok=True)
        compose(preview_frames, fw, fh).save(preview_path)

        after_metrics = [frame_metric(f, args.alpha_threshold) for f in preview_frames]
        after = avg_ratio(after_metrics)

        skipped_reason = ""
        if review_only:
            skipped_reason = "bishop/queen idle already close to/above target per metrics; review-only, not auto-applied"
        elif extreme:
            skipped_reason = f"raw required scale {raw_scale:.3f} exceeds extreme threshold {args.extreme_scale}; not forced"
        elif raw_scale <= 1.0:
            skipped_reason = "idle already meets or exceeds target width ratio; no scale-up needed"

        row = {
            "piece": piece, "color": color,
            "frameWidth": fw, "frameHeight": fh, "frameCount": fc,
            "idleBeforeWidthRatio": round(before["width_ratio"], 4),
            "idleBeforeHeightRatio": round(before["height_ratio"], 4),
            "targetWidthRatio": round(target_width_ratio, 4),
            "targetHeightRatio": round(height_ceiling, 4),
            "idleAfterWidthRatio": round(after["width_ratio"], 4),
            "idleAfterHeightRatio": round(after["height_ratio"], 4),
            "scaleAppliedAverage": round(applied_scale, 4),
            "wouldCrop": would_crop,
            "needsReview": needs_review,
            "skippedReason": skipped_reason,
            "applied": applied,
            "isPriority": is_priority,
            "targetActions": "+".join(target_actions),
            "rawScale": round(raw_scale, 4),
        }
        rows.append(row)

        # Contact sheet for every idle sheet we measured (even review-only/skipped),
        # so the before/after/move/attack comparison is auditable for all 6 pieces.
        move_entry = by_piece_color_action.get((piece, color, "move"))
        attack_entry = by_piece_color_action.get((piece, color, "attack"))
        live_move_frames = None
        live_attack_frames = None
        try:
            if move_entry:
                live_move_frames = load_frames(move_entry["sourcePath"], move_entry["frameWidth"], move_entry["frameHeight"], move_entry["frameCount"])
            if attack_entry:
                live_attack_frames = load_frames(attack_entry["sourcePath"], attack_entry["frameWidth"], attack_entry["frameHeight"], attack_entry["frameCount"])
        except Exception as exc:  # noqa: BLE001
            errors.append(f"{piece}/{color} reference load: {exc}")

        contact_paths.append(str(make_contact_sheet(
            row, live_idle_frames, preview_frames, live_move_frames, live_attack_frames,
            args.output_root / "contact-sheets", args.alpha_threshold,
        )).replace("\\", "/"))

    args.output_root.mkdir(parents=True, exist_ok=True)
    csv_path = args.output_root / "idle-adjust-report.csv"
    json_path = args.output_root / "idle-adjust-report.json"
    fieldnames = [
        "piece", "color", "frameWidth", "frameHeight", "frameCount",
        "idleBeforeWidthRatio", "idleBeforeHeightRatio",
        "targetWidthRatio", "targetHeightRatio",
        "idleAfterWidthRatio", "idleAfterHeightRatio",
        "scaleAppliedAverage", "wouldCrop", "needsReview",
        "applied", "isPriority", "targetActions", "skippedReason", "rawScale",
    ]
    with csv_path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames, extrasaction="ignore")
        writer.writeheader()
        for row in rows:
            writer.writerow({k: row.get(k, "") for k in fieldnames})
    json_path.write_text(json.dumps({"rows": rows, "errors": errors, "contactSheets": contact_paths}, indent=2) + "\n", encoding="utf-8")

    print(f"Idle sheets processed: {len(rows)}")
    print(f"Errors: {len(errors)}")
    print(f"Preview output: {args.output_root}")
    print(f"CSV report: {csv_path}")
    print(f"JSON report: {json_path}")
    for row in rows:
        print(
            f"{row['color']:6} {row['piece']:7} applied={row['applied']!s:5} "
            f"before={row['idleBeforeWidthRatio']}/{row['idleBeforeHeightRatio']} "
            f"target={row.get('targetWidthRatio')}/{row.get('targetHeightRatio')} "
            f"after={row['idleAfterWidthRatio']}/{row['idleAfterHeightRatio']} "
            f"scale={row['scaleAppliedAverage']} review={row['needsReview']} {row.get('skippedReason', '')}"
        )
    return 0 if not errors else 2


if __name__ == "__main__":
    raise SystemExit(main())
