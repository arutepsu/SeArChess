import type { AnimationRenderModel } from "../types";
import { PIECE_SCALE, pieceTransform } from "../types";
import { backgroundPositionFor } from "../animationHelpers";

interface AnimationLayerProps {
  model: AnimationRenderModel;
  cellWidth: number;
  cellHeight: number;
}

function pieceStyle(
  model: Pick<
    AnimationRenderModel["moving"],
    "x" | "y" | "opacity" | "flipX" | "sheet" | "frameIndex"
  > & { scale?: number },
  cellWidth: number,
  cellHeight: number
): React.CSSProperties {
  return {
    left: `${model.x}px`,
    top: `${model.y}px`,
    width: `${cellWidth}px`,
    height: `${cellHeight}px`,
    opacity: model.opacity.toString(),
    transform: pieceTransform(model.flipX, (model.scale ?? 1) * PIECE_SCALE),
    backgroundImage: model.sheet ? `url(${model.sheet.url})` : "",
    backgroundSize: model.sheet ? `${model.sheet.frameCount * 100}% 100%` : "100% 100%",
    backgroundPosition: model.sheet
      ? backgroundPositionFor(model.frameIndex, model.sheet.frameCount)
      : "0% 50%",
  };
}

export default function AnimationLayer({ model, cellWidth, cellHeight }: AnimationLayerProps) {
  return (
    <div className="animation-layer" aria-hidden="true">
      <div className="animation-piece" style={pieceStyle(model.moving, cellWidth, cellHeight)} />
      {model.captured && (
        <div className="animation-piece" style={pieceStyle(model.captured, cellWidth, cellHeight)} />
      )}
      {model.castlingRook && (
        <div className="animation-piece" style={pieceStyle(model.castlingRook, cellWidth, cellHeight)} />
      )}
    </div>
  );
}
