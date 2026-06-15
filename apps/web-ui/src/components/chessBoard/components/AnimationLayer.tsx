import type { AnimationRenderModel } from "../types";
import { PIECE_SCALE, PIECE_VISUAL_OFFSET_Y, pieceDescriptor, pieceTransform } from "../types";
import { backgroundPositionFor } from "../animationHelpers";

interface AnimationLayerProps {
  model: AnimationRenderModel;
  cellWidth: number;
  cellHeight: number;
  debugHit?: boolean;
}

function pieceStyle(
  model: Pick<
    AnimationRenderModel["moving"],
    "piece" | "x" | "y" | "opacity" | "flipX" | "sheet" | "frameIndex"
  > & { scale?: number },
  cellWidth: number,
  cellHeight: number
): React.CSSProperties {
  const descriptor = pieceDescriptor(model.piece);
  const offsetY = descriptor ? (PIECE_VISUAL_OFFSET_Y[descriptor.name] ?? 0) : 0;
  const baseTransform = pieceTransform(model.flipX, (model.scale ?? 1) * PIECE_SCALE);
  const transform = offsetY !== 0
    ? `translate(0%, ${(offsetY * 100).toFixed(2)}%) ${baseTransform}`
    : baseTransform;
  return {
    left: `${model.x}px`,
    top: `${model.y}px`,
    width: `${cellWidth}px`,
    height: `${cellHeight}px`,
    opacity: model.opacity.toString(),
    transform,
    backgroundImage: model.sheet ? `url(${model.sheet.url})` : "",
    backgroundSize: model.sheet ? `${model.sheet.frameCount * 100}% 100%` : "100% 100%",
    backgroundPosition: model.sheet
      ? backgroundPositionFor(model.frameIndex, model.sheet.frameCount)
      : "0% 50%",
  };
}

// Wraps an animation piece in an overflow:hidden container so the scale(PIECE_SCALE)
// overflow is clipped to the cell size — matching the board-square overflow:hidden
// behaviour that makes idle pieces appear the same visual size as animated ones.
function AnimPiece({
  style,
  wrapperExtra,
}: {
  style: React.CSSProperties;
  wrapperExtra?: React.CSSProperties;
}) {
  const { left, top, width, height, ...innerStyle } = style;
  return (
    <div
      style={{
        position: "absolute",
        left, top, width, height,
        overflow: "hidden",
        ...wrapperExtra,
      }}
    >
      <div
        className="animation-piece"
        style={{ left: 0, top: 0, width, height, ...innerStyle }}
      />
    </div>
  );
}

export default function AnimationLayer({ model, cellWidth, cellHeight, debugHit }: AnimationLayerProps) {
  const capturedWrapperExtra =
    debugHit && model.captured?.visualState === "hit"
      ? { outline: "3px solid red", boxShadow: "0 0 12px 4px rgba(255,0,0,0.7)" }
      : undefined;

  return (
    <div className="animation-layer" aria-hidden="true">
      <AnimPiece style={pieceStyle(model.moving, cellWidth, cellHeight)} />
      {model.captured && (
        <AnimPiece
          style={pieceStyle(model.captured, cellWidth, cellHeight)}
          wrapperExtra={capturedWrapperExtra}
        />
      )}
      {model.castlingRook && (
        <AnimPiece style={pieceStyle(model.castlingRook, cellWidth, cellHeight)} />
      )}
    </div>
  );
}
