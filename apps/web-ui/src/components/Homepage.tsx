import { useState } from "react";
import type { PlayableGameMode } from "../api/types";
import type { UserProfileResponse } from "../api/userServiceTypes";
import { gameModes, lichessBridgeModes, isPlayableGameMode, type GameModeId } from "../gameModes";
import ResumeGamePanel from "./ResumeGamePanel.tsx";
import "./Homepage.css";

interface HomepageProps {
  hasActiveGame: boolean;
  busy: boolean;
  onboardingRequired: boolean;
  profile: UserProfileResponse | null;
  onStart: (mode: PlayableGameMode) => void;
  onContinueActiveGame: () => void;
  onResumeSession: (sessionId: string) => Promise<void>;
  onOpenSettings: () => void;
  onOpenOnboarding: () => void;
}

export default function Homepage({
  hasActiveGame,
  busy,
  onboardingRequired,
  profile,
  onStart,
  onContinueActiveGame,
  onResumeSession,
  onOpenSettings
}: HomepageProps) {
  const [mode, setMode] = useState<GameModeId>("HumanVsHuman");

  const hasVerifiedLichessLink = profile?.links?.some(
    (l) => l.provider === "Lichess" && l.verified
  ) ?? false;

  const deployedBotEligibilityBlocked =
    mode === "HumanVsDeployedBot" && !hasVerifiedLichessLink;

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
              if (gameModes.find((entry) => entry.id === id)?.active) {
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

        <div className="lichess-bridge-section" aria-label="Lichess Bridge (Coming Soon)">
          <h2 className="lichess-bridge-heading">Lichess Bridge <span className="lichess-bridge-badge">Coming Soon</span></h2>
          <div className="mode-selection">
            {lichessBridgeModes.map((item) => (
              <article key={item.id} className="mode-card is-disabled">
                <header>
                  <h2>{item.title}</h2>
                  <span>Coming Soon</span>
                </header>
                <p>{item.summary}</p>
                <button type="button" disabled>
                  {item.startLabel}
                </button>
              </article>
            ))}
          </div>
        </div>

        {onboardingRequired && (
          <p className="onboarding-notice">
            Complete your profile (choose a nickname) before starting a game. →{" "}
            <a href="/onboarding">Go to onboarding</a>
          </p>
        )}

        {isPlayableGameMode(mode) && deployedBotEligibilityBlocked ? (
          <div className="onboarding-notice">
            <p>A verified Lichess account link is required to play against the deployed bot.</p>
            <button type="button" onClick={onOpenSettings}>
              Go to Settings
            </button>
          </div>
        ) : isPlayableGameMode(mode) ? (
          <button
            className="start-btn"
            type="button"
            disabled={busy || onboardingRequired}
            onClick={() => onStart(mode)}
          >
            Spiel Starten
          </button>
        ) : null}

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
