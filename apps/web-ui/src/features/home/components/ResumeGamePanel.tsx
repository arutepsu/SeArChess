import { useCallback, useEffect, useState } from "react";
import type { SessionResponse } from "../../../api/backendTypes";
import { listSessions } from "../../../api/client";
import Button from "../../../components/ui/Button";
import GlassPanel from "../../../components/ui/GlassPanel";
import "./ResumeGamePanel.css";

type Props = {
  busy: boolean;
  onResume: (sessionId: string) => Promise<void>;
};

const RESUMABLE_LIFECYCLES = new Set(["Created", "Active", "AwaitingPromotion"]);

function formatMode(mode: string): string {
  switch (mode) {
    case "HumanVsHuman":
      return "Human vs Human";
    case "HumanVsAI":
      return "Human vs AI";
    case "AIVsAI":
      return "AI vs AI";
    default:
      return mode;
  }
}

function formatDate(iso: string): string {
  if (!iso) {
    return "Not available";
  }

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
      setSessions(
        result.sessions
          .filter(session => RESUMABLE_LIFECYCLES.has(session.lifecycle))
          .sort((left, right) =>
            Date.parse(right.updatedAt) - Date.parse(left.updatedAt)
          )
      );
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to load sessions.");
      setSessions(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void handleLoad();
  }, [handleLoad]);

  const handleResume = useCallback(async (sessionId: string) => {
    setError(null);
    try {
      await onResume(sessionId);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to load session.");
    }
  }, [onResume]);

  return (
    <GlassPanel as="section" className="resume-panel" variant="subtle" aria-label="Saved Sessions">
      <div className="resume-header">
        <div>
          <h2>Saved Sessions</h2>
          <p>
            {sessions === null
              ? "Checking for saved games..."
              : `${sessions.length} available`}
          </p>
        </div>

        <Button
          className="resume-game-btn"
          disabled={busy || loading}
          onClick={() => void handleLoad()}
        >
          {loading ? "Refreshing..." : "Refresh"}
        </Button>
      </div>

      {error && <p className="resume-error">{error}</p>}

      {sessions !== null && (
        <div className="session-list">
          {sessions.length === 0 ? (
            <p className="resume-empty">No resumable sessions found.</p>
          ) : (
            sessions.slice(0, 10).map(session => (
              <article
                key={session.sessionId}
                className="session-item"
              >
                <div className="session-main">
                  <span className="session-label">Game ID</span>
                  <span className="session-id" title={session.gameId}>
                    {session.gameId}
                  </span>
                  <span className="session-sub-id" title={session.sessionId}>
                    Session {session.sessionId}
                  </span>
                </div>

                <dl className="session-details">
                  <div>
                    <dt>Mode</dt>
                    <dd>{formatMode(session.mode)}</dd>
                  </div>
                  <div>
                    <dt>Status</dt>
                    <dd>{session.lifecycle}</dd>
                  </div>
                  <div>
                    <dt>Updated</dt>
                    <dd>{formatDate(session.updatedAt)}</dd>
                  </div>
                  <div>
                    <dt>Created</dt>
                    <dd>{formatDate(session.createdAt)}</dd>
                  </div>
                </dl>

                <Button
                  className="session-load-btn"
                  disabled={busy}
                  onClick={() => void handleResume(session.sessionId)}
                >
                  Load
                </Button>
              </article>
            ))
          )}
        </div>
      )}
    </GlassPanel>
  );
}
