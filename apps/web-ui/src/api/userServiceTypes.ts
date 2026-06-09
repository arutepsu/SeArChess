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
