import { useEffect, useMemo, useState } from "react";
import type { PieceCode } from "../../api/types";
import { loadSpriteCatalog, type ClipSpec, type SpriteCatalog, type SpriteSheetSpec } from "../../assets/spriteCatalog";
import { idleFps } from "../../animation/animationConfig";
import { backgroundPositionFor } from "./animationHelpers";
import { PIECE_SCALE, PIECE_VISUAL_OFFSET_Y, assetKeyFor, pieceDescriptor, pieceTransform } from "./types";
import type { VisualState } from "./types";

// Slower than the in-game idle loop (idleFps) — these previews are inspected at rest,
// not glanced at mid-game, so a slower cadence reads more clearly.
const previewFps = idleFps / 2;

/**
 * Resolves a continuously looping preview style for a single piece's sprite sheet
 * (idle, move, attack, hit, or dead), outside of a full ChessBoard/animation sequence.
 * For states with multiple segments in the catalog's statePlayback (e.g. "attack" is
 * attack + attack1), the segments are stitched into one continuous loop, matching how
 * ChessBoard plays them during a real attack. Returns null when the catalog has no
 * sheet for the requested piece/state.
 */
export function usePieceSpriteAnimation(
  piece: PieceCode,
  state: VisualState = "idle",
  scale: number = PIECE_SCALE
): React.CSSProperties | null {
  const [spriteCatalog, setSpriteCatalog] = useState<SpriteCatalog | null>(null);
  const [frameTick, setFrameTick] = useState(0);

  useEffect(() => {
    let active = true;
    loadSpriteCatalog()
      .then((catalog) => { if (active) setSpriteCatalog(catalog); })
      .catch(() => { if (active) setSpriteCatalog(null); });
    return () => { active = false; };
  }, []);

  useEffect(() => {
    let frame: number;
    const tick = () => {
      setFrameTick(Math.floor((performance.now() / 1000) * previewFps));
      frame = requestAnimationFrame(tick);
    };
    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  }, []);

  return useMemo<React.CSSProperties | null>(() => {
    if (!spriteCatalog) return null;

    const assetKey = assetKeyFor(piece, state);
    const segmentKeys = spriteCatalog.statePlayback[assetKey]?.segments ?? [assetKey];
    const segments = segmentKeys
      .map((key) => {
        const sheet = spriteCatalog.spriteSheets[key];
        const clipSpec = sheet ? spriteCatalog.clipSpecs[sheet.clipSpec] : undefined;
        return sheet && clipSpec ? { sheet, clipSpec } : null;
      })
      .filter((segment): segment is { sheet: SpriteSheetSpec; clipSpec: ClipSpec } => segment !== null);

    const totalFrames = segments.reduce((sum, segment) => sum + segment.clipSpec.frameCount, 0);
    if (totalFrames === 0) return null;

    const isBlack = piece.startsWith("b");
    const descriptor = pieceDescriptor(piece);
    const oy = descriptor ? (PIECE_VISUAL_OFFSET_Y[descriptor.name] ?? 0) : 0;
    const baseTransform = pieceTransform(isBlack, scale);
    const transform = oy !== 0 ? `translate(0%, ${(oy * 100).toFixed(2)}%) ${baseTransform}` : baseTransform;

    let localIndex = frameTick % totalFrames;
    for (const segment of segments) {
      if (localIndex < segment.clipSpec.frameCount) {
        return {
          transform,
          backgroundImage: `url(/${segment.sheet.path})`,
          backgroundSize: `${segment.clipSpec.frameCount * 100}% 100%`,
          backgroundPosition: backgroundPositionFor(localIndex, segment.clipSpec.frameCount),
        };
      }
      localIndex -= segment.clipSpec.frameCount;
    }
    return null;
  }, [spriteCatalog, frameTick, piece, state, scale]);
}
