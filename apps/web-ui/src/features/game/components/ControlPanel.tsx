import type { GameState, PlayerColor } from "../../../api/types";
import { formatClockMs } from "../../../utils/timeFormat";
import Button from "../../../components/ui/Button";
import "./ControlPanel.css";

type ControlPanelProps = {
  game?: GameState;
  busy: boolean;
  whiteTimeMs?: number;
  blackTimeMs?: number;
  activeColor?: PlayerColor;
  clockRunning?: boolean;
  canResign: boolean;
  onResign: () => void;
  onBackToMenu: () => void;
  onOpenGameMenu: () => void;
};

const formatStatus = (game?: GameState): string => {
  if (!game) return "idle";
  switch (game.status) {
    case "checkmate":
      return game.winner ? `checkmate, ${game.winner} wins` : "checkmate";
    case "draw":
      return game.drawReason ? `draw, ${game.drawReason}` : "draw";
    case "resigned":
      return game.winner ? `resigned, ${game.winner} wins` : "resigned";
    default:
      return game.status;
  }
};

export default function ControlPanel({
  game,
  busy,
  whiteTimeMs,
  blackTimeMs,
  activeColor,
  clockRunning,
  canResign,
  onResign,
  onBackToMenu,
  onOpenGameMenu,
}: ControlPanelProps) {
  const whiteActive = activeColor === "white" && clockRunning;
  const blackActive = activeColor === "black" && clockRunning;

  const getProgress = (timeMs?: number) => {
    const total = 600000;
    if (!timeMs) return "0%";
    return `${Math.min(100, (timeMs / total) * 100)}%`;
  };

  return (
    <section className="control-panel" aria-label="Game Controls">
      <div className="clocks">
        <div
          className={`clock${whiteActive ? " is-active" : ""}`}
          style={{ "--time-left": getProgress(whiteTimeMs) } as React.CSSProperties}
        >
          <span className="label">White</span>
          <strong className="clock-time">{formatClockMs(whiteTimeMs ?? 0)}</strong>
        </div>
        <div
          className={`clock${blackActive ? " is-active" : ""}`}
          style={{ "--time-left": getProgress(blackTimeMs) } as React.CSSProperties}
        >
          <span className="label">Black</span>
          <strong className="clock-time">{formatClockMs(blackTimeMs ?? 0)}</strong>
        </div>
      </div>

      <div className="status">
        <div>
          <span className="label">Status</span>
          <strong>{formatStatus(game)}</strong>
        </div>
        <div>
          <span className="label">Full move</span>
          <strong>{game?.fullMove ?? 0}</strong>
        </div>
      </div>

      <div className="actions">
        <Button variant="ghost" onClick={onOpenGameMenu}>
          ☰ Game Menu
        </Button>
        <Button variant="danger" disabled={!canResign} onClick={onResign}>
          Resign
        </Button>
        <Button variant="ghost" disabled={busy} onClick={onBackToMenu}>
          Back to Menu
        </Button>
      </div>
    </section>
  );
}
