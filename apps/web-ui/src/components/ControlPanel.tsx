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
  onResign
}: ControlPanelProps) {
  const whiteActive = activeColor === "white" && clockRunning;
  const blackActive = activeColor === "black" && clockRunning;

  const [fenDraft, setFenDraft] = useState("");
  const [pgnDraft, setPgnDraft] = useState("");
  const [exportedNotation, setExportedNotation] =
    useState<ExportedNotation | null>(null);
  const [exportingFormat, setExportingFormat] = useState<"FEN" | "PGN" | null>(
    null
  );
  const [exportError, setExportError] = useState<string | null>(null);
  const [exportNotice, setExportNotice] = useState<string | null>(null);
  const [notationFormat, setNotationFormat] = useState<"FEN" | "PGN">("FEN");

  const readNotationFile = async (
    file: File | undefined,
    setter: (value: string) => void
  ) => {
    if (!file) return;
    setter(await file.text());
  };

  const handleExportClick = async (format: "FEN" | "PGN") => {
    setExportingFormat(format);
    setExportError(null);
    setExportNotice(null);

    try {
      const text = await onExportNotation(format);
      setExportedNotation({ format, text });
    } catch (error) {
      setExportError(
        error instanceof Error ? error.message : `${format} export failed.`
      );
    } finally {
      setExportingFormat(null);
    }
  };

  const copyExportedNotation = async () => {
    if (!exportedNotation || !navigator.clipboard?.writeText) return;

    await navigator.clipboard.writeText(exportedNotation.text);
    setExportNotice(`${exportedNotation.format} copied.`);
  };

  const downloadExportedNotation = () => {
    if (!exportedNotation || !game) return;

    const extension = exportedNotation.format.toLowerCase();
    const blob = new Blob([exportedNotation.text], {
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
    setExportNotice(`${exportedNotation.format} downloaded.`);
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
        <label className="mode-select">
          <span className="label">Notation Format</span>
          <select
            value={notationFormat}
            onChange={(e) => setNotationFormat(e.target.value as "FEN" | "PGN")}
            disabled={busy}
          >
            <option value="FEN">FEN</option>
            <option value="PGN">PGN</option>
          </select>
        </label>
      </div>

      <div className="notation">
        <span className="label">Notation export</span>

        {notationFormat === "FEN" && (
          <div>
            <span className="label">FEN</span>
            <pre className="notation-text">{fen ?? "Not available"}</pre>
            <button
              type="button"
              disabled={busy || !game || exportingFormat !== null}
              onClick={() => void handleExportClick("FEN")}
            >
              {exportingFormat === "FEN" ? "Exporting FEN..." : "Export FEN"}
            </button>
          </div>
        )}

        {notationFormat === "PGN" && (
          <div>
            <span className="label">PGN</span>
            <pre className="notation-text">{pgn ?? "Not available"}</pre>
            <button
              type="button"
              disabled={busy || !game || exportingFormat !== null}
              onClick={() => void handleExportClick("PGN")}
            >
              {exportingFormat === "PGN" ? "Exporting PGN..." : "Export PGN"}
            </button>
          </div>
        )}

        <div className="notation-output">
          <span className="label">
            {exportedNotation
              ? `${exportedNotation.format} export`
              : "Export result"}
          </span>
          <textarea
            readOnly
            rows={5}
            value={exportedNotation?.text ?? ""}
            placeholder="Exported notation will appear here."
          />
          {exportError ? <p className="notation-error">{exportError}</p> : null}
          {exportNotice ? (
            <p className="notation-success">{exportNotice}</p>
          ) : null}
          <div className="notation-output-actions">
            <button
              type="button"
              disabled={!exportedNotation || !navigator.clipboard?.writeText}
              onClick={() => void copyExportedNotation()}
            >
              Copy
            </button>
            <button
              type="button"
              disabled={!exportedNotation || !game}
              onClick={downloadExportedNotation}
            >
              Download
            </button>
          </div>
        </div>
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
      </div>
    </section>
  );
}
