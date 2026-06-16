import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useLocation } from "react-router-dom";
import type { PlayableGameMode } from "./api/types";
import { useSpriteCatalog } from "./app/hooks/useSpriteCatalog";
import { useGameSceneSelection } from "./app/hooks/useGameSceneSelection";
import { useBackgroundSelection } from "./app/hooks/useBackgroundSelection";
import { useGameClock } from "./app/hooks/useGameClock";
import { useReplayTimeline } from "./app/hooks/useReplayTimeline";
import { connectWebSocket, type WsClient } from "./api/ws";
import type { WsEvent } from "./api/wsTypes";
import { useGameState } from "./game/useGameState";
import { useSession } from "./session/SessionProvider";
import { useProfileOnboarding } from "./hooks/useProfileOnboarding";
import { useBotDemoStream } from "./hooks/useBotDemoStream";
import { AuthBar } from "./features/auth";
import AppRoutes from "./app/AppRoutes";
import type { ConnectionState, LiveConnectionState } from "./app/types";
import { useMoveSound } from "./app/hooks/useMoveSound";
import "./App.css";

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

interface BotWebSocketData {
  gameId: string;
  moves: string;
  botColor?: "white" | "black";
  wtime?: number | null;
  btime?: number | null;
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
      chess.move({ from: from as any, to: to as any, promotion: promotion as any });
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
  const moves = verboseMoves.map((m, index) => {
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
    handleResumeSession,
    handleSaveSession,
    handleResign,
    handleRunAiTurns,
    handleAnimationFinished,
    handleResolvePromotion,
    handleCancelPromotion,
    setMessage,
    setBusy,
  } = useGameState();
  const { session, setSession, getSessionId } = useSession();
  const navigate = useNavigate();
  const location = useLocation();
  const isGameRoute = location.pathname === "/game";
  const isHomeRoute = location.pathname === "/";
  const isTournamentsRoute = location.pathname.startsWith("/tournaments");
  const isAnalyticsRoute = location.pathname === "/analytics";
  const isAnalysisRoute = location.pathname === "/analysis";
  const isSettingsRoute = location.pathname === "/settings";

  const { profile, onboardingRequired, setOnboardingRequired } = useProfileOnboarding();

  const [activeTab, setActiveTab] = useState<"local" | "bot">("local");
  const {
    botGameData,
    mappedBotGame,
    botWhiteClockMs,
    botBlackClockMs,
    hasNewBotMoveNotification,
    botConnectionState,
  } = useBotDemoStream(activeTab);

  const [connection, setConnection] = useState<ConnectionState>("loading");
  const [liveConnection, setLiveConnection] = useState<LiveConnectionState>("idle");
  const { whiteClockMs, blackClockMs, clockRunning } = useGameClock({
    gameId: game?.id,
    gameStatus: game?.status,
    activeColor: game?.activeColor,
  });
  const { gameScenes, gameSceneId, setGameSceneId, gameScene } = useGameSceneSelection();
  const { backgroundId, setBackgroundId, backgrounds } = useBackgroundSelection();
  const spriteCatalog = useSpriteCatalog();
  const {
    timelinePly,
    setTimelinePly,
    timelineTotalPlies,
    timelineRawMoves,
    timelineLoading,
    timelineError,
    replayGame,
  } = useReplayTimeline(game);

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
  const [liveTimelineEvents, setLiveTimelineEvents] = useState<LiveTimelineEvent[]>([]);
  const [profile, setProfile] = useState<UserProfileResponse | null>(null);
  const [onboardingRequired, setOnboardingRequired] = useState(false);

  // Bot mode state
  const [activeTab, setActiveTab] = useState<"local" | "bot">("local");
  const [botGameData, setBotGameData] = useState<BotWebSocketData | null>(null);
  const [botWhiteClockMs, setBotWhiteClockMs] = useState<number | null>(null);
  const [botBlackClockMs, setBotBlackClockMs] = useState<number | null>(null);
  const [hasNewBotMoveNotification, setHasNewBotMoveNotification] = useState(false);
  const [botConnectionState, setBotConnectionState] = useState<"idle" | "connecting" | "live" | "disconnected">("idle");

  const lastTickMs = useRef<number | null>(null);
  const wsClientRef = useRef<WsClient | null>(null);

  const sessionClosed =
    sessionLifecycle === "Finished" || sessionLifecycle === "Cancelled";

  const activeController =
    game?.activeColor === "white"
      ? session?.whiteController
      : session?.blackController;

  const boardInteractionDisabled = activeTab === "bot" || busy || sessionClosed || !clockRunning;
  const replayModeActive = timelinePly < timelineTotalPlies;
  const boundedTimelinePly = Math.min(timelinePly, timelineTotalPlies);

  const displayedGame = useMemo(() => {
    if (activeTab === "bot") {
      return mappedBotGame;
    }
    return replayModeActive && replayGame ? replayGame : game;
  }, [activeTab, mappedBotGame, replayModeActive, replayGame, game]);

  const currentReplayMove =
    timelinePly <= 0 ? null : timelineRawMoves[timelinePly - 1] ?? null;

  const botClockRunning = useMemo(() => {
    return Boolean(
      mappedBotGame &&
      mappedBotGame.status !== "checkmate" &&
      mappedBotGame.status !== "draw" &&
      mappedBotGame.status !== "resigned"
    );
  }, [mappedBotGame]);

  const displayedWhiteTimeMs = activeTab === "bot" ? (botWhiteClockMs ?? 0) : whiteClockMs;
  const displayedBlackTimeMs = activeTab === "bot" ? (botBlackClockMs ?? 0) : blackClockMs;
  const displayedClockRunning = activeTab === "bot" ? botClockRunning : clockRunning;

  const canResign =
    activeTab !== "bot" &&
    Boolean(game) &&
    !busy &&
    !sessionClosed &&
    clockRunning &&
    activeController !== "AI";

  useEffect(() => {
    setConnection("loading");
    loadGame()
      .then(() => setConnection("connected"))
      .catch(() => setConnection("offline"));
  }, [loadGame]);

  useEffect(() => {
    let active = true;

    getMyProfile()
      .then((loadedProfile) => {
        if (!active) return;
        setProfile(loadedProfile);
        setOnboardingRequired(!loadedProfile.nickname);
      })
      .catch(() => {
        if (!active) return;
        setProfile(null);
        setOnboardingRequired(false);
      });

    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    if (game?.id) {
      resetClocks();
      setLiveTimelineEvents([]);
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

    const currentTotalPlies = game.moves.length;
    if (timelinePly > currentTotalPlies) {
      setTimelinePly(currentTotalPlies);
      setTimelineError(null);
      setReplayGame(null);
      return;
    }

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
  }, [game?.id, game?.moves.length, timelinePly]);

  const handleStartGame = async (selectedMode: PlayableGameMode) => {
    if (onboardingRequired) {
      navigate("/onboarding");
      return;
    }
    setGameMode(selectedMode);
    navigate("/game");
    await handleNewGame(selectedMode);
  };

  const handleBackToMenu = useCallback(() => {
    navigate("/");
  }, [navigate]);

  const handleOpenBotDemo = useCallback(() => {
    setActiveTab("bot");
    navigate("/game");
  }, [navigate]);

  const handleContinueActiveGame = useCallback(() => navigate("/game"), [navigate]);
  const handleOpenSettings = useCallback(() => navigate("/settings"), [navigate]);
  const handleOpenOnboarding = useCallback(() => navigate("/onboarding"), [navigate]);
  const handleOpenLichessHub = useCallback(() => navigate("/lichess"), [navigate]);
  const handleCompleteOnboarding = useCallback(() => {
    setOnboardingRequired(false);
    navigate("/");
  }, [navigate, setOnboardingRequired]);
  const handleOpenHeatmap = useCallback(() => navigate("/analysis"), [navigate]);
  const handleOpenLichessGame = useCallback((gameId: string) => navigate(`/lichess/games/${gameId}`), [navigate]);
  const handleBackToLichess = useCallback(() => navigate("/lichess"), [navigate]);
  const handleResumeAndNavigate = useCallback(async (sessionId: string) => {
    await handleResumeSession(sessionId);
    navigate("/game");
  }, [handleResumeSession, navigate]);

  useEffect(() => {
    wsClientRef.current?.close();
    wsClientRef.current = null;

    if (!game?.id) {
      setLiveConnection("idle");
      return;
    }

    let active = true;
    setLiveConnection("connecting");

    const refreshGameSnapshotAfterHint = async (event: WsEvent): Promise<void> => {
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

        setLiveTimelineEvents((events) => [
          ...events,
          {
            id: `${event.gameId}:${event.eventType}:${Date.now()}:${events.length}`,
            receivedAt: new Date().toISOString(),
            event
          }
        ].slice(-30));

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
      },
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
    setSession,
  ]);

  useMoveSound(game);

  const displayedConnection: ConnectionState =
    activeTab === "bot"
      ? botConnectionState === "disconnected"
        ? "offline"
        : botConnectionState === "connecting"
          ? "loading"
          : "connected"
      : connection;
  setSession
  ]);

  const clockStateRef = useRef({
    running: false,
    activeColor: "white" as import("./api/types").PlayerColor
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
        setWhiteClockMs((v) => Math.max(0, v - delta));
      } else {
        setBlackClockMs((v) => Math.max(0, v - delta));
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

      const botWsUrl = import.meta.env.VITE_BOT_WS_URL as string | undefined;
      if (!botWsUrl) {
        setBotConnectionState("disconnected");
        return;
      }
      ws = new WebSocket(botWsUrl);

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
            chess.move({ from: from as any, to: to as any, promotion: promotion as any });
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
    if (!game) {
      lastPlayedGameId.current = null;
      prevMovesLength.current = null;
      return;
    }

    const gameId = game.id;
    const currentLength = game.moves.length;

    if (lastPlayedGameId.current === gameId && prevMovesLength.current !== null && currentLength > prevMovesLength.current) {
      playMoveSound();
    }

    lastPlayedGameId.current = gameId;
    prevMovesLength.current = currentLength;
  }, [game]);

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

  const displayedLiveConnection: LiveConnectionState =
    activeTab === "bot"
      ? botConnectionState === "live"
        ? "live"
        : botConnectionState === "connecting"
          ? "connecting"
          : "disconnected"
      : liveConnection;

  const displayedMessage =
    activeTab === "bot"
      ? botConnectionState === "disconnected"
        ? "Disconnected from bot server. Reconnecting..."
        : !botGameData
          ? "Waiting for bot games..."
          : undefined
      : message;

  return (
    <div className="app">
      {!isGameRoute && !isHomeRoute && !isTournamentsRoute && !isAnalyticsRoute && !isAnalysisRoute && !isSettingsRoute && <AuthBar />}

      <AppRoutes
        game={game}
        displayedGame={displayedGame}
        mappedBotGame={mappedBotGame}
        selectedSquare={selectedSquare}
        legalMoves={legalMoves}
        animationPlan={animationPlan}
        promotionPending={promotionPending}
        busy={busy}
        gameMode={gameMode}
        notation={notation}
        boardInteractionDisabled={boardInteractionDisabled}
        canResign={canResign}
        session={session}
        profile={profile}
        onboardingRequired={onboardingRequired}
        activeTab={activeTab}
        setActiveTab={setActiveTab}
        botGameData={botGameData}
        hasNewBotMoveNotification={hasNewBotMoveNotification}
        botConnectionState={botConnectionState}
        displayedConnection={displayedConnection}
        displayedLiveConnection={displayedLiveConnection}
        displayedMessage={displayedMessage}
        timelinePly={timelinePly}
        setTimelinePly={setTimelinePly}
        timelineTotalPlies={timelineTotalPlies}
        timelineLoading={timelineLoading}
        timelineError={timelineError}
        replayModeActive={replayModeActive}
        boundedTimelinePly={boundedTimelinePly}
        currentReplayMove={currentReplayMove}
        displayedWhiteTimeMs={displayedWhiteTimeMs}
        displayedBlackTimeMs={displayedBlackTimeMs}
        displayedClockRunning={displayedClockRunning}
        gameSceneId={gameSceneId}
        setGameSceneId={setGameSceneId}
        gameScenes={gameScenes}
        gameScene={gameScene}
        spriteCatalog={spriteCatalog}
        backgroundId={backgroundId}
        setBackgroundId={setBackgroundId}
        backgrounds={backgrounds}
        onContinueActiveGame={handleContinueActiveGame}
        onStartGame={handleStartGame}
        onResumeSession={handleResumeAndNavigate}
        onOpenSettings={handleOpenSettings}
        onOpenOnboarding={handleOpenOnboarding}
        onOpenLichessHub={handleOpenLichessHub}
        onOpenBotDemo={handleOpenBotDemo}
        onCompleteOnboarding={handleCompleteOnboarding}
        onOpenHeatmap={handleOpenHeatmap}
        onBackToMenu={handleBackToMenu}
        onOpenLichessGame={handleOpenLichessGame}
        onBackToLichess={handleBackToLichess}
        onSelect={handleSelect}
        onAnimationFinished={handleAnimationFinished}
        onResolvePromotion={handleResolvePromotion}
        onCancelPromotion={handleCancelPromotion}
        onNewGame={handleNewGame}
        onImportNotation={handleImportNotation}
        onExportNotation={handleExportNotation}
        onGameModeChange={setGameMode}
        onSaveSession={handleSaveSession}
        onResign={handleResign}
        onRunAiTurns={handleRunAiTurns}
      />
    </div>
  );
}
