import { useState } from "react";
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
import type { GameBackground } from "../backgroundSkins";
import ChessBoard from "../../../components/chessBoard";
import BoardSceneView from "../components/BoardSceneView";
import GameMenuDrawer from "../components/GameMenuDrawer";
import GameHud from "../components/GameHud";
import MoveLogPanel from "../components/MoveLogPanel";
import StatusBanner from "../components/StatusBanner";
import BotDemoGameView from "../components/BotDemoGameView";
import type { ConnectionState, LiveConnectionState } from "../../../app/types";
import Button from "../../../components/ui/Button";

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

  // Scene / sprites
  gameScenes: GameSceneSkin[];
  gameSceneId: string;
  onGameSceneChange: (id: string) => void;
  gameScene: GameSceneSkin | null;
  spriteCatalog: SpriteCatalog | null;

  // Full-screen background
  backgrounds: GameBackground[];
  backgroundId: string;
  onBackgroundChange: (id: string) => void;

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
  // NOTE: onExportNotation, onGameModeChange, onNewGame remain in the interface
  // for the App.tsx → AppRoutes prop chain but are not used inside this component.
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
  canResign: _canResign,
  notation,
  activeTab,
  setActiveTab,
  botGameData,
  hasNewBotMoveNotification,
  botConnectionState: _botConnectionState,
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
  gameScenes,
  gameSceneId,
  onGameSceneChange,
  gameScene,
  spriteCatalog,
  backgrounds,
  backgroundId,
  onBackgroundChange,
  session,
  onSelect,
  onAnimationFinished,
  onResolvePromotion,
  onCancelPromotion,
  onNewGame,
  onImportNotation,
  onSaveSession,
  onResign: _onResign,
  onRunAiTurns,
  onBackToMenu,
  onOpenHeatmap,
}: MainGameViewProps) {
  const [isMenuOpen, setIsMenuOpen] = useState(false);
  const [isMoveLogOpen, setIsMoveLogOpen] = useState(false);
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

  const isSceneMode = !!(gameScene && chessBoardProps && activeTab !== "bot");

  // Shared HUD props to avoid repetition between scene and classic branches.
  const hudProps = {
    whiteTimeMs: whiteClockMs,
    blackTimeMs: blackClockMs,
    clockRunning,
    activeColor: displayedGame?.activeColor ?? game?.activeColor,
    activeTab,
    setActiveTab,
    hasNewBotMoveNotification,
    onOpenGameMenu: () => setIsMenuOpen(true),
    onOpenMoveLog: () => setIsMoveLogOpen(true),
  };

  const statusProps = {
    game: displayedGame ?? undefined,
    connection: displayedConnection,
    liveConnection: displayedLiveConnection,
    message: displayedMessage,
  };

  // Replay timeline JSX — rendered in both scene and classic modes.
  const replayTimeline = (overlay: boolean) => (
    <section
      className={overlay ? "replay-timeline replay-timeline--overlay" : "replay-timeline panel"}
      aria-label="Time-travel timeline"
    >
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
        onChange={(event) => setTimelinePly(Number(event.currentTarget.value))}
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
  );

  return (
    <main className={isSceneMode ? "layout layout--scene" : "layout"}>
      <GameMenuDrawer
        isOpen={isMenuOpen}
        onClose={() => setIsMenuOpen(false)}
        gameScenes={gameScenes}
        gameSceneId={gameSceneId}
        onGameSceneChange={onGameSceneChange}
        backgrounds={backgrounds}
        backgroundId={backgroundId}
        onBackgroundChange={onBackgroundChange}
        fen={activeTab === "bot" ? undefined : notation?.fen}
        pgn={activeTab === "bot" ? undefined : notation?.pgn}
        sessionId={session?.sessionId}
        gameId={
          activeTab === "bot"
            ? (displayedGame?.id ?? undefined)
            : (game?.id ?? session?.gameId)
        }
        game={activeTab === "bot" ? (displayedGame ?? undefined) : game}
        busy={busy}
        gameMode={gameMode}
        onImportNotation={onImportNotation}
        onSaveSession={onSaveSession}
        onRunAiTurns={onRunAiTurns}
        onOpenHeatmap={onOpenHeatmap}
        onBackToMenu={onBackToMenu}
        onNewGame={() => onNewGame()}
      />

      <MoveLogPanel
        isOpen={isMoveLogOpen}
        onClose={() => setIsMoveLogOpen(false)}
        moves={displayedGame?.moves ?? []}
        captured={displayedGame?.captured ?? []}
        spriteCatalog={spriteCatalog}
      />

      {displayedGame || activeTab === "bot" ? (
        <section className="board-column">
          {gameScene && chessBoardProps && activeTab !== "bot" ? (
            // ── Scene mode: BoardSceneView is the full visual stage; ───────
            // ── HUD / status / timeline are overlaid on top of it.    ───────
            <div className="game-stage">
              {/* Inner content area — scene image + overlays, clipped and rounded */}
              <div className="game-stage__content">
                {/* Base layer: scene image + transparent chess board */}
                <BoardSceneView gameScene={gameScene} {...chessBoardProps} />

                {/* Overlay: HUD at the top of the scene */}
                <div className="game-stage__hud-overlay">
                  <GameHud {...hudProps} />
                </div>

                {/* Overlay: status banner below HUD */}
                <div className="game-stage__status-overlay">
                  <StatusBanner {...statusProps} />
                </div>

                {/* Overlay: compact timeline at the bottom of the scene */}
                <div className="game-stage__timeline-overlay">
                  {replayTimeline(true)}
                </div>
              </div>
            </div>
          ) : (
            // ── Classic mode: stacked vertical layout ────────────────────
            <>
              <GameHud {...hudProps} />

              <StatusBanner {...statusProps} />

              {activeTab === "bot" ? (
                <BotDemoGameView
                  mappedBotGame={mappedBotGame}
                  orientation={botGameData?.botColor ?? "white"}
                />
              ) : chessBoardProps ? (
                <ChessBoard {...chessBoardProps} />
              ) : null}

              {activeTab !== "bot" && replayTimeline(false)}
            </>
          )}
        </section>
      ) : (
        <section className="board-shell placeholder">
          <div className="loading">Waiting for game data...</div>
        </section>
      )}

    </main>
  );
}
