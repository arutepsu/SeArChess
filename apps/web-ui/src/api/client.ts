import type {
  CommandGameResponse,
  CreateGameRequest,
  CreateGameResponse,
  ErrorResponse,
  GameNotationResponse,
  ReplayFrameResponse,
  GameSnapshot,
  HeatmapResponse,
  HealthResponse,
  ImportNotationRequest,
  NotationTextResponse,
  ResignRequest,
  RunAiTurnsResponse,
  SessionExportEnvelope,
  SessionListResponse,
  SessionStateResponse,
  SubmitMoveRequest
} from "./backendTypes";
import type { MigrationReport, MigrationRequest } from "./migrationTypes";
import keycloak from "../auth/keycloak";

const devProxyTarget = import.meta.env.VITE_DEV_PROXY_TARGET?.toString().trim() ?? "";

function configuredApiBaseUrl(): string {
  if (devProxyTarget) return "";
  if (import.meta.env.VITE_API_BASE_URL === undefined) return "";
  return import.meta.env.VITE_API_BASE_URL.toString().trim();
}

export const apiBaseUrl = configuredApiBaseUrl();

function normalizePathPrefix(raw: unknown): string {
  const value = raw?.toString().trim() ?? "";
  if (!value || value === "/") return "";
  const withLeadingSlash = value.startsWith("/") ? value : `/${value}`;
  return withLeadingSlash.endsWith("/")
    ? withLeadingSlash.slice(0, -1)
    : withLeadingSlash;
}

export const apiPathPrefix = normalizePathPrefix(
  import.meta.env.VITE_API_PATH_PREFIX
);

function apiPath(path: string): string {
  const normalizedPath = path.startsWith("/") ? path : `/${path}`;
  return `${apiPathPrefix}${normalizedPath}`;
}

export function apiUrl(path: string): string {
  return `${apiBaseUrl}${apiPath(path)}`;
}

async function fetchJson<T>(path: string, options?: RequestInit): Promise<T> {
  const authHeaders: Record<string, string> = keycloak.token
    ? { Authorization: `Bearer ${keycloak.token}` }
    : {};

  const response = await fetch(apiUrl(path), {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...authHeaders,
      ...(options?.headers as Record<string, string> | undefined),
    },
  });

  if (!response.ok) {
    const raw = await response.text();

    let parsedError: ErrorResponse | undefined;
    try {
      parsedError = JSON.parse(raw) as ErrorResponse;
    } catch {
      parsedError = undefined;
    }

    if (parsedError?.code && parsedError.message) {
      throw new Error(`${parsedError.code}: ${parsedError.message}`);
    }

    throw new Error(raw || `Request failed: ${response.status}`);
  }

  return (await response.json()) as T;
}

export async function getStatus(): Promise<HealthResponse> {
  return fetchJson<HealthResponse>("/health");
}

export async function getGameState(gameId: string): Promise<GameSnapshot> {
  return fetchJson<GameSnapshot>(`/games/${gameId}`);
}


export async function getReplayFrame(
  gameId: string,
  ply: number
): Promise<ReplayFrameResponse> {
  return fetchJson<ReplayFrameResponse>(
    `/games/${gameId}/replay?ply=${encodeURIComponent(ply.toString())}`
  );
}

export async function getGameNotation(
  gameId: string
): Promise<GameNotationResponse> {
  const [fen, pgn] = await Promise.all([exportFen(gameId), exportPgn(gameId)]);

  return {
    fen: fen.notation,
    pgn: pgn.notation
  };
}

export async function exportFen(gameId: string): Promise<NotationTextResponse> {
  return fetchJson<NotationTextResponse>(`/games/${gameId}/notation/fen`);
}

export async function exportPgn(gameId: string): Promise<NotationTextResponse> {
  return fetchJson<NotationTextResponse>(`/games/${gameId}/notation/pgn`);
}

export async function createGame(
  payload: CreateGameRequest
): Promise<CreateGameResponse> {
  return fetchJson<CreateGameResponse>(createGamePathForMode(payload.mode), {
    method: "POST",
    body: JSON.stringify(payload)
  });
}

export function createHumanVsHuman(): Promise<CreateGameResponse> {
  return createGame({ mode: "HumanVsHuman" });
}

export function createHumanVsAi(): Promise<CreateGameResponse> {
  return createGame({ mode: "HumanVsAI" });
}

export function createAiVsAi(): Promise<CreateGameResponse> {
  return createGame({ mode: "AIVsAI" });
}

export function createHumanVsDeployedBot(): Promise<CreateGameResponse> {
  return fetchJson<CreateGameResponse>("/sessions/human-vs-deployed-bot", { method: "POST" });
}

function createGamePathForMode(mode?: CreateGameRequest["mode"]): string {
  switch (mode) {
    case "HumanVsAI":
      return "/sessions/human-vs-ai";
    case "AIVsAI":
      return "/sessions/ai-vs-ai";
    case "HumanVsDeployedBot":
      return "/sessions/human-vs-deployed-bot";
    case "HumanVsHuman":
    default:
      return "/sessions/human-vs-human";
  }
}

export async function importGameFromNotation(
  payload: ImportNotationRequest
): Promise<CreateGameResponse> {
  return importNotation(payload);
}
export async function importNotation(
  payload: ImportNotationRequest
): Promise<CreateGameResponse> {
  return fetchJson<CreateGameResponse>("/sessions/import-notation", {
    method: "POST",
    body: JSON.stringify(payload)
  });
}

export async function submitMove(
  gameId: string,
  payload: SubmitMoveRequest
): Promise<CommandGameResponse> {
  return fetchJson<CommandGameResponse>(`/games/${gameId}/moves`, {
    method: "POST",
    body: JSON.stringify(payload)
  });
}

export async function requestAiMove(
  gameId: string
): Promise<CommandGameResponse> {
  return fetchJson<CommandGameResponse>(`/games/${gameId}/ai-move`, {
    method: "POST"
  });
}

export async function runAiTurns(
  gameId: string,
  maxPlies: number
): Promise<RunAiTurnsResponse> {
  return fetchJson<RunAiTurnsResponse>(
    `/games/${gameId}/ai-turns?maxPlies=${encodeURIComponent(maxPlies.toString())}`,
    {
      method: "POST"
    }
  );
}

export async function resignGame(
  gameId: string,
  payload: ResignRequest
): Promise<CommandGameResponse> {
  return fetchJson<CommandGameResponse>(`/games/${gameId}/resign`, {
    method: "POST",
    body: JSON.stringify(payload)
  });
}

export async function listSessions(): Promise<SessionListResponse> {
  return fetchJson<SessionListResponse>("/sessions/mine");
}

export async function listMyArchive(): Promise<{ archives: unknown[] }> {
  return fetchJson<{ archives: unknown[] }>("/archive/mine");
}

export async function loadSessionState(
  sessionId: string
): Promise<SessionStateResponse> {
  return fetchJson<SessionStateResponse>(`/sessions/${sessionId}/state`);
}

export async function exportSession(
  sessionId: string
): Promise<SessionExportEnvelope> {
  return fetchJson<SessionExportEnvelope>(`/sessions/${sessionId}/export`);
}

export async function importSession(
  envelope: SessionExportEnvelope
): Promise<SessionStateResponse> {
  return fetchJson<SessionStateResponse>("/sessions/import", {
    method: "POST",
    body: JSON.stringify(envelope)
  });
}

export async function saveSessionState(
  sessionId: string,
  state: SessionStateResponse
): Promise<SessionStateResponse> {
  return fetchJson<SessionStateResponse>(`/sessions/${sessionId}/state`, {
    method: "PUT",
    body: JSON.stringify(state)
  });
}

export async function runMigration(
  request: MigrationRequest,
  adminToken?: string
): Promise<MigrationReport> {
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (adminToken) {
    headers["X-Admin-Token"] = adminToken;
  }
  return fetchJson<MigrationReport>("/admin/migrations", {
    method: "POST",
    headers,
    body: JSON.stringify(request)
  });
}
export async function getHeatmapStats(
  gameId: string,
  player: "White" | "Black"
): Promise<HeatmapResponse> {
  return fetchJson<HeatmapResponse>(
    `/stats/heatmap?sessionId=${encodeURIComponent(gameId)}&player=${encodeURIComponent(player)}`
  );
}
