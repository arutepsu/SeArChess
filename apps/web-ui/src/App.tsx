import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Routes, Route, useNavigate } from "react-router-dom";
import type { PlayableGameMode, GameState } from "./api/types";
import type { MoveHistoryEntryDto } from "./api/backendTypes";
import { getReplayFrame } from "./api/client";
import { mapGameSnapshotToGameState } from "./api/mapper";
import type { SpriteCatalog } from "./assets/spriteCatalog";
import { loadSpriteCatalog } from "./assets/spriteCatalog";
import { connectWebSocket, type WsClient } from "./api/ws";
import type { WsEvent } from "./api/wsTypes";
import { useGameState } from "./game/useGameState";
import { useSession } from "./session/SessionProvider";
import { useProfileOnboarding } from "./hooks/useProfileOnboarding";
import { useBotDemoStream } from "./hooks/useBotDemoStream";
import MainGameView from "./components/MainGameView.tsx";
import Homepage from "./components/Homepage.tsx";
import OnboardingPage from "./components/OnboardingPage.tsx";
import BackgroundEffectsLayer from "./components/BackgroundEffectsLayer.tsx";
import AuthBar from "./components/AuthBar.tsx";
import ProfilePanel from "./components/ProfilePanel.tsx";
import LichessHubPage from "./components/LichessHubPage.tsx";
import LichessGamePage from "./components/LichessGamePage.tsx";
import GameAnalysisView from "./components/GameAnalysisView.tsx";
import "./App.css";

type ConnectionState = "connected" | "offline" | "loading";
type LiveConnectionState = "idle" | "connecting" | "live" | "disconnected";

const baseClockMs = 10 * 60 * 1000;

const backgrounds = [
  { id: "river", label: "River", url: "/assets/backgrounds/river.png" },
  { id: "sakura-grove", label: "Grove", url: "/assets/backgrounds/sakuratrees.jpg" },
  { id: "forest", label: "Forest", url: "/assets/backgrounds/new.jpg" },
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

function playMoveSound() {
  try {
    const ctx = new (window.AudioContext || (window as any).webkitAudioContext)();

    const osc1 = ctx.createOscillator();
    const gain1 = ctx.createGain();
    osc1.type = "triangle";
    osc1.frequency.setValueAtTime(1200, ctx.currentTime);
    osc1.frequency.exponentialRampToValueAtTime(200, ctx.currentTime + 0.05);
    gain1.gain.setValueAtTime(0.3, ctx.currentTime);
    gain1.gain.exponentialRampToValueAtTime(0.01, ctx.currentTime + 0.05);
    osc1.connect(gain1);
    gain1.connect(ctx.destination);

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

  const clockStateRef = useRef({
    running: false,
    activeColor: "white" as import("./api/types").PlayerColor,
  });

  useEffect(() => {
    clockStateRef.current = {
      running: clockRunning,
      activeColor: game?.activeColor ?? "white",
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

    if (
      lastPlayedGameId.current === gameId &&
      prevMovesLength.current !== null &&
      currentLength > prevMovesLength.current
    ) {
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
      <BackgroundEffectsLayer backgroundId={backgroundId} />
      <AuthBar />

      <Routes>
        <Route
          path="/"
          element={
            <Homepage
              hasActiveGame={Boolean(game)}
              busy={busy}
              onboardingRequired={onboardingRequired}
              profile={profile}
              onStart={handleStartGame}
              onContinueActiveGame={() => navigate("/game")}
              onResumeSession={async (sessionId) => {
                await handleResumeSession(sessionId);
                navigate("/game");
              }}
              onOpenSettings={() => navigate("/settings")}
              onOpenOnboarding={() => navigate("/onboarding")}
              onOpenLichessHub={() => navigate("/lichess")}
              onOpenBotDemo={handleOpenBotDemo}
            />
          }
        />

        <Route
          path="/onboarding"
          element={
            <OnboardingPage
              onComplete={() => {
                setOnboardingRequired(false);
                navigate("/");
              }}
            />
          }
        />

        <Route
          path="/game"
          element={
            <MainGameView
              game={game}
              displayedGame={displayedGame}
              mappedBotGame={mappedBotGame}
              selectedSquare={selectedSquare}
              legalMoves={legalMoves}
              animationPlan={animationPlan}
              promotionPending={promotionPending}
              busy={busy}
              gameMode={gameMode}
              boardInteractionDisabled={boardInteractionDisabled}
              canResign={canResign}
              notation={notation}
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
              whiteClockMs={displayedWhiteTimeMs}
              blackClockMs={displayedBlackTimeMs}
              clockRunning={displayedClockRunning}
              backgroundId={backgroundId}
              onBackgroundChange={setBackgroundId}
              backgrounds={backgrounds}
              spriteCatalog={spriteCatalog}
              session={session}
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
              onBackToMenu={handleBackToMenu}
              onOpenHeatmap={() => navigate("/analysis")}
            />
          }
        />

        <Route
          path="/analysis"
          element={<GameAnalysisView gameId={game?.id ?? session?.gameId ?? null} />}
        />

        <Route
          path="/settings"
          element={<ProfilePanel onBack={() => navigate("/")} />}
        />

        <Route
          path="/lichess"
          element={
            <LichessHubPage
              profile={profile}
              onOpenSettings={() => navigate("/settings")}
              onOpenLichessGame={(gameId) => navigate(`/lichess/games/${gameId}`)}
              onBack={() => navigate("/")}
            />
          }
        />

        <Route
          path="/lichess/games/:gameId"
          element={<LichessGamePage onBack={() => navigate("/lichess")} />}
        />
      </Routes>
    </div>
  );
}
