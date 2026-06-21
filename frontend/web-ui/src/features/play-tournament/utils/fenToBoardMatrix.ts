import { Chess } from "chess.js";
import type { Square, ShortMove } from "chess.js";
import type { BoardMatrix } from "../../../api/types";
import { fenToBoardMatrix as domainFenToBoardMatrix } from "../../../domain/fen";

export const STANDARD_OPENING_FEN =
  "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

/** Safe wrapper: returns an empty board instead of null for invalid FEN. */
export function fenToBoardMatrix(fen: string): BoardMatrix {
  return domainFenToBoardMatrix(fen) ?? emptyBoard();
}

/** Parse whose turn it is from a FEN string. */
export function fenActiveColor(fen: string): "white" | "black" {
  const parts = fen.split(" ");
  return parts[1] === "b" ? "black" : "white";
}

/** Apply a UCI move to a FEN and return the resulting FEN.
 *  Returns null if the move is illegal or the FEN is invalid.
 */
export function applyUciMove(fen: string, uci: string): string | null {
  try {
    const chess = new Chess(fen);
    const mv: ShortMove = {
      from: uci.slice(0, 2) as Square,
      to: uci.slice(2, 4) as Square,
      ...(uci.length > 4 ? { promotion: uci[4] as ShortMove["promotion"] } : {}),
    };
    const move = chess.move(mv);
    return move ? chess.fen() : null;
  } catch {
    return null;
  }
}

function emptyBoard(): BoardMatrix {
  return Array.from({ length: 8 }, () => Array<null>(8).fill(null));
}
