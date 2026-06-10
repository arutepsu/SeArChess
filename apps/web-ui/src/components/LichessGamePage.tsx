import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useParams } from "react-router-dom";
import { getLichessGameState, submitLichessMove } from "../api/userServiceClient";
import { subscribeToLichessGameEvents } from "../api/lichessBridgeClient";
import type { BotGameSnapshot } from "../api/lichessBridgeClient";
import type { LichessGameStateResponse } from "../api/userServiceTypes";
import type { BoardMatrix } from "../api/types";
import type { PromotionPiece } from "../api/backendTypes";
import ChessBoard from "./ChessBoard";
import { fenToBoardMatrix } from "../domain/fen";
import { pieceAt } from "../domain/board";
import "./LichessGamePage.css";

interface LichessGamePageProps {
  onBack: () => void;
}

const EMPTY_BOARD: BoardMatrix = Array.from({ length: 8 }, () =>
  Array.from({ length: 8 }, (): null => null)
);

function isPromotionMove(board: BoardMatrix, from: string, to: string): boolean {
  const piece = pieceAt(board, from);
  if (piece === "wP") return from[1] === "7" && to[1] === "8";
  if (piece === "bP") return from[1] === "2" && to[1] === "1";
  return false;
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
      /\b(NO_LICHESS_LINK|NO_CHALLENGE_READY_CAPABILITY|NO_LICHESS_GAME_CAPABILITY|NO_STORED_LICHESS_TOKEN|TOKEN_ENCRYPTION_NOT_CONFIGURED|LICHESS_TOKEN_EXPIRED|INVALID_LICHESS_GAME_ID|LICHESS_GAME_STATE_FAILED)\b/
    );
    if (match !== null) {
      code = match[1];
    }
  }

  switch (code) {
    case "NO_LICHESS_LINK":
      return "Link your Lichess account first.";
    case "NO_CHALLENGE_READY_CAPABILITY":
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

function moveSubmitErrorMessage(error: unknown): string {
  const rawMessage = error instanceof Error ? error.message : String(error);
  let code = rawMessage.trim();

  try {
    const parsed = JSON.parse(rawMessage) as { code?: unknown };
    if (typeof parsed.code === "string") {
      code = parsed.code;
    }
  } catch {
    const match = rawMessage.match(
      /\b(INVALID_MOVE_FORMAT|NOT_USER_TURN|ILLEGAL_OR_INVALID_MOVE|LICHESS_TOKEN_EXPIRED|LICHESS_MOVE_FAILED|NO_CHALLENGE_READY_CAPABILITY|NO_STORED_LICHESS_TOKEN|TOKEN_ENCRYPTION_NOT_CONFIGURED)\b/
    );
    if (match !== null) {
      code = match[1];
    }
  }

  switch (code) {
    case "INVALID_MOVE_FORMAT":
      return "Use UCI move format, for example e2e4 or e7e8q.";
    case "NOT_USER_TURN":
      return "It is not your turn.";
    case "ILLEGAL_OR_INVALID_MOVE":
      return "Lichess rejected this move. Check that it is legal and your turn.";
    case "LICHESS_TOKEN_EXPIRED":
      return "Your Lichess authorization expired. Please re-authorize.";
    case "LICHESS_MOVE_FAILED":
      return "Could not submit the move to Lichess.";
    case "NO_CHALLENGE_READY_CAPABILITY":
      return "Upgrade your Lichess permissions before playing from Searchess.";
    case "NO_STORED_LICHESS_TOKEN":
      return "Your Lichess authorization is incomplete. Please re-authorize.";
    case "TOKEN_ENCRYPTION_NOT_CONFIGURED":
      return "The server is not configured for Lichess move submission yet.";
    default:
      return "Could not submit the move to Lichess.";
  }
}

function normalizedStatus(status: string | undefined): string {
  return (status ?? "").trim().toLowerCase();
}

function isPlayableStatus(status: string | undefined): boolean {
  return ["created", "started", "playing"].includes(normalizedStatus(status));
}

function isFinishedStatus(status: string | undefined): boolean {
  const normalized = normalizedStatus(status);
  return [
    "aborted",
    "mate",
    "resign",
    "stalemate",
    "timeout",
    "draw",
    "outoftime",
    "cheat",
    "nostart",
    "unknownfinish",
    "variantend",
  ].includes(normalized) || normalized.includes("finish");
}

function botUsername(state: LichessGameStateResponse | null): string {
  if (state?.white.isSearchessBot) return state.white.username;
  if (state?.black.isSearchessBot) return state.black.username;
  return "arutepsu2";
}

function formatClock(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${m}:${s.toString().padStart(2, "0")}`;
}

function playMoveSound(): void {
  try {
    const AudioCtx =
      window.AudioContext ??
      (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    if (!AudioCtx) return;
    const ctx  = new AudioCtx();
    const osc  = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.connect(gain);
    gain.connect(ctx.destination);
    osc.frequency.value = 880;
    gain.gain.setValueAtTime(0.15, ctx.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.08);
    osc.start(ctx.currentTime);
    osc.stop(ctx.currentTime + 0.08);
  } catch {
    // audio unavailable
  }
}

export default function LichessGamePage({ onBack }: LichessGamePageProps) {
  const { gameId } = useParams<{ gameId: string }>();
  const [state, setState] = useState<LichessGameStateResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [moveDraft, setMoveDraft] = useState("");
  const [isSubmittingMove, setIsSubmittingMove] = useState(false);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [moveMessage, setMoveMessage] = useState<string | null>(null);
  const [moveError, setMoveError] = useState<string | null>(null);
  const [lichessSelectedSquare, setLichessSelectedSquare] = useState<string | undefined>();
  const [pendingPromotion, setPendingPromotion] = useState<{ from: string; to: string } | null>(null);

  // ── Live game state via SSE ────────────────────────────────────────────────

  const [liveSnapshot, setLiveSnapshot] = useState<BotGameSnapshot | null>(null);
  const [isLiveConnected, setIsLiveConnected] = useState(false);

  useEffect(() => {
    if (!gameId) return;
    setLiveSnapshot(null);
    setIsLiveConnected(false);
    const cleanup = subscribeToLichessGameEvents(gameId, {
      onSnapshot: setLiveSnapshot,
      onError: () => setIsLiveConnected(false),
      onOpen: () => setIsLiveConnected(true),
    });
    return cleanup;
  }, [gameId]);

  // ── Move sound ─────────────────────────────────────────────────────────────

  const prevLastMoveRef = useRef<string | undefined>(undefined);
  useEffect(() => {
    const cur = liveSnapshot?.lastMove;
    if (cur && cur !== prevLastMoveRef.current) {
      prevLastMoveRef.current = cur;
      playMoveSound();
    }
  }, [liveSnapshot?.lastMove]);

  // ── Clock countdown ────────────────────────────────────────────────────────

  const clockBaseRef = useRef<{
    wtime: number;
    btime: number;
    activeSide: "white" | "black";
    receivedAt: number;
  } | null>(null);
  const [clockDisplay, setClockDisplay] = useState<{ white: string; black: string } | null>(null);

  useEffect(() => {
    if (liveSnapshot?.wtime != null && liveSnapshot.btime != null) {
      const moveCount = liveSnapshot.moves.split(/\s+/).filter(Boolean).length;
      clockBaseRef.current = {
        wtime:      liveSnapshot.wtime,
        btime:      liveSnapshot.btime,
        activeSide: moveCount % 2 === 0 ? "white" : "black",
        receivedAt: Date.now(),
      };
    }
  }, [liveSnapshot]);

  useEffect(() => {
    const id = window.setInterval(() => {
      const base = clockBaseRef.current;
      if (!base) return;
      const elapsed = Date.now() - base.receivedAt;
      const wDisp = base.activeSide === "white" ? Math.max(0, base.wtime - elapsed) : base.wtime;
      const bDisp = base.activeSide === "black" ? Math.max(0, base.btime - elapsed) : base.btime;
      setClockDisplay({ white: formatClock(wDisp), black: formatClock(bDisp) });
    }, 250);
    return () => window.clearInterval(id);
  }, []);

  // ── Polling (fallback) ─────────────────────────────────────────────────────

  const refreshGameState = useCallback(async (options?: { clearMoveFeedback?: boolean; showRefreshing?: boolean }) => {
    if (!gameId) {
      setError("The Lichess game id is invalid.");
      setLoading(false);
      return;
    }

    if (options?.clearMoveFeedback) {
      setMoveMessage(null);
      setMoveError(null);
    }

    if (options?.showRefreshing) {
      setIsRefreshing(true);
    }
    try {
      const next = await getLichessGameState(gameId);
      setState(next);
      setError(null);
    } catch (err) {
      setError(gameStateErrorMessage(err));
    } finally {
      setLoading(false);
      if (options?.showRefreshing) {
        setIsRefreshing(false);
      }
    }
  }, [gameId]);

  useEffect(() => {
    let active = true;

    const load = async () => {
      if (!active) return;
      await refreshGameState();
    };

    void load();
    const intervalId = window.setInterval(() => void load(), 2500);

    return () => {
      active = false;
      window.clearInterval(intervalId);
    };
  }, [refreshGameState]);

  // ── Derived display state ─────────────────────────────────────────────────

  const activeFen    = liveSnapshot?.fen   ?? state?.fen   ?? null;
  const activeMoves  = liveSnapshot?.moves ?? state?.moves ?? "";
  const activeStatus = liveSnapshot?.status ?? state?.status;

  const moves = useMemo(
    () => activeMoves.split(/\s+/).filter(Boolean),
    [activeMoves]
  );

  const lichessUrl = state?.url ?? (gameId ? `https://lichess.org/${gameId}` : "https://lichess.org");
  const gameIsPlayable = isPlayableStatus(activeStatus);
  const gameIsFinished = isFinishedStatus(activeStatus);
  const hasKnownTurn = state !== null && state.userColor !== null;
  const isUserTurn = !hasKnownTurn || state?.userColor === state?.sideToMove;
  const waitingForBot = hasKnownTurn && !isUserTurn;
  const boardOrientation = state?.userColor ?? "white";
  const turnLabel = gameIsFinished
    ? "Game finished"
    : state === null
      ? "Status unknown"
      : isUserTurn
        ? "Your turn"
        : `Waiting for ${botUsername(state)}`;
  const canInteractWithBoard = Boolean(gameId) && gameIsPlayable && isUserTurn && !isSubmittingMove && activeFen !== null;
  const canSubmitMove = Boolean(gameId) && gameIsPlayable && isUserTurn && !isSubmittingMove && moveDraft.trim().length > 0;

  const boardFromFen = useMemo<BoardMatrix>(() => {
    if (!activeFen) return EMPTY_BOARD;
    return fenToBoardMatrix(activeFen) ?? EMPTY_BOARD;
  }, [activeFen]);

  const lastMove = useMemo<{ from: string; to: string } | undefined>(() => {
    const parts = activeMoves.split(/\s+/).filter(Boolean);
    const last = parts[parts.length - 1];
    if (!last || last.length < 4) return undefined;
    return { from: last.slice(0, 2), to: last.slice(2, 4) };
  }, [activeMoves]);

  useEffect(() => {
    if (!canInteractWithBoard) {
      setLichessSelectedSquare(undefined);
      setPendingPromotion(null);
    }
  }, [canInteractWithBoard]);

  const submitMove = async (move: string) => {
    if (!gameId || !gameIsPlayable || !isUserTurn || isSubmittingMove) return;

    setIsSubmittingMove(true);
    setMoveMessage(null);
    setMoveError(null);

    try {
      const result = await submitLichessMove(gameId, move);
      setMoveDraft("");
      setMoveMessage(`Move ${result.move} submitted.`);
      const refreshed = await getLichessGameState(gameId);
      setState(refreshed);
      setError(null);
    } catch (err) {
      setMoveError(moveSubmitErrorMessage(err));
    } finally {
      setIsSubmittingMove(false);
    }
  };

  const handleSubmitMove = async () => {
    if (!canSubmitMove) return;
    await submitMove(moveDraft.trim());
  };

  const handleLichessSquareSelect = (square: string): void => {
    if (!canInteractWithBoard) return;
    if (!lichessSelectedSquare) {
      if (!pieceAt(boardFromFen, square)) return;
      setLichessSelectedSquare(square);
      return;
    }
    if (lichessSelectedSquare === square) {
      setLichessSelectedSquare(undefined);
      return;
    }
    setLichessSelectedSquare(undefined);
    if (isPromotionMove(boardFromFen, lichessSelectedSquare, square)) {
      setPendingPromotion({ from: lichessSelectedSquare, to: square });
      return;
    }
    void submitMove(`${lichessSelectedSquare}${square}`);
  };

  const handleResolvePromotion = (piece: PromotionPiece): void => {
    if (!pendingPromotion) return;
    const char = { Queen: "q", Rook: "r", Bishop: "b", Knight: "n" }[piece];
    const move = `${pendingPromotion.from}${pendingPromotion.to}${char}`;
    setPendingPromotion(null);
    void submitMove(move);
  };

  const handleCancelPromotion = (): void => {
    setPendingPromotion(null);
  };

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
            <button
              type="button"
              disabled={isRefreshing}
              onClick={() => void refreshGameState({ clearMoveFeedback: true, showRefreshing: true })}
            >
              {isRefreshing ? "Refreshing..." : "Refresh now"}
            </button>
            {isLiveConnected && (
              <span className="lichess-live-badge">● Live</span>
            )}
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
              <p className={`lichess-game-turn lichess-game-turn--${gameIsFinished ? "finished" : isUserTurn ? "active" : "waiting"}`}>
                {turnLabel}
              </p>
              <dl className="lichess-game-facts">
                <dt>State</dt>
                <dd>{activeStatus}</dd>
                <dt>Side to move</dt>
                <dd>{state.sideToMove}</dd>
                <dt>Your color</dt>
                <dd>{state.userColor ?? "Unknown"}</dd>
                <dt>BOT color</dt>
                <dd>{state.botColor ?? "Unknown"}</dd>
                <dt>Turn</dt>
                <dd>{turnLabel}</dd>
                <dt>Updated</dt>
                <dd>{new Date(state.lastUpdatedAt).toLocaleTimeString()}</dd>
              </dl>
              {clockDisplay !== null && (
                <dl className="lichess-game-facts lichess-game-clocks">
                  <dt>White clock</dt>
                  <dd className="lichess-clock">{clockDisplay.white}</dd>
                  <dt>Black clock</dt>
                  <dd className="lichess-clock">{clockDisplay.black}</dd>
                </dl>
              )}
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
              <pre className="lichess-game-code">{activeFen ?? "Not available from Lichess yet."}</pre>
            </section>

            <section className="lichess-game-card lichess-game-card--wide" aria-label="Interactive board">
              <h2>Board</h2>
              <p className="lichess-game-muted">
                Click a piece, then click a target square. Use UCI input below for advanced cases.
              </p>
              <p className="lichess-game-muted">
                For pawn promotion, choose the promotion piece when prompted. UCI input remains available for advanced cases.
              </p>
              <div className="lichess-board-wrapper">
                <ChessBoard
                  board={boardFromFen}
                  selectedSquare={lichessSelectedSquare}
                  legalMoves={[]}
                  disabled={!canInteractWithBoard}
                  onSelect={handleLichessSquareSelect}
                  onAnimationFinished={() => {}}
                  orientation={boardOrientation}
                  inCheck={false}
                  activeColor={state.sideToMove}
                  animation={null}
                  lastMove={lastMove}
                  promotionPending={pendingPromotion}
                  onResolvePromotion={handleResolvePromotion}
                  onCancelPromotion={handleCancelPromotion}
                />
              </div>
              {lichessSelectedSquare !== undefined && (
                <p className="lichess-game-muted">Selected: {lichessSelectedSquare}</p>
              )}
            </section>

            {gameIsPlayable ? (
              <section className="lichess-game-card lichess-game-card--wide" aria-label="Submit move">
                <h2>Submit Move</h2>
                {waitingForBot ? (
                  <p className="lichess-game-muted">{`Waiting for ${botUsername(state)}...`}</p>
                ) : null}
                {moveMessage !== null ? (
                  <p className="lichess-game-success">{moveMessage}</p>
                ) : null}
                {moveError !== null ? (
                  <p className="lichess-game-warning">{moveError}</p>
                ) : null}
                <div className="lichess-game-move-form">
                  <input
                    type="text"
                    value={moveDraft}
                    placeholder="e2e4"
                    autoComplete="off"
                    inputMode="text"
                    disabled={isSubmittingMove || !isUserTurn}
                    onChange={(event) => {
                      setMoveDraft(event.currentTarget.value);
                      setMoveMessage(null);
                      setMoveError(null);
                    }}
                    onKeyDown={(event) => {
                      if (event.key === "Enter") {
                        event.preventDefault();
                        void handleSubmitMove();
                      }
                    }}
                  />
                  <button
                    type="button"
                    disabled={!canSubmitMove}
                    onClick={() => void handleSubmitMove()}
                  >
                    {isSubmittingMove ? "Submitting..." : "Submit move"}
                  </button>
                </div>
              </section>
            ) : gameIsFinished ? (
              <section className="lichess-game-card lichess-game-card--wide" aria-label="Finished game">
                <h2>Submit Move</h2>
                <p className="lichess-game-muted">This Lichess game is finished.</p>
              </section>
            ) : (
              <section className="lichess-game-card lichess-game-card--wide" aria-label="Submit move unavailable">
                <h2>Submit Move</h2>
                <p className="lichess-game-muted">Move submission is unavailable for this game status.</p>
              </section>
            )}

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
