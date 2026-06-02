import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Routes, Route, useNavigate } from "react-router-dom";
import type { PlayerColor, PlayableGameMode, GameState, BoardMatrix, PieceCode } from "./api/types";
import type { MoveHistoryEntryDto } from "./api/backendTypes";
import { getReplayFrame } from "./api/client";
import { mapGameSnapshotToGameState, computeCapturedPieces } from "./api/mapper";
import type { SpriteCatalog } from "./assets/spriteCatalog";
import { loadSpriteCatalog } from "./assets/spriteCatalog";
import { connectWebSocket, type WsClient } from "./api/ws";
import type { WsEvent } from "./api/wsTypes";
import { useGameState } from "./game/useGameState";
import { useSession } from "./session/SessionProvider";
import ChessBoard from "./components/ChessBoard.tsx";
import ControlPanel from "./components/ControlPanel.tsx";
import GameAnalysisView from "./components/GameAnalysisView.tsx";
import MoveList from "./components/MoveList.tsx";
//import ResumeGamePanel from "./components/ResumeGamePanel.tsx";
import SessionTransferPanel from "./components/SessionTransferPanel.tsx";
import StatusBanner from "./components/StatusBanner.tsx";
import Homepage from "./components/Homepage.tsx";
import BackgroundEffectsLayer from "./components/BackgroundEffectsLayer.tsx";
import BackgroundPanel from "./components/BackgroundPanel.tsx";
import CapturedPanel from "./components/CapturedPanel.tsx";
import { Chess } from "chess.js";
import "./App.css";

type ConnectionState = "connected" | "offline" | "loading";
type LiveConnectionState = "idle" | "connecting" | "live" | "disconnected";

const baseClockMs = 10 * 60 * 1000;

const backgrounds = [
  { id: "river", label: "River", url: "/assets/backgrounds/river.png" },
  { id: "sakura-grove", label: "Grove", url: "/assets/backgrounds/sakuratrees.jpg" },
  { id: "forest", label: "Forest", url: "/assets/backgrounds/new.jpg" }
];

function isGameStateRefreshHint(event: WsEvent): boolean {
  switch (event.eventType) {
    case "MoveApplied":
    case "GameFinished":
    case "SessionLifecycleChanged":
    case "PromotionPending":
    case "AITurnCompleted":
    case "GameResigned":
    case "SessionCancelled":
      return true;
    case "AITurnRequested":
    case "AITurnFailed":
    case "MoveRejected":
    case "SessionCreated":
      return false;
  }
}

interface BotWebSocketData {
  gameId: string;
  moves: string;
  botColor: PlayerColor;
  wtime: number | null;
  btime: number | null;
  lastUpdated: number;
}

function playMoveSound() {
  try {
    const ctx = new (window.AudioContext || (window as any).webkitAudioContext)();

    // Impact click: short pop at higher frequency
    const osc1 = ctx.createOscillator();
    const gain1 = ctx.createGain();
    osc1.type = "triangle";
    osc1.frequency.setValueAtTime(1200, ctx.currentTime);
    osc1.frequency.exponentialRampToValueAtTime(200, ctx.currentTime + 0.05);
    gain1.gain.setValueAtTime(0.3, ctx.currentTime);
    gain1.gain.exponentialRampToValueAtTime(0.01, ctx.currentTime + 0.05);
    osc1.connect(gain1);
    gain1.connect(ctx.destination);

    // Wooden resonant body: lower frequency decay
    const osc2 = ctx.createOscillator();
    const gain2 = ctx.createGain();
    osc2.type = "sine";
    osc2.frequency.setValueAtTime(250, ctx.currentTime);
    osc2.frequency.exponentialRampToValueAtTime(80, ctx.currentTime + 0.15);
    gain2.gain.setValueAtTime(0.6, ctx.currentTime);
    gain2.gain.exponentialRampToValueAtTime(0.01, ctx.currentTime + 0.15);
    osc2.connect(gain2);
    gain2.connect(ctx.destination);

    osc1.start();
    osc2.start();
    osc1.stop(ctx.currentTime + 0.05);
    osc2.stop(ctx.currentTime + 0.15);
  } catch (e) {
    console.error("Failed to play sound: ", e);
  }
}

function mapBotDataToGameState(
  data: BotWebSocketData
): GameState {
  const chess = new Chess();
  const moveList = data.moves ? data.moves.split(" ").filter(Boolean) : [];
  for (const moveStr of moveList) {
    if (moveStr.length >= 4) {
      const from = moveStr.substring(0, 2);
      const to = moveStr.substring(2, 4);
      const promotion = moveStr.length > 4 ? moveStr.substring(4, 5).toLowerCase() : undefined;
      chess.move({ from, to, promotion });
    }
  }

  const chessBoard = chess.board();
  const board: BoardMatrix = Array.from({ length: 8 }, () => Array(8).fill(null));
  for (let r = 0; r < 8; r++) {
    for (let c = 0; c < 8; c++) {
      const piece = chessBoard[r][c];
      if (piece) {
        board[r][c] = `${piece.color}${piece.type.toUpperCase()}` as PieceCode;
      }
    }
  }

  const captured = computeCapturedPieces(board);

  const verboseMoves = chess.history({ verbose: true });
  const moves = verboseMoves.map((m: any, index: number) => {
    const record: any = {
      ply: index + 1,
      notation: m.san,
      from: m.from,
      to: m.to,
    };
    if (m.captured) {
      const oppColor = m.color === "w" ? "b" : "w";
      record.captured = `${oppColor}${m.captured.toUpperCase()}` as PieceCode;
    }
    if (m.promotion) {
      record.promotion = `${m.color}${m.promotion.toUpperCase()}` as PieceCode;
    }
    return record;
  });

  let status: any = "active";
  if (chess.in_checkmate()) {
    status = "checkmate";
  } else if (chess.in_draw()) {
    status = "draw";
  } else if (chess.in_check()) {
    status = "check";
  }

  const activeColor = chess.turn() === "w" ? "white" : "black";
  const winner = status === "checkmate" ? (activeColor === "white" ? "black" : "white") : undefined;

  return {
    id: data.gameId,
    board,
    activeColor,
    status,
    winner,
    moves,
    captured,
    fullMove: Math.floor((moves.length + 2) / 2),
    halfMoveClock: 0,
    lastMove: moves.length > 0 ? moves[moves.length - 1] : undefined,
    legalTargetsByFrom: {}
  };
}

export default function App() {
  const {
    game,
    selectedSquare,
    legalMoves,
    busy,
    message,
    animationPlan,
    gameMode,
    notation,
    sessionLifecycle,
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
    setMessage,
    setBusy
  } = useGameState();

  const { session, setSession, getSessionId } = useSession();
  const navigate = useNavigate();

  const [connection, setConnection] = useState<ConnectionState>("loading");
  const [liveConnection, setLiveConnection] =
    useState<LiveConnectionState>("idle");
  const [whiteClockMs, setWhiteClockMs] = useState(baseClockMs);
  const [blackClockMs, setBlackClockMs] = useState(baseClockMs);
  const [backgroundId, setBackgroundId] = useState(backgrounds[0].id);
  const [spriteCatalog, setSpriteCatalog] = useState<SpriteCatalog | null>(null);
  const [timelinePly, setTimelinePly] = useState(0);
  const [timelineTotalPlies, setTimelineTotalPlies] = useState(0);
  const [timelineRawMoves, setTimelineRawMoves] = useState<MoveHistoryEntryDto[]>([]);
  const [timelineLoading, setTimelineLoading] = useState(false);
  const [timelineError, setTimelineError] = useState<string | null>(null);
  const [replayGame, setReplayGame] = useState<GameState | null>(null);

  // Bot mode state
  const [activeTab, setActiveTab] = useState<"local" | "bot">("local");
  const [botGameData, setBotGameData] = useState<BotWebSocketData | null>(null);
  const [botWhiteClockMs, setBotWhiteClockMs] = useState<number | null>(null);
  const [botBlackClockMs, setBotBlackClockMs] = useState<number | null>(null);
  const [hasNewBotMoveNotification, setHasNewBotMoveNotification] = useState(false);
  const [botConnectionState, setBotConnectionState] = useState<"idle" | "connecting" | "live" | "disconnected">("idle");
  const [devBotStatus, setDevBotStatus] = useState<string>("stopped");

  const lastTickMs = useRef<number | null>(null);
  const wsClientRef = useRef<WsClient | null>(null);
  const previousTimelineTotalRef = useRef(0);

  const clockRunning = useMemo(() => {
    const status = game?.status;
    return status === "active" || status === "check";
  }, [game?.status]);

  const sessionClosed =
    sessionLifecycle === "Finished" || sessionLifecycle === "Cancelled";

  const activeController =
    game?.activeColor === "white"
      ? session?.whiteController
      : session?.blackController;

  const boardInteractionDisabled = activeTab === "bot" || busy || sessionClosed || !clockRunning;
  const replayModeActive = timelinePly < timelineTotalPlies;

  const mappedBotGame = useMemo(() => {
    if (!botGameData) return null;
    return mapBotDataToGameState(botGameData);
  }, [botGameData]);

  const displayedGame = useMemo(() => {
    if (activeTab === "bot") {
      return mappedBotGame;
    }
    return replayModeActive && replayGame ? replayGame : game;
  }, [activeTab, mappedBotGame, replayModeActive, replayGame, game]);

  const currentReplayMove =
    timelinePly <= 0 ? null : timelineRawMoves[timelinePly - 1] ?? null;

  const botClockRunning = useMemo(() => {
    return Boolean(mappedBotGame && mappedBotGame.status !== "checkmate" && mappedBotGame.status !== "draw" && mappedBotGame.status !== "resigned");
  }, [mappedBotGame]);

  const displayedWhiteTimeMs = activeTab === "bot" ? (botWhiteClockMs ?? 0) : whiteClockMs;
  const displayedBlackTimeMs = activeTab === "bot" ? (botBlackClockMs ?? 0) : blackClockMs;
  const displayedActiveColor = activeTab === "bot" ? displayedGame?.activeColor : game?.activeColor;
  const displayedClockRunning = activeTab === "bot" ? botClockRunning : clockRunning;

  const canResign =
    activeTab !== "bot" &&
    Boolean(game) &&
    !busy &&
    !sessionClosed &&
    clockRunning &&
    activeController !== "AI";

  const resetClocks = useCallback(() => {
    setWhiteClockMs(baseClockMs);
    setBlackClockMs(baseClockMs);
    lastTickMs.current = performance.now();
  }, []);

  useEffect(() => {
    setConnection("loading");

    loadGame()
      .then(() => setConnection("connected"))
      .catch(() => setConnection("offline"));
  }, [loadGame]);

  useEffect(() => {
    if (game?.id) {
      resetClocks();
    }
  }, [game?.id, resetClocks]);

  useEffect(() => {
    if (!game) {
      setTimelinePly(0);
      setTimelineTotalPlies(0);
      setTimelineRawMoves([]);
      setTimelineError(null);
      setReplayGame(null);
      previousTimelineTotalRef.current = 0;
      return;
    }

    const nextTotalPlies = game.moves.length;
    const wasAtLiveEdge = timelinePly >= previousTimelineTotalRef.current;

    setTimelineTotalPlies(nextTotalPlies);
    if (wasAtLiveEdge) {
      setTimelinePly(nextTotalPlies);
    } else {
      setTimelinePly((value) => Math.min(value, nextTotalPlies));
    }

    previousTimelineTotalRef.current = nextTotalPlies;
  }, [game, timelinePly]);

  useEffect(() => {
    if (!game?.id) return;

    let active = true;
    setTimelineLoading(true);
    setTimelineError(null);

    getReplayFrame(game.id, timelinePly)
      .then((frame) => {
        if (!active) return;
        setTimelineTotalPlies(frame.totalPlies);
        setTimelineRawMoves(frame.rawMoves);

        if (timelinePly < frame.totalPlies) {
          setReplayGame(mapGameSnapshotToGameState(frame.game));
        } else {
          setReplayGame(null);
        }
      })
      .catch((error) => {
        if (!active) return;
        setTimelineError(
          error instanceof Error
            ? error.message
            : "Replay timeline could not be loaded."
        );
      })
      .finally(() => {
        if (active) setTimelineLoading(false);
      });

    return () => {
      active = false;
    };
  }, [game?.id, timelinePly]);

  const handleStartGame = async (selectedMode: PlayableGameMode) => {
    setGameMode(selectedMode);
    navigate("/game");
    await handleNewGame(selectedMode);
  };

  const handleBackToMenu = useCallback(() => {
    navigate("/");
  }, [navigate]);

  useEffect(() => {
    wsClientRef.current?.close();
    wsClientRef.current = null;

    if (!game?.id) {
      setLiveConnection("idle");
      return;
    }

    let active = true;
    setLiveConnection("connecting");

    const refreshGameSnapshotAfterHint = async (
      event: WsEvent
    ): Promise<void> => {
      try {
        await refreshFromServer();
        setBusy(false);

        if (
          event.eventType === "SessionLifecycleChanged" &&
          session?.sessionId === event.sessionId
        ) {
          setSession({ ...session, lifecycle: event.to });
        }

        if (event.eventType === "SessionCancelled") {
          if (session?.sessionId === event.sessionId) {
            setSession({ ...session, lifecycle: "Cancelled" });
          }

          setMessage("This session was cancelled.");
        }
      } catch (error) {
        if (!active) return;

        setLiveConnection("disconnected");
        setMessage(
          error instanceof Error
            ? `Live update received, but refresh failed. ${error.message}`
            : "Live update received, but refresh failed."
        );
      }
    };

    const client = connectWebSocket({
      gameId: game.id,
      getSessionId,
      onOpen: () => {
        if (active) setLiveConnection("live");
      },
      onClose: () => {
        if (active) setLiveConnection("disconnected");
      },
      onError: () => {
        if (active) setLiveConnection("disconnected");
      },
      onMessage: (event) => {
        if (!active) return;

        if (isGameStateRefreshHint(event)) {
          void refreshGameSnapshotAfterHint(event);
          return;
        }

        switch (event.eventType) {
          case "AITurnRequested":
            setBusy(true);
            setMessage(`AI is thinking for ${event.currentPlayer}...`);
            return;

          case "AITurnFailed":
            setBusy(false);
            setMessage(`AI move failed. ${event.reason}`);
            return;

          case "MoveRejected":
            setBusy(false);
            setMessage(`Move rejected. ${event.reason}`);
            return;

          case "SessionCreated":
            return;
        }
      }
    });

    wsClientRef.current = client;

    return () => {
      active = false;
      client.close();

      if (wsClientRef.current === client) {
        wsClientRef.current = null;
      }
    };
  }, [
    game?.id,
    getSessionId,
    refreshFromServer,
    session,
    setBusy,
    setMessage,
    setSession
  ]);

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
    const match = backgrounds.find((item) => item.id === backgroundId);
    const nextUrl = match?.url ?? backgrounds[0].url;

    document.documentElement.style.setProperty(
      "--app-background",
      `url("${nextUrl}")`
    );
  }, [backgroundId]);

  // Track activeTab in a ref for the WebSocket callback
  const activeTabRef = useRef(activeTab);
  useEffect(() => {
    activeTabRef.current = activeTab;
    if (activeTab === "bot") {
      setHasNewBotMoveNotification(false);
    }
  }, [activeTab]);

  // Connect to Scala Bot WebSocket server
  const prevBotMovesCountRef = useRef<number>(0);
  const prevBotGameIdRef = useRef<string | null>(null);

  useEffect(() => {
    let ws: WebSocket | null = null;
    let reconnectTimeoutId: any = null;
    let isComponentMounted = true;

    function connect() {
      if (!isComponentMounted) return;
      setBotConnectionState("connecting");

      ws = new WebSocket("ws://localhost:9323");

      ws.onopen = () => {
        if (!isComponentMounted) return;
        setBotConnectionState("live");
      };

      ws.onmessage = (event) => {
        if (!isComponentMounted) return;
        try {
          const data: BotWebSocketData = JSON.parse(event.data);
          setBotGameData(data);

          if (data.wtime !== undefined && data.wtime !== null) {
            setBotWhiteClockMs(data.wtime);
          }
          if (data.btime !== undefined && data.btime !== null) {
            setBotBlackClockMs(data.btime);
          }

          const movesStr = data.moves || "";
          const movesCount = movesStr.split(" ").filter(Boolean).length;

          if (activeTabRef.current === "local") {
            if (prevBotGameIdRef.current !== null &&
              (data.gameId !== prevBotGameIdRef.current || movesCount > prevBotMovesCountRef.current)) {
              setHasNewBotMoveNotification(true);
            }
          }

          prevBotMovesCountRef.current = movesCount;
          prevBotGameIdRef.current = data.gameId;
        } catch (e) {
          console.error("Failed to parse bot websocket message: ", e);
        }
      };

      ws.onclose = () => {
        if (!isComponentMounted) return;
        setBotConnectionState("disconnected");
        reconnectTimeoutId = setTimeout(() => {
          connect();
        }, 3000);
      };

      ws.onerror = () => {
        if (!isComponentMounted) return;
        ws?.close();
      };
    }

    connect();

    return () => {
      isComponentMounted = false;
      if (ws) {
        ws.close();
      }
      if (reconnectTimeoutId) {
        clearTimeout(reconnectTimeoutId);
      }
    };
  }, []);

  // Poll dev-bot status for global indicator
  useEffect(() => {
    let active = true;
    const fetchStatus = async () => {
      try {
        const res = await fetch("/api/dev-bot/status");
        if (active && res.ok) {
          const data = await res.json();
          setDevBotStatus(data.status);
        }
      } catch (err) {
        // Fail silently
      }
    };

    fetchStatus();
    const interval = setInterval(fetchStatus, 3000);
    return () => {
      active = false;
      clearInterval(interval);
    };
  }, []);

  // Bot clock ticking
  useEffect(() => {
    let lastTick = performance.now();
    const intervalId = window.setInterval(() => {
      const now = performance.now();
      const delta = Math.max(0, now - lastTick);
      lastTick = now;

      if (botGameData) {
        const chess = new Chess();
        const moveList = botGameData.moves ? botGameData.moves.split(" ").filter(Boolean) : [];
        for (const moveStr of moveList) {
          if (moveStr.length >= 4) {
            const from = moveStr.substring(0, 2);
            const to = moveStr.substring(2, 4);
            const promotion = moveStr.length > 4 ? moveStr.substring(4, 5).toLowerCase() : undefined;
            chess.move({ from, to, promotion });
          }
        }

        if (!chess.game_over()) {
          const turn = chess.turn();
          if (turn === "w") {
            setBotWhiteClockMs((t) => (t !== null ? Math.max(0, t - delta) : null));
          } else {
            setBotBlackClockMs((t) => (t !== null ? Math.max(0, t - delta) : null));
          }
        }
      }
    }, 250);

    return () => window.clearInterval(intervalId);
  }, [botGameData]);

  // Audio trigger
  const lastPlayedGameId = useRef<string | null>(null);
  const prevMovesLength = useRef<number | null>(null);

  useEffect(() => {
    if (!displayedGame) {
      lastPlayedGameId.current = null;
      prevMovesLength.current = null;
      return;
    }

    const gameId = activeTab === "bot" ? displayedGame.id : (game?.id ?? "local");
    const currentLength = displayedGame.moves.length;

    if (lastPlayedGameId.current === gameId && prevMovesLength.current !== null && currentLength > prevMovesLength.current) {
      playMoveSound();
    }

    lastPlayedGameId.current = gameId;
    prevMovesLength.current = currentLength;
  }, [displayedGame, activeTab, game?.id]);

  useEffect(() => {
    let active = true;

    loadSpriteCatalog()
      .then((catalog) => {
        if (active) setSpriteCatalog(catalog);
      })
      .catch(() => {
        if (active) setSpriteCatalog(null);
      });

    return () => {
      active = false;
    };
  }, []);

  const displayedConnection = activeTab === "bot"
    ? (botConnectionState === "disconnected" ? "offline" as const : botConnectionState === "connecting" ? "loading" as const : "connected" as const)
    : connection;

  const displayedLiveConnection = activeTab === "bot"
    ? (botConnectionState === "live" ? "live" as const : botConnectionState === "connecting" ? "connecting" as const : "disconnected" as const)
    : liveConnection;

  const displayedMessage = activeTab === "bot"
    ? (botConnectionState === "disconnected" ? "Verbindung zum Bot-Server getrennt. Reconnect in 3s... / Disconnected from bot server. Reconnecting..." : (!botGameData ? "Warte auf Bot-Spiele... / Waiting for bot games..." : undefined))
    : message;

  return (
    <div className="app">
      <BackgroundEffectsLayer backgroundId={backgroundId} />

      <Routes>
        <Route path="/" element={
          <Homepage
            hasActiveGame={Boolean(game)}
            busy={busy}
            onStart={handleStartGame}
            onContinueActiveGame={() => navigate("/game")}
            onResumeSession={async (sessionId) => {
              await handleResumeSession(sessionId);
              navigate("/game");
            }}
          />
        } />
        <Route path="/game" element={
          <main className="layout">
            <aside className="side left-side">
              <BackgroundPanel
                backgrounds={backgrounds}
                backgroundId={backgroundId}
                onChange={setBackgroundId}
              />

              <MoveList moves={displayedGame?.moves ?? []} />

              <CapturedPanel captured={displayedGame?.captured ?? []} spriteCatalog={spriteCatalog} />
            </aside>

            {displayedGame || activeTab === "bot" ? (
              <section className="board-column">
                <nav className="tab-navigation" aria-label="Game Mode Tabs">
                  <button
                    type="button"
                    className={`tab-btn ${activeTab === "local" ? "active" : ""}`}
                    onClick={() => setActiveTab("local")}
                  >
                    🎮 Lokal Spielen
                  </button>
                  <button
                    type="button"
                    className={`tab-btn ${activeTab === "bot" ? "active" : ""}`}
                    onClick={() => setActiveTab("bot")}
                  >
                    🤖 Bot-Live-Monitor
                    {devBotStatus === "running" && <span className="bot-live-dot" title="Bot ist aktiv!" />}
                    {hasNewBotMoveNotification && <span className="notification-dot" />}
                  </button>
                </nav>

                <StatusBanner
                  game={displayedGame ?? undefined}
                  connection={displayedConnection}
                  liveConnection={displayedLiveConnection}
                  message={displayedMessage}
                />

                {displayedGame ? (
                  <ChessBoard
                    board={displayedGame.board}
                    selectedSquare={replayModeActive ? undefined : selectedSquare}
                    legalMoves={replayModeActive ? [] : legalMoves}
                    animation={replayModeActive ? null : animationPlan}
                    idleAnimation={true}
                    disabled={boardInteractionDisabled || replayModeActive}
                    onSelect={handleSelect}
                    onAnimationFinished={handleAnimationFinished}
                    inCheck={displayedGame.status === "check"}
                    activeColor={displayedGame.activeColor}
                    gameStatus={displayedGame.status}
                    drawReason={displayedGame.drawReason}
                    winner={displayedGame.winner}
                    promotionPending={promotionPending}
                    onResolvePromotion={handleResolvePromotion}
                    onCancelPromotion={handleCancelPromotion}
                    onNewGame={handleNewGame}
                    orientation={activeTab === "bot" ? (botGameData?.botColor ?? "white") : "white"}
                  />
                ) : (
                  <section className="board-shell placeholder">
                    <div className="loading">Warte auf Bot-Spieldaten... / Waiting for bot game data...</div>
                  </section>
                )}

                {activeTab !== "bot" && (
                  <section className="replay-timeline panel" aria-label="Time-travel timeline">
                    <header className="replay-timeline-header">
                      <h2>Time-Travel</h2>
                      <p>
                        Frame {timelinePly} / {timelineTotalPlies}
                        {replayModeActive ? " (Replay)" : " (Live)"}
                      </p>
                    </header>

                    {timelineError ? (
                      <div className="replay-timeline-error">{timelineError}</div>
                    ) : null}

                    <input
                      type="range"
                      min={0}
                      max={timelineTotalPlies}
                      step={1}
                      value={timelinePly}
                      onChange={(event) =>
                        setTimelinePly(Number(event.currentTarget.value))
                      }
                      disabled={timelineLoading || timelineTotalPlies <= 0}
                    />

                    <div className="replay-timeline-meta">
                      <span>
                        {currentReplayMove
                          ? `${currentReplayMove.from} -> ${currentReplayMove.to}${currentReplayMove.promotion
                            ? ` (${currentReplayMove.promotion})`
                            : ""
                          }`
                          : "Initial position"}
                      </span>
                      {replayModeActive ? (
                        <button
                          type="button"
                          onClick={() => setTimelinePly(timelineTotalPlies)}
                          disabled={timelineLoading}
                        >
                          Back To Live
                        </button>
                      ) : null}
                    </div>
                  </section>
                )}
              </section>
            ) : (
              <section className="board-shell placeholder">
                <div className="loading">Waiting for game data...</div>
              </section>
            )}

            <aside className="side right-side">
              <ControlPanel
                game={activeTab === "bot" ? (displayedGame ?? undefined) : game}
                busy={busy}
                whiteTimeMs={displayedWhiteTimeMs}
                blackTimeMs={displayedBlackTimeMs}
                activeColor={displayedActiveColor}
                clockRunning={displayedClockRunning}
                gameMode={gameMode}
                canResign={canResign}
                sessionId={session?.sessionId}
                gameId={activeTab === "bot" ? (displayedGame?.id ?? undefined) : (game?.id ?? session?.gameId)}
                fen={activeTab === "bot" ? undefined : notation?.fen}
                pgn={activeTab === "bot" ? undefined : notation?.pgn}
                onImportNotation={handleImportNotation}
                onExportNotation={handleExportNotation}
                onGameModeChange={setGameMode}
                onNewGame={handleNewGame}
                onSaveSession={handleSaveSession}
                onResign={handleResign}
                onBackToMenu={handleBackToMenu}
                onOpenHeatmap={() => navigate("/analysis")}
              />

            </aside>
          </main>
        } />
        <Route
          path="/analysis"
          element={<GameAnalysisView gameId={game?.id ?? session?.gameId ?? null} />}
        />
      </Routes>
    </div>
  );
}
