import type { BoardMatrix, GameState } from "../api/types";
import { pieceAt } from "../domain/board";
import type { BoardAnimation } from "./animationTypes";

/**
 * Pure function: given the board state BEFORE a move and the game state AFTER,
 * produce a BoardAnimation describing what to play, or null if nothing should
 * be animated (no last move, moving piece not found, etc.).
 *
 * `nextId` is supplied by the caller and becomes BoardAnimation.id.
 * The caller (App) owns the counter; this function never mutates state.
 *
 * Uses pieceAt from domain/board to read the previous board — the piece that
 * was on `from` before the move is the one that must be animated.
 * The piece on `to` before the move (if any) is the captured piece.
 */
export function planAnimation(
  prevBoard: BoardMatrix,
  nextGame: GameState | undefined,
  nextId: number
): BoardAnimation | null {
  if (!nextGame?.lastMove) return null;

  const { from, to } = nextGame.lastMove;

  const movingPiece = pieceAt(prevBoard, from);
  if (!movingPiece) return null;

  const capturedPiece = pieceAt(prevBoard, to) ?? undefined;

  return {
    id: nextId,
    from,
    to,
    movingPiece,
    capturedPiece,
    isCapture: Boolean(capturedPiece)
  };
}
