import { useState } from "react";
import type { PlayableGameMode } from "../api/types";
import type { BotChallengeResponse } from "../api/backendTypes";
import type { UserProfileResponse } from "../api/userServiceTypes";
import { createBotChallenge } from "../api/client";
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
  onOpenOnboarding
}: HomepageProps) {
  const [mode, setMode] = useState<PlayableGameMode>("HumanVsHuman");
  const [botColor, setBotColor] = useState<"white" | "black" | "random">("random");
  const [botLimit, setBotLimit] = useState(300);
  const [botIncrement, setBotIncrement] = useState(3);
  const [botBusy, setBotBusy] = useState(false);
  const [botResult, setBotResult] = useState<BotChallengeResponse | null>(null);
  const [botError, setBotError] = useState<string | null>(null);

  const verifiedLichessLink = profile?.links.find((link) => link.provider === "Lichess" && link.verified) ?? null;
  const canChallengeBot = Boolean(profile && !profile.onboardingRequired && profile.nickname && verifiedLichessLink);

  const handleBotChallenge = async () => {
    if (onboardingRequired || profile?.onboardingRequired) {
      onOpenOnboarding();
      return;
    }
    if (!verifiedLichessLink || !profile?.nickname) {
      onOpenSettings();
      return;
    }
    setBotBusy(true);
    setBotError(null);
    setBotResult(null);
    try {
      const result = await createBotChallenge({
        clockLimitSeconds: botLimit,
        clockIncrementSeconds: botIncrement,
        color: botColor,
        rated: false
      });
      setBotResult(result);
    } catch (error) {
      setBotError(error instanceof Error ? error.message : "Challenge request failed");
    } finally {
      setBotBusy(false);
    }
  };

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

        <section className="bot-challenge-panel">
          <header>
            <h2>Play against Searchess Bot</h2>
            <span>{canChallengeBot ? "Ready" : "Setup Required"}</span>
          </header>

          <div className="bot-challenge-controls">
            <label>
              Clock
              <select value={botLimit} onChange={(event) => setBotLimit(Number(event.currentTarget.value))}>
                <option value={180}>3 min</option>
                <option value={300}>5 min</option>
                <option value={600}>10 min</option>
              </select>
            </label>
            <label>
              Increment
              <select value={botIncrement} onChange={(event) => setBotIncrement(Number(event.currentTarget.value))}>
                <option value={0}>0 sec</option>
                <option value={3}>3 sec</option>
                <option value={5}>5 sec</option>
              </select>
            </label>
            <label>
              Color
              <select value={botColor} onChange={(event) => setBotColor(event.currentTarget.value as "white" | "black" | "random")}>
                <option value="random">Random</option>
                <option value="white">White</option>
                <option value="black">Black</option>
              </select>
            </label>
          </div>

          <button type="button" disabled={botBusy} onClick={() => void handleBotChallenge()}>
            {canChallengeBot ? "Send Challenge" : onboardingRequired ? "Complete Onboarding" : "Open Settings"}
          </button>

          {botError ? <p className="bot-challenge-error">{botError}</p> : null}
          {botResult ? (
            <div className="bot-challenge-success">
              <span>Challenge sent to @{botResult.lichessUsername}</span>
              {botResult.lichessChallengeUrl ? (
                <a href={botResult.lichessChallengeUrl} target="_blank" rel="noreferrer">
                  Open Lichess
                </a>
              ) : null}
            </div>
          ) : null}
        </section>

      </section>

      <ResumeGamePanel busy={busy} onResume={onResumeSession} />

    </div>
  );
}
