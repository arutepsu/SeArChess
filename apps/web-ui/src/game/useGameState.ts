import { useCallback, useEffect, useRef, useState } from "react";
import type { BoardMatrix, GameState, PlayableGameMode, PlayerColor } from "../api/types";
import type { CreateGameRequest, GameSnapshot, PromotionPiece } from "../api/backendTypes";
import {
  createGame,
  getGameState,
  getStatus,
  requestAiMove,
  resignGame,
  submitMove
} from "../api/client";
import { mapGameSnapshotToGameState } from "../api/mapper";
import type { BoardAnimation } from "../animation/animationTypes";
import { planAnimation } from "../animation/planAnimation";
import { useSession } from "../session/SessionProvider";
import type { SessionContext } from "../session/sessionStore";

const promotionChoices: Record<string, string> = {
  q: "Queen",
  queen: "Queen",
  r: "Rook",
  rook: "Rook",
  b: "Bishop",
  bishop: "Bishop",
  n: "Knight",
  knight: "Knight"
};

function isTerminal(game: GameState): boolean {
  return game.status === "checkmate" || game.status === "draw" || game.status === "resigned";
}

function isClosedLifecycle(session: SessionContext | null): boolean {
  return session?.lifecycle === "Finished" || session?.lifecycle === "Cancelled";
}

function promotionChoiceFromUser(): PromotionPiece | undefined {
  const raw = window.prompt("Promote pawn to Queen, Rook, Bishop, or Knight", "Queen");
  if (raw === null) return undefined;
  return promotionChoices[raw.trim().toLowerCase()] as PromotionPiece | undefined;
}

function controllerFor(session: SessionContext | null, color: PlayerColor): string | undefined {
  return color === "white" ? session?.whiteController : session?.blackController;
}

function isAiTurn(session: SessionContext | null, game: GameState): boolean {
  if (isTerminal(game) || isClosedLifecycle(session)) return false;
  return controllerFor(session, game.activeColor) === "AI";
}

function isStaleHydrationError(error: unknown): boolean {
  return error instanceof Error && error.message.startsWith("GAME_NOT_FOUND:");
}

// ---------------------------------------------------------------------------
// Public contract
// ---------------------------------------------------------------------------

export type UseGameStateReturn = {
  // ── Read state ────────────────────────────────────────────────────────────
  game: GameState | undefined;
  selectedSquare: string | undefined;
  legalMoves: string[];
  busy: boolean;
  message: string | undefined;
  animationPlan: BoardAnimation | null;
  gameMode: PlayableGameMode;
  sessionLifecycle: SessionContext["lifecycle"] | undefined;

  // ── Actions ───────────────────────────────────────────────────────────────
  /** Initial load: get/create game and set game state. Re-throws on error so
   *  the caller (App) can update its connection state. */
  loadGame: () => Promise<void>;
  /** Fetch the latest GameSnapshot and commit it through the canonical mapper. */
  refreshFromServer: () => Promise<void>;
  handleSelect: (square: string) => Promise<void>;
  setGameMode: (mode: PlayableGameMode) => void;
  handleNewGame: () => Promise<void>;
  handleResign: () => Promise<void>;
  handleAnimationFinished: (id: number) => void;

  // ── Transitional setters ──────────────────────────────────────────────────
  // Exposed only for the WS message handler in App until Stage 6 (useWsSync)
  // consolidates WS-driven side-effects into its own hook.
  setMessage: (msg: string | undefined) => void;
  setBusy: (isBusy: boolean) => void;
};

// ---------------------------------------------------------------------------
// Hook
// ---------------------------------------------------------------------------

export function useGameState(): UseGameStateReturn {
  const { session, setSession, getGameId } = useSession();

  const [game, setGame] = useState<GameState | undefined>(undefined);
  const [selectedSquare, setSelectedSquare] = useState<string | undefined>(undefined);
  const [legalMoves, setLegalMoves] = useState<string[]>([]);
  const [busy, setBusyState] = useState(false);
  const [message, setMessageState] = useState<string | undefined>(undefined);
  const [animationPlan, setAnimationPlan] = useState<BoardAnimation | null>(null);
  const [gameMode, setGameMode] = useState<PlayableGameMode>("HumanVsHuman");

  // boardRef holds the board that was rendered most recently.
  // Async callbacks (refreshFromServer, submit move, AI move) read this ref so they
  // always plan animation against the board that was on screen when the
  // operation started, not a stale closure capture.
  const boardRef = useRef<BoardMatrix | null>(null);
  const animationCounter = useRef(0);
  const refreshPromiseRef = useRef<Promise<void> | null>(null);
  const refreshQueuedRef = useRef(false);

  // Stale-response guard.
  // Every async operation that writes game state bumps this counter before
  // awaiting and checks it after.  If the value changed while awaiting, a
  // newer operation started → discard this result.
  // setBusy(false) in finally blocks always runs regardless of the guard
  // so the busy flag can never get permanently stuck.
  const generation = useRef(0);

  // Keep boardRef current so in-flight async closures read the right board.
  useEffect(() => {
    boardRef.current = game?.board ?? null;
  }, [game?.board]);

  // ── Stable transitional setters for App's WS handler ─────────────────────

  const setMessage = useCallback((msg: string | undefined) => {
    setMessageState(msg);
  }, []);

  const setBusy = useCallback((isBusy: boolean) => {
    setBusyState(isBusy);
  }, []);

  const commitGameSnapshot = useCallback(
    (
      snapshot: GameSnapshot,
      options: {
        previousBoard?: BoardMatrix | null;
        clearInteraction?: boolean;
      } = {}
    ): GameState => {
      const nextGame = mapGameSnapshotToGameState(snapshot);
      setGame(nextGame);

      if (options.clearInteraction ?? true) {
        setSelectedSquare(undefined);
        setLegalMoves([]);
      }

      if (options.previousBoard) {
        setAnimationPlan(
          planAnimation(options.previousBoard, nextGame, ++animationCounter.current)
        );
      } else {
        setAnimationPlan(null);
      }

      return nextGame;
    },
    []
  );

  // ── Async operations ──────────────────────────────────────────────────────

  const loadGame = useCallback(async (): Promise<void> => {
    const thisGen = ++generation.current;
    try {
      await getStatus();
      const existingGameId = getGameId();
      if (!existingGameId) {
        if (thisGen !== generation.current) return;
        setGame(undefined);
        setAnimationPlan(null);
        setLegalMoves([]);
        setSelectedSquare(undefined);
        setMessageState("Start a new game to play.");
        return;
      }

      let hydratedSnapshot: GameSnapshot;
      try {
        hydratedSnapshot = await getGameState(existingGameId);
      } catch (error) {
        if (thisGen !== generation.current) return;
        if (isStaleHydrationError(error)) {
          setSession(null);
          setGame(undefined);
          setAnimationPlan(null);
          setLegalMoves([]);
          setSelectedSquare(undefined);
          setMessageState("Previous game was not found. Start a new game to play.");
          return;
        }
        setMessageState(
          error instanceof Error
            ? `Could not restore previous game. ${error.message}`
            : "Could not restore previous game."
        );
        throw error;
      }

      if (thisGen !== generation.current) return;
      commitGameSnapshot(hydratedSnapshot);
      setMessageState(undefined);
    } catch (error) {
      if (thisGen !== generation.current) return;
      setGame(undefined);
      setAnimationPlan(null);
      setLegalMoves([]);
      setSelectedSquare(undefined);
      setMessageState(
        error instanceof Error
          ? `Could not restore previous game. ${error.message}`
          : "Could not restore previous game."
      );
      throw error; // re-throw so App can update its connection state
    }
  }, [getGameId, setSession]);

  const runRefreshFromServer = useCallback(async (): Promise<void> => {
    do {
      refreshQueuedRef.current = false;

      const thisGen = ++generation.current;
      const previousBoard = boardRef.current;
      const gameId = getGameId();
      if (!gameId) return;
      // Throws on error - caller (App WS handler) is responsible for the
      // error message and busy state in that path.
      const snapshot = await getGameState(gameId);
      if (thisGen !== generation.current) continue;
      commitGameSnapshot(snapshot, { previousBoard });
      setMessageState(undefined);
    } while (refreshQueuedRef.current);
  }, [commitGameSnapshot, getGameId]);

  const refreshFromServer = useCallback(async (): Promise<void> => {
    const inFlight = refreshPromiseRef.current;
    if (inFlight) {
      refreshQueuedRef.current = true;
      return inFlight;
    }

    const refreshPromise = runRefreshFromServer();
    refreshPromiseRef.current = refreshPromise;
    try {
      await refreshPromise;
    } finally {
      if (refreshPromiseRef.current === refreshPromise) {
        refreshPromiseRef.current = null;
      }
    }
  }, [runRefreshFromServer]);

  const applyAiMoveIfNeeded = useCallback(
    async (
      gameId: string,
      sessionSnapshot: SessionContext | null,
      currentGame: GameState,
      thisGen: number
    ): Promise<void> => {
      if (!isAiTurn(sessionSnapshot, currentGame)) return;

      setMessageState(`AI is thinking for ${currentGame.activeColor}...`);
      setBusyState(true);
      const previousBoard = currentGame.board;
      try {
        const response = await requestAiMove(gameId);
        if (thisGen !== generation.current) return;
        commitGameSnapshot(response.game, { previousBoard });
        if (sessionSnapshot) {
          setSession({ ...sessionSnapshot, lifecycle: response.sessionLifecycle });
        }
        setMessageState(undefined);
      } catch (error) {
        if (thisGen !== generation.current) return;
        setMessageState(
          error instanceof Error ? `AI move failed. ${error.message}` : "AI move failed."
        );
      }
    },
    [commitGameSnapshot, setSession]
  );

  const handleSelect = useCallback(
    async (square: string): Promise<void> => {
      if (!game || busy) return;
      const normalizedSquare = square.toLowerCase();

      if (isClosedLifecycle(session)) {
        setSelectedSquare(undefined);
        setLegalMoves([]);
        setMessageState(
          session?.lifecycle === "Cancelled"
            ? "This session was cancelled. Start a new game to keep playing."
            : "This game is finished. Start a new game to keep playing."
        );
        return;
      }

      if (isTerminal(game)) {
        setSelectedSquare(undefined);
        setLegalMoves([]);
        setMessageState("This game is finished. Start a new game to keep playing.");
        return;
      }

      // Deselect: user clicked the already-selected square
      if (selectedSquare === normalizedSquare) {
        // Bump generation to cancel any in-flight selection for this square.
        ++generation.current;
        setSelectedSquare(undefined);
        setLegalMoves([]);
        return;
      }

      const gameId = getGameId();
      if (!gameId) return;

      // First click: select a piece using legal targets from backend state.
      if (!selectedSquare) {
        const thisGen = ++generation.current;
        const moves = game.legalTargetsByFrom[normalizedSquare] ?? [];
        if (thisGen !== generation.current) return;
        if (moves.length === 0) {
          setSelectedSquare(undefined);
          setLegalMoves([]);
          setMessageState("Select a piece with a legal move.");
        } else {
          setSelectedSquare(normalizedSquare);
          setLegalMoves(moves);
          setMessageState(undefined);
        }
        return;
      }

      // Second click: submit the move if the target is legal
      if (!legalMoves.includes(normalizedSquare)) {
        const alternateMoves = game.legalTargetsByFrom[normalizedSquare] ?? [];
        if (alternateMoves.length > 0) {
          ++generation.current;
          setSelectedSquare(normalizedSquare);
          setLegalMoves(alternateMoves);
          setMessageState(undefined);
          return;
        }
        setMessageState("Select a legal target square.");
        return;
      }

      const prevBoard = game.board; // snapshot before any await
      const thisGen = ++generation.current;
      setSelectedSquare(undefined);
      setLegalMoves([]);
      setBusyState(true);
      try {
        const response = await submitMove(gameId, { from: selectedSquare, to: normalizedSquare });
        if (thisGen !== generation.current) return;
        const nextGame = commitGameSnapshot(response.game, { previousBoard: prevBoard });
        if (session) {
          setSession({ ...session, lifecycle: response.sessionLifecycle });
        }
        setMessageState(undefined);
        await applyAiMoveIfNeeded(gameId, session, nextGame, thisGen);
      } catch (error) {
        if (thisGen !== generation.current) return;
        if (error instanceof Error && error.message.startsWith("PROMOTION_REQUIRED:")) {
          const promotion = promotionChoiceFromUser();
          if (!promotion) {
            setMessageState("Promotion cancelled. Choose a promotion piece to complete that move.");
            return;
          }
          try {
            const response = await submitMove(gameId, {
              from: selectedSquare,
              to: normalizedSquare,
              promotion
            });
            if (thisGen !== generation.current) return;
            const nextGame = commitGameSnapshot(response.game, { previousBoard: prevBoard });
            if (session) {
              setSession({ ...session, lifecycle: response.sessionLifecycle });
            }
            setMessageState(undefined);
            await applyAiMoveIfNeeded(gameId, session, nextGame, thisGen);
          } catch (retryError) {
            if (thisGen !== generation.current) return;
            setMessageState(
              retryError instanceof Error ? retryError.message : "Promotion rejected by service."
            );
          }
          return;
        }
        setMessageState(
          error instanceof Error ? error.message : "Move rejected by service."
        );
      } finally {
        setBusyState(false);
      }
    },
    [applyAiMoveIfNeeded, busy, commitGameSnapshot, game, getGameId, legalMoves, selectedSquare, session, setSession]
  );

  const handleNewGame = useCallback(async (): Promise<void> => {
    const thisGen = ++generation.current;
    const request: CreateGameRequest = { mode: gameMode };
    setBusyState(true);
    setMessageState("Creating game...");
    try {
      const response = await createGame(request);
      if (thisGen !== generation.current) return;
      setSession(response.session);
      commitGameSnapshot(response.game);
      setMessageState(undefined);
      // Clock reset is NOT called here: App watches game?.id and calls
      // resetClocks reactively via a useEffect, which is equivalent.
    } catch (error) {
      if (thisGen !== generation.current) return;
      setMessageState(
        error instanceof Error ? error.message : "Failed to start game."
      );
    } finally {
      setBusyState(false);
    }
  }, [commitGameSnapshot, gameMode, setSession]);

  const handleResign = useCallback(async (): Promise<void> => {
    if (!game || busy || isTerminal(game) || isClosedLifecycle(session)) return;

    const gameId = getGameId();
    if (!gameId) return;

    if (controllerFor(session, game.activeColor) === "AI") {
      setMessageState("Wait for the AI move before resigning.");
      return;
    }

    const resigningSide = game.activeColor === "white" ? "White" : "Black";
    if (!window.confirm(`${resigningSide} resigns?`)) return;

    const thisGen = ++generation.current;
    const previousBoard = game.board;
    setBusyState(true);
    setMessageState(`${resigningSide} is resigning...`);
    try {
      const response = await resignGame(gameId, { side: resigningSide });
      if (thisGen !== generation.current) return;
      commitGameSnapshot(response.game, { previousBoard });
      if (session) {
        setSession({ ...session, lifecycle: response.sessionLifecycle });
      }
      setMessageState(undefined);
    } catch (error) {
      if (thisGen !== generation.current) return;
      setMessageState(
        error instanceof Error ? error.message : "Resign failed."
      );
    } finally {
      setBusyState(false);
    }
  }, [busy, commitGameSnapshot, game, getGameId, session, setSession]);

  const handleAnimationFinished = useCallback((id: number): void => {
    setAnimationPlan((current) => (current?.id === id ? null : current));
  }, []);

  return {
    game,
    selectedSquare,
    legalMoves,
    busy,
    message,
    animationPlan,
    gameMode,
    sessionLifecycle: session?.lifecycle,
    loadGame,
    refreshFromServer,
    handleSelect,
    setGameMode,
    handleNewGame,
    handleResign,
    handleAnimationFinished,
    setMessage,
    setBusy
  };
}
