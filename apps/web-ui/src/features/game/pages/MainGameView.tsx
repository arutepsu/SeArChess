import type { GameState, PlayableGameMode } from "../../../api/types";
import type {
  GameNotationResponse,
  MoveHistoryEntryDto,
  RunAiTurnsResponse,
} from "../../../api/backendTypes";
import type { BoardAnimation } from "../../../animation/animationTypes";
import type { SpriteCatalog } from "../../../assets/spriteCatalog";
import type { SessionContext } from "../../../session/sessionStore";
import type { BotWebSocketData, BotConnectionState } from "../../../hooks/useBotDemoStream";
import type { GameSceneSkin } from "../sceneSkins";
import ChessBoard from "../../../components/chessBoard";
import BoardSceneView from "../components/BoardSceneView";
import ControlPanel from "../components/ControlPanel";
import MoveList from "../components/MoveList";
import CapturedPanel from "../components/CapturedPanel";
import BackgroundPanel from "../components/BackgroundPanel";
import StatusBanner from "../components/StatusBanner";
import BotDemoGameView from "../components/BotDemoGameView";
import type { ConnectionState, LiveConnectionState } from "../../../app/types";
import Button from "../../../components/ui/Button";

interface Background {
  id: string;
  label: string;
  url: string;
}

export interface MainGameViewProps {
  // Core game state
  game: GameState | undefined;
  displayedGame: GameState | null | undefined;
  mappedBotGame: GameState | null;
  selectedSquare: string | undefined;
  legalMoves: string[];
  animationPlan: BoardAnimation | null;
  promotionPending: { from: string; to: string } | null;
  busy: boolean;
  gameMode: PlayableGameMode;
  boardInteractionDisabled: boolean;
  canResign: boolean;
  notation: GameNotationResponse | undefined;

  // Tab state
  activeTab: "local" | "bot";
  setActiveTab: (tab: "local" | "bot") => void;

  // Bot demo state
  botGameData: BotWebSocketData | null;
  hasNewBotMoveNotification: boolean;
  botConnectionState: BotConnectionState;

  // Display-layer connection (pre-computed by App for whichever tab is active)
  displayedConnection: ConnectionState;
  displayedLiveConnection: LiveConnectionState;
  displayedMessage: string | undefined;

  // Timeline / replay
  timelinePly: number;
  setTimelinePly: (ply: number) => void;
  timelineTotalPlies: number;
  timelineLoading: boolean;
  timelineError: string | null;
  replayModeActive: boolean;
  boundedTimelinePly: number;
  currentReplayMove: MoveHistoryEntryDto | null;

  // Clocks
  whiteClockMs: number;
  blackClockMs: number;
  clockRunning: boolean;

  // Background / scene / sprites
  backgroundId: string;
  onBackgroundChange: (id: string) => void;
  backgrounds: Background[];
  gameScenes: GameSceneSkin[];
  gameSceneId: string;
  onGameSceneChange: (id: string) => void;
  gameScene: GameSceneSkin | null;
  spriteCatalog: SpriteCatalog | null;

  // Session
  session: SessionContext | null;

  // Handlers
  onSelect: (square: string) => void;
  onAnimationFinished: (id: number) => void;
  onResolvePromotion: (piece: any) => void;
  onCancelPromotion: () => void;
  onNewGame: (overrideMode?: PlayableGameMode) => void;
  onImportNotation: (format: "FEN" | "PGN", notation: string) => void;
  onExportNotation: (format: "FEN" | "PGN") => Promise<string>;
  onGameModeChange: (mode: PlayableGameMode) => void;
  onSaveSession: () => Promise<void>;
  onResign: () => void;
  onRunAiTurns: (maxPlies: number) => Promise<RunAiTurnsResponse>;
  onBackToMenu: () => void;
  onOpenHeatmap: () => void;
}

export default function MainGameView({
  game,
  displayedGame,
  mappedBotGame,
  selectedSquare,
  legalMoves,
  animationPlan,
  promotionPending,
  busy,
  gameMode,
  boardInteractionDisabled,
  canResign,
  notation,
  activeTab,
  setActiveTab,
  botGameData,
  hasNewBotMoveNotification,
  displayedConnection,
  displayedLiveConnection,
  displayedMessage,
  timelinePly,
  setTimelinePly,
  timelineTotalPlies,
  timelineLoading,
  timelineError,
  replayModeActive,
  boundedTimelinePly,
  currentReplayMove,
  whiteClockMs,
  blackClockMs,
  clockRunning,
  backgroundId,
  onBackgroundChange,
  backgrounds,
  gameScenes,
  gameSceneId,
  onGameSceneChange,
  gameScene,
  spriteCatalog,
  session,
  onSelect,
  onAnimationFinished,
  onResolvePromotion,
  onCancelPromotion,
  onNewGame,
  onImportNotation,
  onExportNotation,
  onGameModeChange,
  onSaveSession,
  onResign,
  onRunAiTurns,
  onBackToMenu,
  onOpenHeatmap,
}: MainGameViewProps) {
  // Shared props for both classic ChessBoard and BoardSceneView (which wraps ChessBoard).
  const chessBoardProps = displayedGame
    ? {
        board: displayedGame.board,
        selectedSquare: replayModeActive ? undefined : selectedSquare,
        legalMoves: replayModeActive ? [] : legalMoves,
        animation: replayModeActive ? null : animationPlan,
        idleAnimation: true as const,
        disabled: boardInteractionDisabled || replayModeActive,
        onSelect,
        onAnimationFinished,
        inCheck: displayedGame.status === "check",
        activeColor: displayedGame.activeColor,
        gameStatus: displayedGame.status,
        drawReason: displayedGame.drawReason,
        winner: displayedGame.winner,
        promotionPending,
        onResolvePromotion,
        onCancelPromotion,
        onNewGame,
        orientation: "white" as const,
      }
    : null;

  return (
    <main className="layout">
      <aside className="side left-side">
        <BackgroundPanel
          backgrounds={backgrounds}
          backgroundId={backgroundId}
          onChange={onBackgroundChange}
          gameScenes={gameScenes}
          gameSceneId={gameSceneId}
          onGameSceneChange={onGameSceneChange}
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
              {hasNewBotMoveNotification && <span className="notification-dot" />}
            </button>
          </nav>

          <StatusBanner
            game={displayedGame ?? undefined}
            connection={displayedConnection}
            liveConnection={displayedLiveConnection}
            message={displayedMessage}
          />

          {activeTab === "bot" ? (
            <BotDemoGameView
              mappedBotGame={mappedBotGame}
              orientation={botGameData?.botColor ?? "white"}
            />
          ) : chessBoardProps ? (
            gameScene ? (
              <BoardSceneView gameScene={gameScene} {...chessBoardProps} />
            ) : (
              <ChessBoard {...chessBoardProps} />
            )
          ) : null}

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
                value={boundedTimelinePly}
                onChange={(event) =>
                  setTimelinePly(Number(event.currentTarget.value))
                }
                disabled={timelineLoading || timelineTotalPlies <= 0}
              />

              <div className="replay-timeline-meta">
                <span>
                  {currentReplayMove
                    ? `${currentReplayMove.from} -> ${currentReplayMove.to}${
                        currentReplayMove.promotion
                          ? ` (${currentReplayMove.promotion})`
                          : ""
                      }`
                    : "Initial position"}
                </span>
                {replayModeActive ? (
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => setTimelinePly(timelineTotalPlies)}
                    disabled={timelineLoading}
                  >
                    Back To Live
                  </Button>
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
          whiteTimeMs={whiteClockMs}
          blackTimeMs={blackClockMs}
          activeColor={displayedGame?.activeColor ?? game?.activeColor}
          clockRunning={clockRunning}
          gameMode={gameMode}
          canResign={canResign}
          sessionId={session?.sessionId}
          gameId={
            activeTab === "bot"
              ? (displayedGame?.id ?? undefined)
              : (game?.id ?? session?.gameId)
          }
          fen={activeTab === "bot" ? undefined : notation?.fen}
          pgn={activeTab === "bot" ? undefined : notation?.pgn}
          onImportNotation={onImportNotation}
          onExportNotation={onExportNotation}
          onGameModeChange={onGameModeChange}
          onNewGame={onNewGame}
          onSaveSession={onSaveSession}
          onResign={onResign}
          onRunAiTurns={onRunAiTurns}
          onBackToMenu={onBackToMenu}
          onOpenHeatmap={onOpenHeatmap}
        />
      </aside>
    </main>
  );
}
