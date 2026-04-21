import { useCallback, useEffect, useRef, useState } from "react";
import type { BoardMatrix, GameState, PlayerColor, SessionMode } from "../api/types";
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
    [applyAiMoveIfNeeded, busy, game, getGameId, legalMoves, selectedSquare, session, setSession]
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
      setMessageState(
        error instanceof Error ? error.message : "Failed to start game."
      );
    } finally {
      setBusyState(false);
    }
  }, [gameMode, setSession]);

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

  const handleAnimationFinished = useCallback((id: number): void => {
    setAnimationPlan((current) => (current?.id === id ? null : current));
  }, []);

  const clearPgnExport = useCallback((): void => {
    setPgnExport("");
  }, []);

  return {
    game,
    selectedSquare,
    legalMoves,
    busy,
    message,
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
    setMessage,
    setBusy
  };
}
