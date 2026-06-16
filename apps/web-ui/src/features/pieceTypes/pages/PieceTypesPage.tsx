import { useNavigate } from "react-router-dom";
import type { PieceCode } from "../../../api/types";
import Button from "../../../components/ui/Button";
import GlassPanel from "../../../components/ui/GlassPanel";
import type { VisualState } from "../../../components/chessBoard/types";
import { pieceLabel, pieceTone } from "../../../components/chessBoard/types";
import { usePieceSpriteAnimation } from "../../../components/chessBoard/usePieceSpriteAnimation";
import "./PieceTypesPage.css";

const PIECE_TYPES: { name: string; piece: PieceCode }[] = [
  { name: "King", piece: "bK" },
  { name: "Queen", piece: "bQ" },
  { name: "Rook", piece: "bR" },
  { name: "Bishop", piece: "bB" },
  { name: "Knight", piece: "bN" },
  { name: "Pawn", piece: "bP" },
];

const ANIMATION_STATES: { state: VisualState; label: string }[] = [
  { state: "idle", label: "Idle" },
  { state: "attack", label: "Attack" },
  { state: "move", label: "Move" },
  { state: "hit", label: "Hit" },
  { state: "dead", label: "Dead" },
];

function AnimationEntry({ piece, state, label }: { piece: PieceCode; state: VisualState; label: string }) {
  const style = usePieceSpriteAnimation(piece, state);

  return (
    <div className="piece-types-anim-slot">
      {style ? (
        <span className={`piece ${pieceTone(piece)} has-sprite piece-types-sprite`} style={style} aria-hidden="true">
          {pieceLabel(piece)}
        </span>
      ) : (
        <span className="piece-types-sprite piece-types-sprite--missing" aria-hidden="true">
          ?
        </span>
      )}
      <span className="piece-types-anim-label">{label}</span>
    </div>
  );
}

export default function PieceTypesPage() {
  const navigate = useNavigate();

  return (
    <div className="piece-types-page">
      <div className="piece-types-shell">
        <div className="piece-types-header">
          <h1 className="piece-types-title">Piece Types</h1>
          <Button variant="secondary" size="lg" onClick={() => navigate("/")}>
            ← Back
          </Button>
        </div>
        <p className="piece-types-intro">Every black piece in Searchess, with all of its animation states.</p>

        <div className="piece-types-list">
          {PIECE_TYPES.map((entry) => (
            <GlassPanel key={entry.name} as="article" className="piece-types-card" variant="default">
              <h2 className="piece-types-card-name">{entry.name}</h2>
              <div className="piece-types-anim-grid">
                {ANIMATION_STATES.map(({ state, label }) => (
                  <AnimationEntry key={state} piece={entry.piece} state={state} label={label} />
                ))}
              </div>
            </GlassPanel>
          ))}
        </div>
      </div>
    </div>
  );
}
