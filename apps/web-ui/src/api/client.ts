import type {
  CommandGameResponse,
  CreateGameRequest,
  CreateGameResponse,
  ErrorResponse,
  GameNotationResponse,
  GameSnapshot,
  HeatmapResponse,
  HealthResponse,
  ImportNotationRequest,
  NotationTextResponse,
  ResignRequest,
  SessionExportEnvelope,
  SessionListResponse,
  SessionStateResponse,
  SubmitMoveRequest
} from "./backendTypes";
import type { MigrationReport, MigrationRequest } from "./migrationTypes";

const DEFAULT_API_BASE = "http://localhost:10000";

export const apiBaseUrl =
  import.meta.env.VITE_API_BASE_URL?.toString() || DEFAULT_API_BASE;

async function fetchJson<T>(path: string, options?: RequestInit): Promise<T> {
  const response = await fetch(`${apiBaseUrl}${path}`, {
    headers: { "Content-Type": "application/json" },
    ...options
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
  return fetchJson<GameSnapshot>(`/api/games/${gameId}`);
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
  return fetchJson<NotationTextResponse>(`/api/games/${gameId}/notation/fen`);
}

export async function exportPgn(gameId: string): Promise<NotationTextResponse> {
  return fetchJson<NotationTextResponse>(`/api/games/${gameId}/notation/pgn`);
}

export async function createGame(
  payload: CreateGameRequest
): Promise<CreateGameResponse> {
  return fetchJson<CreateGameResponse>("/api/sessions", {
    method: "POST",
    body: JSON.stringify(payload)
  });
}

export async function importGameFromNotation(
  payload: ImportNotationRequest
): Promise<CreateGameResponse> {
  return importNotation(payload);
}

export async function importNotation(
  payload: ImportNotationRequest
): Promise<CreateGameResponse> {
  return fetchJson<CreateGameResponse>("/api/sessions/import-notation", {
    method: "POST",
    body: JSON.stringify(payload)
  });
}

export async function submitMove(
  gameId: string,
  payload: SubmitMoveRequest
): Promise<CommandGameResponse> {
  return fetchJson<CommandGameResponse>(`/api/games/${gameId}/moves`, {
    method: "POST",
    body: JSON.stringify(payload)
  });
}

export async function requestAiMove(
  gameId: string
): Promise<CommandGameResponse> {
  return fetchJson<CommandGameResponse>(`/api/games/${gameId}/ai-move`, {
    method: "POST"
  });
}

export async function resignGame(
  gameId: string,
  payload: ResignRequest
): Promise<CommandGameResponse> {
  return fetchJson<CommandGameResponse>(`/api/games/${gameId}/resign`, {
    method: "POST",
    body: JSON.stringify(payload)
  });
}

export async function listSessions(): Promise<SessionListResponse> {
  return fetchJson<SessionListResponse>("/api/sessions");
}

export async function loadSessionState(
  sessionId: string
): Promise<SessionStateResponse> {
  return fetchJson<SessionStateResponse>(`/api/sessions/${sessionId}/state`);
}

export async function exportSession(
  sessionId: string
): Promise<SessionExportEnvelope> {
  return fetchJson<SessionExportEnvelope>(`/api/sessions/${sessionId}/export`);
}

export async function importSession(
  envelope: SessionExportEnvelope
): Promise<SessionStateResponse> {
  return fetchJson<SessionStateResponse>("/api/sessions/import", {
    method: "POST",
    body: JSON.stringify(envelope)
  });
}

export async function saveSessionState(
  sessionId: string,
  state: SessionStateResponse
): Promise<SessionStateResponse> {
  return fetchJson<SessionStateResponse>(`/api/sessions/${sessionId}/state`, {
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
    `/api/stats/heatmap?sessionId=${encodeURIComponent(gameId)}&player=${encodeURIComponent(player)}`
  );
}
