import type {
  ApiStatus,
  GameState,
  LegalMovesResponse,
  MoveRequest,
  NewGameRequest
} from "./types";

const DEFAULT_API_BASE = "http://localhost:8080";

export const apiBaseUrl =
  import.meta.env.VITE_API_BASE_URL?.toString() || DEFAULT_API_BASE;

const useMock = import.meta.env.VITE_API_MOCK === "true";

async function fetchJson<T>(path: string, options?: RequestInit): Promise<T> {
  const response = await fetch(`${apiBaseUrl}${path}`, {
    headers: { "Content-Type": "application/json" },
    ...options
  });

  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || `Request failed: ${response.status}`);
  }

  return (await response.json()) as T;
}

export async function getStatus(): Promise<ApiStatus> {
  if (useMock) {
    return { ok: true, serviceName: "SeArChess Mock", version: "0.0.0" };
  }
  return fetchJson<ApiStatus>("/api/status");
}

export async function getGameState(): Promise<GameState> {
  if (useMock) {
    return getMockState();
  }
  return fetchJson<GameState>("/api/game");
}

export async function startNewGame(payload: NewGameRequest): Promise<GameState> {
  if (useMock) {
    resetMockState();
    return getMockState();
  }
  return fetchJson<GameState>("/api/game/new", {
    method: "POST",
    body: JSON.stringify(payload)
  });
}

export async function submitMove(payload: MoveRequest): Promise<GameState> {
  if (useMock) {
    applyMockMove(payload);
    return getMockState();
  }
  return fetchJson<GameState>("/api/game/move", {
    method: "POST",
    body: JSON.stringify(payload)
  });
}

export async function undoMove(): Promise<GameState> {
  if (useMock) {
    return getMockState();
  }
  return fetchJson<GameState>("/api/game/undo", {
    method: "POST" }
  );
}

export async function redoMove(): Promise<GameState> {
  if (useMock) {
    return getMockState();
  }
  return fetchJson<GameState>("/api/game/redo", {
    method: "POST" }
  );
}

export async function exportPgn(): Promise<{ pgn: string }> {
  if (useMock) {
    return { pgn: "1. e4 e5 2. Nf3 Nc6 3. Bb5 a6" };
  }
  return fetchJson<{ pgn: string }>("/api/game/notation?format=pgn");
}

export async function getLegalMoves(from: string): Promise<LegalMovesResponse> {
  if (useMock) {
    const moves = mockLegalMoves[from] ?? [];
    return { from, moves };
  }
  const query = new URLSearchParams({ from }).toString();
  return fetchJson<LegalMovesResponse>(`/api/game/legal?${query}`);
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
  ];

  return {
    id: "mock-game",
    board,
    activeColor: "white",
    status: "active",
    fullMove: 1,
    halfMoveClock: 0,
    moves: [],
    captured: []
  };
}

let mockState: GameState = mockGameState();

function getMockState(): GameState {
  return {
    ...mockState,
    board: cloneBoard(mockState.board),
    moves: [...mockState.moves],
    captured: [...mockState.captured]
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

function squareToIndex(square: string): { row: number; col: number } | null {
  if (square.length !== 2) return null;
  const files = "abcdefgh";
  const file = square[0];
  const rank = Number(square[1]);
  const col = files.indexOf(file);
  if (col < 0 || Number.isNaN(rank) || rank < 1 || rank > 8) return null;
  return { row: 8 - rank, col };
}

function cloneBoard(board: GameState["board"]): GameState["board"] {
  return board.map((row) => [...row]);
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
