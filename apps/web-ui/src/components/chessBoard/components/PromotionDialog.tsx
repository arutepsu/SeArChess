import type { BoardSquare, PieceCode } from "../../../api/types";
import type { PromotionPiece } from "../../../api/backendTypes";
import Button from "../../ui/Button";
import { pieceTone, pieceLabel } from "../types";

interface PromotionOption {
  pieceType: PromotionPiece;
  code: PieceCode;
  name: string;
}

interface PromotionDialogProps {
  options: PromotionOption[];
  onResolve: (piece: PromotionPiece) => void;
  onCancel: () => void;
  spriteStyle: (piece: BoardSquare) => React.CSSProperties;
}

export default function PromotionDialog({
  options,
  onResolve,
  onCancel,
  spriteStyle,
}: PromotionDialogProps) {
  return (
    <div className="promotion-overlay animate-fade-in" role="presentation">
      <div
        className="promotion-dialog panel animate-scale-up"
        role="dialog"
        aria-modal="true"
        aria-labelledby="promotion-dialog-title"
      >
        <h3 id="promotion-dialog-title">Promote Pawn</h3>
        <p className="promotion-desc">Choose a piece for promotion:</p>
        <div className="promotion-options">
          {options.map((option) => (
            <button
              key={option.pieceType}
              type="button"
              className="promotion-option-btn"
              aria-label={`Promote to ${option.name}`}
              onClick={() => onResolve(option.pieceType)}
            >
              <span
                className={`piece ${pieceTone(option.code)} has-sprite`}
                style={spriteStyle(option.code)}
              >
                {pieceLabel(option.code)}
              </span>
              <span className="promotion-option-label">{option.name}</span>
            </button>
          ))}
        </div>
        <Button variant="ghost" size="sm" onClick={onCancel} aria-label="Cancel promotion">
          Cancel
        </Button>
      </div>
    </div>
  );
}
