// Client for the Searchess gateway-service that proxies the public tournament-server.
// The browser never calls the tournament-server directly.
// Intentionally separate from tournamentClient.ts (local bot tournament feature).

import { authHeaders } from "./client";
import type {
  CreatePublicTournamentRequest,
  PublicAnalyticsResult,
  PublicOpening,
  PublicRegisteredBot,
  PublicResult,
  PublicRoundPairings,
  PublicTournament,
  PublicTournamentListResponse,
  RegisterBotRequest,
  TournamentIdentityResponse,
} from "./publicTournamentTypes";

// Resolved auth headers as a plain Headers object.
async function gatewayAuthHeaders(): Promise<Headers> {
  const auth = await authHeaders();
  const h = new Headers();
  for (const [k, v] of Object.entries(auth)) h.set(k, v);
  return h;
}

const GATEWAY_PREFIX = "/api/gateway";

// ── Authenticated GET helper (all gateway reads require Keycloak auth via Envoy) ──

async function fetchGateway<T>(path: string): Promise<T> {
  const headers = await gatewayAuthHeaders();
  const response = await fetch(`${GATEWAY_PREFIX}${path}`, { headers });

  if (response.status === 401) {
    throw new Error("Sign in to access the Public Tournament server.");
  }
  if (!response.ok) {
    const text = await response.text().catch(() => "");
    let message = `Request failed: ${response.status}`;
    try {
      const json = JSON.parse(text) as { message?: string; code?: string };
      if (json.message) message = json.message;
    } catch {
      // ignore parse error
    }
    throw new Error(message);
  }

  return (await response.json()) as T;
}

export async function listPublicTournaments(): Promise<PublicTournamentListResponse> {
  return fetchGateway<PublicTournamentListResponse>("/tournament");
}

export async function getPublicTournament(id: string): Promise<PublicTournament> {
  return fetchGateway<PublicTournament>(`/tournament/${encodeURIComponent(id)}`);
}

export async function getPublicTournamentResults(id: string): Promise<PublicResult[]> {
  const headers = await gatewayAuthHeaders();
  const response = await fetch(
    `${GATEWAY_PREFIX}/tournament/${encodeURIComponent(id)}/results`,
    { headers }
  );
  if (response.status === 401) throw new Error("Sign in to access the Public Tournament server.");
  if (!response.ok) throw new Error(`Failed to load results: ${response.status}`);
  const data = await response.json() as PublicResult[] | unknown;
  return Array.isArray(data) ? (data as PublicResult[]) : [];
}

export async function getPublicTournamentRound(
  id: string,
  round: number
): Promise<PublicRoundPairings> {
  return fetchGateway<PublicRoundPairings>(
    `/tournament/${encodeURIComponent(id)}/round/${round}`
  );
}

export async function getPublicTournamentAnalyticsExport(
  id: string
): Promise<PublicAnalyticsResult> {
  const headers = await gatewayAuthHeaders();
  const response = await fetch(
    `${GATEWAY_PREFIX}/tournament/${encodeURIComponent(id)}/analytics-export`,
    { headers }
  );
  if (response.status === 401) throw new Error("Sign in to access the Public Tournament server.");
  if (response.status === 409) {
    return {
      notReady: true,
      message: "Analytics not yet available. The tournament must finish first.",
    };
  }
  if (!response.ok) throw new Error(`Failed to load analytics: ${response.status}`);
  return (await response.json()) as PublicAnalyticsResult;
}

export async function listPublicOpenings(): Promise<PublicOpening[]> {
  const data = await fetchGateway<PublicOpening[] | { openings?: PublicOpening[] }>(
    "/openings"
  );
  if (Array.isArray(data)) return data as PublicOpening[];
  const obj = data as { openings?: PublicOpening[] };
  return obj.openings ?? [];
}

export async function listPublicBots(): Promise<PublicRegisteredBot[]> {
  const data = await fetchGateway<
    PublicRegisteredBot[] | { bots?: PublicRegisteredBot[] }
  >("/bots");
  if (Array.isArray(data)) return data as PublicRegisteredBot[];
  const obj = data as { bots?: PublicRegisteredBot[] };
  return obj.bots ?? [];
}

// ── Authenticated (director) helper ──────────────────────────────────────────
// Browser sends only the Keycloak token. Gateway owns tournament-server JWT.

async function fetchGatewayAuth<T>(path: string, options?: RequestInit): Promise<T> {
  const auth = await authHeaders();
  const headers = new Headers({ "Content-Type": "application/json" });
  for (const [k, v] of Object.entries(auth)) headers.set(k, v);

  const response = await fetch(`${GATEWAY_PREFIX}${path}`, {
    ...options,
    headers,
  });

  if (response.status === 401) {
    throw new Error("Sign in to access the Public Tournament server.");
  }
  if (!response.ok) {
    const text = await response.text().catch(() => "");
    let message = `Request failed: ${response.status}`;
    try {
      const json = JSON.parse(text) as { message?: string; code?: string; error?: string };
      if (json.code === "BOT_ID_MISMATCH")
        message = "Ownership check failed: this bot is registered under a different ID. Re-register the bot and try again.";
      else if (json.message) message = json.message;
      else if (json.error) message = json.error;
    } catch {
      // ignore parse error
    }
    throw new Error(message);
  }

  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}

// ── Bot registration (authenticated) ─────────────────────────────────────────

export async function registerPublicBot(req: RegisterBotRequest): Promise<PublicRegisteredBot> {
  return fetchGatewayAuth<PublicRegisteredBot>("/bots", {
    method: "POST",
    body: JSON.stringify(req),
  });
}

// ── Director endpoints ────────────────────────────────────────────────────────

export async function createPublicTournament(
  form: CreatePublicTournamentRequest,
): Promise<PublicTournament> {
  return fetchGatewayAuth<PublicTournament>("/tournament", {
    method: "POST",
    body: JSON.stringify(form),
  });
}

export async function startPublicTournament(id: string): Promise<PublicTournament> {
  return fetchGatewayAuth<PublicTournament>(
    `/tournament/${encodeURIComponent(id)}/start`,
    { method: "POST" },
  );
}

export async function deletePublicTournament(id: string): Promise<void> {
  await fetchGatewayAuth<void>(
    `/tournament/${encodeURIComponent(id)}`,
    { method: "DELETE" },
  );
}

export async function addPublicTournamentParticipant(
  id: string,
  botId: string,
): Promise<void> {
  await fetchGatewayAuth<void>(
    `/tournament/${encodeURIComponent(id)}/participants`,
    { method: "POST", body: JSON.stringify({ botId }) },
  );
}

// Returns the current user's tournament-server identity (tournamentUserId, preferredUsername).
// Registers the user with the tournament-server if not already cached on the gateway.
// tournament.createdBy is the tournament-server userId, not the Keycloak preferred_username;
// compare against tournamentUserId for correct director detection.
export async function getCurrentTournamentIdentity(): Promise<TournamentIdentityResponse> {
  return fetchGatewayAuth<TournamentIdentityResponse>("/tournament/me");
}

// Bot self-join via the gateway /join endpoint.
// Gateway acquires a bot JWT (isBot=true) and calls the tournament-server /join.
// Ownership is verified server-side: registration of (botName, isBot=true) must
// return the expected botId, otherwise the gateway rejects with BOT_ID_MISMATCH.
export async function joinPublicTournamentWithBot(
  id: string,
  req: { botId: string; botName: string },
): Promise<void> {
  await fetchGatewayAuth<void>(
    `/tournament/${encodeURIComponent(id)}/join`,
    { method: "POST", body: JSON.stringify(req) },
  );
}

// ── Game snapshot (authenticated) ─────────────────────────────────────────────

export async function getPublicTournamentGame(
  tournamentId: string,
  gameId: string,
): Promise<import("./publicTournamentTypes").PublicGameSnapshot> {
  return fetchGatewayAuth<import("./publicTournamentTypes").PublicGameSnapshot>(
    `/tournament/${encodeURIComponent(tournamentId)}/game/${encodeURIComponent(gameId)}`,
  );
}

// ── Stream URL helpers ─────────────────────────────────────────────────────────
// These return URLs for fetch-based NDJSON streams.
// The browser sends only the Keycloak token; the gateway injects the tournament JWT.

export function publicTournamentStreamUrl(tournamentId: string): string {
  return `${GATEWAY_PREFIX}/tournament/${encodeURIComponent(tournamentId)}/stream`;
}

export function publicTournamentGameStreamUrl(tournamentId: string, gameId: string): string {
  return `${GATEWAY_PREFIX}/tournament/${encodeURIComponent(tournamentId)}/game/${encodeURIComponent(gameId)}/stream`;
}
