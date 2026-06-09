import type { BoardMatrix, BoardSquare } from "../api/types";

const FEN_PIECE: Record<string, BoardSquare> = {
  K: "wK", Q: "wQ", R: "wR", B: "wB", N: "wN", P: "wP",
  k: "bK", q: "bQ", r: "bR", b: "bB", n: "bN", p: "bP",
};

/**
 * Parse the board-placement section of a FEN string into a BoardMatrix.
 *
 * Board array convention (matches domain/board.ts):
 *   row 0 = rank 8, row 7 = rank 1
 *   col 0 = file a, col 7 = file h
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
