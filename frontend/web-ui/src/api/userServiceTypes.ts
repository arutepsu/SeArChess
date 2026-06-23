export type LichessLinkCapability =
  | "identity_only"
  | "manual_dev"
  | "unknown"
  | "challenge_ready"
  | "board_play"
  | "expired"
  | "revoked";

export interface ExternalAccountLinkDto {
  linkId: string;
  provider: string;
  externalId: string | null;
  externalUsername: string;
  verified: boolean;
  verificationSource: string;
  linkedAt: string;
  capability: LichessLinkCapability;
}

export interface UserProfileResponse {
  userId: string;
  keycloakSubject: string;
  displayName: string;
  email: string | null;
  nickname: string | null;
  onboardingRequired: boolean;
  links: ExternalAccountLinkDto[];
}

export interface PatchProfileRequest {
  nickname: string;
}

export interface SetManualLichessLinkRequest {
  lichessUsername: string;
}

export interface LichessLinkStartResponse {
  authorizationUrl: string;
}

export interface LichessUpgradeResponse {
  authorizationUrl: string;
}

export type LichessChallengeColor = "random" | "white" | "black";

export interface CreateSearchessBotChallengeRequest {
  clockSeconds?: number;
  clockIncrement?: number;
  rated?: false;
  variant?: "standard";
  color?: LichessChallengeColor;
}

export interface CreateSearchessBotChallengeResponse {
  challengeId: string;
  url: string;
}

export interface LichessGamePlayerState {
  username: string;
  isSearchessBot: boolean;
}

export interface LichessGameStateResponse {
  gameId: string;
  status: string;
  fen: string | null;
  moves: string;
  white: LichessGamePlayerState;
  black: LichessGamePlayerState;
  sideToMove: "white" | "black";
  userColor: "white" | "black" | null;
  botColor: "white" | "black" | null;
  url: string;
  lastUpdatedAt: string;
}

export interface SubmitLichessMoveRequest {
  move: string;
}

export interface SubmitLichessMoveResponse {
  gameId: string;
  move: string;
  accepted: true;
}

export interface LichessActiveGameSummary {
  gameId: string;
  status: string;
  url: string;
  white: { username: string; isSearchessBot: boolean };
  black: { username: string; isSearchessBot: boolean };
  userColor?: "white" | "black";
  botColor?: "white" | "black";
  lastUpdatedAt: string;
}

export interface LichessActiveGamesResponse {
  games: LichessActiveGameSummary[];
}

// ── Tournament bot ownership types ────────────────────────────────────────────

export interface OwnedTournamentBot {
  searchessBotId:          string;   // Searchess ownership UUID — primary key for join/participant identity
  tournamentServerBotId:   string;
  tournamentServerBotName: string;
  searchessCatalogBotId:   string | null;
  createdAt: string;
}

export interface OwnedTournamentBotsResponse {
  bots: OwnedTournamentBot[];
}

export interface RecordTournamentBotOwnershipRequest {
  tournamentServerBotId:   string;
  tournamentServerBotName: string;
  searchessCatalogBotId?:  string;
}

// ── Tournament participant registry types ─────────────────────────────────────

export interface TournamentParticipant {
  tournamentId:            string;
  searchessUserId:         string;
  displayName:             string;
  searchessBotId:          string | null;
  searchessCatalogBotId:   string | null;
  tournamentServerUserId:  string | null;
  tournamentServerBotId:   string;
  tournamentServerBotName: string;
  joinedAt:                string;
}

export interface TournamentParticipantsResponse {
  tournamentId: string;
  participants: TournamentParticipant[];
}

export interface RecordTournamentParticipantRequest {
  tournamentServerBotId:   string;
  tournamentServerBotName: string;
  tournamentServerUserId?: string;
}
