import { useState } from "react";
import type { PlayableGameMode } from "../api/types";
import ResumeGamePanel from "./ResumeGamePanel.tsx";
import "./Homepage.css";

interface HomepageProps {
  hasActiveGame: boolean;
  busy: boolean;
  onStart: (mode: PlayableGameMode) => void;
  onContinueActiveGame: () => void;
  onResumeSession: (sessionId: string) => Promise<void>;
}

export default function Homepage({ hasActiveGame, busy, onStart, onContinueActiveGame, onResumeSession }: HomepageProps) {
  const [mode, setMode] = useState<PlayableGameMode>("HumanVsHuman");

  return (
    <div className="homepage">
      <section className="panel homepage-panel">
        <header>
          <h1 className="homepage-title">SeArChess</h1>
          <p className="homepage-subtitle">Wähle deinen Spielmodus, um zu beginnen.</p>
        </header>

        <div className="mode-selection">
          <button
            type="button"
            className={`mode-btn ${mode === "HumanVsHuman" ? "is-active" : ""}`}
            onClick={() => setMode("HumanVsHuman")}
          >
            Mensch vs Mensch
          </button>
          <button
            type="button"
            className={`mode-btn ${mode === "HumanVsAI" ? "is-active" : ""}`}
            onClick={() => setMode("HumanVsAI")}
          >
            Mensch vs AI
          </button>
        </div>

        <button
          className="start-btn"
          type="button"
          onClick={() => onStart(mode)}
        >
          Spiel Starten
        </button>

        {hasActiveGame && (
          <button
            className="resume-btn"
            type="button"
            onClick={onContinueActiveGame}
          >
            Aktuelles Spiel fortsetzen
          </button>
        )}
        <ResumeGamePanel busy={busy} onResume={onResumeSession} />
      </section>


    </div>
  );
}
