import type { GameState, PlayerColor } from "../api/types";
import "./ControlPanel.css";

type ControlPanelProps = {
  game?: GameState;
  busy: boolean;
  whiteTimeMs?: number;
  blackTimeMs?: number;
  activeColor?: PlayerColor;
  clockRunning?: boolean;
  onNewGame: () => void;
  onUndo: () => void;
  onRedo: () => void;
  onExport: () => void;
};

const formatTime = (ms?: number) => {
  const totalSeconds = Math.max(0, Math.floor((ms ?? 0) / 1000));
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${seconds.toString().padStart(2, "0")}`;
};

export default function ControlPanel({
  game,
  busy,
  whiteTimeMs,
  blackTimeMs,
  activeColor,
  clockRunning,
  onNewGame,
  onUndo,
  onRedo,
  onExport
}: ControlPanelProps) {
  const whiteActive = activeColor === "white" && clockRunning;
  const blackActive = activeColor === "black" && clockRunning;

  return (
    <section className="control-panel" aria-label="Controls">
      <header>
        <h2>Command Deck</h2>
        <p>Direct the battle from here.</p>
      </header>
      <div className="clocks">
        <div className={`clock${whiteActive ? " is-active" : ""}`}>
          <span className="label">White</span>
          <strong className="clock-time">{formatTime(whiteTimeMs)}</strong>
        </div>
        <div className={`clock${blackActive ? " is-active" : ""}`}>
          <span className="label">Black</span>
          <strong className="clock-time">{formatTime(blackTimeMs)}</strong>
        </div>
      </div>
      <div className="status">
        <div>
          <span className="label">Status</span>
          <strong>{game?.status ?? "idle"}</strong>
        </div>
        <div>
          <span className="label">Full move</span>
          <strong>{game?.fullMove ?? 0}</strong>
        </div>
        <div>
          <span className="label">Half move</span>
          <strong>{game?.halfMoveClock ?? 0}</strong>
        </div>
      </div>
      <div className="actions">
        <button type="button" disabled={busy} onClick={onNewGame}>
          New Game
        </button>
        <button type="button" disabled={busy} onClick={onUndo}>
          Undo
        </button>
        <button type="button" disabled={busy} onClick={onRedo}>
          Redo
        </button>
        <button type="button" disabled={busy} onClick={onExport}>
          Export PGN
        </button>
      </div>
    </section>
  );
}
