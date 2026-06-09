import type { PlayableGameMode } from "./api/types";

export type PlaceholderGameMode =
  | "AIVsLichessBot"
  | "BotVsBot"
  | "Tournament"
  | "LichessBridgeHumanVsLichess"
  | "LichessBridgeAiVsLichess"
  | "LichessBridgeSpectate";

export type GameModeId = PlayableGameMode | PlaceholderGameMode;

export interface GameModeDefinition {
  id: GameModeId;
  title: string;
  summary: string;
  active: boolean;
  startLabel: string;
}

export const gameModes: GameModeDefinition[] = [
  {
    id: "HumanVsHuman",
    title: "Human vs Human",
    summary: "Create a regular local two-player session.",
    active: true,
    startLabel: "Start"
  },
  {
    id: "HumanVsAI",
    title: "Human vs AI",
    summary: "Play White locally; Game Service applies the AI reply after your move.",
    active: true,
    startLabel: "Start"
  },
  {
    id: "AIVsAI",
    title: "AI vs AI",
    summary: "Create an AI-only session, then advance it with bounded AI turn batches.",
    active: true,
    startLabel: "Start"
  },
  {
    id: "HumanVsDeployedBot",
    title: "Human vs Searchess Bot",
    summary: "Play against the deployed Searchess bot inside this Web UI. Requires a verified linked Lichess account.",
    active: true,
    startLabel: "Start"
  },
  {
    id: "AIVsLichessBot",
    title: "AI vs Lichess Bot",
    summary: "Server-side orchestration required.",
    active: false,
    startLabel: "Coming Next"
  },
  {
    id: "BotVsBot",
    title: "Bot vs Bot",
    summary: "Server-side orchestration required.",
    active: false,
    startLabel: "Coming Next"
  },
  {
    id: "Tournament",
    title: "Tournament",
    summary: "Tournament orchestration needs a server-side API before browser control is safe.",
    active: false,
    startLabel: "Coming Next"
  }
];

/** Lichess Bridge modes shown as a separate disabled section (Phase 1 placeholder). */
export const lichessBridgeModes: GameModeDefinition[] = [
  {
    id: "LichessBridgeHumanVsLichess",
    title: "Human vs Lichess",
    summary: "Challenge a Lichess player directly from Searchess. Requires the Lichess Bridge service.",
    active: false,
    startLabel: "Coming Soon"
  },
  {
    id: "LichessBridgeAiVsLichess",
    title: "Searchess AI vs Lichess",
    summary: "Let the Searchess AI play on Lichess on your behalf. Requires the Lichess Bridge service.",
    active: false,
    startLabel: "Coming Soon"
  },
  {
    id: "LichessBridgeSpectate",
    title: "Spectate Lichess Game",
    summary: "Watch and analyse a live Lichess game inside Searchess. Requires the Lichess Bridge service.",
    active: false,
    startLabel: "Coming Soon"
  }
];

export function isPlayableGameMode(mode: GameModeId): mode is PlayableGameMode {
  return mode === "HumanVsHuman" || mode === "HumanVsAI" || mode === "AIVsAI" || mode === "HumanVsDeployedBot";
}

