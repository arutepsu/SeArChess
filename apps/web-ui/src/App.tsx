import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { PieceCode, PlayerColor } from "./api/types";
import { connectWebSocket } from "./api/ws";
import type { WsClient } from "./api/ws";
import { useSession } from "./session/SessionProvider";
import type { SpriteCatalog } from "./assets/spriteCatalog";
import { loadSpriteCatalog } from "./assets/spriteCatalog";
import { apiBaseUrl } from "./api/client";
import { useGameState } from "./game/useGameState";
import ChessBoard from "./components/ChessBoard.tsx";
import ControlPanel from "./components/ControlPanel.tsx";
import MoveList from "./components/MoveList.tsx";
import "./App.css";

type ConnectionState = "connected" | "offline" | "loading";

const baseClockMs = 10 * 60 * 1000;

const backgrounds = [
  { id: "river", label: "River", url: "/assets/backgrounds/river.png" },
  { id: "sakura-grove", label: "Grove", url: "/assets/backgrounds/sakuratrees.jpg" },
  { id: "forest", label: "Forest", url: "/assets/backgrounds/new.jpg" }
];

export default function App() {
  const {
    game,
    selectedSquare,
    legalMoves,
    busy,
    animationPlan,
    pgnExport,
    loadGame,
    refreshFromServer,
    handleSelect,
    handleNewGame,
    handleUndo,
    handleRedo,
    handleExport,
    handleAnimationFinished,
    clearPgnExport,
    setMessage,
    setBusy
  } = useGameState();

  const { getSessionId } = useSession();
  const [, setConnection] = useState<ConnectionState>("loading");
  const wsClientRef = useRef<WsClient | null>(null);
  const [whiteClockMs, setWhiteClockMs] = useState(baseClockMs);
  const [blackClockMs, setBlackClockMs] = useState(baseClockMs);
  const lastTickMs = useRef<number | null>(null);
  const [backgroundId, setBackgroundId] = useState(backgrounds[0].id);
  const [spriteCatalog, setSpriteCatalog] = useState<SpriteCatalog | null>(null);

  const clockRunning = useMemo(() => {
    const status = game?.status;
    return status === "active" || status === "check";
  }, [game?.status]);

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

  // WebSocket subscription.
  // refreshFromServer is stable (empty useCallback deps inside the hook),
  // so this effect re-subscribes only when the WS url changes (never in practice).
  useEffect(() => {
    wsClientRef.current?.close();

    const client = connectWebSocket({
      getSessionId,
      onOpen: () => { setConnection("connected"); },
      onClose: () => { /* REST may still work; don't force offline */ },
      onError: () => { /* lightweight warning can be added later */ },
      onMessage: async (event) => {
        try {
          switch (event.eventType) {
            case "MoveApplied":
            case "PromotionPending":
            case "GameFinished":
            case "SessionLifecycleChanged":
            case "AITurnCompleted": {
              await refreshFromServer();
              setBusy(false);
              break;
            }
            case "AITurnRequested": {
              setMessage(`AI is thinking for ${event.currentPlayer}...`);
              setBusy(true);
              break;
            }
            case "AITurnFailed": {
              setMessage(`AI turn failed: ${event.reason}`);
              setBusy(false);
              break;
            }
            case "SessionCreated":
            default:
              break;
          }
        } catch (error) {
          setBusy(false);
          setMessage(
            error instanceof Error
              ? `Failed to refresh game: ${error.message}`
              : "Failed to refresh game."
          );
        }
      }
    });

    wsClientRef.current = client;
    return () => {
      client.close();
      if (wsClientRef.current === client) wsClientRef.current = null;
    };
  }, [refreshFromServer, setBusy, setMessage]);

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
      {pgnExport ? (
        <section className="panel export">
          <div className="export-header">
            <h2>PGN Export</h2>
            <button type="button" onClick={clearPgnExport}>
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
