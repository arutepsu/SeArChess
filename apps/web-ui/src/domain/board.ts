/**
 * Single source of truth for chess square and board coordinate math.
 * Pure TypeScript — no React, no API, no DOM.
 *
 * Coordinate systems used in this app:
 *
 *   Board array:    board[row][col]
 *                   row 0 = rank 8 (back rank), row 7 = rank 1
 *                   col 0 = file a,             col 7 = file h
 *
 *   Display grid:   the HTML grid rendered by ChessBoard
 *                   rotated 90° clockwise vs the board array to match
 *                   the desktop GUI isometric projection
 *                   displayRow 0 = top of screen, displayCol 0 = left of screen
 *                   (0,0) → h1   (0,7) → h8   (7,0) → a1   (7,7) → a8
 *
 *   Pixel position: CSS left = displayCol × squareSize
 *                   CSS top  = displayRow × squareSize
 *                   squareSize is a runtime measurement kept in ChessBoard state
 */

export const FILES = "abcdefgh";

// ── Square ↔ board-array index ────────────────────────────────────────────────

/**
 * Parse a UCI square string (e.g. "e4") into board-array coordinates.
 * Returns null for any invalid input.
 */
export function squareToIndex(square: string): { row: number; col: number } | null {
  if (square.length !== 2) return null;
  const col = FILES.indexOf(square[0].toLowerCase());
  const rank = Number(square[1]);
  if (col < 0 || Number.isNaN(rank) || rank < 1 || rank > 8) return null;
  return { row: 8 - rank, col };
}

/**
 * Convert board-array coordinates back to a UCI square string.
 * Inverse of squareToIndex; does not validate bounds.
 */
export function indexToSquare(row: number, col: number): string {
  return `${FILES[col]}${8 - row}`;
}

// ── Display grid ↔ board array ────────────────────────────────────────────────

/**
 * Map display grid indices to board-array coordinates.
 * The display is rotated 90° clockwise relative to the board array.
 */
export function displayToIndex(
  rowIndex: number,
  colIndex: number
): { row: number; col: number } {
  return { row: 7 - colIndex, col: 7 - rowIndex };
}

/**
 * Map display grid indices to a UCI square string.
 */
export function displayToSquare(rowIndex: number, colIndex: number): string {
  const { row, col } = displayToIndex(rowIndex, colIndex);
  return indexToSquare(row, col);
}

// ── Square → display pixel grid ───────────────────────────────────────────────

/**
 * Map a UCI square to display-grid coordinates (unit grid, not pixels).
 * Multiply displayCol by squareSize to get CSS left; displayRow for CSS top.
 * Returns null for invalid squares.
 *
 * Consistent with displayToSquare: displayToSquare(displayRow, displayCol)
 * round-trips back to the original square.
 */
export function squareToDisplayCoords(
  square: string
): { displayRow: number; displayCol: number } | null {
  if (square.length !== 2) return null;
  const fileIndex = FILES.indexOf(square[0].toLowerCase());
  const rank = Number(square[1]);
  if (fileIndex < 0 || Number.isNaN(rank) || rank < 1 || rank > 8) return null;
  return {
    displayCol: rank - 1,       // rank 1 → col 0, rank 8 → col 7
    displayRow: 7 - fileIndex   // file a → row 7, file h → row 0
  };
}

// ── Board piece access ────────────────────────────────────────────────────────

/**
 * Read the piece at a UCI square from a board matrix.
 * Returns null if the square is invalid or unoccupied.
 */
export function pieceAt<T>(board: (T | null)[][], square: string): T | null {
  const pos = squareToIndex(square);
  if (!pos) return null;
  return board[pos.row]?.[pos.col] ?? null;
}
