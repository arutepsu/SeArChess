import { useCallback, useEffect, useRef, useState } from "react";
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

function promotionChoiceFromUser(): PromotionPiece | undefined {
  const raw = window.prompt(
    "Promote pawn to Queen, Rook, Bishop, or Knight",
    "Queen"
  );

  if (raw === null) return undefined;

  return promotionChoices[
    raw.trim().toLowerCase()
  ] as PromotionPiece | undefined;
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
  notation: GameNotationResponse | undefined;
  sessionLifecycle: SessionContext["lifecycle"] | undefined;

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

  // ── Transitional setters ──────────────────────────────────────────────────
  setMessage: (msg: string | undefined) => void;
  setBusy: (isBusy: boolean) => void;
};

// ---------------------------------------------------------------------------
// Hook
// ---------------------------------------------------------------------------

export function useGameState(): UseGameStateReturn {
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

  const boardRef = useRef<BoardMatrix | null>(null);
  const animationCounter = useRef(0);
  const refreshPromiseRef = useRef<Promise<void> | null>(null);
  const refreshQueuedRef = useRef(false);
  const notationRequestId = useRef(0);

  const generation = useRef(0);

  useEffect(() => {
    boardRef.current = game?.board ?? null;
  }, [game?.board]);

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
        void refreshNotation(gameId, thisGen);

        if (sessionSnapshot) {
          setSession({
            ...sessionSnapshot,
            lifecycle: response.sessionLifecycle
          });
        }

        setMessageState(undefined);
      } catch (error) {
        if (thisGen !== generation.current) return;

        setMessageState(
          error instanceof Error
            ? `AI move failed. ${error.message}`
            : "AI move failed."
        );
      }
    },
    [commitGameSnapshot, refreshNotation, setSession]
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

      if (selectedSquare === normalizedSquare) {
        ++generation.current;
        setSelectedSquare(undefined);
        setLegalMoves([]);

        return;
      }

      const gameId = getGameId();

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
        await applyAiMoveIfNeeded(gameId, session, nextGame, thisGen);
      } catch (error) {
        if (thisGen !== generation.current) return;

        if (error instanceof Error && error.message.startsWith("PROMOTION_REQUIRED:")) {
          const promotion = promotionChoiceFromUser();

          if (!promotion) {
            setMessageState(
              "Promotion cancelled. Choose a promotion piece to complete that move."
            );

            return;
          }

          try {
            const response = await submitMove(gameId, {
              from: selectedSquare,
              to: normalizedSquare,
              promotion
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
            await applyAiMoveIfNeeded(gameId, session, nextGame, thisGen);
          } catch (retryError) {
            if (thisGen !== generation.current) return;

            setMessageState(
              retryError instanceof Error
                ? retryError.message
                : "Promotion rejected by service."
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
    [
      applyAiMoveIfNeeded,
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

      setMessageState(
        error instanceof Error ? error.message : "Failed to start game."
      );
    } finally {
      setBusyState(false);
    }
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
    notation,
    sessionLifecycle: session?.lifecycle,
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
    setMessage,
    setBusy
  };
}
