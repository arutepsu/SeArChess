export interface ExternalAccountLinkDto {
  linkId: string;
  provider: string;
  externalId: string | null;
  externalUsername: string;
  verified: boolean;
  verificationSource: string;
  linkedAt: string;
}

export interface UserProfileResponse {
  userId: string;
  displayName: string;
  email: string | null;
  links: ExternalAccountLinkDto[];
}

export interface SetManualLichessLinkRequest {
  lichessUsername: string;
}
