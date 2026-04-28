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
  onExportNotation: (format: "FEN" | "PGN") => Promise<string>;
  onGameModeChange: (mode: PlayableGameMode) => void;
  onNewGame: () => void;
  onResign: () => void;
  onBackToMenu: () => void;
};

type ExportedNotation = {
  format: "FEN" | "PGN";
  text: string;
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
  onExportNotation,
  onGameModeChange,
  onNewGame,
  onResign,
  onBackToMenu
}: ControlPanelProps) {
  const whiteActive = activeColor === "white" && clockRunning;
  const blackActive = activeColor === "black" && clockRunning;

  const [fenDraft, setFenDraft] = useState("");
  const [pgnDraft, setPgnDraft] = useState("");
  const [exportNotice, setExportNotice] = useState<string | null>(null);
  const [notationFormat, setNotationFormat] = useState<"FEN" | "PGN">("FEN");

  const readNotationFile = async (
    file: File | undefined,
    setter: (value: string) => void
  ) => {
    if (!file) return;
    setter(await file.text());
  };

  const copyNotation = async () => {
    const textToCopy = notationFormat === "FEN" ? fen : pgn;
    if (!textToCopy || !navigator.clipboard?.writeText) return;

    await navigator.clipboard.writeText(textToCopy);
    setExportNotice(`${notationFormat} copied.`);
    setTimeout(() => setExportNotice(null), 3000);
  };

  const downloadNotation = () => {
    const textToDownload = notationFormat === "FEN" ? fen : pgn;
    if (!textToDownload || !game) return;

    const extension = notationFormat.toLowerCase();
    const blob = new Blob([textToDownload], {
      type: "text/plain;charset=utf-8"
    });
    const url = window.URL.createObjectURL(blob);
    const anchor = document.createElement("a");

    anchor.href = url;
    anchor.download = `searchess-game-${game.id.slice(0, 8)}.${extension}`;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    window.URL.revokeObjectURL(url);
    setExportNotice(`${notationFormat} downloaded.`);
    setTimeout(() => setExportNotice(null), 3000);
  };

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

      <div className="notation-format-select">

      </div>

      <div className="notation">

        {notationFormat === "FEN" && (
          <div>
            <span className="label">FEN</span>
            <pre className="notation-text">{fen ?? "Not available"}</pre>
            <div className="notation-output-actions">
              <button
                type="button"
                disabled={!fen || !navigator.clipboard?.writeText}
                onClick={() => void copyNotation()}
              >
                Copy
              </button>
              <button
                type="button"
                disabled={!fen || !game}
                onClick={downloadNotation}
              >
                Download
              </button>
            </div>
          </div>
        )}

        {notationFormat === "PGN" && (
          <div>
            <span className="label">PGN</span>
            <pre className="notation-text">{pgn ?? "Not available"}</pre>
            <div className="notation-output-actions">
              <button
                type="button"
                disabled={!pgn || !navigator.clipboard?.writeText}
                onClick={() => void copyNotation()}
              >
                Copy
              </button>
              <button
                type="button"
                disabled={!pgn || !game}
                onClick={downloadNotation}
              >
                Download
              </button>
            </div>
          </div>
        )}

        {exportNotice ? (
          <p className="notation-success">{exportNotice}</p>
        ) : null}
      </div>

      <div className="import-notation">
        <span className="label">Notation import</span>

        {notationFormat === "FEN" && (
          <div>
            <span className="label">Import FEN</span>
            <textarea
              value={fenDraft}
              onChange={(event) => setFenDraft(event.target.value)}
              placeholder="Paste FEN here"
              rows={3}
              disabled={busy}
            />
            <input
              type="file"
              accept=".fen,.txt"
              disabled={busy}
              onChange={(event) =>
                void readNotationFile(event.currentTarget.files?.[0], setFenDraft)
              }
            />
            <button
              type="button"
              disabled={busy || !fenDraft.trim()}
              onClick={() => onImportNotation("FEN", fenDraft)}
            >
              Import FEN
            </button>
          </div>
        )}

        {notationFormat === "PGN" && (
          <div>
            <span className="label">Import PGN</span>
            <textarea
              value={pgnDraft}
              onChange={(event) => setPgnDraft(event.target.value)}
              placeholder="Paste PGN here"
              rows={3}
              disabled={busy}
            />
            <input
              type="file"
              accept=".pgn,.txt"
              disabled={busy}
              onChange={(event) =>
                void readNotationFile(event.currentTarget.files?.[0], setPgnDraft)
              }
            />
            <button
              type="button"
              disabled={busy || !pgnDraft.trim()}
              onClick={() => onImportNotation("PGN", pgnDraft)}
            >
              Import PGN
            </button>
          </div>
        )}
      </div>

      <div className="actions">
        <label className="mode-select">
          <span className="label">Mode</span>
          <select
            value={gameMode}
            disabled={busy}
            onChange={(event) =>
              onGameModeChange(event.target.value as PlayableGameMode)
            }
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

        <button type="button" disabled={busy} onClick={onBackToMenu}>
          Back to Menu
        </button>
      </div>
    </section>
  );
}
