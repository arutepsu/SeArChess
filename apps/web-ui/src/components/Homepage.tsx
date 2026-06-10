import { useState, useEffect, useRef } from "react";
import type { PlayableGameMode } from "../api/types";
import type { UserProfileResponse } from "../api/userServiceTypes";
import { gameModes, isPlayableGameMode, type GameModeId } from "../gameModes";
import ResumeGamePanel from "./ResumeGamePanel.tsx";
import keycloak from "../auth/keycloak";
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
  onOpenLichessHub,
}: HomepageProps) {
  const [mode, setMode] = useState<GameModeId>("HumanVsHuman");
  const [botStatus, setBotStatus] = useState<string>("stopped");
  const [botLogs, setBotLogs] = useState<string[]>([]);
  const [actionLoading, setActionLoading] = useState<boolean>(false);
  const logContainerRef = useRef<HTMLDivElement | null>(null);

  const hasVerifiedLichessLink =
    profile?.links?.some((l) => l.provider === "Lichess" && l.verified) ?? false;

  const deployedBotEligibilityBlocked =
    mode === "HumanVsDeployedBot" && !hasVerifiedLichessLink;

  useEffect(() => {
    let active = true;
    const fetchStatus = async () => {
      try {
        const headers: Record<string, string> = {};
        if (keycloak.token) {
          headers["Authorization"] = `Bearer ${keycloak.token}`;
        }
        const res = await fetch("/api/bot/status", { headers });
        if (!active) return;
        if (res.ok) {
          const data = await res.json();
          setBotStatus(data.status);
          setBotLogs(data.logs || []);
        }
      } catch (err) {
        console.error("Failed to fetch bot status:", err);
      }
    };

    fetchStatus();
    const interval = setInterval(fetchStatus, 2000);
    return () => {
      active = false;
      clearInterval(interval);
    };
  }, []);

  useEffect(() => {
    if (logContainerRef.current) {
      logContainerRef.current.scrollTop = logContainerRef.current.scrollHeight;
    }
  }, [botLogs]);

  const handleStartBot = async () => {
    setActionLoading(true);
    try {
      const headers: Record<string, string> = {};
      if (keycloak.token) {
        headers["Authorization"] = `Bearer ${keycloak.token}`;
      }
      const res = await fetch("/api/bot/start", { method: "POST", headers });
      if (res.ok) {
        const data = await res.json();
        setBotStatus(data.status);
      }
    } catch (err) {
      console.error("Failed to start bot:", err);
    } finally {
      setActionLoading(false);
    }
  };

  const handleStopBot = async () => {
    setActionLoading(true);
    try {
      const headers: Record<string, string> = {};
      if (keycloak.token) {
        headers["Authorization"] = `Bearer ${keycloak.token}`;
      }
      const res = await fetch("/api/bot/stop", { method: "POST", headers });
      if (res.ok) {
        const data = await res.json();
        setBotStatus(data.status);
      }
    } catch (err) {
      console.error("Failed to stop bot:", err);
    } finally {
      setActionLoading(false);
    }
  };

  const getStatusText = () => {
    switch (botStatus) {
      case "running":
        return "Aktiv";
      case "starting":
        return "Startet...";
      case "stopping":
        return "Stoppt...";
      case "stopped":
      default:
        return "Inaktiv";
    }
  };

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
              Challenge the Searchess Bot on Lichess from your linked Lichess account. More
              Lichess features will appear here as OAuth support grows.
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

      <section className="panel bot-control-panel">
        <header className="bot-header">
          <h2 className="bot-panel-title">🤖 Lichess Bot</h2>
          <div className={`bot-status-badge ${botStatus}`}>
            <span className="bot-status-dot" />
            <span className="bot-status-text">{getStatusText()}</span>
          </div>
        </header>

        <p className="bot-panel-desc">
          Steuere die lokale Lichess Bot API. Der Bot akzeptiert Herausforderungen und spielt
          automatisch.
        </p>

        <div className="bot-actions">
          {botStatus === "stopped" ? (
            <button
              className="bot-action-btn start"
              type="button"
              onClick={handleStartBot}
              disabled={actionLoading}
            >
              {actionLoading ? "Startet..." : "Bot Starten"}
            </button>
          ) : (
            <button
              className="bot-action-btn stop"
              type="button"
              onClick={handleStopBot}
              disabled={actionLoading}
            >
              {actionLoading ? "Stoppt..." : "Bot Stoppen"}
            </button>
          )}
        </div>

        <div className="bot-console-container">
          <div className="bot-console-header">Console Output</div>
          <div className="bot-console" ref={logContainerRef}>
            {botLogs.length === 0 ? (
              <div className="bot-console-empty">
                Keine Logs vorhanden. Starte den Bot, um Ausgaben zu sehen.
              </div>
            ) : (
              botLogs.map((log, i) => (
                <div key={i} className="bot-console-line">
                  {log}
                </div>
              ))
            )}
          </div>
        </div>
      </section>

      <ResumeGamePanel busy={busy} onResume={onResumeSession} />
    </div>
  );
}
