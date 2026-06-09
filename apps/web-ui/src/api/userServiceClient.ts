import type {
  CreateSearchessBotChallengeRequest,
  CreateSearchessBotChallengeResponse,
  ExternalAccountLinkDto,
  LichessGameStateResponse,
  LichessLinkStartResponse,
  LichessUpgradeResponse,
  PatchProfileRequest,
  SetManualLichessLinkRequest,
  SubmitLichessMoveResponse,
  UserProfileResponse,
} from "./userServiceTypes";
import keycloak from "../auth/keycloak";

function authHeaders(): Record<string, string> {
  return keycloak.token ? { Authorization: `Bearer ${keycloak.token}` } : {};
}

async function fetchUserJson<T>(path: string, options?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...authHeaders(),
      ...(options?.headers as Record<string, string> | undefined),
    },
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || `Request failed: ${response.status}`);
  }

  return (await response.json()) as T;
}

export async function getMyProfile(): Promise<UserProfileResponse> {
  return fetchUserJson<UserProfileResponse>("/api/users/me");
}

export async function setManualLichessLink(
  request: SetManualLichessLinkRequest
): Promise<ExternalAccountLinkDto> {
  return fetchUserJson<ExternalAccountLinkDto>("/api/users/me/links/lichess/manual", {
    method: "PUT",
    body: JSON.stringify(request),
  });
}

export async function startLichessLink(): Promise<LichessLinkStartResponse> {
  return fetchUserJson<LichessLinkStartResponse>("/api/users/me/links/lichess/start");
}

export async function patchProfile(request: PatchProfileRequest): Promise<UserProfileResponse> {
  return fetchUserJson<UserProfileResponse>("/api/users/me/profile", {
    method: "PATCH",
    body: JSON.stringify(request),
  });
}

export async function upgradeLichessLink(targetCapability: "challenge_ready"): Promise<LichessUpgradeResponse> {
  return fetchUserJson<LichessUpgradeResponse>("/api/users/me/links/lichess/upgrade", {
    method: "POST",
    body: JSON.stringify({ targetCapability }),
  });
}

export async function createSearchessBotChallenge(
  request: CreateSearchessBotChallengeRequest = {}
): Promise<CreateSearchessBotChallengeResponse> {
  return fetchUserJson<CreateSearchessBotChallengeResponse>("/api/users/me/lichess/challenges/searchess-bot", {
    method: "POST",
    body: JSON.stringify({
      clockSeconds: 300,
      clockIncrement: 3,
      rated: false,
      variant: "standard",
      color: "random",
      ...request,
    }),
  });
}

export async function getLichessGameState(gameId: string): Promise<LichessGameStateResponse> {
  return fetchUserJson<LichessGameStateResponse>(`/api/users/me/lichess/games/${encodeURIComponent(gameId)}`);
}

export async function submitLichessMove(gameId: string, move: string): Promise<SubmitLichessMoveResponse> {
  return fetchUserJson<SubmitLichessMoveResponse>(`/api/users/me/lichess/games/${encodeURIComponent(gameId)}/move`, {
    method: "POST",
    body: JSON.stringify({ move }),
  });
}

export async function deleteLichessLink(): Promise<void> {
  const response = await fetch("/api/users/me/links/lichess", {
    method: "DELETE",
    headers: authHeaders(),
  });
  if (!response.ok && response.status !== 404) {
    throw new Error(`Delete failed: ${response.status}`);
  }
}
