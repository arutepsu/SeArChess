import type { BoardMatrix, BoardSquare } from "../api/types";
import { squareToIndex } from "./board";

const FEN_PIECE: Record<string, BoardSquare> = {
  K: "wK", Q: "wQ", R: "wR", B: "wB", N: "wN", P: "wP",
  k: "bK", q: "bQ", r: "bR", b: "bB", n: "bN", p: "bP",
};

/**
 * Parse the board-placement section of a FEN string into a BoardMatrix.
 *
 * Board array convention (matches domain/board.ts):
 *   row 0 = rank 8 (back rank), row 7 = rank 1
 *   col 0 = file a, col 7 = file h
 *
 * Starting FEN produces:
 *   a8 = bR  →  board[0][0]
 *   d8 = bQ  →  board[0][3]
 *   e8 = bK  →  board[0][4]
 *   a1 = wR  →  board[7][0]
 *   d1 = wQ  →  board[7][3]
 *   e1 = wK  →  board[7][4]
 *   e2 = wP  →  board[6][4]
 *   e7 = bP  →  board[1][4]
 *
 * Returns null for any invalid FEN placement or unrecognised characters.
 */
export function fenToBoardMatrix(fen: string): BoardMatrix | null {
  const placement = fen.trim().split(/\s+/)[0];
  if (!placement) return null;

  const ranks = placement.split("/");
  if (ranks.length !== 8) return null;

  const board: BoardMatrix = Array.from({ length: 8 }, () =>
    Array.from({ length: 8 }, (): null => null)
  );

  for (let row = 0; row < 8; row++) {
    let col = 0;
    for (const ch of ranks[row]) {
      if (col > 7) return null;
      const digit = parseInt(ch, 10);
      if (!isNaN(digit)) {
        if (digit < 1 || digit > 8) return null;
        col += digit;
      } else {
        const piece = FEN_PIECE[ch];
        if (piece === undefined) return null;
        board[row][col] = piece;
        col++;
      }
    }
    if (col !== 8) return null;
  }

  return board;
}

if (import.meta.env.DEV) {
  const STARTING_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
  const devBoard = fenToBoardMatrix(STARTING_FEN);
  if (devBoard === null) {
    console.error("[fenToBoardMatrix] starting FEN returned null — coordinate mapping is broken");
  } else {
    const checks: Array<[string, string]> = [
      ["a8", "bR"], ["d8", "bQ"], ["e8", "bK"],
      ["a1", "wR"], ["d1", "wQ"], ["e1", "wK"],
      ["e2", "wP"], ["e7", "bP"],
    ];
    for (const [square, expected] of checks) {
      const idx = squareToIndex(square);
      const found = idx !== null ? (devBoard[idx.row][idx.col] ?? "empty") : "invalid-square";
      if (found !== expected) {
        console.error(`[fenToBoardMatrix] ${square}: expected ${expected}, got ${String(found)}`);
      }
    }
  }
}
