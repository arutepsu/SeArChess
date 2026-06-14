import type { AnimationRenderModel } from "../types";
import { PIECE_SCALE, pieceTransform } from "../types";
import { backgroundPositionFor } from "../animationHelpers";

interface AnimationLayerProps {
  model: AnimationRenderModel;
  squareSize: number;
}

function pieceStyle(
  model: Pick<
    AnimationRenderModel["moving"],
    "x" | "y" | "opacity" | "flipX" | "sheet" | "frameIndex"
  > & { scale?: number },
  squareSize: number
): React.CSSProperties {
  return {
    left: `${model.x}px`,
    top: `${model.y}px`,
    width: `${squareSize}px`,
    height: `${squareSize}px`,
    opacity: model.opacity.toString(),
    transform: pieceTransform(model.flipX, (model.scale ?? 1) * PIECE_SCALE),
    backgroundImage: model.sheet ? `url(${model.sheet.url})` : "",
    backgroundSize: model.sheet ? `${model.sheet.frameCount * 100}% 100%` : "100% 100%",
    backgroundPosition: model.sheet
      ? backgroundPositionFor(model.frameIndex, model.sheet.frameCount)
      : "0% 50%",
  };
}

export default function AnimationLayer({ model, squareSize }: AnimationLayerProps) {
  return (
    <div className="animation-layer" aria-hidden="true">
      <div className="animation-piece" style={pieceStyle(model.moving, squareSize)} />
      {model.captured && (
        <div className="animation-piece" style={pieceStyle(model.captured, squareSize)} />
      )}
      {model.castlingRook && (
        <div className="animation-piece" style={pieceStyle(model.castlingRook, squareSize)} />
      )}
    </div>
  );
}
