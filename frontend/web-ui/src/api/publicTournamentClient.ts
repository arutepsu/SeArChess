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
} from "./publicTournamentTypes";

const GATEWAY_PREFIX = "/api/gateway";

// ── Public (unauthenticated) helper ──────────────────────────────────────────

async function fetchGateway<T>(path: string): Promise<T> {
  const response = await fetch(`${GATEWAY_PREFIX}${path}`);

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
  const response = await fetch(
    `${GATEWAY_PREFIX}/tournament/${encodeURIComponent(id)}/results`
  );
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
  const response = await fetch(
    `${GATEWAY_PREFIX}/tournament/${encodeURIComponent(id)}/analytics-export`
  );
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

  if (!response.ok) {
    const text = await response.text().catch(() => "");
    let message = `Request failed: ${response.status}`;
    try {
      const json = JSON.parse(text) as { message?: string; code?: string; error?: string };
      if (json.message) message = json.message;
      else if (json.error) message = json.error;
    } catch {
      // ignore parse error
    }
    throw new Error(message);
  }

  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
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
