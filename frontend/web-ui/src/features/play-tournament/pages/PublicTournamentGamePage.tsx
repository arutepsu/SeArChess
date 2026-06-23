import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { authHeaders } from "../../../api/client";
import {
  getPublicTournamentGame,
  publicTournamentGameStreamUrl,
} from "../../../api/publicTournamentClient";
import type { PublicTournamentEvent } from "../../../api/publicTournamentTypes";
import Button from "../../../components/ui/Button";
import ErrorState from "../../../components/ui/ErrorState";
import LoadingState from "../../../components/ui/LoadingState";
import { useNDJSONStream } from "../../../hooks/useNDJSONStream";
import PublicGameBoard from "../components/PublicGameBoard";
import { usePublicGameReplay } from "../hooks/usePublicGameReplay";
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

function ReplayControls({
  isPlaying,
  isAtLive,
  canGoPrevious,
  canGoNext,
  currentIndex,
  totalFrames,
  onGoToStart,
  onGoPrevious,
  onTogglePlay,
  onGoNext,
  onGoToEnd,
  onJumpToLive,
}: {
  isPlaying: boolean;
  isAtLive: boolean;
  canGoPrevious: boolean;
  canGoNext: boolean;
  currentIndex: number;
  totalFrames: number;
  onGoToStart: () => void;
  onGoPrevious: () => void;
  onTogglePlay: () => void;
  onGoNext: () => void;
  onGoToEnd: () => void;
  onJumpToLive: () => void;
}) {
  if (totalFrames === 0) return null;
  return (
    <div className="pt-replay-controls">
      <div className="pt-replay-controls__buttons">
        <button
          className="pt-replay-btn"
          onClick={onGoToStart}
          disabled={!canGoPrevious}
          title="First position"
          aria-label="Go to start"
        >
          ⏮
        </button>
        <button
          className="pt-replay-btn"
          onClick={onGoPrevious}
          disabled={!canGoPrevious}
          title="Previous move"
          aria-label="Previous move"
        >
          ◀
        </button>
        <button
          className="pt-replay-btn pt-replay-btn--play"
          onClick={onTogglePlay}
          disabled={!canGoNext && !isPlaying}
          title={isPlaying ? "Pause" : "Play"}
          aria-label={isPlaying ? "Pause" : "Play"}
        >
          {isPlaying ? "⏸" : "▶"}
        </button>
        <button
          className="pt-replay-btn"
          onClick={onGoNext}
          disabled={!canGoNext}
          title="Next move"
          aria-label="Next move"
        >
          ▶
        </button>
        <button
          className="pt-replay-btn"
          onClick={onGoToEnd}
          disabled={!canGoNext}
          title="Last position"
          aria-label="Go to end"
        >
          ⏭
        </button>
      </div>
      <span className="pt-replay-controls__counter">
        {currentIndex + 1} / {totalFrames}
      </span>
      {!isAtLive && (
        <button className="pt-replay-live-btn" onClick={onJumpToLive}>
          Jump to live
        </button>
      )}
    </div>
  );
}

function MoveHistory({
  frames,
  currentIndex,
  onJumpTo,
}: {
  frames: Array<{ index: number; moveUci?: string }>;
  currentIndex: number;
  onJumpTo: (index: number) => void;
}) {
  const activeRowRef = useRef<HTMLTableRowElement | null>(null);

  // Scroll active row into view whenever currentIndex changes
  useEffect(() => {
    activeRowRef.current?.scrollIntoView({ block: "nearest" });
  }, [currentIndex]);

  const moveFrames = frames.filter((f) => f.moveUci);
  if (moveFrames.length === 0) {
    return <p className="play-tournament-empty">No moves yet.</p>;
  }

  type Pair = {
    n: number;
    white: { uci: string; frameIndex: number };
    black?: { uci: string; frameIndex: number };
  };
  const pairs: Pair[] = [];
  for (let i = 0; i < moveFrames.length; i += 2) {
    const w = moveFrames[i];
    const b = moveFrames[i + 1];
    pairs.push({
      n: Math.floor(i / 2) + 1,
      white: { uci: w.moveUci!, frameIndex: w.index },
      ...(b ? { black: { uci: b.moveUci!, frameIndex: b.index } } : {}),
    });
  }

  return (
    <div className="pt-move-history">
      <table className="play-tournament-table">
        <thead>
          <tr>
            <th>#</th>
            <th>White</th>
            <th>Black</th>
          </tr>
        </thead>
        <tbody>
          {pairs.map((pair) => {
            const wActive = pair.white.frameIndex === currentIndex;
            const bActive = pair.black?.frameIndex === currentIndex;
            const rowActive = wActive || bActive;
            return (
              <tr
                key={pair.n}
                ref={rowActive ? activeRowRef : null}
                className={rowActive ? "pt-move-row--active" : undefined}
              >
                <td className="play-tournament-table-num">{pair.n}</td>
                <td>
                  <button
                    className={`pt-move-cell ${wActive ? "pt-move-cell--active" : ""}`}
                    onClick={() => onJumpTo(pair.white.frameIndex)}
                  >
                    {pair.white.uci}
                  </button>
                </td>
                <td>
                  {pair.black && (
                    <button
                      className={`pt-move-cell ${bActive ? "pt-move-cell--active" : ""}`}
                      onClick={() => onJumpTo(pair.black!.frameIndex)}
                    >
                      {pair.black.uci}
                    </button>
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

export default function PublicTournamentGamePage() {
  const { id, gameId } = useParams<{ id: string; gameId: string }>();
  const navigate = useNavigate();

  const [whiteName, setWhiteName]   = useState<string | null>(null);
  const [blackName, setBlackName]   = useState<string | null>(null);
  const [gameStatus, setGameStatus] = useState<string | null>(null);
  const [winner, setWinner]         = useState<string | null>(null);
  const [round, setRound]           = useState<number | null>(null);
  const [snapshotLoading, setSnapshotLoading] = useState(true);
  const [snapshotError, setSnapshotError]     = useState<string | null>(null);
  // Becomes true once the snapshot has been loaded and fed to the replay hook.
  // The game stream only connects after this flag is set.
  const [snapshotReady, setSnapshotReady]     = useState(false);
  const [showFen, setShowFen] = useState(false);

  const replay = usePublicGameReplay();

  // Load snapshot first; set snapshotReady to trigger stream connection
  useEffect(() => {
    if (!id || !gameId) return;
    let cancelled = false;
    setSnapshotLoading(true);
    setSnapshotError(null);
    setSnapshotReady(false);
    getPublicTournamentGame(id, gameId)
      .then((snap) => {
        if (cancelled) return;
        setWhiteName(snap.whiteBotName ?? snap.whiteBotId ?? null);
        setBlackName(snap.blackBotName ?? snap.blackBotId ?? null);
        setGameStatus(snap.status ?? null);
        setWinner(snap.winner ?? null);
        setRound(snap.round ?? null);
        replay.addSnapshot(snap);
        setSnapshotReady(true);
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        setSnapshotError(e instanceof Error ? e.message : "Failed to load game snapshot");
      })
      .finally(() => {
        if (!cancelled) setSnapshotLoading(false);
      });
    return () => { cancelled = true; };
    // replay.addSnapshot is stable
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id, gameId]);

  const getRequest = useCallback(async () => {
    const auth = await authHeaders();
    return {
      url: publicTournamentGameStreamUrl(id ?? "", gameId ?? ""),
      headers: auth,
    };
  }, [id, gameId]);

  // autoConnect: false — we trigger connect manually after snapshot is ready
  const { latestEvent, status, error, connect, disconnect } =
    useNDJSONStream<PublicTournamentEvent>(getRequest, { autoConnect: false });

  // Connect stream once snapshot has been processed
  useEffect(() => {
    if (snapshotReady) connect();
    // connect is stable (no deps other than maxEvents)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [snapshotReady]);

  // Feed stream events into replay hook
  useEffect(() => {
    if (!latestEvent) return;
    replay.addEvent(latestEvent);
    // Update game metadata from stream
    if (latestEvent.status) setGameStatus(latestEvent.status);
    if (typeof latestEvent.winner === "string") setWinner(latestEvent.winner);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [latestEvent]);

  const displayTitle =
    whiteName && blackName
      ? `${whiteName} vs ${blackName}`
      : gameId ?? "Game";

  // Map raw "white"/"black" winner token to the actual bot name for display.
  const winnerName =
    winner === "white" ? (whiteName ?? "White") :
    winner === "black" ? (blackName ?? "Black") :
    winner;

  const streamIdle = status === "idle";
  const streamDone = status === "closed" || status === "error";

  return (
    <div className="play-tournament-page">
      <div className="play-tournament-shell">

        {/* Header */}
        <div className="play-tournament-header">
          <div>
            <h1 className="play-tournament-title">{displayTitle}</h1>
            <p className="play-tournament-subtitle">
              Bot vs Bot · Spectator &nbsp;·&nbsp; <StreamStatusBadge status={status} />
            </p>
          </div>
          <div style={{ display: "flex", gap: 10, flexWrap: "wrap" }}>
            {streamDone && (
              <Button variant="primary" size="sm" onClick={connect}>Reconnect</Button>
            )}
            {status === "open" && (
              <Button variant="secondary" size="sm" onClick={disconnect}>Disconnect</Button>
            )}
            <Button
              variant="secondary"
              size="sm"
              onClick={() => navigate(`/play-tournament/${id ?? ""}/live`)}
            >
              Live view
            </Button>
            <Button
              variant="secondary"
              size="sm"
              onClick={() => navigate(`/play-tournament/${id ?? ""}`)}
            >
              ← Tournament
            </Button>
          </div>
        </div>

        {snapshotLoading && <LoadingState message="Loading game…" />}

        {snapshotError && (
          <div className="pt-snapshot-error">
            <ErrorState message={snapshotError} />
            <p className="pt-snapshot-error__hint">
              Game history is unavailable. You can still connect to the live stream to watch ongoing play.
            </p>
            {streamIdle && (
              <Button variant="secondary" size="sm" onClick={connect}>
                Connect to live stream anyway
              </Button>
            )}
          </div>
        )}

        {error && (
          <div className="play-tournament-stream-error">Stream error: {error}</div>
        )}

        {/* Reconstruction notice — shown when move list was replayed but could not be verified */}
        {replay.reconstructedFromStandard && (
          <div className="pt-reconstruction-notice">
            ⓘ Move history replayed from the standard starting position. Custom opening start positions may not replay accurately.
          </div>
        )}

        {/* Main content — show once snapshot load attempt is complete */}
        {!snapshotLoading && (
          <>
            {/* Game info row */}
            {(gameStatus ?? winner ?? round) !== null && (
              <div className="play-tournament-info-grid" style={{ marginBottom: 20 }}>
                {gameStatus && (
                  <div className="play-tournament-info-item">
                    <p className="play-tournament-info-label">Status</p>
                    <p className="play-tournament-info-value">{gameStatus}</p>
                  </div>
                )}
                {winnerName && (
                  <div className="play-tournament-info-item">
                    <p className="play-tournament-info-label">Winner</p>
                    <p className="play-tournament-info-value">{winnerName}</p>
                  </div>
                )}
                {round !== null && (
                  <div className="play-tournament-info-item">
                    <p className="play-tournament-info-label">Round</p>
                    <p className="play-tournament-info-value">{round}</p>
                  </div>
                )}
              </div>
            )}

            {/* Board + move history */}
            {replay.totalFrames > 0 && (
              <div className="pt-game-layout">

                {/* Left column: board + labels + controls */}
                <div className="pt-game-board-col">
                  <div className="pt-board-wrapper">
                    <PublicGameBoard
                      fen={replay.currentFrame?.fen ?? ""}
                      lastMoveUci={replay.currentFrame?.moveUci}
                      prevFen={replay.frames[replay.currentIndex - 1]?.fen}
                      frameIndex={replay.currentIndex}
                    />
                  </div>

                  <div className="pt-player-labels">
                    <span className="pt-player-label pt-player-label--black">
                      ♞ {blackName ?? "Black"}
                    </span>
                    <span className="pt-player-label pt-player-label--white">
                      ♘ {whiteName ?? "White"}
                    </span>
                  </div>

                  <ReplayControls
                    isPlaying={replay.isPlaying}
                    isAtLive={replay.isAtLive}
                    canGoPrevious={replay.canGoPrevious}
                    canGoNext={replay.canGoNext}
                    currentIndex={replay.currentIndex}
                    totalFrames={replay.totalFrames}
                    onGoToStart={replay.goToStart}
                    onGoPrevious={replay.goPrevious}
                    onTogglePlay={replay.togglePlay}
                    onGoNext={replay.goNext}
                    onGoToEnd={replay.goToEnd}
                    onJumpToLive={replay.jumpToLive}
                  />
                </div>

                {/* Right column: move history */}
                <div className="pt-game-moves-col">
                  <p className="play-tournament-group-title">
                    Moves ({replay.frames.filter((f) => f.moveUci).length})
                  </p>
                  <MoveHistory
                    frames={replay.frames}
                    currentIndex={replay.currentIndex}
                    onJumpTo={replay.jumpTo}
                  />
                </div>
              </div>
            )}

            {/* FEN debug — collapsible */}
            {replay.currentFrame && (
              <div style={{ marginTop: 24 }}>
                <button
                  className="pt-debug-toggle"
                  onClick={() => setShowFen((v) => !v)}
                >
                  {showFen ? "▲ Hide FEN" : "▼ Show FEN"}
                </button>
                {showFen && (
                  <p className="play-tournament-fen" style={{ marginTop: 8 }}>
                    {replay.currentFrame.fen}
                  </p>
                )}
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}
