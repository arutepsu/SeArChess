import type {
  CommandGameResponse,
  CreateGameRequest,
  CreateGameResponse,
  ErrorResponse,
  GameNotationResponse,
  GameSnapshot,
  HealthResponse,
  ImportNotationRequest,
  ResignRequest,
  SubmitMoveRequest
} from "./backendTypes";

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

export async function getGameNotation(gameId: string): Promise<GameNotationResponse> {
  return fetchJson<GameNotationResponse>(`/api/games/${gameId}/notation`);
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
  return fetchJson<CreateGameResponse>("/api/sessions/import", {
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
