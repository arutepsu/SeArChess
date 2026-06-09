import { useEffect, useMemo, useState } from "react";
import { useParams } from "react-router-dom";
import { getLichessGameState } from "../api/userServiceClient";
import type { LichessGameStateResponse } from "../api/userServiceTypes";
import "./LichessGamePage.css";

interface LichessGamePageProps {
  onBack: () => void;
}

function gameStateErrorMessage(error: unknown): string {
  const rawMessage = error instanceof Error ? error.message : String(error);
  let code = rawMessage.trim();

  try {
    const parsed = JSON.parse(rawMessage) as { code?: unknown };
    if (typeof parsed.code === "string") {
      code = parsed.code;
    }
  } catch {
    const match = rawMessage.match(
      /\b(NO_LICHESS_LINK|NO_LICHESS_GAME_CAPABILITY|NO_STORED_LICHESS_TOKEN|TOKEN_ENCRYPTION_NOT_CONFIGURED|LICHESS_TOKEN_EXPIRED|INVALID_LICHESS_GAME_ID|LICHESS_GAME_STATE_FAILED)\b/
    );
    if (match !== null) {
      code = match[1];
    }
  }

  switch (code) {
    case "NO_LICHESS_LINK":
      return "Link your Lichess account first.";
    case "NO_LICHESS_GAME_CAPABILITY":
      return "Upgrade your Lichess permissions before viewing this game in Searchess.";
    case "NO_STORED_LICHESS_TOKEN":
      return "Your Lichess authorization is incomplete. Please re-authorize.";
    case "TOKEN_ENCRYPTION_NOT_CONFIGURED":
      return "The server is not configured for Lichess game tracking yet.";
    case "LICHESS_TOKEN_EXPIRED":
      return "Your Lichess authorization expired. Please re-authorize.";
    case "INVALID_LICHESS_GAME_ID":
      return "The Lichess game id is invalid.";
    case "LICHESS_GAME_STATE_FAILED":
      return "Could not load the Lichess game state.";
    default:
      return "Could not load the Lichess game state.";
  }
}

export default function LichessGamePage({ onBack }: LichessGamePageProps) {
  const { gameId } = useParams<{ gameId: string }>();
  const [state, setState] = useState<LichessGameStateResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!gameId) {
      setError("The Lichess game id is invalid.");
      setLoading(false);
      return;
    }

    let active = true;

    const load = async () => {
      try {
        const next = await getLichessGameState(gameId);
        if (!active) return;
        setState(next);
        setError(null);
      } catch (err) {
        if (!active) return;
        setError(gameStateErrorMessage(err));
      } finally {
        if (active) setLoading(false);
      }
    };

    void load();
    const intervalId = window.setInterval(() => void load(), 2500);

    return () => {
      active = false;
      window.clearInterval(intervalId);
    };
  }, [gameId]);

  const moves = useMemo(
    () => state?.moves.split(/\s+/).filter(Boolean) ?? [],
    [state?.moves]
  );

  const lichessUrl = state?.url ?? (gameId ? `https://lichess.org/${gameId}` : "https://lichess.org");

  return (
    <main className="lichess-game-page">
      <section className="panel lichess-game-panel">
        <header className="lichess-game-header">
          <div>
            <h1>Lichess Game</h1>
            <p className="lichess-game-muted">{gameId ?? "Unknown game"}</p>
          </div>
          <div className="lichess-game-actions">
            <button type="button" onClick={onBack}>Back to Lichess Hub</button>
            <a href={lichessUrl} target="_blank" rel="noopener noreferrer">
              Open on Lichess ↗
            </a>
          </div>
        </header>

        {error !== null && (
          <p className="lichess-game-warning">{error}</p>
        )}

        {loading && state === null ? (
          <div className="lichess-game-loading">Loading game state...</div>
        ) : state !== null ? (
          <div className="lichess-game-grid">
            <section className="lichess-game-card" aria-label="Game status">
              <h2>Status</h2>
              <dl className="lichess-game-facts">
                <dt>State</dt>
                <dd>{state.status}</dd>
                <dt>Side to move</dt>
                <dd>{state.sideToMove}</dd>
                <dt>Your color</dt>
                <dd>{state.userColor ?? "Unknown"}</dd>
                <dt>BOT color</dt>
                <dd>{state.botColor ?? "Unknown"}</dd>
                <dt>Updated</dt>
                <dd>{new Date(state.lastUpdatedAt).toLocaleTimeString()}</dd>
              </dl>
            </section>

            <section className="lichess-game-card" aria-label="Players">
              <h2>Players</h2>
              <div className="lichess-game-player-row">
                <span>White</span>
                <strong>{state.white.username}</strong>
                {state.white.isSearchessBot ? <em>Searchess BOT</em> : null}
              </div>
              <div className="lichess-game-player-row">
                <span>Black</span>
                <strong>{state.black.username}</strong>
                {state.black.isSearchessBot ? <em>Searchess BOT</em> : null}
              </div>
            </section>

            <section className="lichess-game-card lichess-game-card--wide" aria-label="Current FEN">
              <h2>Current FEN</h2>
              <pre className="lichess-game-code">{state.fen ?? "Not available from Lichess yet."}</pre>
            </section>

            <section className="lichess-game-card lichess-game-card--wide" aria-label="Moves">
              <h2>Moves</h2>
              {moves.length === 0 ? (
                <p className="lichess-game-muted">No moves yet.</p>
              ) : (
                <ol className="lichess-game-moves">
                  {moves.map((move, index) => (
                    <li key={`${move}-${index}`}>{move}</li>
                  ))}
                </ol>
              )}
            </section>
          </div>
        ) : null}
      </section>
    </main>
  );
}
