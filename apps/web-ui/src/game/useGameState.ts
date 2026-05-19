import { useCallback, useEffect, useRef, useState } from "react";
<<<<<<< HEAD
<<<<<<< HEAD
import type {
  BoardMatrix,
  GameState,
  PlayableGameMode,
  PlayerColor
} from "../api/types";
import type {
  CreateGameRequest,
  GameNotationResponse,
  GameSnapshot,
  ImportNotationRequest,
  PromotionPiece,
  SessionExportEnvelope
} from "../api/backendTypes";
import {
  createGame,
  exportFen,
  exportPgn,
  getGameState,
  getGameNotation,
  getStatus,
  importNotation,
  importSession,
  loadSessionState,
  requestAiMove,
  resignGame,
  saveSessionState,
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
  return (
    game.status === "checkmate" ||
    game.status === "draw" ||
    game.status === "resigned"
  );
}

function isClosedLifecycle(session: SessionContext | null): boolean {
  return session?.lifecycle === "Finished" || session?.lifecycle === "Cancelled";
}



function controllerFor(
  session: SessionContext | null,
  color: PlayerColor
): string | undefined {
  return color === "white" ? session?.whiteController : session?.blackController;
}

function isAiTurn(session: SessionContext | null, game: GameState): boolean {
  if (isTerminal(game) || isClosedLifecycle(session)) return false;
  return controllerFor(session, game.activeColor) === "AI";
}

function isStaleHydrationError(error: unknown): boolean {
  return error instanceof Error && error.message.startsWith("GAME_NOT_FOUND:");
}
=======
import type { BoardMatrix, GameState } from "../api/types";
=======
import type { BoardMatrix, GameState, PlayerColor, SessionMode } from "../api/types";
>>>>>>> 3bfa20a2 (polish web ui)
import {
  exportPgn,
  getGameState,
  getStatus,
  redoMove,
  requestAiMove,
  startNewGame,
  submitMove,
  undoMove
} from "../api/client";
import type { BoardAnimation } from "../animation/animationTypes";
import { planAnimation } from "../animation/planAnimation";
import { useSession } from "../session/SessionProvider";
<<<<<<< HEAD
>>>>>>> ce08c01e (local microservices)
=======
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

function promotionChoiceFromUser(): string | undefined {
  const raw = window.prompt("Promote pawn to Queen, Rook, Bishop, or Knight", "Queen");
  if (raw === null) return undefined;
  return promotionChoices[raw.trim().toLowerCase()];
}

function controllerFor(session: SessionContext | null, color: PlayerColor): string | undefined {
  return color === "white" ? session?.whiteController : session?.blackController;
}

function isAiTurn(session: SessionContext | null, game: GameState): boolean {
  if (isTerminal(game)) return false;
  return controllerFor(session, game.activeColor) === "AI";
}
>>>>>>> 3bfa20a2 (polish web ui)

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
<<<<<<< HEAD
  animationPlan: BoardAnimation | null;
  gameMode: PlayableGameMode;
  notation: GameNotationResponse | undefined;
  sessionLifecycle: SessionContext["lifecycle"] | undefined;
  promotionPending: { from: string; to: string } | null;

  // ── Actions ───────────────────────────────────────────────────────────────
  loadGame: () => Promise<void>;
  refreshFromServer: () => Promise<void>;
  handleSelect: (square: string) => Promise<void>;
  setGameMode: (mode: PlayableGameMode) => void;
  handleNewGame: (overrideMode?: PlayableGameMode) => Promise<void>;
  handleImportNotation: (
    format: "FEN" | "PGN",
    notation: string
  ) => Promise<void>;
  handleExportNotation: (format: "FEN" | "PGN") => Promise<string>;
  handleImportSession: (envelope: SessionExportEnvelope) => Promise<void>;
  handleResumeSession: (sessionId: string) => Promise<void>;
  handleSaveSession: () => Promise<void>;
  handleResign: () => Promise<void>;
  handleAnimationFinished: (id: number) => void;
  handleResolvePromotion: (piece: PromotionPiece) => Promise<void>;
  handleCancelPromotion: () => void;

  // ── Transitional setters ──────────────────────────────────────────────────
=======
  pgnExport: string;
  animationPlan: BoardAnimation | null;
  gameMode: SessionMode;

  // ── Actions ───────────────────────────────────────────────────────────────
  /** Initial load: get/create game and set game state. Re-throws on error so
   *  the caller (App) can update its connection state. */
  loadGame: () => Promise<void>;
  /** Fetch the latest game state from the server and commit it. */
  refreshFromServer: () => Promise<void>;
  handleSelect: (square: string) => Promise<void>;
  setGameMode: (mode: SessionMode) => void;
  handleNewGame: () => Promise<void>;
  handleUndo: () => Promise<void>;
  handleRedo: () => Promise<void>;
  handleExport: () => Promise<void>;
  handleAnimationFinished: (id: number) => void;
  clearPgnExport: () => void;

  // ── Transitional setters ──────────────────────────────────────────────────
  // Exposed only for the WS message handler in App until Stage 6 (useWsSync)
  // consolidates WS-driven side-effects into its own hook.
>>>>>>> ce08c01e (local microservices)
  setMessage: (msg: string | undefined) => void;
  setBusy: (isBusy: boolean) => void;
};

// ---------------------------------------------------------------------------
// Hook
// ---------------------------------------------------------------------------

export function useGameState(): UseGameStateReturn {
<<<<<<< HEAD
<<<<<<< HEAD
  const { session, setSession, getSessionId, getGameId } = useSession();

  const [game, setGame] = useState<GameState | undefined>(undefined);
  const [selectedSquare, setSelectedSquare] = useState<string | undefined>(
    undefined
  );
  const [legalMoves, setLegalMoves] = useState<string[]>([]);
  const [busy, setBusyState] = useState(false);
  const [message, setMessageState] = useState<string | undefined>(undefined);
  const [animationPlan, setAnimationPlan] = useState<BoardAnimation | null>(
    null
  );
  const [gameMode, setGameMode] =
    useState<PlayableGameMode>("HumanVsHuman");
  const [notation, setNotation] = useState<GameNotationResponse | undefined>(
    undefined
  );
  const [promotionPending, setPromotionPending] = useState<{ from: string; to: string } | null>(null);

  const boardRef = useRef<BoardMatrix | null>(null);
  const animationCounter = useRef(0);
  const refreshPromiseRef = useRef<Promise<void> | null>(null);
  const refreshQueuedRef = useRef(false);
  const notationRequestId = useRef(0);

  const generation = useRef(0);

=======
  const { setSession, getGameId } = useSession();
=======
  const { session, setSession, getGameId } = useSession();
>>>>>>> 3bfa20a2 (polish web ui)

  const [game, setGame] = useState<GameState | undefined>(undefined);
  const [selectedSquare, setSelectedSquare] = useState<string | undefined>(undefined);
  const [legalMoves, setLegalMoves] = useState<string[]>([]);
  const [busy, setBusyState] = useState(false);
  const [message, setMessageState] = useState<string | undefined>(undefined);
  const [pgnExport, setPgnExport] = useState("");
  const [animationPlan, setAnimationPlan] = useState<BoardAnimation | null>(null);
  const [gameMode, setGameMode] = useState<SessionMode>("HumanVsHuman");

  // boardRef holds the board that was rendered most recently.
  // Async callbacks (refreshFromServer, undo, redo) read this ref so they
  // always plan animation against the board that was on screen when the
  // operation started, not a stale closure capture.
  const boardRef = useRef<BoardMatrix | null>(null);
  const animationCounter = useRef(0);

  // Stale-response guard.
  // Every async operation that writes game state bumps this counter before
  // awaiting and checks it after.  If the value changed while awaiting, a
  // newer operation started → discard this result.
  // setBusy(false) in finally blocks always runs regardless of the guard
  // so the busy flag can never get permanently stuck.
  const generation = useRef(0);

  // Keep boardRef current so in-flight async closures read the right board.
>>>>>>> ce08c01e (local microservices)
  useEffect(() => {
    boardRef.current = game?.board ?? null;
  }, [game?.board]);

<<<<<<< HEAD
=======
  // ── Stable transitional setters for App's WS handler ─────────────────────

>>>>>>> ce08c01e (local microservices)
  const setMessage = useCallback((msg: string | undefined) => {
    setMessageState(msg);
  }, []);

  const setBusy = useCallback((isBusy: boolean) => {
    setBusyState(isBusy);
  }, []);

<<<<<<< HEAD
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

  const refreshNotation = useCallback(
    async (gameId: string, thisGen: number): Promise<void> => {
      const requestId = ++notationRequestId.current;

      try {
        const response = await getGameNotation(gameId);

        if (thisGen !== generation.current) return;
        if (requestId !== notationRequestId.current) return;

        setNotation(response);
      } catch {
        if (thisGen !== generation.current) return;
        if (requestId !== notationRequestId.current) return;

        setNotation(undefined);
      }
    },
    []
  );

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
        setNotation(undefined);
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
          setNotation(undefined);
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
      void refreshNotation(existingGameId, thisGen);
      setMessageState(undefined);
    } catch (error) {
      if (thisGen !== generation.current) return;

      setGame(undefined);
      setAnimationPlan(null);
      setLegalMoves([]);
      setSelectedSquare(undefined);
      setNotation(undefined);
      setMessageState(
        error instanceof Error
          ? `Could not restore previous game. ${error.message}`
          : "Could not restore previous game."
      );

      throw error;
    }
  }, [commitGameSnapshot, getGameId, refreshNotation, setSession]);

  const runRefreshFromServer = useCallback(async (): Promise<void> => {
    do {
      refreshQueuedRef.current = false;

      const thisGen = ++generation.current;
      const previousBoard = boardRef.current;
      const gameId = getGameId();

      if (!gameId) return;

      const snapshot = await getGameState(gameId);

      if (thisGen !== generation.current) continue;

      commitGameSnapshot(snapshot, { previousBoard });
      void refreshNotation(gameId, thisGen);
      setMessageState(undefined);
    } while (refreshQueuedRef.current);
  }, [commitGameSnapshot, getGameId, refreshNotation]);

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

  const lastAiRequestedTurn = useRef<string>("");
<<<<<<< HEAD

  useEffect(() => {
    if (!game || !session || !game.id) return;

    if (isAiTurn(session, game)) {
      const turnKey = `${game.id}-${game.fullMove}-${game.activeColor}`;
      if (lastAiRequestedTurn.current === turnKey) return;
      lastAiRequestedTurn.current = turnKey;

      setMessageState(`AI is thinking for ${game.activeColor}...`);
      setBusyState(true);

      const thisGen = generation.current;
      
      requestAiMove(game.id)
        .then(response => {
          if (thisGen !== generation.current) return;
          commitGameSnapshot(response.game);
          void refreshNotation(game.id, thisGen);
          setSession(prev => prev ? { ...prev, lifecycle: response.sessionLifecycle } : null);
          setMessageState(undefined);
        })
        .catch(error => {
          if (thisGen !== generation.current) return;
          setMessageState(
            error instanceof Error ? `AI move failed. ${error.message}` : "AI move failed."
          );
        })
        .finally(() => {
          if (thisGen === generation.current) {
            setBusyState(false);
          }
        });
    }
  }, [game, session, commitGameSnapshot, refreshNotation, setSession]);
=======
  // ── Async operations ──────────────────────────────────────────────────────

  const loadGame = useCallback(async (): Promise<void> => {
    const thisGen = ++generation.current;
    try {
      await getStatus();
      const existingGameId = getGameId();
      const result = existingGameId
        ? { game: await getGameState(existingGameId), session: null }
        : await startNewGame({ mode: "HumanVsHuman" });
      if (thisGen !== generation.current) return;
      if (result.session) setSession(result.session);
      setGame(result.game);
      setAnimationPlan(null);
      setLegalMoves([]);
      setMessageState(undefined);
    } catch (error) {
      if (thisGen !== generation.current) return;
      setMessageState(
        error instanceof Error
          ? `Service offline. ${error.message}`
          : "Service offline."
      );
      throw error; // re-throw so App can update its connection state
    }
  }, [getGameId, setSession]);

  const refreshFromServer = useCallback(async (): Promise<void> => {
    const thisGen = ++generation.current;
    const previousBoard = boardRef.current;
    const gameId = getGameId();
    if (!gameId) return;
    // Throws on error — caller (App WS handler) is responsible for the
    // error message and busy state in that path.
    const latestGame = await getGameState(gameId);
    if (thisGen !== generation.current) return;
    setGame(latestGame);
    setLegalMoves([]);
    setSelectedSquare(undefined);
    if (previousBoard) {
      setAnimationPlan(
        planAnimation(previousBoard, latestGame, ++animationCounter.current)
      );
    } else {
      setAnimationPlan(null);
    }
    setMessageState(undefined);
  }, [getGameId]);
>>>>>>> ce08c01e (local microservices)

  const applyAiMoveIfNeeded = useCallback(
    async (
      gameId: string,
      sessionSnapshot: SessionContext | null,
      currentGame: GameState,
      thisGen: number
    ): Promise<void> => {
      if (!isAiTurn(sessionSnapshot, currentGame)) return;
=======
>>>>>>> 97d0df0b (added ai for lichess)

  useEffect(() => {
    if (!game || !session || !game.id) return;

    if (isAiTurn(session, game)) {
      const turnKey = `${game.id}-${game.fullMove}-${game.activeColor}`;
      if (lastAiRequestedTurn.current === turnKey) return;
      lastAiRequestedTurn.current = turnKey;

      setMessageState(`AI is thinking for ${game.activeColor}...`);
      setBusyState(true);
<<<<<<< HEAD
      const previousBoard = currentGame.board;
      try {
        const { game: aiGame, lifecycle } = await requestAiMove(gameId);
        if (thisGen !== generation.current) return;
        setGame(aiGame);
        if (sessionSnapshot) {
          setSession({ ...sessionSnapshot, lifecycle });
        }
        setAnimationPlan(
          planAnimation(previousBoard, aiGame, ++animationCounter.current)
        );
        setMessageState(undefined);
      } catch (error) {
        if (thisGen !== generation.current) return;
        setMessageState(
          error instanceof Error ? `AI move failed. ${error.message}` : "AI move failed."
        );
      }
    },
    [setSession]
  );
=======

      const thisGen = generation.current;
      
      requestAiMove(game.id)
        .then(response => {
          if (thisGen !== generation.current) return;
          commitGameSnapshot(response.game);
          void refreshNotation(game.id, thisGen);
          setSession(prev => prev ? { ...prev, lifecycle: response.sessionLifecycle } : null);
          setMessageState(undefined);
        })
        .catch(error => {
          if (thisGen !== generation.current) return;
          setMessageState(
            error instanceof Error ? `AI move failed. ${error.message}` : "AI move failed."
          );
        })
        .finally(() => {
          if (thisGen === generation.current) {
            setBusyState(false);
          }
        });
    }
  }, [game, session, commitGameSnapshot, refreshNotation, setSession]);
>>>>>>> 97d0df0b (added ai for lichess)

  const handleSelect = useCallback(
    async (square: string): Promise<void> => {
      if (!game || busy) return;
      const normalizedSquare = square.toLowerCase();

      if (isTerminal(game)) {
        setSelectedSquare(undefined);
        setLegalMoves([]);
        setMessageState("This game is finished. Start a new game to keep playing.");
        return;
      }

<<<<<<< HEAD
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

      if (selectedSquare === normalizedSquare) {
        ++generation.current;
        setSelectedSquare(undefined);
        setLegalMoves([]);

=======
      // Deselect: user clicked the already-selected square
      if (selectedSquare === normalizedSquare) {
        // Bump generation to cancel any in-flight selection for this square.
        ++generation.current;
        setSelectedSquare(undefined);
        setLegalMoves([]);
>>>>>>> ce08c01e (local microservices)
        return;
      }

      const gameId = getGameId();
<<<<<<< HEAD

      if (!gameId) return;

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

      if (!legalMoves.includes(normalizedSquare)) {
        const moveStr = `${selectedSquare}-${normalizedSquare}`;
        console.log(`Move ${moveStr} is illegal`);

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

      const moveStr = `${selectedSquare}-${normalizedSquare}`;
      console.log(`Move ${moveStr} is legal`);

      const prevBoard = game.board;
      const thisGen = ++generation.current;

      setSelectedSquare(undefined);
      setLegalMoves([]);
      setBusyState(true);

      try {
        const response = await submitMove(gameId, {
          from: selectedSquare,
          to: normalizedSquare
        });

        if (thisGen !== generation.current) return;

        const nextGame = commitGameSnapshot(response.game, {
          previousBoard: prevBoard
        });

        void refreshNotation(gameId, thisGen);

        if (session) {
          setSession({
            ...session,
            lifecycle: response.sessionLifecycle
          });
        }

        setMessageState(undefined);
      } catch (error) {
        if (thisGen !== generation.current) return;

        if (error instanceof Error && error.message.startsWith("PROMOTION_REQUIRED:")) {
          setPromotionPending({ from: selectedSquare, to: normalizedSquare });
          setMessageState("Choose a promotion piece.");
          return;
        }

=======
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
        const { game: nextGame } = await submitMove(gameId, { from: selectedSquare, to: normalizedSquare });
        if (thisGen !== generation.current) return;
        setGame(nextGame);
        if (session) {
          setSession({ ...session, lifecycle: isTerminal(nextGame) ? "Finished" : "Active" });
        }
        setAnimationPlan(
          planAnimation(prevBoard, nextGame, ++animationCounter.current)
        );
        setMessageState(undefined);
        await applyAiMoveIfNeeded(gameId, session, nextGame, thisGen);
      } catch (error) {
        if (thisGen !== generation.current) return;
<<<<<<< HEAD
>>>>>>> ce08c01e (local microservices)
=======
        if (error instanceof Error && error.message.startsWith("PROMOTION_REQUIRED:")) {
          const promotion = promotionChoiceFromUser();
          if (!promotion) {
            setMessageState("Promotion cancelled. Choose a promotion piece to complete that move.");
            return;
          }
          try {
            const { game: nextGame } = await submitMove(gameId, {
              from: selectedSquare,
              to: normalizedSquare,
              promotion
            });
            if (thisGen !== generation.current) return;
            setGame(nextGame);
            if (session) {
              setSession({ ...session, lifecycle: isTerminal(nextGame) ? "Finished" : "Active" });
            }
            setAnimationPlan(
              planAnimation(prevBoard, nextGame, ++animationCounter.current)
            );
            setMessageState(undefined);
          } catch (retryError) {
            if (thisGen !== generation.current) return;
            setMessageState(
              retryError instanceof Error ? retryError.message : "Promotion rejected by service."
            );
          }
          return;
        }
>>>>>>> 3bfa20a2 (polish web ui)
        setMessageState(
          error instanceof Error ? error.message : "Move rejected by service."
        );
      } finally {
        setBusyState(false);
      }
    },
<<<<<<< HEAD
<<<<<<< HEAD
    [
      busy,
      commitGameSnapshot,
      game,
      getGameId,
      legalMoves,
      refreshNotation,
      selectedSquare,
      session,
      setSession
    ]
  );

  const handleResolvePromotion = useCallback(
    async (promotion: PromotionPiece): Promise<void> => {
      if (!promotionPending || !game) return;

      const { from, to } = promotionPending;
      const gameId = game.id;
      if (!gameId) return;

      const prevBoard = game.board;
      const thisGen = ++generation.current;

      setPromotionPending(null);
      setBusyState(true);
      setMessageState(`Promoting to ${promotion}...`);

      try {
        const response = await submitMove(gameId, {
          from,
          to,
          promotion
        });

        if (thisGen !== generation.current) return;

        commitGameSnapshot(response.game, {
          previousBoard: prevBoard
        });

        void refreshNotation(gameId, thisGen);

        if (session) {
          setSession({
            ...session,
            lifecycle: response.sessionLifecycle
          });
        }

        setMessageState(undefined);
      } catch (error) {
        if (thisGen !== generation.current) return;

        setMessageState(
          error instanceof Error ? error.message : "Promotion failed."
        );
      } finally {
        setBusyState(false);
      }
    },
    [commitGameSnapshot, game, promotionPending, refreshNotation, session, setSession]
  );

  const handleCancelPromotion = useCallback(() => {
    setPromotionPending(null);
    setMessageState("Promotion cancelled.");
  }, []);

  const handleNewGame = useCallback(async (overrideMode?: PlayableGameMode): Promise<void> => {
    const thisGen = ++generation.current;
    const request: CreateGameRequest = { mode: overrideMode ?? gameMode };

    setBusyState(true);
    setMessageState("Creating game...");

    try {
      const response = await createGame(request);

      if (thisGen !== generation.current) return;

      setSession(response.session);
      commitGameSnapshot(response.game);
      void refreshNotation(response.game.gameId, thisGen);
      setMessageState(undefined);
    } catch (error) {
      if (thisGen !== generation.current) return;

=======
    [busy, game, getGameId, legalMoves, selectedSquare, setSession]
=======
    [applyAiMoveIfNeeded, busy, game, getGameId, legalMoves, selectedSquare, session, setSession]
>>>>>>> 3bfa20a2 (polish web ui)
  );

  const handleNewGame = useCallback(async (): Promise<void> => {
    const thisGen = ++generation.current;
    setBusyState(true);
    try {
      const { game: nextGame, session } = await startNewGame({ mode: gameMode });
      if (thisGen !== generation.current) return;
      setSession(session);
      setGame(nextGame);
      setMessageState(undefined);
      setSelectedSquare(undefined);
      setLegalMoves([]);
      setAnimationPlan(null);
      // Clock reset is NOT called here: App watches game?.id and calls
      // resetClocks reactively via a useEffect, which is equivalent.
    } catch (error) {
      if (thisGen !== generation.current) return;
>>>>>>> ce08c01e (local microservices)
      setMessageState(
        error instanceof Error ? error.message : "Failed to start game."
      );
    } finally {
      setBusyState(false);
    }
<<<<<<< HEAD
<<<<<<< HEAD
  }, [commitGameSnapshot, gameMode, refreshNotation, setSession]);

  const handleImportNotation = useCallback(
    async (format: "FEN" | "PGN", notation: string): Promise<void> => {
      const trimmed = notation.trim();

      if (!trimmed) {
        setMessageState("Notation is empty.");
        return;
      }

      const thisGen = ++generation.current;
      const request: ImportNotationRequest = {
        format,
        notation: trimmed,
        mode: gameMode
      };

      setBusyState(true);
      setMessageState(`Importing ${format}...`);

      try {
        const response = await importNotation(request);

        if (thisGen !== generation.current) return;

        setSession(response.session);
        commitGameSnapshot(response.game);
        void refreshNotation(response.game.gameId, thisGen);
        setMessageState(undefined);
      } catch (error) {
        if (thisGen !== generation.current) return;

        setMessageState(error instanceof Error ? error.message : "Import failed.");
      } finally {
        setBusyState(false);
      }
    },
    [commitGameSnapshot, gameMode, refreshNotation, setSession]
  );

  const handleExportNotation = useCallback(
    async (format: "FEN" | "PGN"): Promise<string> => {
      const gameId = getGameId();

      if (!gameId) {
        setMessageState("Start or resume a game before exporting notation.");
        throw new Error("Start or resume a game before exporting notation.");
      }

      setBusyState(true);
      setMessageState(`Exporting ${format} notation...`);

      try {
        const response =
          format === "FEN" ? await exportFen(gameId) : await exportPgn(gameId);

        setNotation((current) => ({
          fen: format === "FEN" ? response.notation : current?.fen ?? "",
          pgn: format === "PGN" ? response.notation : current?.pgn ?? ""
        }));
        setMessageState(`${format} notation exported.`);
        return response.notation;
      } catch (error) {
        setMessageState(
          error instanceof Error ? error.message : `${format} export failed.`
        );
        throw error;
      } finally {
        setBusyState(false);
      }
    },
    [getGameId]
  );

  const handleResumeSession = useCallback(
    async (sessionId: string): Promise<void> => {
      const thisGen = ++generation.current;

      setBusyState(true);
      setMessageState("Loading session...");

      try {
        const state = await loadSessionState(sessionId);

        if (thisGen !== generation.current) return;

        setSession(state.session);
        commitGameSnapshot(state.game);
        void refreshNotation(state.game.gameId, thisGen);
        setMessageState(undefined);
      } catch (error) {
        if (thisGen !== generation.current) return;

        setMessageState(
          error instanceof Error ? error.message : "Failed to load session."
        );
        throw error;
      } finally {
        setBusyState(false);
      }
    },
    [commitGameSnapshot, refreshNotation, setSession]
  );

  const handleImportSession = useCallback(
    async (envelope: SessionExportEnvelope): Promise<void> => {
      const thisGen = ++generation.current;

      setBusyState(true);
      setMessageState("Importing session...");

      try {
        const state = await importSession(envelope);

        if (thisGen !== generation.current) return;

        setSession(state.session);
        commitGameSnapshot(state.game);
        void refreshNotation(state.game.gameId, thisGen);
        setMessageState("Session imported.");
      } catch (error) {
        if (thisGen !== generation.current) return;

        setMessageState(error instanceof Error ? error.message : "Session import failed.");
        throw error;
      } finally {
        setBusyState(false);
      }
    },
    [commitGameSnapshot, refreshNotation, setSession]
  );

  const handleSaveSession = useCallback(async (): Promise<void> => {
    const sessionId = getSessionId();

    if (!sessionId) {
      const message = "Start or resume a game before saving a session.";
      setMessageState(message);
      throw new Error(message);
    }

    const thisGen = ++generation.current;

    setBusyState(true);
    setMessageState("Saving session...");

    try {
      const currentState = await loadSessionState(sessionId);
      const savedState = await saveSessionState(sessionId, currentState);

      if (thisGen !== generation.current) return;

      setSession(savedState.session);
      commitGameSnapshot(savedState.game);
      void refreshNotation(savedState.game.gameId, thisGen);
      setMessageState("Session saved successfully.");
    } catch (error) {
      if (thisGen !== generation.current) return;

      setMessageState(
        error instanceof Error ? error.message : "Session save failed."
      );
      throw error;
    } finally {
      if (thisGen === generation.current) {
        setBusyState(false);
      }
    }
  }, [commitGameSnapshot, getSessionId, refreshNotation, setSession]);

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
      void refreshNotation(gameId, thisGen);

      if (session) {
        setSession({
          ...session,
          lifecycle: response.sessionLifecycle
        });
      }

      setMessageState(undefined);
    } catch (error) {
      if (thisGen !== generation.current) return;

      setMessageState(error instanceof Error ? error.message : "Resign failed.");
    } finally {
      setBusyState(false);
    }
  }, [
    busy,
    commitGameSnapshot,
    game,
    getGameId,
    refreshNotation,
    session,
    setSession
  ]);
=======
  }, [setSession]);
=======
  }, [gameMode, setSession]);
>>>>>>> 3bfa20a2 (polish web ui)

  const handleUndo = useCallback(async (): Promise<void> => {
    const thisGen = ++generation.current;
    // Read ref, not closure, so this callback needs no dep on game.board
    const prevBoard = boardRef.current;
    const gameId = getGameId();
    if (!gameId) return;
    setBusyState(true);
    try {
      const nextGame = await undoMove(gameId);
      if (thisGen !== generation.current) return;
      setGame(nextGame);
      setSelectedSquare(undefined);
      setLegalMoves([]);
      if (prevBoard) {
        setAnimationPlan(
          planAnimation(prevBoard, nextGame, ++animationCounter.current)
        );
      }
    } catch (error) {
      if (thisGen !== generation.current) return;
      setMessageState(
        error instanceof Error ? error.message : "Undo failed."
      );
    } finally {
      setBusyState(false);
    }
  }, [getGameId]);

  const handleRedo = useCallback(async (): Promise<void> => {
    const thisGen = ++generation.current;
    const prevBoard = boardRef.current;
    const gameId = getGameId();
    if (!gameId) return;
    setBusyState(true);
    try {
      const nextGame = await redoMove(gameId);
      if (thisGen !== generation.current) return;
      setGame(nextGame);
      setSelectedSquare(undefined);
      setLegalMoves([]);
      if (prevBoard) {
        setAnimationPlan(
          planAnimation(prevBoard, nextGame, ++animationCounter.current)
        );
      }
    } catch (error) {
      if (thisGen !== generation.current) return;
      setMessageState(
        error instanceof Error ? error.message : "Redo failed."
      );
    } finally {
      setBusyState(false);
    }
  }, [getGameId]);

  const handleExport = useCallback(async (): Promise<void> => {
    const thisGen = ++generation.current;
    setBusyState(true);
    try {
      const result = await exportPgn();
      if (thisGen !== generation.current) return;
      setPgnExport(result.pgn);
    } catch (error) {
      if (thisGen !== generation.current) return;
      setMessageState(
        error instanceof Error ? error.message : "Export failed."
      );
    } finally {
      setBusyState(false);
    }
  }, []);
>>>>>>> ce08c01e (local microservices)

  const handleAnimationFinished = useCallback((id: number): void => {
    setAnimationPlan((current) => (current?.id === id ? null : current));
  }, []);

<<<<<<< HEAD
=======
  const clearPgnExport = useCallback((): void => {
    setPgnExport("");
  }, []);

>>>>>>> ce08c01e (local microservices)
  return {
    game,
    selectedSquare,
    legalMoves,
    busy,
    message,
<<<<<<< HEAD
    animationPlan,
    gameMode,
    notation,
    sessionLifecycle: session?.lifecycle,
    promotionPending,
    loadGame,
    refreshFromServer,
    handleSelect,
    setGameMode,
    handleNewGame,
    handleImportNotation,
    handleExportNotation,
    handleImportSession,
    handleResumeSession,
    handleSaveSession,
    handleResign,
    handleAnimationFinished,
    handleResolvePromotion,
    handleCancelPromotion,
=======
    pgnExport,
    animationPlan,
    gameMode,
    loadGame,
    refreshFromServer,
    handleSelect,
    setGameMode,
    handleNewGame,
    handleUndo,
    handleRedo,
    handleExport,
    handleAnimationFinished,
    clearPgnExport,
>>>>>>> ce08c01e (local microservices)
    setMessage,
    setBusy
  };
}
