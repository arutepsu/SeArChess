import { useState } from "react";
import type { PlayableGameMode } from "../api/types";
import { gameModes, isPlayableGameMode, type GameModeId } from "../gameModes";
import ResumeGamePanel from "./ResumeGamePanel.tsx";
import "./Homepage.css";

interface HomepageProps {
  hasActiveGame: boolean;
  busy: boolean;
  onboardingRequired: boolean;
  onStart: (mode: PlayableGameMode) => void;
  onContinueActiveGame: () => void;
  onResumeSession: (sessionId: string) => Promise<void>;
}

export default function Homepage({ hasActiveGame, busy, onboardingRequired, onStart, onContinueActiveGame, onResumeSession }: HomepageProps) {
  const [mode, setMode] = useState<PlayableGameMode>("HumanVsHuman");

  return (
    <div className="homepage">
      <section className="panel homepage-panel">
        <header>
          <h1 className="homepage-title">SeArChess</h1>
          <p className="homepage-subtitle">Wähle deinen Spielmodus, um zu beginnen.</p>
        </header>

        <div className="mode-selection" aria-label="Game modes">
          {gameModes.map((item) => {
            const selected = item.id === mode;
            const startMode = (id: GameModeId) => {
              if (isPlayableGameMode(id)) {
                setMode(id);
              }
            };

            return (
              <article
                key={item.id}
                className={`mode-card ${selected ? "is-active" : ""} ${item.active ? "" : "is-disabled"}`}
              >
                <header>
                  <h2>{item.title}</h2>
                  <span>{item.active ? "Available" : "Coming Next"}</span>
                </header>
                <p>{item.summary}</p>
                <button
                  type="button"
                  disabled={busy || !item.active}
                  onClick={() => startMode(item.id)}
                >
                  {item.startLabel}
                </button>
              </article>
            );
          })}
        </div>

        {onboardingRequired && (
          <p className="onboarding-notice">
            Complete your profile (choose a nickname) before starting a game. →{" "}
            <a href="/onboarding">Go to onboarding</a>
          </p>
        )}

        <button
          className="start-btn"
          type="button"
          disabled={busy || onboardingRequired}
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

      </section>

      <ResumeGamePanel busy={busy} onResume={onResumeSession} />

    </div>
  );
}
