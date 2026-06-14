import type { BoardSquare, PieceCode } from "../../api/types";

export const PIECE_SCALE = 2;

export type SpriteState = "idle" | "move";

export type SpriteInfo = {
  url: string;
  frameCount: number;
  durationMs: number;
  animate: boolean;
};

export type VisualState = "idle" | "move" | "attack" | "attack1" | "dead" | "hit";

export type MotionStyle =
  | { kind: "linear" }
  | { kind: "smooth" }
  | { kind: "heavy" }
  | { kind: "arc"; heightFraction: number }
  | { kind: "attack"; overshootFraction: number };

export type AnimatedPieceModel = {
  piece: PieceCode;
  x: number;
  y: number;
  opacity: number;
  scale?: number;
  flipX: boolean;
  visualState: VisualState;
  frameIndex: number;
  sheet: { url: string; frameCount: number } | null;
};

export type AnimationRenderModel = {
  moving: AnimatedPieceModel;
  captured: AnimatedPieceModel | null;
  castlingRook: AnimatedPieceModel | null;
};

export const clamp = (value: number, min: number, max: number) =>
  Math.max(min, Math.min(max, value));

export const pieceTransform = (flipX: boolean, scale: number = PIECE_SCALE): string =>
  `scaleX(${flipX ? -1 : 1}) scale(${scale})`;

export function pieceLabel(piece: BoardSquare): string {
  if (!piece) return "";
  const map: Record<string, string> = {
    wK: "K", wQ: "Q", wR: "R", wB: "B", wN: "N", wP: "P",
    bK: "K", bQ: "Q", bR: "R", bB: "B", bN: "N", bP: "P",
  };
  return map[piece] ?? "?";
}

export function pieceDescriptor(piece: BoardSquare): { color: "white" | "black"; name: string } | null {
  if (!piece) return null;
  const color = piece.startsWith("w") ? "white" : "black";
  const map: Record<string, string> = {
    wK: "king", wQ: "queen", wR: "rook", wB: "bishop", wN: "knight", wP: "pawn",
    bK: "king", bQ: "queen", bR: "rook", bB: "bishop", bN: "knight", bP: "pawn",
  };
  return { color, name: map[piece] ?? "pawn" };
}

export function pieceType(piece: PieceCode): string {
  return pieceDescriptor(piece)?.name ?? "pawn";
}

export function pieceTone(piece: BoardSquare): string {
  if (!piece) return "";
  return piece.startsWith("w") ? "piece-light" : "piece-dark";
}

export function assetKeyFor(piece: PieceCode, state: VisualState): string {
  const descriptor = pieceDescriptor(piece);
  if (!descriptor) return "";
  return `classic/${descriptor.color}_${descriptor.name}_${state}`;
}
