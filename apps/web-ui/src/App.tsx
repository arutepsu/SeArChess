import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import type { PlayableGameMode } from "./api/types";
import { useSpriteCatalog } from "./app/hooks/useSpriteCatalog";
import { useBackgroundSelection } from "./app/hooks/useBackgroundSelection";
import { useGameSceneSelection } from "./app/hooks/useGameSceneSelection";
import { useGameClock } from "./app/hooks/useGameClock";
import { useReplayTimeline } from "./app/hooks/useReplayTimeline";
import { connectWebSocket, type WsClient } from "./api/ws";
import type { WsEvent } from "./api/wsTypes";
import { useGameState } from "./game/useGameState";
import { useSession } from "./session/SessionProvider";
import { useProfileOnboarding } from "./hooks/useProfileOnboarding";
import { useBotDemoStream } from "./hooks/useBotDemoStream";
import BackgroundEffectsLayer from "./components/layout/BackgroundEffectsLayer";
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
  const { backgrounds, backgroundId, setBackgroundId } = useBackgroundSelection(Boolean(gameScene));
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
        ? "Verbindung zum Bot-Server getrennt. Reconnect in 3s... / Disconnected from bot server. Reconnecting..."
        : !botGameData
        ? "Warte auf Bot-Spiele... / Waiting for bot games..."
        : undefined
      : message;

  return (
    <div className="app">
      <BackgroundEffectsLayer backgroundId={backgroundId} disabled={Boolean(gameScene)} />
      <AuthBar />

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
        backgroundId={backgroundId}
        setBackgroundId={setBackgroundId}
        backgrounds={backgrounds}
        gameSceneId={gameSceneId}
        setGameSceneId={setGameSceneId}
        gameScenes={gameScenes}
        gameScene={gameScene}
        spriteCatalog={spriteCatalog}
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
