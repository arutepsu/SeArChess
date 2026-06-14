import { useState } from "react";
import type { GameState, PlayableGameMode, PlayerColor } from "../../../api/types";
import type { RunAiTurnsResponse } from "../../../api/backendTypes";
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
  gameMode: PlayableGameMode;
  canResign: boolean;
  sessionId?: string;
  gameId?: string;
  fen?: string;
  pgn?: string;
  onImportNotation: (format: "FEN" | "PGN", notation: string) => void;
  onExportNotation: (format: "FEN" | "PGN") => Promise<string>;
  onGameModeChange: (mode: PlayableGameMode) => void;
  onNewGame: () => void;
  onSaveSession: () => Promise<void>;
  onResign: () => void;
  onRunAiTurns: (maxPlies: number) => Promise<RunAiTurnsResponse>;
  onBackToMenu: () => void;
  onOpenHeatmap: () => void;
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
  sessionId,
  gameId,
  fen,
  pgn,
  onImportNotation,
  onSaveSession,
  onResign,
  onRunAiTurns,
  onBackToMenu,
  onOpenHeatmap
}: ControlPanelProps) {
  const whiteActive = activeColor === "white" && clockRunning;
  const blackActive = activeColor === "black" && clockRunning;

  const getProgress = (timeMs?: number) => {
    const total = 600000; // 10 Minuten als Standard
    if (!timeMs) return "0%";
    const percentage = Math.min(100, (timeMs / total) * 100);
    return `${percentage}%`;
  };

  const [fenDraft, setFenDraft] = useState("");
  const [pgnDraft, setPgnDraft] = useState("");
  const [exportNotice, setExportNotice] = useState<string | null>(null);
  const [saveNotice, setSaveNotice] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [notationFormat, setNotationFormat] = useState<"FEN" | "PGN">("FEN");
  const [aiMaxPlies, setAiMaxPlies] = useState(20);
  const [aiRunResult, setAiRunResult] = useState<RunAiTurnsResponse | null>(null);
  const [aiRunError, setAiRunError] = useState<string | null>(null);

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

  const saveSession = async () => {
    setSaveNotice(null);
    setSaveError(null);

    try {
      await onSaveSession();
      setSaveNotice("Session saved successfully.");
      setTimeout(() => setSaveNotice(null), 5000);
    } catch (error) {
      setSaveError(
        error instanceof Error ? error.message : "Session save failed."
      );
    }
  };

  const runAiTurnBatch = async () => {
    setAiRunResult(null);
    setAiRunError(null);

    try {
      const result = await onRunAiTurns(aiMaxPlies);
      setAiRunResult(result);
    } catch (error) {
      setAiRunError(error instanceof Error ? error.message : "AI turn run failed.");
    }
  };

  return (
    <section className="control-panel" aria-label="Controls">
      <header>
        <h2>Command Deck</h2>
        <p>Direct the battle from here.</p>
      </header>

      <div className="clocks">
        {/* WEISS */}
        <div
          className={`clock${whiteActive ? " is-active" : ""}`}
          style={{ "--time-left": getProgress(whiteTimeMs) } as React.CSSProperties}
        >
          <span className="label">White</span>
          <strong className="clock-time">{formatClockMs(whiteTimeMs ?? 0)}</strong>
        </div>

        {/* SCHWARZ */}
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

        <div>
          <span className="label">Half move</span>
          <strong>{game?.halfMoveClock ?? 0}</strong>
        </div>
      </div>

      <div className="session-save">
        <span className="label">Session persistence</span>

        <dl className="session-identifiers">
          <div>
            <dt>Game ID</dt>
            <dd title={gameId}>{gameId ?? "Not available"}</dd>
          </div>
          <div>
            <dt>Session ID</dt>
            <dd title={sessionId}>{sessionId ?? "Not available"}</dd>
          </div>
        </dl>

        <Button
          variant="primary"
          disabled={busy || !sessionId || !game}
          onClick={() => void saveSession()}
        >
          Save Session
        </Button>

        <p className="session-save-note">
          Moves are persisted automatically; this confirms the current session
          through the save endpoint.
        </p>

        {saveNotice ? (
          <p className="session-save-success">{saveNotice}</p>
        ) : null}

        {saveError ? (
          <p className="session-save-error">{saveError}</p>
        ) : null}
      </div>

      {gameMode === "AIVsAI" ? (
        <div className="ai-turn-runner">
          <span className="label">AI vs AI control</span>
          <label htmlFor="ai-max-plies">Max plies</label>
          <input
            id="ai-max-plies"
            type="number"
            min={1}
            max={1000}
            step={1}
            value={aiMaxPlies}
            disabled={busy}
            onChange={(event) => {
              const next = Number(event.currentTarget.value);
              setAiMaxPlies(Number.isFinite(next) ? next : 20);
            }}
          />
          <Button
            variant="primary"
            disabled={busy || !gameId || !game}
            onClick={() => void runAiTurnBatch()}
          >
            Run AI Turns
          </Button>
          {aiRunResult ? (
            <p className="ai-turn-result">
              Plies run: {aiRunResult.pliesRun}. Stop: {aiRunResult.stopReason}.
            </p>
          ) : null}
          {aiRunError ? <p className="ai-turn-error">{aiRunError}</p> : null}
        </div>
      ) : null}


      <div className="notation-format-select">
        <label htmlFor="notation-format-select" className="label" style={{ marginRight: 8 }}>
          Notation Format
        </label>
        <select
          id="notation-format-select"
          value={notationFormat}
          onChange={e => setNotationFormat(e.target.value as "FEN" | "PGN")}
          disabled={busy}
        >
          <option value="FEN">FEN</option>
          <option value="PGN">PGN</option>
        </select>
      </div>

      <div className="notation">

        {notationFormat === "FEN" && (
          <div>
            <span className="label">FEN</span>
            <pre className="notation-text">{fen ?? "Not available"}</pre>
            <div className="notation-output-actions">
              <Button
                variant="ghost"
                disabled={!fen || !navigator.clipboard?.writeText}
                onClick={() => void copyNotation()}
              >
                Copy
              </Button>
              <Button
                variant="ghost"
                disabled={!fen || !game}
                onClick={downloadNotation}
              >
                Download
              </Button>
            </div>
          </div>
        )}

        {notationFormat === "PGN" && (
          <div>
            <span className="label">PGN</span>
            <pre className="notation-text">{pgn ?? "Not available"}</pre>
            <div className="notation-output-actions">
              <Button
                variant="ghost"
                disabled={!pgn || !navigator.clipboard?.writeText}
                onClick={() => void copyNotation()}
              >
                Copy
              </Button>
              <Button
                variant="ghost"
                disabled={!pgn || !game}
                onClick={downloadNotation}
              >
                Download
              </Button>
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
            <Button
              variant="primary"
              disabled={busy || !fenDraft.trim()}
              onClick={() => onImportNotation("FEN", fenDraft)}
            >
              Import FEN
            </Button>
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
            <Button
              variant="primary"
              disabled={busy || !pgnDraft.trim()}
              onClick={() => onImportNotation("PGN", pgnDraft)}
            >
              Import PGN
            </Button>
          </div>
        )}
      </div>

      <div className="actions">
        <Button variant="ghost" disabled={!gameId || busy} onClick={onOpenHeatmap}>
          Heatmap
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
