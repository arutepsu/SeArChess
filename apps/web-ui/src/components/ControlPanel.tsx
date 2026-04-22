import { useState } from "react";
import type { GameState, PlayableGameMode, PlayerColor } from "../api/types";
import "./ControlPanel.css";

type ControlPanelProps = {
  game?: GameState;
  busy: boolean;
  whiteTimeMs?: number;
  blackTimeMs?: number;
  activeColor?: PlayerColor;
  clockRunning?: boolean;
  gameMode: PlayableGameMode;
  canResign: boolean;
  fen?: string;
  pgn?: string;
  onImportNotation: (format: "FEN" | "PGN", notation: string) => void;
  onGameModeChange: (mode: PlayableGameMode) => void;
  onNewGame: () => void;
  onResign: () => void;
};

const formatTime = (ms?: number) => {
  const totalSeconds = Math.max(0, Math.floor((ms ?? 0) / 1000));
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${seconds.toString().padStart(2, "0")}`;
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
  gameMode,
  canResign,
  fen,
  pgn,
  onImportNotation,
  onGameModeChange,
  onNewGame,
  onResign
}: ControlPanelProps) {
  const whiteActive = activeColor === "white" && clockRunning;
  const blackActive = activeColor === "black" && clockRunning;
  const [fenDraft, setFenDraft] = useState("");
  const [pgnDraft, setPgnDraft] = useState("");

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
          <strong>{formatStatus(game)}</strong>
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
      <div className="notation">
        <div>
          <span className="label">FEN</span>
          <pre className="notation-text">{fen ?? "Not available"}</pre>
        </div>
        <div>
          <span className="label">PGN</span>
          <pre className="notation-text">{pgn ?? "Not available"}</pre>
        </div>
      </div>
      <div className="import-notation">
        <div>
          <span className="label">Import FEN</span>
          <textarea
            value={fenDraft}
            onChange={(event) => setFenDraft(event.target.value)}
            placeholder="Paste FEN here"
            rows={3}
            disabled={busy}
          />
          <button
            type="button"
            disabled={busy || !fenDraft.trim()}
            onClick={() => onImportNotation("FEN", fenDraft)}
          >
            Import FEN
          </button>
        </div>
        <div>
          <span className="label">Import PGN</span>
          <textarea
            value={pgnDraft}
            onChange={(event) => setPgnDraft(event.target.value)}
            placeholder="Paste PGN here"
            rows={3}
            disabled={busy}
          />
          <button
            type="button"
            disabled={busy || !pgnDraft.trim()}
            onClick={() => onImportNotation("PGN", pgnDraft)}
          >
            Import PGN
          </button>
        </div>
      </div>
      <div className="actions">
        <label className="mode-select">
          <span className="label">Mode</span>
          <select
            value={gameMode}
            disabled={busy}
            onChange={(event) => onGameModeChange(event.target.value as PlayableGameMode)}
          >
            <option value="HumanVsHuman">Human vs Human</option>
            <option value="HumanVsAI">Human vs AI</option>
          </select>
        </label>
        <button type="button" disabled={busy} onClick={onNewGame}>
          New Game
        </button>
        <button type="button" disabled={!canResign} onClick={onResign}>
          Resign
        </button>
      </div>
    </section>
  );
}
