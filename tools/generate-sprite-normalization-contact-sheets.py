#!/usr/bin/env python3
"""Generate before/after contact sheets for normalized sprite previews.

This script reads the preview normalization report and renders visual review
sheets into shared/chess-assets-normalized-preview/contact-sheets/. It never
modifies original assets, normalized sprite sheets, the catalog, renderer code,
or CSS.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:
    print("Pillow is required. Install it with: python -m pip install Pillow", file=sys.stderr)
    raise SystemExit(1)


DEFAULT_CATALOG = Path("shared/chess-assets/sprite_catalog.json")
DEFAULT_REPORT = Path("shared/chess-assets-normalized-preview/normalization-report.json")
DEFAULT_OUTPUT_DIR = Path("shared/chess-assets-normalized-preview/contact-sheets")
ALPHA_THRESHOLD = 10
SAFE_SAMPLE_ACTIONS = {
    ("pawn", "move"),
    ("rook", "move"),
    ("queen", "move"),
    ("king", "move"),
}
REVIEW_FLAGS = {"needs_review", "extreme_scale", "dead_pose_warning"}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Create visual before/after contact sheets for sprite normalization review."
    )
    parser.add_argument("--catalog", type=Path, default=DEFAULT_CATALOG)
    parser.add_argument("--report", type=Path, default=DEFAULT_REPORT)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--alpha-threshold", type=int, default=ALPHA_THRESHOLD)
    return parser.parse_args()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def should_include(row: dict[str, Any]) -> bool:
    flags = set(str(row.get("flags", "")).split(";"))
    if flags & REVIEW_FLAGS:
        return True
    return (row["piece"], row["action"]) in SAFE_SAMPLE_ACTIONS


def selected_rows(report: dict[str, Any]) -> list[dict[str, Any]]:
    rows = [row for row in report["rows"] if should_include(row)]
    return sorted(rows, key=lambda row: (row["piece"], row["action"], row["color"]))


def split_frames(sheet: Image.Image, frame_width: int, frame_height: int, frame_count: int) -> list[Image.Image]:
    frames: list[Image.Image] = []
    for index in range(frame_count):
        left = index * frame_width
        frames.append(sheet.crop((left, 0, left + frame_width, frame_height)).convert("RGBA"))
    return frames


def visible_bbox(frame: Image.Image, alpha_threshold: int) -> tuple[int, int, int, int] | None:
    alpha = frame.getchannel("A").point(lambda value: 255 if value > alpha_threshold else 0)
    return alpha.getbbox()


def checkerboard(size: tuple[int, int], block: int = 12) -> Image.Image:
    width, height = size
    image = Image.new("RGBA", size, (35, 36, 39, 255))
    draw = ImageDraw.Draw(image)
    colors = ((49, 50, 55, 255), (29, 30, 34, 255))
    for y in range(0, height, block):
        for x in range(0, width, block):
            color = colors[((x // block) + (y // block)) % 2]
            draw.rectangle((x, y, min(x + block, width), min(y + block, height)), fill=color)
    return image


def draw_bbox(draw: ImageDraw.ImageDraw, bbox: tuple[int, int, int, int] | None, scale: int, offset: tuple[int, int], color: tuple[int, int, int]) -> None:
    if bbox is None:
        return
    x, y = offset
    left, top, right, bottom = bbox
    draw.rectangle(
        (
            x + left * scale,
            y + top * scale,
            x + right * scale - 1,
            y + bottom * scale - 1,
        ),
        outline=color,
        width=2,
    )


def draw_centerlines(draw: ImageDraw.ImageDraw, offset: tuple[int, int], display_size: tuple[int, int], scale: int, frame_width: int, frame_height: int) -> None:
    x, y = offset
    display_width, display_height = display_size
    center_x = x + (frame_width * scale) // 2
    center_y = y + (frame_height * scale) // 2
    draw.line((center_x, y, center_x, y + display_height), fill=(99, 179, 237), width=1)
    draw.line((x, center_y, x + display_width, center_y), fill=(99, 179, 237), width=1)


def cell_image(
    frame: Image.Image,
    scale: int,
    padding: int,
    bbox: tuple[int, int, int, int] | None,
    bbox_color: tuple[int, int, int] | None,
    show_centerlines: bool,
) -> Image.Image:
    display_size = (frame.width * scale, frame.height * scale)
    cell = checkerboard((display_size[0] + padding * 2, display_size[1] + padding * 2))
    resized = frame.resize(display_size, Image.Resampling.NEAREST)
    cell.alpha_composite(resized, (padding, padding))
    draw = ImageDraw.Draw(cell)
    if show_centerlines:
        draw_centerlines(draw, (padding, padding), display_size, scale, frame.width, frame.height)
    if bbox_color is not None:
        draw_bbox(draw, bbox, scale, (padding, padding), bbox_color)
    return cell


def guide_cell(
    original: Image.Image,
    normalized: Image.Image,
    scale: int,
    padding: int,
    original_bbox: tuple[int, int, int, int] | None,
    normalized_bbox: tuple[int, int, int, int] | None,
) -> Image.Image:
    display_size = (normalized.width * scale, normalized.height * scale)
    cell = checkerboard((display_size[0] + padding * 2, display_size[1] + padding * 2))
    original_layer = Image.new("RGBA", display_size, (0, 0, 0, 0))
    normalized_layer = Image.new("RGBA", display_size, (0, 0, 0, 0))
    original_layer.alpha_composite(original.resize(display_size, Image.Resampling.NEAREST))
    normalized_layer.alpha_composite(normalized.resize(display_size, Image.Resampling.NEAREST))
    original_layer.putalpha(original_layer.getchannel("A").point(lambda value: int(value * 0.35)))
    cell.alpha_composite(original_layer, (padding, padding))
    cell.alpha_composite(normalized_layer, (padding, padding))

    draw = ImageDraw.Draw(cell)
    draw_centerlines(draw, (padding, padding), display_size, scale, normalized.width, normalized.height)
    draw_bbox(draw, original_bbox, scale, (padding, padding), (239, 68, 68))
    draw_bbox(draw, normalized_bbox, scale, (padding, padding), (34, 197, 94))
    return cell


def safe_filename(row: dict[str, Any]) -> str:
    raw = f"{row['piece']}_{row['color']}_{row['action']}_{row['flags']}"
    return re.sub(r"[^a-zA-Z0-9_.-]+", "-", raw).strip("-") + ".png"


def load_font(size: int) -> ImageFont.ImageFont:
    candidates = [
        Path("C:/Windows/Fonts/segoeui.ttf"),
        Path("C:/Windows/Fonts/arial.ttf"),
    ]
    for candidate in candidates:
        if candidate.exists():
            return ImageFont.truetype(str(candidate), size)
    return ImageFont.load_default()


def draw_text(draw: ImageDraw.ImageDraw, xy: tuple[int, int], text: str, font: ImageFont.ImageFont, fill: tuple[int, int, int] = (232, 226, 213)) -> None:
    draw.text(xy, text, font=font, fill=fill)


def make_contact_sheet(row: dict[str, Any], output_dir: Path, alpha_threshold: int) -> Path:
    source_path = Path(row["sourcePath"])
    output_path = Path(row["outputPath"])
    frame_width = int(row["frameWidth"])
    frame_height = int(row["frameHeight"])
    frame_count = int(row["frameCount"])

    with Image.open(source_path) as original_sheet, Image.open(output_path) as normalized_sheet:
        original_sheet = original_sheet.convert("RGBA")
        normalized_sheet = normalized_sheet.convert("RGBA")
        expected_size = (frame_width * frame_count, frame_height)
        if original_sheet.size != expected_size:
            raise ValueError(f"{source_path} has {original_sheet.size}, expected {expected_size}")
        if normalized_sheet.size != expected_size:
            raise ValueError(f"{output_path} has {normalized_sheet.size}, expected {expected_size}")

        original_frames = split_frames(original_sheet, frame_width, frame_height, frame_count)
        normalized_frames = split_frames(normalized_sheet, frame_width, frame_height, frame_count)

    display_indices = list(range(frame_count))
    if frame_count > 9:
        display_indices = list(range(8)) + [frame_count - 1]

    scale = 2 if max(frame_width, frame_height) <= 128 else 1
    padding = 10
    label_width = 128
    gap = 12
    title_height = 116
    row_gap = 30
    cell_width = frame_width * scale + padding * 2
    cell_height = frame_height * scale + padding * 2
    content_width = label_width + (len(display_indices) * cell_width) + ((len(display_indices) - 1) * gap)
    sheet_width = max(content_width + 32, 900)
    sheet_height = title_height + (cell_height * 3) + (row_gap * 2) + 42

    sheet = Image.new("RGBA", (sheet_width, sheet_height), (16, 18, 22, 255))
    draw = ImageDraw.Draw(sheet)
    title_font = load_font(22)
    meta_font = load_font(14)
    label_font = load_font(16)
    small_font = load_font(12)

    title = f"{row['piece']} / {row['color']} / {row['action']}"
    meta = (
        f"frame {frame_width}x{frame_height} | count {frame_count} | "
        f"flags {row['flags']} | avg scale {float(row['scaleAppliedAverage']):.3f}"
    )
    draw_text(draw, (16, 16), title, title_font)
    draw_text(draw, (16, 48), meta, meta_font, (205, 196, 174))
    draw_text(draw, (16, 72), "Red bbox: original visible alpha. Green bbox: normalized visible alpha. Blue lines: frame center.", small_font, (169, 176, 190))

    row_specs = [
        ("ORIGINAL", original_frames, (239, 68, 68)),
        ("NORMALIZED", normalized_frames, (34, 197, 94)),
        ("BBOX GUIDE", normalized_frames, None),
    ]

    y = title_height
    for row_label, frames, bbox_color in row_specs:
        draw_text(draw, (16, y + 12), row_label, label_font)
        x = label_width
        for frame_index in display_indices:
            original_bbox = visible_bbox(original_frames[frame_index], alpha_threshold)
            normalized_bbox = visible_bbox(normalized_frames[frame_index], alpha_threshold)
            if row_label == "BBOX GUIDE":
                cell = guide_cell(
                    original_frames[frame_index],
                    normalized_frames[frame_index],
                    scale,
                    padding,
                    original_bbox,
                    normalized_bbox,
                )
            else:
                active_bbox = original_bbox if row_label == "ORIGINAL" else normalized_bbox
                cell = cell_image(frames[frame_index], scale, padding, active_bbox, bbox_color, True)
            sheet.alpha_composite(cell, (x, y))
            draw_text(draw, (x + 4, y + cell_height + 4), f"f{frame_index + 1}", small_font, (169, 176, 190))
            x += cell_width + gap
        y += cell_height + row_gap

    output_dir.mkdir(parents=True, exist_ok=True)
    sheet_path = output_dir / safe_filename(row)
    sheet.save(sheet_path)
    return sheet_path


def write_index(output_dir: Path, generated: list[dict[str, Any]], errors: list[str]) -> Path:
    output_dir.mkdir(parents=True, exist_ok=True)
    index_path = output_dir / "index.md"
    lines = [
        "# Sprite Normalization Contact Sheets",
        "",
        "Recommendation values are placeholders for human review.",
        "",
        "| Sheet | Piece | Color | Action | Flags | Avg scale | Recommendation |",
        "|---|---|---|---|---|---:|---|",
    ]
    for item in generated:
        path = Path(item["sheet"]).name
        row = item["row"]
        recommendation = "likely safe" if row["flags"] == "safe" else "review"
        lines.append(
            f"| [{path}]({path}) | {row['piece']} | {row['color']} | {row['action']} | "
            f"{row['flags']} | {float(row['scaleAppliedAverage']):.3f} | {recommendation} |"
        )

    if errors:
        lines.extend(["", "## Generation Errors", ""])
        for error in errors:
            lines.append(f"- {error}")

    index_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return index_path


def main() -> int:
    args = parse_args()
    catalog = load_json(args.catalog)
    report = load_json(args.report)
    rows = selected_rows(report)

    pre_hashes: dict[Path, str] = {}
    for row in rows:
        pre_hashes[Path(row["sourcePath"])] = sha256(Path(row["sourcePath"]))
        pre_hashes[Path(row["outputPath"])] = sha256(Path(row["outputPath"]))
    pre_hashes[args.catalog] = sha256(args.catalog)
    pre_hashes[args.report] = sha256(args.report)

    generated: list[dict[str, Any]] = []
    errors: list[str] = []
    for row in rows:
        try:
            sheet_path = make_contact_sheet(row, args.output_dir, args.alpha_threshold)
            generated.append({"sheet": str(sheet_path).replace("\\", "/"), "row": row})
        except Exception as exc:  # Report all failures while still producing useful sheets.
            errors.append(f"{row['color']} {row['piece']} {row['action']}: {exc}")

    index_path = write_index(args.output_dir, generated, errors)

    modified_inputs = []
    for path, before_hash in pre_hashes.items():
        after_hash = sha256(path)
        if before_hash != after_hash:
            modified_inputs.append(str(path))

    included_actions = sorted({f"{item['row']['piece']} {item['row']['action']}" for item in generated})
    cataloged_count = len(catalog.get("spriteSheets", {}))

    print(f"Cataloged sheets in source catalog: {cataloged_count}")
    print(f"Selected rows: {len(rows)}")
    print(f"Contact sheets generated: {len(generated)}")
    print(f"Errors: {len(errors)}")
    for error in errors:
        print(f"  - {error}")
    print(f"Output folder: {args.output_dir}")
    print(f"Index: {index_path}")
    print(f"Input assets modified: {len(modified_inputs)}")
    for path in modified_inputs:
        print(f"  - {path}")
    print("Included piece/actions:")
    for action in included_actions:
        print(f"  - {action}")

    return 0 if not errors and not modified_inputs else 2


if __name__ == "__main__":
    raise SystemExit(main())
