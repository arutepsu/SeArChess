import type { PlayableGameMode } from "./api/types";

export type PlaceholderGameMode =
  | "AIVsLichessBot"
  | "BotVsBot"
  | "Tournament";

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
    title: "Human vs Deployed Bot",
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

export function isPlayableGameMode(mode: GameModeId): mode is PlayableGameMode {
  return mode === "HumanVsHuman" || mode === "HumanVsAI" || mode === "AIVsAI" || mode === "HumanVsDeployedBot";
}

