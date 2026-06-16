import { useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import type { PlayableGameMode } from "../../../api/types";
import type { UserProfileResponse } from "../../../api/userServiceTypes";
import type { GameBackground } from "../../game/backgroundSkins";
import type { GameSceneSkin } from "../../game/sceneSkins";
import UserMenu from "../../auth/components/UserMenu";
import MenuCarousel, { type MenuCarouselItem } from "../components/MenuCarousel";
import ResumeGamePanel from "../components/ResumeGamePanel";
import "./Homepage.css";

const botDemoConfigured = Boolean(import.meta.env.VITE_BOT_WS_URL);

type HomeMenuState =
  | "main"
  | "botTournament"
  | "extras"
  | "options"
  | "savedSessions"
  | "backgroundOptions"
  | "boardSceneOptions";

interface HomepageProps {
  hasActiveGame: boolean;
  busy: boolean;
  onboardingRequired: boolean;
  profile: UserProfileResponse | null;
  backgrounds: GameBackground[];
  backgroundId: string;
  setBackgroundId: (id: string) => void;
  gameScenes: GameSceneSkin[];
  gameSceneId: string;
  setGameSceneId: (id: string) => void;
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
  backgrounds,
  backgroundId,
  setBackgroundId,
  gameScenes,
  gameSceneId,
  setGameSceneId,
  onStart,
  onContinueActiveGame,
  onResumeSession,
  onOpenSettings,
  onOpenOnboarding,
  onOpenLichessHub,
  onOpenBotDemo,
}: HomepageProps) {
  const navigate = useNavigate();
  const [menuState, setMenuState] = useState<HomeMenuState>("main");
  const hasVerifiedLichessLink =
    profile?.links?.some((l) => l.provider === "Lichess" && l.verified) ?? false;
  const lichessLockedReason = !hasVerifiedLichessLink
    ? "Link your Lichess account in Profile first."
    : undefined;
  const botDemoLockedReason = !botDemoConfigured
    ? "Set VITE_BOT_WS_URL to enable the live WebSocket demo in this environment."
    : undefined;

  const boardSceneItems = useMemo(() => {
    return [
      ...gameScenes.map<MenuCarouselItem>((scene) => ({
        id: scene.id,
        label: scene.label,
        previewImageUrl: scene.imageUrl,
        badge: gameSceneId === scene.id ? "ACTIVE" : undefined,
        onSelect: () => setGameSceneId(scene.id),
      })),
      {
        id: "classic",
        label: "Classic",
        previewClassName: "menu-carousel__preview--classic",
        badge: gameSceneId === "" ? "ACTIVE" : undefined,
        onSelect: () => setGameSceneId(""),
      },
      {
        id: "back",
        label: "← Back",
        onSelect: () => setMenuState("options"),
      },
    ];
  }, [gameSceneId, gameScenes, setGameSceneId]);

  const menuConfig = useMemo<{
    title?: string;
    subtitle?: string;
    initialSelectedId?: string;
    items: MenuCarouselItem[];
    onBack?: () => void;
  }>(() => {
    switch (menuState) {
      case "botTournament":
        return {
          title: "Bot Tournament",
          subtitle: "Run bot leagues and inspect the results.",
          onBack: () => setMenuState("main"),
          items: [
            {
              id: "start-bot-tournament",
              label: "Start Bot Tournament",
              description: "Open the tournament builder.",
              onSelect: () => navigate("/tournaments"),
            },
            {
              id: "analytics",
              label: "Analytics",
              description: "Review completed Spark analytics runs.",
              onSelect: () => navigate("/analytics"),
            },
            {
              id: "back",
              label: "← Back",
              onSelect: () => setMenuState("main"),
            },
          ],
        };

      case "extras":
        return {
          title: "Extras",
          subtitle: "Streams, Lichess, and saved games.",
          onBack: () => setMenuState("main"),
          items: [
            {
              id: "reactive-streams-demo",
              label: "Reactive Streams Demo",
              description: "Watch the live bot stream in the game scene.",
              disabled: !botDemoConfigured,
              disabledReason: botDemoLockedReason,
              onSelect: onOpenBotDemo,
            },
            {
              id: "lichess-hub",
              label: "Lichess Hub",
              description: "Open linked Lichess bot tools.",
              disabled: !hasVerifiedLichessLink,
              disabledReason: lichessLockedReason,
              onSelect: onOpenLichessHub,
            },
            {
              id: "saved-sessions",
              label: "Saved Sessions",
              description: "Resume a stored local session.",
              onSelect: () => setMenuState("savedSessions"),
            },
            {
              id: "back",
              label: "← Back",
              onSelect: () => setMenuState("main"),
            },
          ],
        };

      case "options":
        return {
          title: "Options",
          subtitle: "Change global display preferences.",
          onBack: () => setMenuState("main"),
          items: [
            {
              id: "background",
              label: "Background",
              description: "Change the full-screen menu and game backdrop.",
              onSelect: () => setMenuState("backgroundOptions"),
            },
            {
              id: "board-scene",
              label: "Board Scene",
              description: "Choose the game board scene preference.",
              onSelect: () => setMenuState("boardSceneOptions"),
            },
            {
              id: "profile",
              label: "Profile",
              description: "Open account and profile settings.",
              onSelect: onOpenSettings,
            },
            {
              id: "back",
              label: "← Back",
              onSelect: () => setMenuState("main"),
            },
          ],
        };

      case "savedSessions":
        return {
          title: "Saved Sessions",
          subtitle: "Resume a stored local session.",
          onBack: () => setMenuState("extras"),
          items: [
            {
              id: "back",
              label: "← Back",
              onSelect: () => setMenuState("extras"),
            },
          ],
        };

      case "backgroundOptions":
        return {
          title: "Background",
          subtitle: "Select the global full-screen background.",
          onBack: () => setMenuState("options"),
          items: [
            ...backgrounds.map<MenuCarouselItem>((background) => ({
              id: background.id,
              label: background.label,
              previewImageUrl: background.imageUrl,
              badge: backgroundId === background.id ? "ACTIVE" : undefined,
              onSelect: () => setBackgroundId(background.id),
            })),
            {
              id: "back",
              label: "← Back",
              onSelect: () => setMenuState("options"),
            },
          ],
        };

      case "boardSceneOptions":
        return {
          title: "Board Scene",
          subtitle: "Select the global board preference.",
          onBack: () => setMenuState("options"),
          items: boardSceneItems,
        };

      case "main":
      default:
        return {
          initialSelectedId: "human-vs-bot",
          items: [
            ...(hasActiveGame
              ? [
                  {
                    id: "continue-active-game",
                    label: "Continue",
                    description: "Return to the game currently in progress.",
                    onSelect: onContinueActiveGame,
                  },
                ]
              : []),
            {
              id: "human-vs-human",
              label: "Human vs Human",
              description: "Start a classic local chess match.",
              onSelect: () => onStart("HumanVsHuman"),
            },
            {
              id: "human-vs-bot",
              label: "Human vs Bot",
              description: "Challenge the self-trained Searchess AI bot.",
              onSelect: () => onStart("HumanVsAI"),
            },
            {
              id: "bot-vs-bot",
              label: "Bot vs Bot",
              description: "Watch the Searchess AI play both sides.",
              onSelect: () => onStart("AIVsAI"),
            },
            {
              id: "tournament",
              label: "Tournament",
              badge: "COMING NEXT",
              disabled: true,
              disabledReason: "Coming next.",
            },
            {
              id: "bot-tournament",
              label: "Bot Tournament",
              description: "Run bot leagues and inspect results.",
              onSelect: () => setMenuState("botTournament"),
            },
            {
              id: "extras",
              label: "Extras",
              description: "Streams, Lichess, and saved sessions.",
              onSelect: () => setMenuState("extras"),
            },
            {
              id: "options",
              label: "Options",
              description: "Background, board scene, and profile settings.",
              onSelect: () => setMenuState("options"),
            },
          ],
        };
    }
  }, [
    backgroundId,
    backgrounds,
    boardSceneItems,
    botDemoLockedReason,
    hasActiveGame,
    hasVerifiedLichessLink,
    lichessLockedReason,
    menuState,
    navigate,
    onContinueActiveGame,
    onOpenBotDemo,
    onOpenLichessHub,
    onOpenSettings,
    onStart,
    setBackgroundId,
  ]);

  return (
    <div className="main-menu-page">
      <div className="main-menu-shell">
        <header className="main-menu-topbar">
          <UserMenu dropdownAlign="right" />
        </header>

        {onboardingRequired && (
          <div className="main-menu-notice">
            <span>Complete your profile before starting a game.</span>
            <button
              type="button"
              className="main-menu-notice__link"
              onClick={onOpenOnboarding}
            >
              Go to onboarding
            </button>
          </div>
        )}

        <main className="main-menu-stage">
          <section className="main-menu-title-block" aria-label="Searchess title">
            <img
              className="homepage-logo"
              src="/assets/logo/logo512.png"
              alt="Searchess"
            />
            <h1>SEARCHESS</h1>
          </section>

          <MenuCarousel
            key={menuState}
            title={menuConfig.title}
            subtitle={menuConfig.subtitle}
            items={menuConfig.items}
            initialSelectedId={menuConfig.initialSelectedId}
            descriptionMode={menuState === "main" ? "selected" : "all"}
            onBack={menuConfig.onBack}
          />

          {menuState === "savedSessions" && (
            <div className="main-menu-resume-panel">
              <ResumeGamePanel busy={busy} onResume={onResumeSession} />
            </div>
          )}
        </main>
      </div>
    </div>
  );
}
