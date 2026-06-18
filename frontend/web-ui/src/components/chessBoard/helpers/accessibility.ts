import type { BoardSquare } from "../../../api/types";
import { pieceDescriptor } from "../types";

export function squareAriaLabel(square: string, piece: BoardSquare): string {
  if (!piece) return `${square}: empty`;
  const desc = pieceDescriptor(piece);
  if (!desc) return square;
  return `${square}: ${desc.color} ${desc.name}`;
}
