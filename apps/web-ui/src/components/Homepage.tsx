import { useState, useEffect, useRef } from "react";
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
  const [botStatus, setBotStatus] = useState<string>("stopped");
  const [botLogs, setBotLogs] = useState<string[]>([]);
  const [actionLoading, setActionLoading] = useState<boolean>(false);
  const logContainerRef = useRef<HTMLDivElement | null>(null);

  // Poll status
  useEffect(() => {
    let active = true;
    const fetchStatus = async () => {
      try {
        const res = await fetch("/api/dev-bot/status");
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

  // Scroll to bottom when logs change
  useEffect(() => {
    if (logContainerRef.current) {
      logContainerRef.current.scrollTop = logContainerRef.current.scrollHeight;
    }
  }, [botLogs]);

  const handleStartBot = async () => {
    setActionLoading(true);
    try {
      const res = await fetch("/api/dev-bot/start", { method: "POST" });
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
      const res = await fetch("/api/dev-bot/stop", { method: "POST" });
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
          <button
            type="button"
            className={`mode-btn ${mode === "AIVsAI" ? "is-active" : ""}`}
            onClick={() => setMode("AIVsAI")}
          >
            AI vs AI
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
          Steuere die lokale Lichess Bot API. Der Bot akzeptiert Herausforderungen und spielt automatisch.
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
              <div className="bot-console-empty">Keine Logs vorhanden. Starte den Bot, um Ausgaben zu sehen.</div>
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
