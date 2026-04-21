import type { PieceCode } from "../api/types";

/**
 * Describes a single move to animate on the board.
 *
 * This is the planner's output and the renderer's input.
 * It answers WHAT to animate; ChessBoard decides HOW to render it.
 *
 * `id` is a monotonically increasing counter owned by the caller (App).
 * ChessBoard uses it to detect when a new plan has arrived and to guard
 * against clearing a newer animation when an older one finishes.
 */
export type BoardAnimation = {
  id: number;
  from: string;
  to: string;
  movingPiece: PieceCode;
  capturedPiece?: PieceCode;
  isCapture: boolean;
};
