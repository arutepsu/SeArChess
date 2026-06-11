import type { PlayableGameMode } from "../api/types";
import type { UserProfileResponse } from "../api/userServiceTypes";
import GameModeCard from "./GameModeCard.tsx";
import ResumeGamePanel from "./ResumeGamePanel.tsx";
import "./Homepage.css";

const botDemoConfigured = Boolean(import.meta.env.VITE_BOT_WS_URL);

interface HomepageProps {
  hasActiveGame: boolean;
  busy: boolean;
  onboardingRequired: boolean;
  profile: UserProfileResponse | null;
  onStart: (mode: PlayableGameMode) => void;
  onContinueActiveGame: () => void;
  onResumeSession: (sessionId: string) => Promise<void>;
  onOpenSettings: () => void;
  onOpenOnboarding: () => void;
  onOpenLichessHub: () => void;
  onOpenBotDemo: () => void;
}

export default function Homepage({
  hasActiveGame,
  busy,
  onboardingRequired,
  profile,
  onStart,
  onContinueActiveGame,
  onResumeSession,
  onOpenOnboarding,
  onOpenLichessHub,
  onOpenBotDemo,
}: HomepageProps) {
  const hasVerifiedLichessLink =
    profile?.links?.some((l) => l.provider === "Lichess" && l.verified) ?? false;

  return (
    <div className="homepage">
      <div className="homepage-inner">
        <header className="homepage-hero">
          <h1 className="homepage-title">SeArChess</h1>
          <p className="homepage-subtitle">Choose how you want to play.</p>
        </header>

        {onboardingRequired && (
          <div className="homepage-notice">
            Complete your profile before starting a game.{" "}
            <button
              type="button"
              className="homepage-notice-link"
              onClick={onOpenOnboarding}
            >
              Go to onboarding →
            </button>
          </div>
        )}

        <div className="homepage-cards">
          {hasActiveGame && (
            <GameModeCard
              title="Continue Active Game"
              description="Resume your current game in progress."
              buttonLabel="Continue"
              onAction={onContinueActiveGame}
              busy={busy}
            />
          )}

          <GameModeCard
            title="Local Game"
            description="Play a two-player game on this device."
            buttonLabel="Start"
            onAction={() => onStart("HumanVsHuman")}
            busy={busy}
          />

          <GameModeCard
            title="Play vs Searchess AI"
            description="Play White; the Searchess AI replies after each of your moves."
            buttonLabel="Start"
            onAction={() => onStart("HumanVsAI")}
            busy={busy}
          />

          <GameModeCard
            title="AI vs AI Demo"
            description="Watch the Searchess AI play both sides."
            buttonLabel="Start"
            onAction={() => onStart("AIVsAI")}
            busy={busy}
          />

          <GameModeCard
            title="Human vs Deployed Bot"
            description="Play against the deployed Lichess bot inside Searchess."
            buttonLabel="Start"
            onAction={() => onStart("HumanVsDeployedBot")}
            busy={busy}
            disabled={!hasVerifiedLichessLink}
            disabledReason={
              !hasVerifiedLichessLink
                ? "Link your Lichess account in Settings first."
                : undefined
            }
          />

          <GameModeCard
            title="Play on Lichess"
            description="Challenge the Searchess Bot on Lichess from your linked account."
            buttonLabel="Open Lichess Hub"
            onAction={onOpenLichessHub}
            busy={busy}
            disabled={!hasVerifiedLichessLink}
            disabledReason={
              !hasVerifiedLichessLink
                ? "Link your Lichess account in Settings first."
                : undefined
            }
          />

          {botDemoConfigured ? (
            <GameModeCard
              title="Reactive Streams Demo"
              description="Watch the reactive streams bot play in real time via live WebSocket stream."
              buttonLabel="Open Demo"
              onAction={onOpenBotDemo}
              busy={busy}
            />
          ) : (
            <GameModeCard
              title="Reactive Streams Demo"
              description="Live WebSocket demo of the reactive streams bot."
              buttonLabel="Open Demo"
              onAction={onOpenBotDemo}
              info="Not configured in this environment. Set VITE_BOT_WS_URL to enable."
            />
          )}
        </div>

        <ResumeGamePanel busy={busy} onResume={onResumeSession} />
      </div>
    </div>
  );
}
