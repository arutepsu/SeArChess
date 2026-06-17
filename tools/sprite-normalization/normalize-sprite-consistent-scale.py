#!/usr/bin/env python3
"""Normalize every cataloged chess sprite sheet toward a per-piece/color canonical
visual target, so a single piece (e.g. white pawn) keeps a consistent perceived
size across all of its own animation states (idle/move/attack/attack1/hit/dead).

Does not assume any single action is the reference. For each piece/color:
  1. Audit every action's visible-alpha bbox (width/height ratio, area ratio,
     center offset, bottom/baseline Y) per frame, averaged per action.
  2. Compute a canonical target as the MEDIAN of the per-action averages across
     the "upright" actions (idle, move, attack, attack1, hit) — robust against
     one extreme action skewing the target. `dead` is excluded from the target
     computation (its pose is legitimately different) but IS normalized in
     step 3, using a width/area-dominant scale and the same baseline anchor.
  3. Resize+reposition every frame of every action (including idle and hit)
     toward that target: uniform per-frame scale (aspect-preserving), bottom
     edge aligned to the canonical baseline Y, horizontally centered on the
     canonical center X. Scale clamped to [min-scale, max-scale]; if a frame
     cannot fit the target position without cropping, scale is reduced until
     it fits (never crops); if the *required* (pre-clamp) scale is extreme,
     the frame is left unchanged and flagged instead of forced.

Writes a full preview tree, CSV/JSON reports, and per-piece/color contact
sheets. Does not touch live assets, the catalog, or any renderer/CSS code.
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import sys
from pathlib import Path
from statistics import mean, median
from typing import Any

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:
    print("Pillow is required. Install it with: python -m pip install Pillow", file=sys.stderr)
    raise SystemExit(1)


CATALOG_KEY_RE = re.compile(r"^[^/]+/(?P<color>white|black)_(?P<piece>[a-z]+)_(?P<action>[a-z0-9]+)$")
UPRIGHT_ACTIONS = ("idle", "move", "attack", "attack1", "hit")
ALL_ACTIONS = ("idle", "move", "attack", "attack1", "hit", "dead")
ALPHA_THRESHOLD = 10
MIN_SCALE = 0.80
MAX_SCALE = 1.25
EXTREME_LOW = 0.55
EXTREME_HIGH = 1.70
# Non-dead actions: balance width and height equally toward the target.
UPRIGHT_WEIGHT_HEIGHT = 0.5
UPRIGHT_WEIGHT_WIDTH = 0.5
# Dead: width/area is the stronger, more reliable signal — a fallen pose's
# height is expected to legitimately differ from standing height.
DEAD_WEIGHT_HEIGHT = 0.25
DEAD_WEIGHT_WIDTH = 0.75


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Normalize all sprite actions toward a per-piece canonical scale.")
    parser.add_argument("--source-root", type=Path, default=Path("shared/chess-assets"))
    parser.add_argument("--output-root", type=Path, default=Path("shared/chess-assets-consistent-scale-preview"))
    parser.add_argument("--alpha-threshold", type=int, default=ALPHA_THRESHOLD)
    parser.add_argument("--min-scale", type=float, default=MIN_SCALE)
    parser.add_argument("--max-scale", type=float, default=MAX_SCALE)
    parser.add_argument("--extreme-low", type=float, default=EXTREME_LOW)
    parser.add_argument("--extreme-high", type=float, default=EXTREME_HIGH)
    return parser.parse_args()


def load_catalog(source_root: Path) -> dict[str, Any]:
    return json.loads((source_root / "sprite_catalog.json").read_text(encoding="utf-8-sig"))


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


def load_frames(path: Path, fw: int, fh: int, fc: int) -> list[Image.Image]:
    with Image.open(path) as sheet:
        sheet = sheet.convert("RGBA")
        if sheet.size != (fw * fc, fh):
            raise ValueError(f"Bad sheet dimensions: {path} expected {(fw * fc, fh)} got {sheet.size}")
        return split_frames(sheet, fw, fh, fc)


def visible_bbox(frame: Image.Image, alpha_threshold: int) -> tuple[int, int, int, int] | None:
    alpha = frame.getchannel("A").point(lambda value: 255 if value > alpha_threshold else 0)
    return alpha.getbbox()


def opaque_pixel_ratio(frame: Image.Image, alpha_threshold: int) -> float:
    alpha = frame.getchannel("A").point(lambda value: 1 if value > alpha_threshold else 0)
    opaque = sum(alpha.getdata())
    total = frame.width * frame.height
    return opaque / total if total else 0.0


def frame_metric(frame: Image.Image, alpha_threshold: int) -> dict[str, Any]:
    bbox = visible_bbox(frame, alpha_threshold)
    fw, fh = frame.width, frame.height
    if bbox is None:
        return {
            "width_ratio": 0.0, "height_ratio": 0.0, "area_ratio": 0.0,
            "center_x": fw / 2, "center_y": fh / 2,
            "center_x_offset": 0.0, "center_y_offset": 0.0,
            "bottom_y_ratio": 0.5, "top_y_ratio": 0.5,
            "bbox": None,
        }
    left, top, right, bottom = bbox
    return {
        "width_ratio": (right - left) / fw,
        "height_ratio": (bottom - top) / fh,
        "area_ratio": opaque_pixel_ratio(frame, alpha_threshold),
        "center_x": (left + right) / 2,
        "center_y": (top + bottom) / 2,
        "center_x_offset": ((left + right) / 2 - fw / 2) / fw,
        "center_y_offset": ((top + bottom) / 2 - fh / 2) / fh,
        "bottom_y_ratio": bottom / fh,
        "top_y_ratio": top / fh,
        "bbox": bbox,
    }


def avg_metric(metrics: list[dict[str, Any]]) -> dict[str, float]:
    keys = ["width_ratio", "height_ratio", "area_ratio", "center_x_offset", "center_y_offset", "bottom_y_ratio", "top_y_ratio"]
    return {k: mean(m[k] for m in metrics) for k in keys}


def fit_scale_baseline(
    scale: float, bbox_w: float, bbox_h: float,
    target_center_x: float, target_bottom_y: float, fw: int, fh: int,
) -> tuple[float, bool]:
    """Largest scale that keeps a bbox_w x bbox_h crop, resized by `scale` and
    positioned with its bottom edge at target_bottom_y and horizontally
    centered at target_center_x, fully inside the frame."""
    if bbox_w <= 0 or bbox_h <= 0:
        return scale, False
    room_left = target_center_x
    room_right = fw - target_center_x
    max_width = 2 * min(room_left, room_right)
    max_height = target_bottom_y  # top must stay >= 0; bottom is pinned at target_bottom_y
    max_fit = min(max_width / bbox_w, max_height / bbox_h) if bbox_w > 0 and bbox_h > 0 else scale
    if scale <= max_fit:
        return scale, False
    return max(0.01, max_fit), True


def adjust_frame(
    frame: Image.Image,
    metric: dict[str, Any],
    target: dict[str, float],
    weight_height: float,
    weight_width: float,
    min_scale: float,
    max_scale: float,
    extreme_low: float,
    extreme_high: float,
) -> dict[str, Any]:
    bbox = metric["bbox"]
    fw, fh = frame.width, frame.height
    if bbox is None or metric["width_ratio"] <= 0 or metric["height_ratio"] <= 0:
        return {"frame": frame.copy(), "scale": 1.0, "would_crop": False, "extreme": False, "applied": False}

    height_scale = target["height_ratio"] / metric["height_ratio"]
    width_scale = target["width_ratio"] / metric["width_ratio"]
    raw_scale = weight_height * height_scale + weight_width * width_scale

    extreme = raw_scale < extreme_low or raw_scale > extreme_high
    if extreme:
        return {"frame": frame.copy(), "scale": 1.0, "would_crop": False, "extreme": True, "applied": False}

    clamped_scale = min(max(raw_scale, min_scale), max_scale)

    target_center_x_abs = fw / 2 + target["center_x_offset"] * fw
    target_bottom_y_abs = target["bottom_y_ratio"] * fh

    bbox_w = bbox[2] - bbox[0]
    bbox_h = bbox[3] - bbox[1]
    fitted_scale, would_crop = fit_scale_baseline(
        clamped_scale, bbox_w, bbox_h, target_center_x_abs, target_bottom_y_abs, fw, fh
    )

    crop = frame.crop(bbox)
    new_w = max(1, round(crop.width * fitted_scale))
    new_h = max(1, round(crop.height * fitted_scale))
    resized = crop.resize((new_w, new_h), Image.Resampling.LANCZOS)

    paste_x = round(target_center_x_abs - new_w / 2)
    paste_y = round(target_bottom_y_abs - new_h)
    # Final safety clamp — guards against rounding pushing 1px outside bounds.
    paste_x = max(0, min(paste_x, fw - new_w)) if new_w <= fw else 0
    paste_y = max(0, min(paste_y, fh - new_h)) if new_h <= fh else 0

    output = Image.new("RGBA", (fw, fh), (0, 0, 0, 0))
    output.alpha_composite(resized, (paste_x, paste_y))
    return {"frame": output, "scale": fitted_scale, "would_crop": would_crop, "extreme": False, "applied": True}


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


def draw_guides(cd: ImageDraw.ImageDraw, fw: int, fh: int, scale: int, pad: int, target: dict[str, float]) -> None:
    cx = pad + round((fw / 2 + target["center_x_offset"] * fw) * scale)
    by = pad + round(target["bottom_y_ratio"] * fh * scale)
    cd.line((cx, pad, cx, pad + fh * scale), fill=(168, 85, 247))
    cd.line((pad, by, pad + fw * scale, by), fill=(250, 204, 21))


def draw_bbox(draw: ImageDraw.ImageDraw, bbox: tuple[int, int, int, int] | None, scale: int, x: int, y: int, color: tuple[int, int, int]) -> None:
    if bbox is None:
        return
    left, top, right, bottom = bbox
    draw.rectangle((x + left * scale, y + top * scale, x + right * scale - 1, y + bottom * scale - 1), outline=color, width=2)


def make_contact_sheet(
    piece: str, color: str, fw: int, fh: int, target: dict[str, float],
    action_frames: dict[str, tuple[list[Image.Image], list[Image.Image]]],
    action_rows: dict[str, dict[str, Any]],
    output_dir: Path, alpha_threshold: int,
) -> Path:
    scale = 2 if max(fw, fh) <= 128 else 1
    pad = 10
    cell_w = fw * scale + pad * 2
    cell_h = fh * scale + pad * 2
    label_w = 150
    gap = 10
    title_h = 84
    section_gap = 22

    max_frames = max((len(v[0]) for v in action_frames.values()), default=1)
    width = max(960, label_w + max_frames * cell_w + (max_frames - 1) * gap + 28)
    # 3 rows per action (live, preview, bbox-guide) + a header line per action.
    height = title_h + sum((cell_h * 3 + section_gap + 18) for _ in action_frames)
    sheet = Image.new("RGBA", (width, height), (16, 18, 22, 255))
    draw = ImageDraw.Draw(sheet)
    draw.text((16, 12), f"{piece} / {color} — consistent scale", font=font(22), fill=(238, 230, 210))
    draw.text(
        (16, 42),
        f"canonical target width/height: {target['width_ratio']:.3f}/{target['height_ratio']:.3f}  "
        f"bottomY: {target['bottom_y_ratio']:.3f}  centerXoff: {target['center_x_offset']:.3f}",
        font=font(14), fill=(205, 196, 174),
    )
    draw.text((16, 62), "Yellow line: canonical baseline. Purple line: canonical center X.", font=font(12), fill=(169, 176, 190))

    y = title_h
    for action in ALL_ACTIONS:
        if action not in action_frames:
            continue
        live_frames, preview_frames = action_frames[action]
        row = action_rows.get(action, {})
        draw.text(
            (16, y), f"{action.upper()}  scale avg/min/max: {row.get('scaleAppliedAverage')}/{row.get('scaleAppliedMin')}/{row.get('scaleAppliedMax')}  "
            f"crop={row.get('wouldCrop')} extreme={row.get('extremeScale')} review={row.get('needsReview')}",
            font=font(13), fill=(238, 230, 210),
        )
        y += 18
        for label, frames, color_box in (("live", live_frames, (239, 68, 68)), ("preview", preview_frames, (34, 197, 94))):
            x = label_w
            for frame in frames:
                cell = checker((cell_w, cell_h))
                resized = frame.resize((fw * scale, fh * scale), Image.Resampling.NEAREST)
                cell.alpha_composite(resized, (pad, pad))
                cd = ImageDraw.Draw(cell)
                draw_guides(cd, fw, fh, scale, pad, target)
                bbox = visible_bbox(frame, alpha_threshold)
                draw_bbox(cd, bbox, scale, pad, pad, color_box)
                sheet.alpha_composite(cell, (x, y))
                x += cell_w + gap
            y += cell_h
        # bbox guide row: live(red) vs preview(green) overlaid, using preview frame as backdrop
        x = label_w
        for live_f, prev_f in zip(live_frames, preview_frames):
            cell = checker((cell_w, cell_h))
            resized = prev_f.resize((fw * scale, fh * scale), Image.Resampling.NEAREST)
            cell.alpha_composite(resized, (pad, pad))
            cd = ImageDraw.Draw(cell)
            draw_guides(cd, fw, fh, scale, pad, target)
            draw_bbox(cd, visible_bbox(live_f, alpha_threshold), scale, pad, pad, (239, 68, 68))
            draw_bbox(cd, visible_bbox(prev_f, alpha_threshold), scale, pad, pad, (34, 197, 94))
            sheet.alpha_composite(cell, (x, y))
            x += cell_w + gap
        y += cell_h + section_gap

    output_dir.mkdir(parents=True, exist_ok=True)
    path = output_dir / f"{piece}_{color}.png"
    sheet.save(path)
    return path


def classify(row: dict[str, Any]) -> str:
    if row["extremeScale"]:
        return "reject_for_now"
    if row["wouldCrop"] or row["needsReview"]:
        return "needs_human_review"
    return "safe_to_apply"


def main() -> int:
    args = parse_args()
    catalog = load_catalog(args.source_root)
    clip_specs = catalog["clipSpecs"]

    entries: list[dict[str, Any]] = []
    by_pca: dict[tuple[str, str, str], dict[str, Any]] = {}
    for key, sheet in catalog["spriteSheets"].items():
        parsed = parse_key(key)
        spec = clip_specs[sheet["clipSpec"]]
        entry = {
            **parsed,
            "key": key,
            "sourcePath": source_path(args.source_root, sheet["path"]),
            "catalogPath": sheet["path"],
            "frameWidth": int(spec["frameSize"]["width"]),
            "frameHeight": int(spec["frameSize"]["height"]),
            "frameCount": int(spec["frameCount"]),
        }
        entries.append(entry)
        by_pca[(entry["piece"], entry["color"], entry["action"])] = entry

    piece_colors = sorted({(e["piece"], e["color"]) for e in entries})

    rows: list[dict[str, Any]] = []
    errors: list[str] = []
    contact_paths: list[str] = []
    index_lines: list[str] = []

    for piece, color in piece_colors:
        available_actions = [a for a in ALL_ACTIONS if (piece, color, a) in by_pca]
        live_frames_by_action: dict[str, list[Image.Image]] = {}
        metrics_by_action: dict[str, list[dict[str, Any]]] = {}
        avg_by_action: dict[str, dict[str, float]] = {}

        load_failed = False
        for action in available_actions:
            entry = by_pca[(piece, color, action)]
            try:
                frames = load_frames(entry["sourcePath"], entry["frameWidth"], entry["frameHeight"], entry["frameCount"])
            except Exception as exc:  # noqa: BLE001
                errors.append(f"{piece}/{color}/{action}: {exc}")
                load_failed = True
                continue
            live_frames_by_action[action] = frames
            metrics = [frame_metric(f, args.alpha_threshold) for f in frames]
            metrics_by_action[action] = metrics
            avg_by_action[action] = avg_metric(metrics)

        if load_failed or not avg_by_action:
            continue

        upright_present = [a for a in UPRIGHT_ACTIONS if a in avg_by_action]
        if not upright_present:
            # Only dead exists (shouldn't happen) — fall back to whatever is present.
            upright_present = list(avg_by_action.keys())

        target = {
            "width_ratio": median(avg_by_action[a]["width_ratio"] for a in upright_present),
            "height_ratio": median(avg_by_action[a]["height_ratio"] for a in upright_present),
            "center_x_offset": median(avg_by_action[a]["center_x_offset"] for a in upright_present),
            "bottom_y_ratio": median(avg_by_action[a]["bottom_y_ratio"] for a in upright_present),
        }

        fw = by_pca[(piece, color, available_actions[0])]["frameWidth"]
        fh = by_pca[(piece, color, available_actions[0])]["frameHeight"]

        action_frames_for_sheet: dict[str, tuple[list[Image.Image], list[Image.Image]]] = {}
        action_rows: dict[str, dict[str, Any]] = {}

        for action in available_actions:
            entry = by_pca[(piece, color, action)]
            fw_a, fh_a, fc_a = entry["frameWidth"], entry["frameHeight"], entry["frameCount"]
            frames = live_frames_by_action[action]
            metrics = metrics_by_action[action]
            before = avg_by_action[action]

            is_dead = action == "dead"
            wh, ww = (DEAD_WEIGHT_HEIGHT, DEAD_WEIGHT_WIDTH) if is_dead else (UPRIGHT_WEIGHT_HEIGHT, UPRIGHT_WEIGHT_WIDTH)

            results = [adjust_frame(f, m, target, wh, ww, args.min_scale, args.max_scale, args.extreme_low, args.extreme_high)
                       for f, m in zip(frames, metrics)]
            preview_frames = [r["frame"] for r in results]
            scales = [r["scale"] for r in results]
            would_crop = any(r["would_crop"] for r in results)
            extreme = any(r["extreme"] for r in results)

            rel = entry["sourcePath"].relative_to(args.source_root)
            preview_path = args.output_root / "images" / rel.relative_to("images") if str(rel).startswith("images") else args.output_root / rel
            preview_path = args.output_root / rel
            preview_path.parent.mkdir(parents=True, exist_ok=True)
            compose(preview_frames, fw_a, fh_a).save(preview_path)

            after_metrics = [frame_metric(f, args.alpha_threshold) for f in preview_frames]
            after = avg_metric(after_metrics)

            needs_review = would_crop or extreme or any(not r["applied"] for r in results)

            row = {
                "piece": piece, "color": color, "action": action,
                "frameWidth": fw_a, "frameHeight": fh_a, "frameCount": fc_a,
                "beforeVisibleWidthRatio": round(before["width_ratio"], 4),
                "beforeVisibleHeightRatio": round(before["height_ratio"], 4),
                "beforeAreaRatio": round(before["area_ratio"], 4),
                "beforeCenterOffsetX": round(before["center_x_offset"], 4),
                "beforeCenterOffsetY": round(before["center_y_offset"], 4),
                "beforeBottomY": round(before["bottom_y_ratio"], 4),
                "targetVisibleWidthRatio": round(target["width_ratio"], 4),
                "targetVisibleHeightRatio": round(target["height_ratio"], 4),
                "targetCenterOffsetX": round(target["center_x_offset"], 4),
                "targetBottomY": round(target["bottom_y_ratio"], 4),
                "afterVisibleWidthRatio": round(after["width_ratio"], 4),
                "afterVisibleHeightRatio": round(after["height_ratio"], 4),
                "afterAreaRatio": round(after["area_ratio"], 4),
                "afterCenterOffsetX": round(after["center_x_offset"], 4),
                "afterCenterOffsetY": round(after["center_y_offset"], 4),
                "afterBottomY": round(after["bottom_y_ratio"], 4),
                "scaleAppliedAverage": round(mean(scales), 4),
                "scaleAppliedMin": round(min(scales), 4),
                "scaleAppliedMax": round(max(scales), 4),
                "wouldCrop": would_crop,
                "extremeScale": extreme,
                "needsReview": needs_review,
                "skippedReason": "extreme scale required; frame(s) left unchanged" if extreme else "",
                "sourcePath": str(entry["sourcePath"]).replace("\\", "/"),
                "previewPath": str(preview_path).replace("\\", "/"),
            }
            row["classification"] = classify(row)
            rows.append(row)
            action_rows[action] = row
            action_frames_for_sheet[action] = (frames, preview_frames)

        contact_path = make_contact_sheet(
            piece, color, fw, fh, target, action_frames_for_sheet, action_rows,
            args.output_root / "contact-sheets", args.alpha_threshold,
        )
        contact_paths.append(str(contact_path).replace("\\", "/"))

        classes = [action_rows[a]["classification"] for a in available_actions]
        biggest = max(available_actions, key=lambda a: abs(action_rows[a]["scaleAppliedAverage"] - 1.0))
        mechanically_safe = all(c == "safe_to_apply" for c in classes)
        index_lines.append(
            f"## {piece} / {color}\n\n"
            f"- Canonical target: width={target['width_ratio']:.3f} height={target['height_ratio']:.3f} "
            f"bottomY={target['bottom_y_ratio']:.3f} centerXoff={target['center_x_offset']:.3f}\n"
            f"- Biggest correction: **{biggest}** (scale avg {action_rows[biggest]['scaleAppliedAverage']})\n"
            f"- Actions needing review: {', '.join(a for a in available_actions if action_rows[a]['classification'] != 'safe_to_apply') or 'none'}\n"
            f"- Mechanically safe overall: **{mechanically_safe}**\n"
            f"- Contact sheet: `contact-sheets/{piece}_{color}.png`\n"
        )

    args.output_root.mkdir(parents=True, exist_ok=True)
    csv_path = args.output_root / "consistent-scale-report.csv"
    json_path = args.output_root / "consistent-scale-report.json"
    fieldnames = [
        "piece", "color", "action", "frameWidth", "frameHeight", "frameCount",
        "beforeVisibleWidthRatio", "beforeVisibleHeightRatio", "beforeAreaRatio",
        "beforeCenterOffsetX", "beforeCenterOffsetY", "beforeBottomY",
        "targetVisibleWidthRatio", "targetVisibleHeightRatio", "targetCenterOffsetX", "targetBottomY",
        "afterVisibleWidthRatio", "afterVisibleHeightRatio", "afterAreaRatio",
        "afterCenterOffsetX", "afterCenterOffsetY", "afterBottomY",
        "scaleAppliedAverage", "scaleAppliedMin", "scaleAppliedMax",
        "wouldCrop", "extremeScale", "needsReview", "skippedReason", "classification",
    ]
    with csv_path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames, extrasaction="ignore")
        writer.writeheader()
        for row in rows:
            writer.writerow({k: row.get(k, "") for k in fieldnames})
    json_path.write_text(json.dumps({"rows": rows, "errors": errors, "contactSheets": contact_paths}, indent=2) + "\n", encoding="utf-8")

    index_path = args.output_root / "contact-sheets" / "index.md"
    index_path.parent.mkdir(parents=True, exist_ok=True)
    safe_count = sum(1 for r in rows if r["classification"] == "safe_to_apply")
    review_count = sum(1 for r in rows if r["classification"] == "needs_human_review")
    reject_count = sum(1 for r in rows if r["classification"] == "reject_for_now")
    index_path.write_text(
        "# Consistent-scale normalization — contact sheet index\n\n"
        f"Total piece/color/action rows: {len(rows)}\n\n"
        f"- safe_to_apply: {safe_count}\n"
        f"- needs_human_review: {review_count}\n"
        f"- reject_for_now: {reject_count}\n\n"
        "---\n\n" + "\n".join(index_lines),
        encoding="utf-8",
    )

    print(f"Sheets processed: {len(rows)} across {len(piece_colors)} piece/colors")
    print(f"Errors: {len(errors)}")
    print(f"safe_to_apply={safe_count} needs_human_review={review_count} reject_for_now={reject_count}")
    print(f"Preview output: {args.output_root}")
    print(f"CSV report: {csv_path}")
    print(f"JSON report: {json_path}")
    print(f"Index: {index_path}")
    for row in rows:
        print(
            f"{row['piece']:7} {row['color']:6} {row['action']:7} "
            f"before(w/h)={row['beforeVisibleWidthRatio']}/{row['beforeVisibleHeightRatio']} "
            f"target(w/h)={row['targetVisibleWidthRatio']}/{row['targetVisibleHeightRatio']} "
            f"after(w/h)={row['afterVisibleWidthRatio']}/{row['afterVisibleHeightRatio']} "
            f"scale(avg/min/max)={row['scaleAppliedAverage']}/{row['scaleAppliedMin']}/{row['scaleAppliedMax']} "
            f"crop={row['wouldCrop']} extreme={row['extremeScale']} class={row['classification']}"
        )
    return 0 if not errors else 2


if __name__ == "__main__":
    raise SystemExit(main())
