import { useState } from "react";
import type { PlayableGameMode } from "../api/types";
import type { UserProfileResponse } from "../api/userServiceTypes";
import { gameModes, isPlayableGameMode, type GameModeId } from "../gameModes";
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
  onOpenLichessHub: () => void;
}

export default function Homepage({
  hasActiveGame,
  busy,
  onboardingRequired,
  profile,
  onStart,
  onContinueActiveGame,
  onResumeSession,
  onOpenSettings,
  onOpenLichessHub
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
          <p className="homepage-subtitle">Choose how you want to play.</p>
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

        <section className="lichess-hub-section" aria-label="Play on Lichess">
          <h2 className="lichess-hub-heading">Play on Lichess</h2>
          <article className="lichess-hub-card">
            <p className="lichess-hub-description">
              Challenge the Searchess Bot on Lichess from your linked Lichess account. More Lichess features will appear here as OAuth support grows.
            </p>
            <p className="lichess-hub-account-hint">
              {hasVerifiedLichessLink
                ? "Linked Lichess account detected."
                : "Link your Lichess account in Settings first."}
            </p>
            <button type="button" className="lichess-hub-cta" onClick={onOpenLichessHub}>
              Open Lichess Hub
            </button>
          </article>
        </section>

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
            Start Game
          </button>
        ) : null}

        {hasActiveGame && (
          <button
            className="resume-btn"
            type="button"
            onClick={onContinueActiveGame}
          >
            Continue Active Game
          </button>
        )}

      </section>

      <ResumeGamePanel busy={busy} onResume={onResumeSession} />

    </div>
  );
}
