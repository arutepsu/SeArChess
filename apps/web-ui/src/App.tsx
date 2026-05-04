import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Routes, Route, useNavigate } from "react-router-dom";
import type { PlayerColor, PlayableGameMode } from "./api/types";
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
//import StatusBanner from "./components/StatusBanner.tsx";
import Homepage from "./components/Homepage.tsx";
import BackgroundEffectsLayer from "./components/BackgroundEffectsLayer.tsx";
import BackgroundPanel from "./components/BackgroundPanel.tsx";
import CapturedPanel from "./components/CapturedPanel.tsx";
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

  const lastTickMs = useRef<number | null>(null);
  const wsClientRef = useRef<WsClient | null>(null);

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

  const canResign =
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

              <MoveList moves={game?.moves ?? []} />

              <CapturedPanel captured={game?.captured ?? []} spriteCatalog={spriteCatalog} />
            </aside>

            {game ? (
              <ChessBoard
                board={game.board}
                selectedSquare={selectedSquare}
                legalMoves={legalMoves}
                animation={animationPlan}
                idleAnimation={true}
                disabled={boardInteractionDisabled}
                onSelect={handleSelect}
                onAnimationFinished={handleAnimationFinished}
              />
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
