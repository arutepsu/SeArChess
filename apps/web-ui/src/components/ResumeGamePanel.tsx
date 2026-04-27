import { useCallback, useState } from "react";
import type { SessionResponse } from "../api/backendTypes";
import { listSessions } from "../api/client";
import "./ResumeGamePanel.css";

type Props = {
  busy: boolean;
  onResume: (sessionId: string) => Promise<void>;
};

const RESUMABLE_LIFECYCLES = new Set(["Created", "Active", "AwaitingPromotion"]);

function formatMode(mode: string): string {
  switch (mode) {
    case "HumanVsHuman": return "Human vs Human";
    case "HumanVsAI":    return "Human vs AI";
    case "AIVsAI":       return "AI vs AI";
    default:             return mode;
  }
}

function formatDate(iso: string): string {
  try {
    return new Date(iso).toLocaleString(undefined, {
      month: "short",
      day: "numeric",
      hour: "2-digit",
      minute: "2-digit"
    });
  } catch {
    return iso;
  }
}

export default function ResumeGamePanel({ busy, onResume }: Props) {
  const [loading, setLoading] = useState(false);
  const [sessions, setSessions] = useState<SessionResponse[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const handleLoad = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await listSessions();
      setSessions(result.sessions.filter(s => RESUMABLE_LIFECYCLES.has(s.lifecycle)));
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to load sessions.");
      setSessions(null);
    } finally {
      setLoading(false);
    }
  }, []);

  const handleResume = useCallback(async (sessionId: string) => {
    setSessions(null);
    await onResume(sessionId);
  }, [onResume]);

  return (
    <section className="panel resume-panel" aria-label="Resume Game">
      <header>
        <h2>Resume Game</h2>
        <p>Continue a previous session.</p>
      </header>
      <div className="resume-actions">
        <button
          type="button"
          disabled={busy || loading}
          onClick={() => void handleLoad()}
        >
          {loading ? "Loading..." : "Load Sessions"}
        </button>
      </div>
      {error && <p className="resume-error">{error}</p>}
      {sessions !== null && (
        <div className="session-list">
          {sessions.length === 0 ? (
            <p className="resume-empty">No resumable sessions found.</p>
          ) : (
            sessions.map(session => (
              <button
                key={session.sessionId}
                type="button"
                className="session-item"
                disabled={busy}
                onClick={() => void handleResume(session.sessionId)}
              >
                <span className="session-id">{session.sessionId.slice(0, 8)}</span>
                <span className="session-meta">
                  {formatMode(session.mode)} · {session.lifecycle}
                </span>
                <span className="session-date">{formatDate(session.updatedAt)}</span>
              </button>
            ))
          )}
        </div>
      )}
    </section>
  );
}
