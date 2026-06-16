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
  hasNewBotMoveNotification: boolean;
  onOpenGameMenu: () => void;
  onOpenMoveLog: () => void;
}

export default function GameHud({
  whiteTimeMs,
  blackTimeMs,
  clockRunning,
  activeColor,
  hasNewBotMoveNotification,
  onOpenGameMenu,
  onOpenMoveLog,
}: GameHudProps) {
  const whiteActive = activeColor === "white" && clockRunning;
  const blackActive = activeColor === "black" && clockRunning;

  return (
    <div className="game-hud" aria-label="Game Controls">
      <div className="game-hud__left">
        <UserMenu dropdownAlign="left" />
        <img className="game-hud__logo" src="/assets/logo/logo64.png" alt="Searchess" />
      </div>

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
          {hasNewBotMoveNotification && (
            <span className="notification-dot" aria-hidden="true" />
          )}
        </Button>
      </div>
    </div>
  );
}
