import type {
<<<<<<< HEAD
  CommandGameResponse,
  CreateGameRequest,
  CreateGameResponse,
  ErrorResponse,
  GameNotationResponse,
  ReplayFrameResponse,
  GameSnapshot,
  HeatmapResponse,
=======
  ApiStatus,
  BoardMatrix,
  GameState,
  LegalMovesResponse,
  MoveRequest,
  NewGameRequest
} from "./types";
import { squareToIndex } from "../domain/board";
import type {
  CreateSessionResponse,
  GameResponse,
>>>>>>> ce08c01e (local microservices)
  HealthResponse,
  ImportNotationRequest,
  NotationTextResponse,
  ResignRequest,
  SessionExportEnvelope,
  SessionListResponse,
  SessionStateResponse,
  SubmitMoveRequest
} from "./backendTypes";
<<<<<<< HEAD
import type { MigrationReport, MigrationRequest } from "./migrationTypes";
import keycloak from "../auth/keycloak";
=======
import { mapGameResponseToGameState } from "./mapper";
import type { ErrorResponse } from "./backendTypes";
import type { SessionContext } from "../session/sessionStore";
>>>>>>> ce08c01e (local microservices)

const DEFAULT_API_BASE = "http://localhost:10000";

export const apiBaseUrl =
  import.meta.env.VITE_API_BASE_URL?.toString() || DEFAULT_API_BASE;

<<<<<<< HEAD
=======
const useMock = import.meta.env.VITE_API_MOCK === "true";

// ── HTTP helper ───────────────────────────────────────────────────────────────

>>>>>>> ce08c01e (local microservices)
async function fetchJson<T>(path: string, options?: RequestInit): Promise<T> {
  const authHeaders: Record<string, string> = keycloak.token
    ? { Authorization: `Bearer ${keycloak.token}` }
    : {};

  const response = await fetch(`${apiBaseUrl}${path}`, {
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

<<<<<<< HEAD
export async function getStatus(): Promise<HealthResponse> {
  return fetchJson<HealthResponse>("/health");
}
=======
// ── Public API ────────────────────────────────────────────────────────────────
>>>>>>> ce08c01e (local microservices)

export async function getGameState(gameId: string): Promise<GameSnapshot> {
  return fetchJson<GameSnapshot>(`/api/games/${gameId}`);
}

<<<<<<< HEAD
export async function getReplayFrame(
  gameId: string,
  ply: number
): Promise<ReplayFrameResponse> {
  return fetchJson<ReplayFrameResponse>(
    `/api/games/${gameId}/replay?ply=${encodeURIComponent(ply.toString())}`
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
  return fetchJson<NotationTextResponse>(`/api/games/${gameId}/notation/fen`);
=======
export async function getGameState(gameId: string): Promise<GameState> {
  if (useMock) {
    return getMockState();
  }

  const game = await fetchJson<GameResponse>(`/api/games/${gameId}`);
  return mapGameResponseToGameState(game);
}

export async function startNewGame(
  payload: NewGameRequest
): Promise<{ game: GameState; session: SessionContext }> {
  if (useMock) {
    resetMockState();
    return { game: getMockState(), session: mockSession() };
  }

  const response = await fetchJson<CreateSessionResponse>("/api/sessions", {
    method: "POST",
    body: JSON.stringify({
      mode: payload.mode ?? "HumanVsHuman"
    })
  });

  const session: SessionContext = {
    sessionId: response.session.sessionId,
    gameId: response.session.gameId,
    mode: response.session.mode,
    lifecycle: response.session.lifecycle,
    whiteController: response.session.whiteController,
    blackController: response.session.blackController,
    createdAt: response.session.createdAt,
    updatedAt: response.session.updatedAt
  };

  return { game: mapGameResponseToGameState(response.game), session };
}

export async function submitMove(
  gameId: string,
  payload: MoveRequest
): Promise<{ game: GameState; lifecycle: string }> {
  if (useMock) {
    applyMockMove(payload);
    return { game: getMockState(), lifecycle: "active" };
  }

  const body: Record<string, string> = {
    from: payload.from,
    to: payload.to
  };

  if (payload.promotion) {
    body["promotion"] = payload.promotion;
  }

  const response = await fetchJson<SubmitMoveResponse>(
    `/api/games/${gameId}/moves`,
    { method: "POST", body: JSON.stringify(body) }
  );

  return {
    game: mapGameResponseToGameState(response.game),
    lifecycle: response.sessionLifecycle
  };
}

export async function requestAiMove(
  gameId: string
): Promise<{ game: GameState; lifecycle: string }> {
  if (useMock) {
    const from = mockState.activeColor === "black" ? "e7" : "e2";
    const to = mockLegalMoves[from]?.[0] ?? from;
    applyMockMove({ from, to });
    return { game: getMockState(), lifecycle: "active" };
  }

  const response = await fetchJson<SubmitMoveResponse>(
    `/api/games/${gameId}/ai-move`,
    { method: "POST" }
  );

  return {
    game: mapGameResponseToGameState(response.game),
    lifecycle: response.sessionLifecycle
  };
}

export async function undoMove(gameId: string): Promise<GameState> {
  if (useMock) {
    return getMockState();
  }
  const game = await fetchJson<GameResponse>(`/api/games/${gameId}/undo`, {
    method: "POST"
  });
  return mapGameResponseToGameState(game);
}

export async function redoMove(gameId: string): Promise<GameState> {
  if (useMock) {
    return getMockState();
  }
  const game = await fetchJson<GameResponse>(`/api/games/${gameId}/redo`, {
    method: "POST"
  });
  return mapGameResponseToGameState(game);
}

export async function exportPgn(): Promise<{ pgn: string }> {
  if (useMock) {
    return { pgn: "1. e4 e5 2. Nf3 Nc6 3. Bb5 a6" };
  }
  throw new Error("PGN export is not supported in server mode.");
}

export async function getLegalMoves(
  gameId: string,
  from: string
): Promise<LegalMovesResponse> {
  if (useMock) {
    const moves = mockLegalMoves[from] ?? [];
    return { from, moves };
  }

  const game = await fetchJson<GameResponse>(`/api/games/${gameId}`);
  const moves = game.legalTargetsByFrom[from] ?? [];
  return { from, moves };
}

// ── Mock implementation ───────────────────────────────────────────────────────

function mockSession(): SessionContext {
  return {
    sessionId: "mock-session",
    gameId: "mock-game",
    mode: "human_vs_human",
    lifecycle: "active",
    whiteController: "human",
    blackController: "human",
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString()
  };
}

function mockGameState(): GameState {
  const emptyRow = Array.from({ length: 8 }, () => null);
  const board = [
    ["bR", "bN", "bB", "bQ", "bK", "bB", "bN", "bR"],
    Array.from({ length: 8 }, () => "bP"),
    [...emptyRow],
    [...emptyRow],
    [...emptyRow],
    [...emptyRow],
    Array.from({ length: 8 }, () => "wP"),
    ["wR", "wN", "wB", "wQ", "wK", "wB", "wN", "wR"]
  ] as BoardMatrix;

  return {
    id: "mock-game",
    board,
    activeColor: "white",
    status: "active",
    winner: undefined,
    drawReason: undefined,
    fullMove: 1,
    halfMoveClock: 0,
    moves: [],
    captured: [],
    legalTargetsByFrom: mockLegalMoves
  };
}

let mockState: GameState = mockGameState();

function getMockState(): GameState {
  return {
    ...mockState,
    board: cloneBoard(mockState.board),
    moves: [...mockState.moves],
    captured: [...mockState.captured],
    legalTargetsByFrom: { ...mockState.legalTargetsByFrom }
  };
>>>>>>> ce08c01e (local microservices)
}

export async function exportPgn(gameId: string): Promise<NotationTextResponse> {
  return fetchJson<NotationTextResponse>(`/api/games/${gameId}/notation/pgn`);
}

export async function createGame(
  payload: CreateGameRequest
): Promise<CreateGameResponse> {
  return fetchJson<CreateGameResponse>(createGamePathForMode(payload.mode), {
    method: "POST",
    body: JSON.stringify(payload)
  });
}

<<<<<<< HEAD
function createGamePathForMode(mode?: CreateGameRequest["mode"]): string {
  switch (mode) {
    case "HumanVsAI":
      return "/api/sessions/human-vs-ai";
    case "AIVsAI":
      return "/api/sessions/ai-vs-ai";
    case "HumanVsHuman":
    default:
      return "/api/sessions/human-vs-human";
  }
}

export async function importGameFromNotation(
  payload: ImportNotationRequest
): Promise<CreateGameResponse> {
  return importNotation(payload);
=======
function cloneBoard(board: GameState["board"]): GameState["board"] {
  return board.map((row) => [...row]);
>>>>>>> ce08c01e (local microservices)
}

<<<<<<< HEAD
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
=======
const mockLegalMoves: Record<string, string[]> = {
  e2: ["e3", "e4"],
  d2: ["d3", "d4"],
  g1: ["f3", "h3"],
  b1: ["a3", "c3"],
  e7: ["e6", "e5"],
  d7: ["d6", "d5"],
  g8: ["f6", "h6"],
  b8: ["a6", "c6"]
};
>>>>>>> abcc8c8c (envoy + ai service prerp)
