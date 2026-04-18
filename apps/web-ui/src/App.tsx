import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { BoardMatrix, GameState, MoveRequest, PieceCode } from "./api/types";
import {
  apiBaseUrl,
  exportPgn,
  getCurrentGameId,
  getGameState,
  getLegalMoves,
  getStatus,
  redoMove,
  startNewGame,
  submitMove,
  undoMove
} from "./api/client";
import ChessBoard, { type BoardAnimation } from "./components/ChessBoard.tsx";
import ControlPanel from "./components/ControlPanel.tsx";
import MoveList from "./components/MoveList.tsx";
import "./App.css";

type ConnectionState = "connected" | "offline" | "loading";

const baseClockMs = 10 * 60 * 1000;

const pieceAt = (board: BoardMatrix, square: string): PieceCode | null => {
  const position = squareToIndex(square);
  if (!position) return null;
  return board[position.row]?.[position.col] ?? null;
};

const squareToIndex = (square: string): { row: number; col: number } | null => {
  if (square.length !== 2) return null;
  const files = "abcdefgh";
  const file = square[0].toLowerCase();
  const rank = Number(square[1]);
  const col = files.indexOf(file);
  if (col < 0 || Number.isNaN(rank) || rank < 1 || rank > 8) return null;
  return { row: 8 - rank, col };
};

export default function App() {
  const [game, setGame] = useState<GameState>();
  const [, setConnection] = useState<ConnectionState>("loading");
  const [, setMessage] = useState<string>();
  const [selectedSquare, setSelectedSquare] = useState<string>();
  const [legalMoves, setLegalMoves] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [pgnExport, setPgnExport] = useState("");
  const [animationPlan, setAnimationPlan] = useState<BoardAnimation | null>(null);
  const animationCounter = useRef(0);
  const [whiteClockMs, setWhiteClockMs] = useState(baseClockMs);
  const [blackClockMs, setBlackClockMs] = useState(baseClockMs);
  const lastTickMs = useRef<number | null>(null);

  const clockRunning = useMemo(() => {
    const status = game?.status;
    return status === "active" || status === "check";
  }, [game?.status]);

  const loadGame = useCallback(async () => {
    setConnection("loading");
    try {
      await getStatus();
      const existingId = getCurrentGameId();
      const state = existingId ? await getGameState() : await startNewGame({});
      setGame(state);
      setAnimationPlan(null);
      setLegalMoves([]);
      setConnection("connected");
      setMessage(undefined);
    } catch (error) {
      setConnection("offline");
      setMessage(
        error instanceof Error
          ? `Service offline. ${error.message}`
          : "Service offline."
      );
    }
  }, []);

  const resetClocks = useCallback(() => {
    setWhiteClockMs(baseClockMs);
    setBlackClockMs(baseClockMs);
    lastTickMs.current = performance.now();
  }, []);



  const planAnimation = useCallback((prevBoard: BoardMatrix, nextGame?: GameState): BoardAnimation | null => {
    if (!nextGame?.lastMove) return null;
    const from = nextGame.lastMove.from;
    const to = nextGame.lastMove.to;
    const movingPiece = pieceAt(prevBoard, from);
    if (!movingPiece) return null;
    const capturedPiece = pieceAt(prevBoard, to) ?? undefined;
    animationCounter.current += 1;
    return {
      id: animationCounter.current,
      from,
      to,
      movingPiece,
      capturedPiece,
      isCapture: Boolean(capturedPiece)
    };
  }, []);

  const handleSelect = useCallback(
    async (square: string) => {
      if (!game || busy) return;
      if (!selectedSquare) {
        setSelectedSquare(square);
        try {
          const result = await getLegalMoves(square);
          setLegalMoves(result.moves);
        } catch {
          setLegalMoves([]);
        }
        return;
      }

      if (selectedSquare === square) {
        setSelectedSquare(undefined);
        setLegalMoves([]);
        return;
      }

      if (legalMoves.length > 0 && !legalMoves.includes(square)) {
        setMessage("Select a legal target square.");
        return;
      }

      const move: MoveRequest = { from: selectedSquare, to: square };
      const prevBoard = game.board;
      setSelectedSquare(undefined);
      setLegalMoves([]);
      setBusy(true);
      try {
        const nextGame = await submitMove(move);
        setGame(nextGame);
        setAnimationPlan(planAnimation(prevBoard, nextGame));
        setMessage(undefined);
      } catch (error) {
        setMessage(
          error instanceof Error ? error.message : "Move rejected by service."
        );
      } finally {
        setBusy(false);
      }
    },
    [busy, game, legalMoves, planAnimation, selectedSquare]
  );

  const handleNewGame = useCallback(async () => {
    setBusy(true);
    try {
      const nextGame = await startNewGame({});
      setGame(nextGame);
      setMessage(undefined);
      setSelectedSquare(undefined);
      setLegalMoves([]);
      setAnimationPlan(null);
    } catch (error) {
      setMessage(
        error instanceof Error ? error.message : "Failed to start game."
      );
    } finally {
      setBusy(false);
    }
  }, []);

  const handleUndo = useCallback(async () => {
    setBusy(true);
    try {
      const prevBoard = game?.board ?? null;
      const nextGame = await undoMove();
      setGame(nextGame);
      setSelectedSquare(undefined);
      setLegalMoves([]);
      if (prevBoard) {
        setAnimationPlan(planAnimation(prevBoard, nextGame));
      }
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "Undo failed.");
    } finally {
      setBusy(false);
    }
  }, [game?.board, planAnimation]);

  const handleRedo = useCallback(async () => {
    setBusy(true);
    try {
      const prevBoard = game?.board ?? null;
      const nextGame = await redoMove();
      setGame(nextGame);
      setSelectedSquare(undefined);
      setLegalMoves([]);
      if (prevBoard) {
        setAnimationPlan(planAnimation(prevBoard, nextGame));
      }
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "Redo failed.");
    } finally {
      setBusy(false);
    }
  }, [game?.board, planAnimation]);

  const handleExport = useCallback(async () => {
    setBusy(true);
    try {
      const result = await exportPgn();
      setPgnExport(result.pgn);
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "Export failed.");
    } finally {
      setBusy(false);
    }
  }, []);

  const handleAnimationFinished = useCallback((id: number) => {
    setAnimationPlan((current) => (current?.id === id ? null : current));
  }, []);

  useEffect(() => {
    loadGame();
  }, [loadGame]);

  const clockStateRef = useRef({
    running: false,
    activeColor: "white" as PlayerColor
  });

  useEffect(() => {
    clockStateRef.current = {
      running: clockRunning,
      activeColor: game?.activeColor ?? "white"
    };
  }, [clockRunning, game?.activeColor]);

  useEffect(() => {
    lastTickMs.current = performance.now();
    const intervalId = window.setInterval(() => {
      const now = performance.now();
      const last = lastTickMs.current ?? now;
      lastTickMs.current = now;

      const { running, activeColor } = clockStateRef.current;
      if (!running) return;

      const delta = Math.max(0, now - last);
      if (activeColor === "white") {
        setWhiteClockMs((value) => Math.max(0, value - delta));
      } else {
        setBlackClockMs((value) => Math.max(0, value - delta));
      }
    }, 250);
    return () => window.clearInterval(intervalId);
  }, []);

  useEffect(() => {
    if (game?.id) {
      resetClocks();
    }
  }, [game?.id, resetClocks]);

  return (
    <div className="app">
      <div className="leaf-layer" aria-hidden="true">
        <span className="leaf leaf-1"></span>
        <span className="leaf leaf-2"></span>
        <span className="leaf leaf-3"></span>
        <span className="leaf leaf-4"></span>
        <span className="leaf leaf-5"></span>
        <span className="leaf leaf-6"></span>
      </div>
      <main className="layout">
        {game ? (
          <ChessBoard
            board={game.board}
            selectedSquare={selectedSquare}
            legalMoves={legalMoves}
            animation={animationPlan}
            idleAnimation={true}
            onSelect={handleSelect}
            onAnimationFinished={handleAnimationFinished}
          />
        ) : (
          <section className="board-shell placeholder">
            <div className="loading">Waiting for game data...</div>
          </section>
        )}
        <aside className="side">
          <ControlPanel
            game={game}
            busy={busy}
            whiteTimeMs={whiteClockMs}
            blackTimeMs={blackClockMs}
            activeColor={game?.activeColor}
            clockRunning={clockRunning}
            onNewGame={handleNewGame}
            onUndo={handleUndo}
            onRedo={handleRedo}
            onExport={handleExport}
          />
          <MoveList moves={game?.moves ?? []} />
          <section className="panel capture-panel">
            <header>
              <h2>Captured</h2>
              <p>Pieces claimed during the match.</p>
            </header>
            <div className="captured">
              {!game || game.captured.length === 0 ? (
                <span>None yet.</span>
              ) : (
                game.captured.map((piece, index) => (
                  <span key={`${piece}-${index}`}>{piece}</span>
                ))
              )}
            </div>
          </section>
        </aside>
      </main>
      {pgnExport ? (
        <section className="panel export">
          <div className="export-header">
            <h2>PGN Export</h2>
            <button type="button" onClick={() => setPgnExport("")}>
              Close
            </button>
          </div>
          <pre>{pgnExport}</pre>
          <span className="hint">API base: {apiBaseUrl}</span>
        </section>
      ) : null}
    </div>
  );
}
