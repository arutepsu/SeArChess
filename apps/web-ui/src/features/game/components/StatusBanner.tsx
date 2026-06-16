import type { GameState } from "../../../api/types";
import "./StatusBanner.css";

type StatusBannerProps = {
  game?: GameState;
  connection: "connected" | "offline" | "loading";
  liveConnection?: "idle" | "connecting" | "live" | "disconnected";
  message?: string;
};

export default function StatusBanner({ game, connection, liveConnection = "idle", message }: StatusBannerProps) {
  const getBannerDetails = () => {
    if (connection === "loading") {
      return {
        className: "is-loading",
        icon: "⏳",
        text: "Connecting to game service..."
      };
    }
    if (connection === "offline") {
      return {
        className: "is-offline",
        icon: "⚠️",
        text: "Game service unreachable. Please start Docker Compose."
      };
    }
    if (message) {
      return {
        className: "is-message",
        icon: "💬",
        text: message
      };
    }
    if (!game) {
      return {
        className: "is-idle",
        icon: "🎮",
        text: "Ready for a new game."
      };
    }

    const liveText = liveConnection === "connecting"
      ? " (Connecting...)"
      : liveConnection === "disconnected"
        ? " (Offline, using HTTP)"
        : "";

    switch (game.status) {
      case "checkmate":
        return {
          className: "is-checkmate",
          icon: "🏆",
          text: game.winner
            ? `Checkmate! ${game.winner.toUpperCase()} wins!`
            : "Checkmate! Game over."
        };
      case "draw":
        const drawReasonText = game.drawReason === "stalemate"
          ? "Stalemate! Draw."
          : game.drawReason
            ? `Draw! (${game.drawReason})`
            : "Draw!";
        return {
          className: "is-draw",
          icon: "🤝",
          text: drawReasonText
        };
      case "resigned":
        return {
          className: "is-resigned",
          icon: "🏳️",
          text: game.winner
            ? `Resigned! ${game.winner.toUpperCase()} wins!`
            : "Resigned."
        };
      case "check":
        return {
          className: "is-check",
          icon: "🚨",
          text: `Check! ${game.activeColor.toUpperCase()} to move.` + liveText
        };
      default:
        const turnText = game.activeColor === "white"
          ? "White to move"
          : "Black to move";
        return {
          className: "is-active",
          icon: "⚔️",
          text: `${turnText}${liveText}`
        };
    }
  };

  const details = getBannerDetails();

  return (
    <section className={`banner ${details.className}`} aria-live="polite">
      <div className="banner-content">
        <span className="banner-icon" role="img" aria-hidden="true">
          {details.icon}
        </span>
        <p className="banner-message">{details.text}</p>
      </div>
    </section>
  );
}
