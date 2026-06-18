import { Routes, Route } from "react-router-dom";
import type { GameState, PlayableGameMode } from "../api/types";
import type { GameNotationResponse, MoveHistoryEntryDto, RunAiTurnsResponse } from "../api/backendTypes";
import type { BoardAnimation } from "../animation/animationTypes";
import type { SpriteCatalog } from "../assets/spriteCatalog";
import type { GameSceneSkin } from "../features/game/sceneSkins";
import type { GameBackground } from "../features/game/backgroundSkins";
import type { SessionContext } from "../session/sessionStore";
import type { BotWebSocketData, BotConnectionState } from "../hooks/useBotDemoStream";
import type { UserProfileResponse } from "../api/userServiceTypes";
import type { ConnectionState, LiveConnectionState } from "./types";
import { MainGameView } from "../features/game";
import { Homepage } from "../features/home";
import { OnboardingPage } from "../features/onboarding";
import { ProfilePanel } from "../features/settings";
import { LichessHubPage, LichessGamePage } from "../features/lichess";
import { AnalyticsPage, GameAnalysisView } from "../features/analytics";
import { TournamentBuilderPage, TournamentJobsPage, TournamentRunningPage } from "../features/tournaments";
import { PieceTypesPage } from "../features/pieceTypes";

interface AppRoutesProps {
  // Game state
  game: GameState | undefined;
  displayedGame: GameState | null | undefined;
  mappedBotGame: GameState | null;
  selectedSquare: string | undefined;
  legalMoves: string[];
  animationPlan: BoardAnimation | null;
  promotionPending: { from: string; to: string } | null;
  busy: boolean;
  gameMode: PlayableGameMode;
  notation: GameNotationResponse | undefined;
  boardInteractionDisabled: boolean;
  canResign: boolean;

  // Session
  session: SessionContext | null;

  // Profile / onboarding
  profile: UserProfileResponse | null;
  onboardingRequired: boolean;

  // Tabs
  activeTab: "local" | "bot";
  setActiveTab: (tab: "local" | "bot") => void;

  // Bot demo
  botGameData: BotWebSocketData | null;
  hasNewBotMoveNotification: boolean;
  botConnectionState: BotConnectionState;

  // Connection display
  displayedConnection: ConnectionState;
  displayedLiveConnection: LiveConnectionState;
  displayedMessage: string | undefined;

  // Timeline
  timelinePly: number;
  setTimelinePly: (ply: number) => void;
  timelineTotalPlies: number;
  timelineLoading: boolean;
  timelineError: string | null;
  replayModeActive: boolean;
  boundedTimelinePly: number;
  currentReplayMove: MoveHistoryEntryDto | null;

  // Clocks
  displayedWhiteTimeMs: number;
  displayedBlackTimeMs: number;
  displayedClockRunning: boolean;

  // Scene / sprites
  gameSceneId: string;
  setGameSceneId: (id: string) => void;
  gameScenes: GameSceneSkin[];
  gameScene: GameSceneSkin | null;
  spriteCatalog: SpriteCatalog | null;

  // Full-screen background
  backgroundId: string;
  setBackgroundId: (id: string) => void;
  backgrounds: GameBackground[];

  // Navigation handlers (all defined in App.tsx)
  onContinueActiveGame: () => void;
  onStartGame: (mode: PlayableGameMode) => Promise<void>;
  onResumeSession: (sessionId: string) => Promise<void>;
  onOpenSettings: () => void;
  onOpenOnboarding: () => void;
  onOpenLichessHub: () => void;
  onOpenBotDemo: () => void;
  onCompleteOnboarding: () => void;
  onOpenHeatmap: () => void;
  onBackToMenu: () => void;
  onOpenLichessGame: (gameId: string) => void;
  onBackToLichess: () => void;

  // Game action handlers
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
}

export default function AppRoutes({
  game,
  displayedGame,
  mappedBotGame,
  selectedSquare,
  legalMoves,
  animationPlan,
  promotionPending,
  busy,
  gameMode,
  notation,
  boardInteractionDisabled,
  canResign,
  session,
  profile,
  onboardingRequired,
  activeTab,
  setActiveTab,
  botGameData,
  hasNewBotMoveNotification,
  botConnectionState,
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
  displayedWhiteTimeMs,
  displayedBlackTimeMs,
  displayedClockRunning,
  gameSceneId,
  setGameSceneId,
  gameScenes,
  gameScene,
  spriteCatalog,
  backgroundId,
  setBackgroundId,
  backgrounds,
  onContinueActiveGame,
  onStartGame,
  onResumeSession,
  onOpenSettings,
  onOpenOnboarding,
  onOpenLichessHub,
  onOpenBotDemo,
  onCompleteOnboarding,
  onOpenHeatmap,
  onBackToMenu,
  onOpenLichessGame,
  onBackToLichess,
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
}: AppRoutesProps) {
  return (
    <Routes>
      <Route
        path="/"
        element={
          <Homepage
            hasActiveGame={Boolean(game)}
            busy={busy}
            onboardingRequired={onboardingRequired}
            profile={profile}
            backgrounds={backgrounds}
            backgroundId={backgroundId}
            setBackgroundId={setBackgroundId}
            gameScenes={gameScenes}
            gameSceneId={gameSceneId}
            setGameSceneId={setGameSceneId}
            onStart={onStartGame}
            onContinueActiveGame={onContinueActiveGame}
            onResumeSession={onResumeSession}
            onOpenSettings={onOpenSettings}
            onOpenOnboarding={onOpenOnboarding}
            onOpenLichessHub={onOpenLichessHub}
            onOpenBotDemo={onOpenBotDemo}
          />
        }
      />

      <Route
        path="/onboarding"
        element={<OnboardingPage onComplete={onCompleteOnboarding} />}
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
            gameScenes={gameScenes}
            gameSceneId={gameSceneId}
            onGameSceneChange={setGameSceneId}
            gameScene={gameScene}
            spriteCatalog={spriteCatalog}
            backgrounds={backgrounds}
            backgroundId={backgroundId}
            onBackgroundChange={setBackgroundId}
            session={session}
            onSelect={onSelect}
            onAnimationFinished={onAnimationFinished}
            onResolvePromotion={onResolvePromotion}
            onCancelPromotion={onCancelPromotion}
            onNewGame={onNewGame}
            onImportNotation={onImportNotation}
            onExportNotation={onExportNotation}
            onGameModeChange={onGameModeChange}
            onSaveSession={onSaveSession}
            onResign={onResign}
            onRunAiTurns={onRunAiTurns}
            onBackToMenu={onBackToMenu}
            onOpenHeatmap={onOpenHeatmap}
          />
        }
      />

      <Route
        path="/analysis"
        element={<GameAnalysisView gameId={game?.id ?? session?.gameId ?? null} />}
      />

      <Route
        path="/settings"
        element={<ProfilePanel onBack={onBackToMenu} />}
      />

      <Route
        path="/lichess"
        element={
          <LichessHubPage
            profile={profile}
            onOpenSettings={onOpenSettings}
            onOpenLichessGame={onOpenLichessGame}
            onBack={onBackToMenu}
          />
        }
      />

      <Route
        path="/lichess/games/:gameId"
        element={<LichessGamePage onBack={onBackToLichess} />}
      />

      <Route path="/pieces" element={<PieceTypesPage />} />
      <Route path="/analytics" element={<AnalyticsPage />} />
      <Route path="/tournaments" element={<TournamentBuilderPage />} />
      <Route path="/tournaments/jobs" element={<TournamentJobsPage />} />
      <Route path="/tournaments/:jobId" element={<TournamentRunningPage />} />
    </Routes>
  );
}
