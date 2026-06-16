import keycloak from "../auth/keycloak";
import { apiUrl } from "./client";

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

export interface BotGameSnapshot {
  gameId: string;
  fen: string;
  moves: string;
  botColor: "white" | "black";
  wtime?: number;
  btime?: number;
  status: string;
  lastMove?: string;
  lastUpdated: number;
}

export interface GameEventsCallbacks {
  onSnapshot: (snap: BotGameSnapshot) => void;
  onError: (err: Error) => void;
  onOpen?: () => void;
}

export function subscribeToLichessGameEvents(
  gameId: string,
  callbacks: GameEventsCallbacks
): () => void {
  const controller = new AbortController();

  void (async () => {
    try {
      const resp = await fetch(apiUrl(`/lichess/games/${gameId}/events`), {
        headers: { Accept: "text/event-stream", ...authHeaders() },
        signal: controller.signal,
      });

      if (!resp.ok) {
        callbacks.onError(new Error(`SSE failed: ${resp.status}`));
        return;
      }

      callbacks.onOpen?.();

      const reader = resp.body?.getReader();
      if (!reader) {
        callbacks.onError(new Error("No response body"));
        return;
      }

      const decoder = new TextDecoder();
      let buffer = "";

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split("\n");
        buffer = lines.pop() ?? "";
        for (const line of lines) {
          if (line.startsWith("data: ")) {
            try {
              const snap = JSON.parse(line.slice(6)) as BotGameSnapshot;
              callbacks.onSnapshot(snap);
            } catch {
              // malformed event — skip
            }
          }
        }
      }
    } catch (err) {
      if ((err as Error).name !== "AbortError") {
        callbacks.onError(err instanceof Error ? err : new Error(String(err)));
      }
    }
  })();

  return () => controller.abort();
}

function authHeaders(): Record<string, string> {
  return keycloak.token ? { Authorization: `Bearer ${keycloak.token}` } : {};
}

async function fetchBridgeJson<T>(path: string): Promise<T> {
  const response = await fetch(apiUrl(path), {
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
  return fetchBridgeJson<LichessBridgeStatusResponse>("/lichess/bridge/status");
}

export async function getLichessBridgePolicy(): Promise<LichessBridgePolicyResponse> {
  return fetchBridgeJson<LichessBridgePolicyResponse>("/lichess/bridge/policy");
}
