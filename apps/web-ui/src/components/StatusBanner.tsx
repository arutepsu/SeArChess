import type { GameState } from "../api/types";
import "./StatusBanner.css";

type StatusBannerProps = {
  game?: GameState;
  connection: "connected" | "offline" | "loading";
  liveConnection?: "idle" | "connecting" | "live" | "disconnected";
  message?: string;
};

export default function StatusBanner({ game, connection, liveConnection = "idle", message }: StatusBannerProps) {
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
    </section>
  );
}
