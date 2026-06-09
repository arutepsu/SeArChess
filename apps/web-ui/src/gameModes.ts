import type { PlayableGameMode } from "./api/types";

export type GameModeId =
  | PlayableGameMode
  | "BotVsBot"
  | "Tournament";

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
    summary: "Play a two-player game inside Searchess.",
    active: true,
    startLabel: "Start"
  },
  {
    id: "HumanVsAI",
    title: "Human vs Searchess AI",
    summary: "Play White; the Searchess AI replies after each of your moves.",
    active: true,
    startLabel: "Start"
  },
  {
    id: "HumanVsDeployedBot",
    title: "Human vs Searchess Bot",
    summary: "Play inside Searchess against the deployed bot. A linked and verified Lichess account is required to confirm your identity.",
    active: true,
    startLabel: "Start"
  },
  {
    id: "AIVsAI",
    title: "AI vs AI Demo",
    summary: "Watch the Searchess AI play both sides. Advance with batched AI turns.",
    active: true,
    startLabel: "Start"
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
