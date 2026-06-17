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
import type { LiveTimelineEvent } from "./components/EventTimeline";
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
  const [liveTimelineEvents, setLiveTimelineEvents] = useState<LiveTimelineEvent[]>([]);
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

  const wsClientRef = useRef<WsClient | null>(null);
  const recordedLiveEventIdsRef = useRef<Set<string>>(new Set());

  const appendLiveTimelineEvent = useCallback((entry: LiveTimelineEvent) => {
    if (recordedLiveEventIdsRef.current.has(entry.id)) return;

    recordedLiveEventIdsRef.current.add(entry.id);
    setLiveTimelineEvents((events) => [...events, entry].slice(-100));
  }, []);

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

  const gameStateTimelineEvents = useMemo<LiveTimelineEvent[]>(() => {
    if (!game?.id) return [];

    const sessionId = session?.sessionId ?? game.id;
    const correlationId = session?.sessionId ?? game.id;
    const snapshotTime = new Date().toISOString();

    const createdEvent: LiveTimelineEvent = {
      id: `state:${game.id}:created`,
      receivedAt: snapshotTime,
      event: {
        eventType: "GameCreated",
        gameId: game.id,
        sessionId,
        producer: "game-service",
        correlationId,
        occurredAt: snapshotTime,
        status: "processed"
      } as unknown as WsEvent
    };

    const moveEvents = game.moves.map((move) => ({
      id: `state:${game.id}:move:${move.ply}`,
      receivedAt: snapshotTime,
      event: {
        eventType: "MoveApplied",
        gameId: game.id,
        sessionId,
        producer: "game-service",
        correlationId,
        occurredAt: snapshotTime,
        status: "processed",
        move: {
          from: move.from,
          to: move.to,
          ...(move.promotion ? { promotion: move.promotion } : {})
        },
        playerWhoMoved: move.ply % 2 === 1 ? "white" : "black"
      } as unknown as WsEvent
    }));

    const finishedEvent =
      game.status === "checkmate" || game.status === "draw" || game.status === "resigned"
        ? [
            {
              id: `state:${game.id}:finished:${game.status}`,
              receivedAt: snapshotTime,
              event: {
                eventType: "GameFinished",
                gameId: game.id,
                sessionId,
                producer: "game-service",
                correlationId,
                occurredAt: snapshotTime,
                status: "processed",
                winner: game.winner,
                drawReason: game.drawReason
              } as unknown as WsEvent
            }
          ]
        : [];

    return [createdEvent, ...moveEvents, ...finishedEvent];
  }, [
    game?.drawReason,
    game?.id,
    game?.moves,
    game?.status,
    game?.winner,
    session?.sessionId
  ]);

  const displayedLiveTimelineEvents = useMemo<LiveTimelineEvent[]>(() => {
    const byId = new Map<string, LiveTimelineEvent>();
    [...gameStateTimelineEvents, ...liveTimelineEvents].forEach((event) => byId.set(event.id, event));
    return Array.from(byId.values()).slice(-100);
  }, [gameStateTimelineEvents, liveTimelineEvents]);

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

        appendLiveTimelineEvent({
          id: `ws:${event.gameId}:${event.eventType}:${Date.now()}`,
          receivedAt: new Date().toISOString(),
          event
        });

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
    appendLiveTimelineEvent,
    getSessionId,
    refreshFromServer,
    session,
    setBusy,
    setMessage,
    setSession,
  ]);

  useMoveSound(game);



  useEffect(() => {
    const match = backgrounds.find((item) => item.id === backgroundId);
    const nextUrl = match?.imageUrl ?? backgrounds[0].imageUrl;

    document.documentElement.style.setProperty(
      "--app-background",
      `url("${nextUrl}")`
    );
  }, [backgroundId]);





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
        liveTimelineEvents={displayedLiveTimelineEvents}
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
        onClearLiveTimelineEvents={() => {
          recordedLiveEventIdsRef.current.clear();
          setLiveTimelineEvents([]);
        }}
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
