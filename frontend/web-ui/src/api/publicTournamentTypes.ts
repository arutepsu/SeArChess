// Types for the public tournament-server, accessed via Searchess gateway-service.
// These are intentionally separate from local bot tournament types (tournamentTypes.ts).

export interface PublicBotRef {
  id: string;
  name: string;
}

export type PublicTournamentStatus = "created" | "started" | "finished";

export type PublicTournamentFormat =
  | "swiss"
  | "singleElimination"
  | "doubleElimination"
  | "groupStage"
  | "league"
  | "randomKnockout";

export interface PublicTournamentClock {
  limit: number;
  increment: number;
}

export interface PublicTournamentVariant {
  key: string;
  name: string;
}

export interface PublicTournamentInfo {
  id: string;
  fullName: string;
  clock: PublicTournamentClock;
  variant: PublicTournamentVariant;
  rated: boolean;
  nbPlayers: number;
  nbRounds: number;
  format: PublicTournamentFormat;
  matchesPerPairing: number;
  startPosition: string;
  createdBy: string;
  startsAt: string | null;
}

export interface PublicResult {
  rank: number;
  points: number;
  tieBreak: number;
  bot: PublicBotRef;
  nbGames: number;
  wins: number;
  draws: number;
  losses: number;
}

export interface PublicStanding {
  page: number;
  results: PublicResult[];
}

export interface PublicTournament extends PublicTournamentInfo {
  status: PublicTournamentStatus;
  round: number;
  standing: PublicStanding | null;
  winner: PublicBotRef | null;
}

export interface PublicTournamentListResponse {
  created: PublicTournamentInfo[];
  started: PublicTournamentInfo[];
  finished: PublicTournamentInfo[];
}

export interface PublicRoundGame {
  gameId: string;
  white: PublicBotRef;
  black: PublicBotRef;
  status: string;
  winner: "white" | "black" | null;
}

export interface PublicRoundPairings {
  round: number;
  games: PublicRoundGame[];
}

export interface PublicRegisteredBot {
  id: string;
  name: string;
  endpoint: string | null;
  family: string | null;
  strategyType: string | null;
  engineType: string | null;
  modelVersion: string | null;
}

export interface PublicOpening {
  key: string;
  name: string;
  fen: string;
}

export interface PublicAnalyticsExportStanding {
  rank: number;
  points: number;
  tieBreak: number;
  botId: string;
  botName: string;
  botFamily: string | null;
  botStrategyType: string | null;
  botEngineType: string | null;
  botModelVersion: string | null;
  nbGames: number;
  wins: number;
  draws: number;
  losses: number;
}

export interface PublicAnalyticsExportGame {
  gameId: string;
  tournamentId: string;
  round: number;
  whiteBotId: string;
  whiteBotName: string;
  whiteBotFamily: string | null;
  whiteBotStrategyType: string | null;
  whiteBotEngineType: string | null;
  whiteBotModelVersion: string | null;
  blackBotId: string;
  blackBotName: string;
  blackBotFamily: string | null;
  blackBotStrategyType: string | null;
  blackBotEngineType: string | null;
  blackBotModelVersion: string | null;
  winner: "white" | "black" | null;
  winnerBotId: string | null;
  terminationReason: string;
  totalPly: number;
  moves: string;
  startedAt: string | null;
  endedAt: string | null;
  durationMillis: number | null;
}

export interface PublicAnalyticsExport {
  schemaVersion: string;
  tournamentId: string;
  format: PublicTournamentFormat;
  clock: PublicTournamentClock;
  rated: boolean;
  nbRounds: number;
  startedAt: string | null;
  finishedAt: string | null;
  exportedAt: string;
  standings: PublicAnalyticsExportStanding[];
  games: PublicAnalyticsExportGame[];
}

export interface PublicAnalyticsNotReady {
  notReady: true;
  message: string;
}

export type PublicAnalyticsResult = PublicAnalyticsExport | PublicAnalyticsNotReady;

export function isAnalyticsNotReady(r: PublicAnalyticsResult): r is PublicAnalyticsNotReady {
  return (r as PublicAnalyticsNotReady).notReady === true;
}

// ── Director request/response types ───────────────────────────────────────────

export interface CreatePublicTournamentRequest {
  name: string;
  nbRounds: number;
  clockLimit: number;
  clockIncrement: number;
  rated: boolean;
  format: PublicTournamentFormat;
  startPosition?: string;
  matchesPerPairing?: number;
}

export interface PublicTournamentUserInfo {
  tournamentUserId: string;
}

// ── Stream event types ─────────────────────────────────────────────────────────

export type PublicTournamentEventType =
  | "tournamentStarted"
  | "roundStarted"
  | "gameStart"
  | "roundFinished"
  | "tournamentFinished"
  | "heartbeat"
  | "gameState"
  | "move"
  | "gameEnd";

/** A single event from the tournament-server NDJSON stream.
 *  Unknown event types and extra fields are tolerated. */
export interface PublicTournamentEvent {
  type: PublicTournamentEventType | string;
  tournamentId?: string;
  gameId?: string;
  round?: number;
  fen?: string;
  moves?: string[];
  lastMove?: string;
  status?: string;
  winner?: string;
  whiteBotId?: string;
  blackBotId?: string;
  whiteBotName?: string;
  blackBotName?: string;
  [key: string]: unknown;
}

/** Initial game snapshot returned from GET /api/gateway/tournament/:id/game/:gameId */
export interface PublicGameSnapshot {
  gameId: string;
  tournamentId?: string;
  whiteBotId?: string;
  blackBotId?: string;
  whiteBotName?: string;
  blackBotName?: string;
  fen?: string;
  moves?: string[];
  status?: string;
  winner?: string;
  round?: number;
}
