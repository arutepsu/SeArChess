import type {
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
  HealthResponse,
  SubmitMoveResponse
} from "./backendTypes";
import { mapGameResponseToGameState } from "./mapper";
import type { ErrorResponse } from "./backendTypes";
import type { SessionContext } from "../session/sessionStore";

const DEFAULT_API_BASE = "http://localhost:10000";

export const apiBaseUrl =
  import.meta.env.VITE_API_BASE_URL?.toString() || DEFAULT_API_BASE;

const useMock = import.meta.env.VITE_API_MOCK === "true";

// ── HTTP helper ───────────────────────────────────────────────────────────────

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

// ── Public API ────────────────────────────────────────────────────────────────

export async function getStatus(): Promise<ApiStatus> {
  if (useMock) {
    return { ok: true, serviceName: "SeArChess Mock", version: "0.0.0" };
  }

  const health = await fetchJson<HealthResponse>("/health");
  return {
    ok: health.status === "ok",
    serviceName: "SeArChess",
    version: "0.1.0"
  };
}

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
}

function resetMockState(): void {
  mockState = mockGameState();
}

function applyMockMove(payload: MoveRequest): void {
  const from = payload.from.toLowerCase();
  const to = payload.to.toLowerCase();
  const fromPos = squareToIndex(from);
  const toPos = squareToIndex(to);
  if (!fromPos || !toPos) return;

  const board = cloneBoard(mockState.board);
  const moving = board[fromPos.row]?.[fromPos.col] ?? null;
  if (!moving) return;

  const captured = board[toPos.row]?.[toPos.col] ?? null;
  board[fromPos.row][fromPos.col] = null;
  board[toPos.row][toPos.col] = moving;

  const ply = mockState.moves.length + 1;
  const notation = `${from}${to}`;
  const moveRecord = {
    ply,
    notation,
    from,
    to,
    captured: captured ?? undefined
  };

  mockState = {
    ...mockState,
    board,
    activeColor: mockState.activeColor === "white" ? "black" : "white",
    fullMove: mockState.fullMove + (mockState.activeColor === "black" ? 1 : 0),
    halfMoveClock: 0,
    lastMove: moveRecord,
    moves: [...mockState.moves, moveRecord],
    captured: captured ? [...mockState.captured, captured] : mockState.captured
  };
}

function cloneBoard(board: GameState["board"]): GameState["board"] {
  return board.map((row) => [...row]);
}

