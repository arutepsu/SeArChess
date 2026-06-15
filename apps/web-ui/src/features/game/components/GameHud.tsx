import type { PlayerColor } from "../../../api/types";
import { formatClockMs } from "../../../utils/timeFormat";
import Button from "../../../components/ui/Button";
import UserMenu from "../../auth/components/UserMenu";
import "./GameHud.css";

interface GameHudProps {
  whiteTimeMs: number;
  blackTimeMs: number;
  clockRunning: boolean;
  activeColor?: PlayerColor;
  activeTab: "local" | "bot";
  setActiveTab: (tab: "local" | "bot") => void;
  hasNewBotMoveNotification: boolean;
  onBackToMenu: () => void;
  onDemo?: () => void;
  onOpenGameMenu: () => void;
  onOpenMoveLog: () => void;
}

export default function GameHud({
  whiteTimeMs,
  blackTimeMs,
  clockRunning,
  activeColor,
  activeTab,
  setActiveTab,
  hasNewBotMoveNotification,
  onBackToMenu,
  onDemo,
  onOpenGameMenu,
  onOpenMoveLog,
}: GameHudProps) {
  const whiteActive = activeColor === "white" && clockRunning;
  const blackActive = activeColor === "black" && clockRunning;

  return (
    <div className="game-hud" aria-label="Game Controls">
      <nav className="game-hud__tabs" aria-label="Game Mode Tabs">
        <button
          type="button"
          className={`game-hud__tab${activeTab === "local" ? " is-active" : ""}`}
          onClick={() => setActiveTab("local")}
        >
          🎮 Lokal Spielen
        </button>
        <button
          type="button"
          className={`game-hud__tab${activeTab === "bot" ? " is-active" : ""}`}
          onClick={() => setActiveTab("bot")}
        >
          🤖 Bot-Live-Monitor
          {hasNewBotMoveNotification && (
            <span className="notification-dot" aria-hidden="true" />
          )}
        </button>
      </nav>

      <div className="game-hud__clocks">
        <div className={`game-hud__clock${whiteActive ? " is-active" : ""}`}>
          <span className="game-hud__clock-label">White</span>
          <span className="game-hud__clock-time">{formatClockMs(whiteTimeMs)}</span>
        </div>
        <div className={`game-hud__clock${blackActive ? " is-active" : ""}`}>
          <span className="game-hud__clock-label">Black</span>
          <span className="game-hud__clock-time">{formatClockMs(blackTimeMs)}</span>
        </div>
      </div>

      <div className="game-hud__actions">
        <Button variant="ghost" onClick={onOpenMoveLog} aria-label="Open move log">
          Moves
        </Button>
        <Button variant="ghost" onClick={onOpenGameMenu} aria-label="Open game menu">
          ☰ Menu
        </Button>
        <UserMenu onBackToMenu={onBackToMenu} onDemo={onDemo} dropdownAlign="right" />
      </div>
    </div>
  );
}
