import type {
  CreateSearchessBotChallengeRequest,
  CreateSearchessBotChallengeResponse,
  ExternalAccountLinkDto,
  LichessActiveGamesResponse,
  LichessGameStateResponse,
  LichessLinkStartResponse,
  LichessUpgradeResponse,
  OwnedTournamentBot,
  OwnedTournamentBotsResponse,
  PatchProfileRequest,
  RecordTournamentBotOwnershipRequest,
  RecordTournamentParticipantRequest,
  SetManualLichessLinkRequest,
  SubmitLichessMoveResponse,
  TournamentParticipant,
  TournamentParticipantsResponse,
  UserProfileResponse,
} from "./userServiceTypes";
import keycloak from "../auth/keycloak";
import { apiUrl } from "./client";

async function authHeaders(): Promise<Record<string, string>> {
  if (!keycloak.authenticated) return {};

  try {
    await keycloak.updateToken(30);
  } catch {
    keycloak.clearToken();
    return {};
  }

  return keycloak.token ? { Authorization: `Bearer ${keycloak.token}` } : {};
}

async function fetchUserJson<T>(path: string, options?: RequestInit): Promise<T> {
  const response = await fetch(apiUrl(path), {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...(await authHeaders()),
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
  return fetchUserJson<UserProfileResponse>("/users/me");
}

export async function setManualLichessLink(
  request: SetManualLichessLinkRequest
): Promise<ExternalAccountLinkDto> {
  return fetchUserJson<ExternalAccountLinkDto>("/users/me/links/lichess/manual", {
    method: "PUT",
    body: JSON.stringify(request),
  });
}

export async function startLichessLink(): Promise<LichessLinkStartResponse> {
  return fetchUserJson<LichessLinkStartResponse>("/users/me/links/lichess/start");
}

export async function patchProfile(request: PatchProfileRequest): Promise<UserProfileResponse> {
  return fetchUserJson<UserProfileResponse>("/users/me/profile", {
    method: "PATCH",
    body: JSON.stringify(request),
  });
}

export async function upgradeLichessLink(targetCapability: "challenge_ready"): Promise<LichessUpgradeResponse> {
  return fetchUserJson<LichessUpgradeResponse>("/users/me/links/lichess/upgrade", {
    method: "POST",
    body: JSON.stringify({ targetCapability }),
  });
}

export async function createSearchessBotChallenge(
  request: CreateSearchessBotChallengeRequest = {}
): Promise<CreateSearchessBotChallengeResponse> {
  return fetchUserJson<CreateSearchessBotChallengeResponse>("/users/me/lichess/challenges/searchess-bot", {
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
  return fetchUserJson<LichessGameStateResponse>(`/users/me/lichess/games/${encodeURIComponent(gameId)}`);
}

export async function submitLichessMove(gameId: string, move: string): Promise<SubmitLichessMoveResponse> {
  return fetchUserJson<SubmitLichessMoveResponse>(`/users/me/lichess/games/${encodeURIComponent(gameId)}/move`, {
    method: "POST",
    body: JSON.stringify({ move }),
  });
}

export async function getActiveLichessGames(): Promise<LichessActiveGamesResponse> {
  return fetchUserJson<LichessActiveGamesResponse>("/users/me/lichess/games/active");
}

export async function getMyTournamentBots(): Promise<OwnedTournamentBot[]> {
  const data = await fetchUserJson<OwnedTournamentBotsResponse>("/users/me/tournament-bots");
  return data.bots;
}

export async function recordTournamentBotOwnership(
  req: RecordTournamentBotOwnershipRequest
): Promise<OwnedTournamentBot> {
  return fetchUserJson<OwnedTournamentBot>("/users/me/tournament-bots", {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export async function getTournamentParticipants(
  tournamentId: string
): Promise<TournamentParticipant[]> {
  const data = await fetchUserJson<TournamentParticipantsResponse>(
    `/users/tournaments/${encodeURIComponent(tournamentId)}/participants`
  );
  return data.participants;
}

export async function recordTournamentParticipant(
  tournamentId: string,
  req: RecordTournamentParticipantRequest
): Promise<TournamentParticipant> {
  return fetchUserJson<TournamentParticipant>(
    `/users/tournaments/${encodeURIComponent(tournamentId)}/participants`,
    { method: "POST", body: JSON.stringify(req) }
  );
}

export async function deleteLichessLink(): Promise<void> {
  const response = await fetch(apiUrl("/users/me/links/lichess"), {
    method: "DELETE",
    headers: await authHeaders(),
  });
  if (!response.ok && response.status !== 404) {
    throw new Error(`Delete failed: ${response.status}`);
  }
}


