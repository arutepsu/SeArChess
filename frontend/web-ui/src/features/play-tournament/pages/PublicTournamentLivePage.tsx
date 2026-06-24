import { useCallback, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { authHeaders } from "../../../api/client";
import {
  getPublicTournament,
  publicTournamentStreamUrl,
} from "../../../api/publicTournamentClient";
import type { PublicTournament, PublicTournamentEvent } from "../../../api/publicTournamentTypes";
import Button from "../../../components/ui/Button";
import { useNDJSONStream } from "../../../hooks/useNDJSONStream";
import "./PlayTournament.css";

function StreamStatusBadge({ status }: { status: string }) {
  const map: Record<string, { label: string; cls: string }> = {
    idle:       { label: "Idle",        cls: "play-tournament-stream-badge--idle" },
    connecting: { label: "Connecting…", cls: "play-tournament-stream-badge--connecting" },
    open:       { label: "Live",        cls: "play-tournament-stream-badge--open" },
    closed:     { label: "Closed",      cls: "play-tournament-stream-badge--closed" },
    error:      { label: "Error",       cls: "play-tournament-stream-badge--error" },
  };
  const { label, cls } = map[status] ?? { label: status, cls: "" };
  return <span className={`play-tournament-stream-badge ${cls}`}>{label}</span>;
}

function shortEventTypeLabel(type: string): string {
  const labels: Record<string, string> = {
    tournamentStarted:  "Started",
    tournamentFinished: "Finished",
    roundStarted:       "Round ▶",
    roundFinished:      "Round ■",
    gameStart:          "Game ▶",
    gameEnd:            "Game ■",
    heartbeat:          "♥",
  };
  return labels[type] ?? type;
}

function eventDescription(e: PublicTournamentEvent): string {
  switch (e.type) {
    case "tournamentStarted":
      return "Tournament is underway";
    case "tournamentFinished":
      return `Tournament finished${e.winner ? ` — winner: ${String(e.winner)}` : ""}`;
    case "roundStarted":
      return `Round ${e.round ?? "?"} started`;
    case "roundFinished":
      return `Round ${e.round ?? "?"} finished`;
    case "gameStart":
      return `${e.whiteBotName ?? e.whiteBotId ?? "?"} vs ${e.blackBotName ?? e.blackBotId ?? "?"}`;
    case "gameEnd":
      return `Game ended${e.winner ? ` — ${String(e.winner)} wins` : ""}`;
    case "heartbeat":
      return "Connection alive";
    default:
      return `Event: ${e.type}`;
  }
}

interface ActiveGame {
  gameId: string;
  whiteName: string;
  blackName: string;
  round?: number;
  moveCount: number;
  lastMoveUci?: string;
}

export default function PublicTournamentLivePage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [tournament, setTournament] = useState<PublicTournament | null>(null);
  // Active games: added on gameStart, removed on gameEnd
  const [activeGames, setActiveGames] = useState<Map<string, ActiveGame>>(new Map());

  useEffect(() => {
    if (!id) return;
    getPublicTournament(id).then(setTournament).catch(() => {});
  }, [id]);

  const getRequest = useCallback(async () => {
    const auth = await authHeaders();
    return { url: publicTournamentStreamUrl(id ?? ""), headers: auth };
  }, [id]);

  const { events, latestEvent, status, error, connect, disconnect } =
    useNDJSONStream<PublicTournamentEvent>(getRequest, { autoConnect: true });

  // Update active games map as events arrive
  useEffect(() => {
    if (!latestEvent) return;
    if (latestEvent.type === "gameStart" && typeof latestEvent.gameId === "string") {
      const gid = latestEvent.gameId;
      setActiveGames((prev) => {
        const next = new Map(prev);
        next.set(gid, {
          gameId: gid,
          whiteName: (latestEvent.whiteBotName ?? latestEvent.whiteBotId ?? "?") as string,
          blackName: (latestEvent.blackBotName ?? latestEvent.blackBotId ?? "?") as string,
          round: latestEvent.round,
          moveCount: 0,
        });
        return next;
      });
    } else if (latestEvent.type === "gameEnd" && typeof latestEvent.gameId === "string") {
      const gid = latestEvent.gameId;
      setActiveGames((prev) => {
        const next = new Map(prev);
        next.delete(gid);
        return next;
      });
    } else if (
      (latestEvent.type === "gameState" || latestEvent.type === "move") &&
      typeof latestEvent.gameId === "string"
    ) {
      const gid = latestEvent.gameId;
      setActiveGames((prev) => {
        if (!prev.has(gid)) return prev;
        const next = new Map(prev);
        const game = next.get(gid)!;
        const movesArr = latestEvent.moves as string[] | undefined;
        const lastMove = latestEvent.lastMove as string | undefined;
        if (movesArr !== undefined) {
          // Server-provided full moves list is authoritative — use its length directly
          // to avoid double-counting when gameState and move events arrive for the same move.
          next.set(gid, {
            ...game,
            moveCount: movesArr.length,
            lastMoveUci: movesArr[movesArr.length - 1],
          });
        } else if (lastMove && lastMove !== game.lastMoveUci) {
          // Incremental move event — only count if it's a move we haven't seen yet.
          next.set(gid, { ...game, moveCount: game.moveCount + 1, lastMoveUci: lastMove });
        }
        return next;
      });
    } else if (latestEvent.type === "tournamentFinished") {
      // Clear active games when tournament ends
      setActiveGames(new Map());
    }
  }, [latestEvent]);

  const visibleEvents = events.filter((e) => e.type !== "heartbeat");
  const activeGamesList = [...activeGames.values()];

  return (
    <div className="play-tournament-page">
      <div className="play-tournament-shell">
        <div className="play-tournament-header">
          <div>
            <h1 className="play-tournament-title">
              {tournament?.fullName ?? "Public Tournament"} — Live
            </h1>
            <p className="play-tournament-subtitle">
              Spectator mode &nbsp;·&nbsp; <StreamStatusBadge status={status} />
            </p>
          </div>
          <div style={{ display: "flex", gap: 10, flexWrap: "wrap" }}>
            {(status === "closed" || status === "error") && (
              <Button variant="primary" size="sm" onClick={connect}>Reconnect</Button>
            )}
            {status === "open" && (
              <Button variant="secondary" size="sm" onClick={disconnect}>Disconnect</Button>
            )}
            <Button
              variant="secondary"
              size="lg"
              onClick={() => navigate(`/play-tournament/${id ?? ""}`)}
            >
              ← Tournament
            </Button>
          </div>
        </div>

        {error && (
          <div className="play-tournament-stream-error">Stream error: {error}</div>
        )}

        {/* Active games — always shown; empty state explains the streaming model */}
        <div className="play-tournament-group">
          <p className="play-tournament-group-title">
            Active games{activeGamesList.length > 0 ? ` (${activeGamesList.length})` : ""}
          </p>
          {activeGamesList.length > 0 ? (
            <div className="play-tournament-grid">
              {activeGamesList.map((game) => (
                <div
                  key={game.gameId}
                  className="play-tournament-card pt-game-card"
                >
                  <p className="play-tournament-card-name">
                    ♘ {game.whiteName}
                  </p>
                  <p className="play-tournament-card-name" style={{ marginTop: 2 }}>
                    ♞ {game.blackName}
                  </p>
                  <p className="play-tournament-card-meta" style={{ marginTop: 8 }}>
                    {game.round !== undefined ? `Round ${game.round}` : ""}
                    {game.round !== undefined && " · "}
                    <span
                      className="play-tournament-stream-badge play-tournament-stream-badge--open"
                      style={{ fontSize: "0.65rem", padding: "1px 6px", animation: "none" }}
                    >
                      Ongoing
                    </span>
                    {game.moveCount > 0 && ` · ${game.moveCount} move${game.moveCount !== 1 ? "s" : ""}`}
                  </p>
                  <div style={{ display: "flex", gap: 8, marginTop: 10 }}>
                    <button
                      type="button"
                      className="pt-replay-live-btn"
                      style={{ fontSize: "0.78rem" }}
                      onClick={() =>
                        navigate(`/play-tournament/${id ?? ""}/game/${game.gameId}`)
                      }
                    >
                      Watch →
                    </button>
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <p className="play-tournament-empty">
              {status === "open"
                ? "No active games right now. Games will appear here automatically when a new round starts."
                : "Connect to the stream to watch active games. When you join mid-round, games appear as new game-start events arrive."}
            </p>
          )}
        </div>

        {/* Event feed */}
        <div className="play-tournament-group">
          <p className="play-tournament-group-title">
            Event feed
            {visibleEvents.length > 0 && (
              <span style={{ fontWeight: 400, marginLeft: 8 }}>
                ({visibleEvents.length} event{visibleEvents.length !== 1 ? "s" : ""})
              </span>
            )}
          </p>

          {visibleEvents.length === 0 ? (
            <p className="play-tournament-empty">
              {status === "connecting"
                ? "Connecting to stream…"
                : status === "idle"
                ? "Stream not started."
                : "No events yet."}
            </p>
          ) : (
            <ul className="play-tournament-event-list">
              {[...visibleEvents].reverse().map((e, i) => (
                <li
                  key={i}
                  className={[
                    "play-tournament-event-item",
                    e.type === "gameStart" ? "play-tournament-event-item--game" : "",
                  ]
                    .filter(Boolean)
                    .join(" ")}
                >
                  <span className="play-tournament-event-type">
                    {shortEventTypeLabel(e.type)}
                  </span>
                  <span className="play-tournament-event-label">
                    {eventDescription(e)}
                  </span>
                  {e.type === "gameStart" && typeof e.gameId === "string" && (
                    <button
                      type="button"
                      className="play-tournament-event-link"
                      onClick={() =>
                        navigate(
                          `/play-tournament/${id ?? ""}/game/${e.gameId as string}`
                        )
                      }
                    >
                      Watch →
                    </button>
                  )}
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
    </div>
  );
}
