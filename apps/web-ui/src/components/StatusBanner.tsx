import type { GameState } from "../api/types";
import "./StatusBanner.css";

type StatusBannerProps = {
  game?: GameState;
  connection: "connected" | "offline" | "loading";
  message?: string;
};

export default function StatusBanner({ message }: StatusBannerProps) {
  return (
    <section className="banner" aria-live="polite">
      {message ? <p className="message">{message}</p> : null}
    </section>
  );
}
