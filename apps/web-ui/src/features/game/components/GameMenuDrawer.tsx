import { useState, useEffect, useRef } from "react";
import type { GameState, PlayableGameMode } from "../../../api/types";
import type { RunAiTurnsResponse } from "../../../api/backendTypes";
import type { GameSceneSkin } from "../sceneSkins";
import type { GameBackground } from "../backgroundSkins";
import BackgroundPanel from "./BackgroundPanel";
import AppBackgroundPanel from "./AppBackgroundPanel";
import Button from "../../../components/ui/Button";
import "./GameMenuDrawer.css";

interface GameMenuDrawerProps {
  isOpen: boolean;
  onClose: () => void;

  gameScenes: GameSceneSkin[];
  gameSceneId: string;
  onGameSceneChange: (id: string) => void;

  backgrounds: GameBackground[];
  backgroundId: string;
  onBackgroundChange: (id: string) => void;

  fen?: string;
  pgn?: string;
  onImportNotation: (format: "FEN" | "PGN", notation: string) => void;

  game?: GameState;
  sessionId?: string;
  gameId?: string;
  busy: boolean;
  onSaveSession: () => Promise<void>;

  gameMode: PlayableGameMode;
  onRunAiTurns: (maxPlies: number) => Promise<RunAiTurnsResponse>;
  onOpenHeatmap: () => void;
  onBackToMenu: () => void;
  onNewGame: () => void;

  activeTab: "local" | "bot";
  setActiveTab: (tab: "local" | "bot") => void;
}

export default function GameMenuDrawer({
  isOpen,
  onClose,
  gameScenes,
  gameSceneId,
  onGameSceneChange,
  backgrounds,
  backgroundId,
  onBackgroundChange,
  fen,
  pgn,
  onImportNotation,
  game,
  sessionId,
  gameId,
  busy,
  onSaveSession,
  gameMode,
  onRunAiTurns,
  onOpenHeatmap,
  onBackToMenu,
  onNewGame,
  activeTab,
  setActiveTab,
}: GameMenuDrawerProps) {
  const [notationFormat, setNotationFormat] = useState<"FEN" | "PGN">("FEN");
  const [fenDraft, setFenDraft] = useState("");
  const [pgnDraft, setPgnDraft] = useState("");
  const [exportNotice, setExportNotice] = useState<string | null>(null);
  const [saveNotice, setSaveNotice] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [aiMaxPlies, setAiMaxPlies] = useState(20);
  const [aiRunResult, setAiRunResult] = useState<RunAiTurnsResponse | null>(null);
  const [aiRunError, setAiRunError] = useState<string | null>(null);
  const importFileInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!isOpen) return;
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [isOpen, onClose]);

  if (!isOpen) return null;

  const currentNotation = notationFormat === "FEN" ? fen : pgn;
  const currentDraft = notationFormat === "FEN" ? fenDraft : pgnDraft;
  const setCurrentDraft = notationFormat === "FEN" ? setFenDraft : setPgnDraft;

  const copyNotation = async () => {
    if (!currentNotation || !navigator.clipboard?.writeText) return;
    await navigator.clipboard.writeText(currentNotation);
    setExportNotice(`${notationFormat} copied.`);
    setTimeout(() => setExportNotice(null), 3000);
  };

  const downloadNotation = () => {
    if (!currentNotation || !game) return;
    const ext = notationFormat.toLowerCase();
    const blob = new Blob([currentNotation], { type: "text/plain;charset=utf-8" });
    const url = window.URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `searchess-game-${game.id.slice(0, 8)}.${ext}`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    window.URL.revokeObjectURL(url);
    setExportNotice(`${notationFormat} downloaded.`);
    setTimeout(() => setExportNotice(null), 3000);
  };

  const readNotationFile = async (file: File | undefined, setter: (v: string) => void) => {
    if (!file) return;
    setter(await file.text());
  };

  const saveSession = async () => {
    setSaveNotice(null);
    setSaveError(null);
    try {
      await onSaveSession();
      setSaveNotice("Session saved successfully.");
      setTimeout(() => setSaveNotice(null), 5000);
    } catch (error) {
      setSaveError(error instanceof Error ? error.message : "Session save failed.");
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
    <>
      <div className="gmd-backdrop" onClick={onClose} aria-hidden="true" />

      <div
        className="gmd-drawer"
        role="dialog"
        aria-modal="true"
        aria-label="Game Menu"
      >
        <header className="gmd-header">
          <h2 className="gmd-title">Game Menu</h2>
          <Button variant="ghost" onClick={onClose} aria-label="Close menu">
            ✕
          </Button>
        </header>

        <div className="gmd-body">

          {/* ── Game Actions ───────────────────────────────────────────── */}
          <section className="gmd-section gmd-section--actions">
            <h3 className="gmd-section-label">Game</h3>
            <div className="gmd-action-row">
              <Button
                variant="ghost"
                onClick={() => { onBackToMenu(); onClose(); }}
              >
                Return to Main Menu
              </Button>
              <Button
                variant="secondary"
                disabled={busy}
                onClick={() => { onNewGame(); onClose(); }}
              >
                Restart Game
              </Button>
              <Button
                variant="ghost"
                onClick={() => { onOpenHeatmap(); onClose(); }}
              >
                View Analytics
              </Button>
              <Button
                variant="ghost"
                disabled={activeTab === "local"}
                onClick={() => { setActiveTab("local"); onClose(); }}
              >
                🎮 Play Locally
              </Button>
              <Button
                variant="ghost"
                disabled={activeTab === "bot"}
                onClick={() => { setActiveTab("bot"); onClose(); }}
              >
                🤖 Bot-Live-Monitor
              </Button>
            </div>
          </section>

          {/* ── Appearance ─────────────────────────────────────────────── */}
          <section className="gmd-section">
            <AppBackgroundPanel
              backgrounds={backgrounds}
              backgroundId={backgroundId}
              onBackgroundChange={onBackgroundChange}
            />
          </section>

          <section className="gmd-section">
            <BackgroundPanel
              gameScenes={gameScenes}
              gameSceneId={gameSceneId}
              onGameSceneChange={onGameSceneChange}
            />
          </section>

          {/* ── Notation ───────────────────────────────────────────────── */}
          <section className="gmd-section">
            <h3 className="gmd-section-label">Notation</h3>

            <div className="gmd-notation-format">
              <label htmlFor="gmd-notation-format" className="gmd-label">Format</label>
              <select
                id="gmd-notation-format"
                className="gmd-select"
                value={notationFormat}
                onChange={e => setNotationFormat(e.target.value as "FEN" | "PGN")}
                disabled={busy}
              >
                <option value="FEN">FEN</option>
                <option value="PGN">PGN</option>
              </select>
            </div>

            <pre className="gmd-notation-text">{currentNotation ?? "Not available"}</pre>

            <div className="gmd-row">
              <Button
                variant="secondary"
                disabled={!currentNotation || !navigator.clipboard?.writeText}
                onClick={() => void copyNotation()}
              >
                Copy
              </Button>
              <Button
                variant="secondary"
                disabled={!currentNotation || !game}
                onClick={downloadNotation}
              >
                Download
              </Button>
            </div>

            {exportNotice && <p className="gmd-success">{exportNotice}</p>}

            <div className="gmd-import">
              <label className="gmd-label">Import {notationFormat}</label>
              <textarea
                className="gmd-textarea"
                value={currentDraft}
                onChange={e => setCurrentDraft(e.target.value)}
                placeholder={`Paste ${notationFormat} here`}
                rows={3}
                disabled={busy}
              />
              <input
                ref={importFileInputRef}
                type="file"
                accept={notationFormat === "FEN" ? ".fen,.txt" : ".pgn,.txt"}
                disabled={busy}
                onChange={e => void readNotationFile(e.currentTarget.files?.[0], setCurrentDraft)}
                className="gmd-file-input"
              />
              <Button
                variant="secondary"
                disabled={busy}
                onClick={() => importFileInputRef.current?.click()}
              >
                Choose File for Import
              </Button>
              <Button
                variant="secondary"
                disabled={busy || !currentDraft.trim()}
                onClick={() => onImportNotation(notationFormat, currentDraft)}
              >
                Import {notationFormat}
              </Button>
            </div>
          </section>

          {/* ── Session ────────────────────────────────────────────────── */}
          <section className="gmd-section">
            <h3 className="gmd-section-label">Session</h3>

            <dl className="gmd-identifiers">
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
              variant="secondary"
              disabled={busy || !sessionId || !game}
              onClick={() => void saveSession()}
            >
              Save Session
            </Button>

            <p className="gmd-note">
              Moves are persisted automatically; this confirms the current session.
            </p>

            {saveNotice && <p className="gmd-success">{saveNotice}</p>}
            {saveError && <p className="gmd-error">{saveError}</p>}
          </section>

          {/* ── AI vs AI (only when relevant) ──────────────────────────── */}
          {gameMode === "AIVsAI" && (
            <section className="gmd-section">
              <h3 className="gmd-section-label">AI vs AI</h3>

              <label htmlFor="gmd-ai-max-plies" className="gmd-label">Max plies</label>
              <input
                id="gmd-ai-max-plies"
                type="number"
                min={1}
                max={1000}
                step={1}
                value={aiMaxPlies}
                disabled={busy}
                className="gmd-number-input"
                onChange={e => {
                  const n = Number(e.currentTarget.value);
                  setAiMaxPlies(Number.isFinite(n) ? n : 20);
                }}
              />

              <Button
                variant="primary"
                disabled={busy || !gameId || !game}
                onClick={() => void runAiTurnBatch()}
              >
                Run AI Turns
              </Button>

              {aiRunResult && (
                <p className="gmd-success">
                  Plies run: {aiRunResult.pliesRun}. Stop: {aiRunResult.stopReason}.
                </p>
              )}
              {aiRunError && <p className="gmd-error">{aiRunError}</p>}
            </section>
          )}

        </div>
      </div>
    </>
  );
}
