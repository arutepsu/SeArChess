import keycloak from "../auth/keycloak";

export const LICHESS_BOT_USERNAME = "arutepsu2";
export const LICHESS_BOT_PROFILE_URL = `https://lichess.org/@/${LICHESS_BOT_USERNAME}`;

export interface LichessBridgeStatusResponse {
  service: string;
  phase: string;
  enabled: boolean;
  botUsernameConfigured: boolean;
  tokenConfigured: boolean;
  maxConcurrentGames: number;
  workerRunning: boolean;
  acceptChallenges: boolean;
  activeGamesCount: number;
  lastEventAt: string | null;
  lastError: string | null;
}

export interface LichessBridgePolicyResponse {
  acceptChallenges: boolean;
  acceptRated: boolean;
  allowedVariants: string[];
  minClockSeconds: number;
  maxClockSeconds: number;
  maxConcurrentGames: number;
}

export type BridgeHealthVariant =
  | "healthy"
  | "degraded"
  | "offline"
  | "disabled"
  | "unknown";

export interface BridgeHealth {
  label: string;
  variant: BridgeHealthVariant;
}

export function deriveBridgeHealth(
  status: LichessBridgeStatusResponse | null,
  hasFetchError: boolean
): BridgeHealth {
  if (hasFetchError || status === null) {
    return { label: "Status unavailable", variant: "unknown" };
  }
  if (!status.enabled) {
    return { label: "Disabled", variant: "disabled" };
  }
  if (!status.workerRunning) {
    return { label: "Offline", variant: "offline" };
  }
  if (!status.acceptChallenges) {
    return { label: "Online · Not accepting challenges", variant: "degraded" };
  }
  if (status.lastError !== null) {
    return { label: "Online · Accepting challenges · Degraded", variant: "degraded" };
  }
  return { label: "Online · Accepting challenges", variant: "healthy" };
}

function authHeaders(): Record<string, string> {
  return keycloak.token ? { Authorization: `Bearer ${keycloak.token}` } : {};
}

async function fetchBridgeJson<T>(path: string): Promise<T> {
  const response = await fetch(path, {
    headers: {
      "Content-Type": "application/json",
      ...authHeaders(),
    },
  });
  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || `Request failed: ${response.status}`);
  }
  return (await response.json()) as T;
}

export async function getLichessBridgeStatus(): Promise<LichessBridgeStatusResponse> {
  return fetchBridgeJson<LichessBridgeStatusResponse>("/api/lichess/bridge/status");
}

export async function getLichessBridgePolicy(): Promise<LichessBridgePolicyResponse> {
  return fetchBridgeJson<LichessBridgePolicyResponse>("/api/lichess/bridge/policy");
}
