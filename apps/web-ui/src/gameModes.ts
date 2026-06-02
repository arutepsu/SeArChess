import type { PlayableGameMode } from "./api/types";

export type PlaceholderGameMode =
  | "HumanVsLichessBot"
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

export const orchestrationRequiredMessage =
  "Lichess bot games run through the deployed bot and need server-side orchestration. Direct browser control is intentionally disabled.";

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
    id: "HumanVsLichessBot",
    title: "Human vs Lichess Bot",
    summary: orchestrationRequiredMessage,
    active: false,
    startLabel: "Coming Next"
  },
  {
    id: "AIVsLichessBot",
    title: "AI vs Lichess Bot",
    summary: orchestrationRequiredMessage,
    active: false,
    startLabel: "Coming Next"
  },
  {
    id: "BotVsBot",
    title: "Bot vs Bot",
    summary: orchestrationRequiredMessage,
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
  return mode === "HumanVsHuman" || mode === "HumanVsAI" || mode === "AIVsAI";
}

