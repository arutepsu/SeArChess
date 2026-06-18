import type { BoardMatrix, GameState, PieceCode } from "../api/types";
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

  let capturedPiece = pieceAt(prevBoard, to) ?? undefined;
  let capturedSquare: string | undefined = undefined;
  let isCapture = Boolean(capturedPiece);

  // 1. Detect En Passant capture
  if (movingPiece.endsWith("P") && from[0] !== to[0] && !capturedPiece) {
    const epSquare = `${to[0]}${from[1]}`;
    const epPiece = pieceAt(prevBoard, epSquare);
    if (epPiece && epPiece.endsWith("P") && epPiece[0] !== movingPiece[0]) {
      capturedPiece = epPiece;
      capturedSquare = epSquare;
      isCapture = true;
    }
  }

  // 2. Detect Castling
  let castlingRook: { from: string; to: string; piece: PieceCode } | undefined = undefined;
  if (movingPiece.endsWith("K") && Math.abs(from.charCodeAt(0) - to.charCodeAt(0)) === 2) {
    const rank = from[1]; // "1" or "8"
    const color = movingPiece[0] as "w" | "b";
    const isKingSide = to[0] === "g";
    const rookFrom = isKingSide ? `h${rank}` : `a${rank}`;
    const rookTo = isKingSide ? `f${rank}` : `d${rank}`;
    castlingRook = {
      from: rookFrom,
      to: rookTo,
      piece: `${color}R` as PieceCode
    };
  }

  return {
    id: nextId,
    from,
    to,
    movingPiece,
    capturedPiece,
    capturedSquare,
    isCapture,
    castlingRook
  };
}
