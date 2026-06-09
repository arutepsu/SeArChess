import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Routes, Route, useNavigate } from "react-router-dom";
import type { PlayableGameMode, GameState } from "./api/types";
import type { MoveHistoryEntryDto } from "./api/backendTypes";
import { getReplayFrame } from "./api/client";
import { getMyProfile } from "./api/userServiceClient";
import type { UserProfileResponse } from "./api/userServiceTypes";
import { mapGameSnapshotToGameState } from "./api/mapper";
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
import StatusBanner from "./components/StatusBanner.tsx";
import Homepage from "./components/Homepage.tsx";
import OnboardingPage from "./components/OnboardingPage.tsx";
import BackgroundEffectsLayer from "./components/BackgroundEffectsLayer.tsx";
import BackgroundPanel from "./components/BackgroundPanel.tsx";
import CapturedPanel from "./components/CapturedPanel.tsx";
import AuthBar from "./components/AuthBar.tsx";
import ProfilePanel from "./components/ProfilePanel.tsx";
import LichessHubPage from "./components/LichessHubPage.tsx";
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
  const [onboardingRequired, setOnboardingRequired] = useState(false);
  const [profile, setProfile] = useState<UserProfileResponse | null>(null);

  useEffect(() => {
    getMyProfile()
      .then((p) => {
        setProfile(p);
        setOnboardingRequired(p.onboardingRequired);
        if (p.onboardingRequired) navigate("/onboarding");
      })
      .catch(() => { /* ignore — don't block the app if user-service is unreachable */ });
  }, [navigate]);

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

  const boardInteractionDisabled = busy || sessionClosed || !clockRunning;
  const boundedTimelinePly = Math.min(timelinePly, timelineTotalPlies);
  const replayModeActive = boundedTimelinePly < timelineTotalPlies;

  const displayedGame = replayModeActive && replayGame ? replayGame : game;

  const currentReplayMove =
    boundedTimelinePly <= 0 ? null : timelineRawMoves[boundedTimelinePly - 1] ?? null;

  const canResign =
    Boolean(game) &&
    !busy &&
    !sessionClosed &&
    clockRunning &&
    activeController !== "AI" &&
    activeController !== "DeployedBot";

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

  return (
    <div className="app">
      <BackgroundEffectsLayer backgroundId={backgroundId} />
      <AuthBar />

      <Routes>
        <Route path="/" element={
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
          />
        } />
        <Route path="/onboarding" element={
          <OnboardingPage onComplete={() => {
            setOnboardingRequired(false);
            navigate("/");
          }} />
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

            {displayedGame ? (
              <section className="board-column">
                <StatusBanner
                  game={displayedGame}
                  connection={connection}
                  liveConnection={liveConnection}
                  message={message}
                />

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
                  orientation="white"
                />

                <section className="replay-timeline panel" aria-label="Time-travel timeline">
                  <header className="replay-timeline-header">
                    <h2>Time-Travel</h2>
                    <p>
                      Frame {boundedTimelinePly} / {timelineTotalPlies}
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
                    value={boundedTimelinePly}
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
              </section>
            ) : (
              <section className="board-shell placeholder">
                <div className="loading">Waiting for game data...</div>
              </section>
            )}

            <aside className="side right-side">
              <ControlPanel
                game={game}
                busy={busy}
                whiteTimeMs={whiteClockMs}
                blackTimeMs={blackClockMs}
                activeColor={game?.activeColor}
                clockRunning={clockRunning}
                gameMode={gameMode}
                canResign={canResign}
                sessionId={session?.sessionId}
                gameId={game?.id ?? session?.gameId}
                fen={notation?.fen}
                pgn={notation?.pgn}
                onImportNotation={handleImportNotation}
                onExportNotation={handleExportNotation}
                onGameModeChange={setGameMode}
                onNewGame={handleNewGame}
                onSaveSession={handleSaveSession}
                onResign={handleResign}
                onRunAiTurns={handleRunAiTurns}
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
              onBack={() => navigate("/")}
            />
          }
        />
      </Routes>
    </div>
  );
}
