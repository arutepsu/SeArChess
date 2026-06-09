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
