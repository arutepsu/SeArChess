import type { GameState } from "../api/types";
import "./StatusBanner.css";

type StatusBannerProps = {
  game?: GameState;
  connection: "connected" | "offline" | "loading";
  liveConnection?: "idle" | "connecting" | "live" | "disconnected";
  message?: string;
};

export default function StatusBanner({ game, connection, liveConnection = "idle", message }: StatusBannerProps) {
<<<<<<< HEAD
  const getBannerDetails = () => {
    if (connection === "loading") {
      return {
        className: "is-loading",
        icon: "⏳",
        text: "Verbindung zum Spiel-Server wird hergestellt... / Connecting to game service..."
      };
    }
    if (connection === "offline") {
      return {
        className: "is-offline",
        icon: "⚠️",
        text: "Spiel-Server nicht erreichbar. Bitte Docker Compose starten. / Game service unreachable."
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
        text: "Bereit für ein neues Spiel. / Ready for a new game."
      };
    }

    const liveText = liveConnection === "connecting"
      ? " (Live-Verbindung wird aufgebaut... / Connecting...)"
      : liveConnection === "disconnected"
        ? " (Live-Verbindung getrennt / Offline, using HTTP)"
        : "";

    switch (game.status) {
      case "checkmate":
        return {
          className: "is-checkmate",
          icon: "🏆",
          text: game.winner 
            ? `Schachmatt! ${game.winner.toUpperCase()} gewinnt! / Checkmate! ${game.winner.toUpperCase()} wins!` 
            : "Schachmatt! Spiel beendet. / Checkmate! Game over."
        };
      case "draw":
        const drawReasonText = game.drawReason === "stalemate" 
          ? "Patt! Unentschieden. / Stalemate! Draw." 
          : game.drawReason 
            ? `Remis! (${game.drawReason}) / Draw! (${game.drawReason})` 
            : "Remis! Unentschieden. / Draw!";
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
            ? `Aufgegeben! ${game.winner.toUpperCase()} gewinnt! / Resigned! ${game.winner.toUpperCase()} wins!`
            : "Aufgegeben. / Resigned."
        };
      case "check":
        return {
          className: "is-check",
          icon: "🚨",
          text: `Schach! ${game.activeColor.toUpperCase()} ist am Zug. / Check! ${game.activeColor.toUpperCase()} to move.` + liveText
        };
      default:
        const turnText = game.activeColor === "white" 
          ? "Weiß ist am Zug / White to move" 
          : "Schwarz ist am Zug / Black to move";
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
=======
  const resultMessage =
    game?.status === "checkmate"
      ? game.winner
        ? `Checkmate. ${game.winner} wins.`
        : "Checkmate."
      : game?.status === "draw"
        ? game.drawReason
          ? `Draw: ${game.drawReason}.`
          : "Draw."
        : game?.status === "resigned"
          ? game.winner
            ? `Game over. ${game.winner} wins by resignation.`
            : "Game over by resignation."
          : undefined;

  const liveMessage =
    liveConnection === "live"
      ? " Live updates connected."
      : liveConnection === "connecting"
        ? " Live updates connecting..."
        : liveConnection === "disconnected"
          ? " Live updates disconnected; using HTTP refresh."
          : "";

  const baseStatusMessage =
    message ??
    resultMessage ??
    (connection === "loading"
      ? "Connecting to Game Service..."
      : connection === "offline"
        ? "Game Service is unreachable. Check that Docker Compose and Envoy are running."
        : game
          ? `Connected to Game Service. Game ${game.id}`
          : "Connected to Game Service. Starting a game...");

  const statusMessage =
    connection === "connected" && game
      ? `${baseStatusMessage}${liveMessage}`
      : baseStatusMessage;

  return (
    <section className="banner" aria-live="polite">
      {statusMessage ? <p className="message">{statusMessage}</p> : null}
>>>>>>> 3bfa20a2 (polish web ui)
    </section>
  );
}
