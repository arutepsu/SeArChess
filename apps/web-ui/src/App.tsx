import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { PieceCode, PlayerColor } from "./api/types";
import type { SpriteCatalog } from "./assets/spriteCatalog";
import { loadSpriteCatalog } from "./assets/spriteCatalog";
import { connectWebSocket, type WsClient } from "./api/ws";
import type { WsEvent } from "./api/wsTypes";
import { useGameState } from "./game/useGameState";
import { useSession } from "./session/SessionProvider";
import ChessBoard from "./components/ChessBoard.tsx";
import ControlPanel from "./components/ControlPanel.tsx";
import MoveList from "./components/MoveList.tsx";
import StatusBanner from "./components/StatusBanner.tsx";
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
    handleImportNotation,
    sessionLifecycle,
    loadGame,
    refreshFromServer,
    handleSelect,
    setGameMode,
    handleNewGame,
    handleResign,
    handleAnimationFinished,
    setMessage,
    setBusy,
  } = useGameState();
  const { session, setSession, getSessionId } = useSession();

  const [connection, setConnection] = useState<ConnectionState>("loading");
  const [liveConnection, setLiveConnection] = useState<LiveConnectionState>("idle");
  const [whiteClockMs, setWhiteClockMs] = useState(baseClockMs);
  const [blackClockMs, setBlackClockMs] = useState(baseClockMs);
  const lastTickMs = useRef<number | null>(null);
  const wsClientRef = useRef<WsClient | null>(null);
  const [backgroundId, setBackgroundId] = useState(backgrounds[0].id);
  const [spriteCatalog, setSpriteCatalog] = useState<SpriteCatalog | null>(null);

  const clockRunning = useMemo(() => {
    const status = game?.status;
    return status === "active" || status === "check";
  }, [game?.status]);
  const sessionClosed = sessionLifecycle === "Finished" || sessionLifecycle === "Cancelled";
  const activeController = game?.activeColor === "white"
    ? session?.whiteController
    : session?.blackController;
  const boardInteractionDisabled = busy || sessionClosed || !clockRunning;
  const canResign = Boolean(game) && !busy && !sessionClosed && clockRunning && activeController !== "AI";

  const resetClocks = useCallback(() => {
    setWhiteClockMs(baseClockMs);
    setBlackClockMs(baseClockMs);
    lastTickMs.current = performance.now();
  }, []);

  // Initial load: hook handles game state; App wraps with connection state.
  useEffect(() => {
    setConnection("loading");
    loadGame()
      .then(() => setConnection("connected"))
      .catch(() => setConnection("offline"));
  }, [loadGame]);

  // Reset clocks whenever a new game begins (new game.id).
  useEffect(() => {
    if (game?.id) {
      resetClocks();
    }
  }, [game?.id, resetClocks]);

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
        if (event.eventType === "SessionLifecycleChanged" && session?.sessionId === event.sessionId) {
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

        // WebSocket messages are scoped live hints, not authoritative game
        // state. Any event that can affect board/turn/status is followed by
        // GET /api/games/{gameId}, then committed through GameSnapshot mapping.
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
  }, [game?.id, getSessionId, refreshFromServer, session, setBusy, setMessage, setSession]);

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
    document.documentElement.style.setProperty("--app-background", `url("${nextUrl}")`);
  }, [backgroundId]);

  useEffect(() => {
    let active = true;
    loadSpriteCatalog()
      .then((catalog) => { if (active) setSpriteCatalog(catalog); })
      .catch(() => { if (active) setSpriteCatalog(null); });
    return () => { active = false; };
  }, []);

  const spriteInfoFor = useCallback(
    (piece: PieceCode): { url: string; frameCount: number } | null => {
      if (!spriteCatalog) return null;
      const color = piece.startsWith("w") ? "white" : "black";
      const letter = piece[1];
      const nameMap: Record<string, string> = {
        K: "king", Q: "queen", R: "rook", B: "bishop", N: "knight", P: "pawn"
      };
      const name = nameMap[letter] ?? "pawn";
      const key = `classic/${color}_${name}_idle`;
      const sheet = spriteCatalog.spriteSheets[key];
      if (!sheet) return null;
      const clipSpec = spriteCatalog.clipSpecs[sheet.clipSpec];
      if (!clipSpec) return null;
      return { url: `/${sheet.path}`, frameCount: clipSpec.frameCount };
    },
    [spriteCatalog]
  );

  const isRainBackground = backgroundId === "river";
  const isSakuraBackground = backgroundId === "sakura-grove";

  return (
    <div className="app">
      {isRainBackground ? (
        <div className="rain-layer" aria-hidden="true">
          <img className="rain-gif" src="/assets/backgrounds/rain.gif" alt="" />
        </div>
      ) : isSakuraBackground ? (
        <div className="sakura-layer" aria-hidden="true">
          <img className="sakura-leaf sakura-1" src="/assets/backgrounds/sakuraleaf1.png" alt="" />
          <img className="sakura-leaf sakura-2" src="/assets/backgrounds/sakuraleaf.png" alt="" />
          <img className="sakura-leaf sakura-3" src="/assets/backgrounds/sakuraleaf1.png" alt="" />
          <img className="sakura-leaf sakura-4" src="/assets/backgrounds/sakuraleaf.png" alt="" />
          <img className="sakura-leaf sakura-5" src="/assets/backgrounds/sakuraleaf.png" alt="" />
        </div>
      ) : (
        <div className="leaf-layer" aria-hidden="true">
          <span className="leaf leaf-1"></span>
          <span className="leaf leaf-2"></span>
          <span className="leaf leaf-3"></span>
          <span className="leaf leaf-4"></span>
          <span className="leaf leaf-5"></span>
          <span className="leaf leaf-6"></span>
        </div>
      )}
      <StatusBanner
        game={game}
        connection={connection}
        liveConnection={liveConnection}
        message={message}
      />
      <main className="layout">
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
        <aside className="side">
          <ControlPanel
            game={game}
            busy={busy}
            whiteTimeMs={whiteClockMs}
            blackTimeMs={blackClockMs}
            activeColor={game?.activeColor}
            clockRunning={clockRunning}
            gameMode={gameMode}
            canResign={canResign}
            fen={notation?.fen}
            pgn={notation?.pgn}
            onImportNotation={handleImportNotation}
            onGameModeChange={setGameMode}
            onNewGame={handleNewGame}
            onResign={handleResign}
          />
          <section className="panel background-panel">
            <header>
              <h2>Background</h2>
              <p>Pick the arena for your next battle.</p>
            </header>
            <div className="background-grid">
              {backgrounds.map((item) => (
                <button
                  key={item.id}
                  type="button"
                  className={`background-option${backgroundId === item.id ? " is-active" : ""}`}
                  onClick={() => setBackgroundId(item.id)}
                >
                  <span style={{ backgroundImage: `url("${item.url}")` }} />
                  <small>{item.label}</small>
                </button>
              ))}
            </div>
          </section>
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
                game.captured.map((piece, index) => {
                  const sprite = spriteInfoFor(piece);
                  const frameCount = sprite?.frameCount ?? 1;
                  const style = sprite
                    ? {
                      backgroundImage: `url(${sprite.url})`,
                      backgroundSize: `${frameCount * 100}% 100%`,
                      backgroundPosition: "0% 50%"
                    }
                    : undefined;
                  return (
                    <span
                      key={`${piece}-${index}`}
                      className={`captured-piece${piece.startsWith("b") ? " is-black" : ""}${sprite ? " has-sprite" : ""}`}
                      style={style}
                      aria-label={piece}
                    >
                      {sprite ? "" : piece}
                    </span>
                  );
                })
              )}
            </div>
          </section>
        </aside>
      </main>
    </div>
  );
}
