import type { GameResponse, MoveHistoryEntryDto } from "./backendTypes";
import type {
  BoardMatrix,
  GameState,
  GameStatus,
  MoveRecord,
  PieceCode,
  PlayerColor
} from "./types";

const FILES = "abcdefgh";

function squareToPosition(square: string): { row: number; col: number } | null {
  if (square.length !== 2) return null;
  const col = FILES.indexOf(square[0].toLowerCase());
  const rank = Number(square[1]);
  if (col < 0 || Number.isNaN(rank) || rank < 1 || rank > 8) return null;
  return { row: 8 - rank, col };
}

function mapPieceCode(color: string, pieceType: string): PieceCode | null {
  const prefix = color === "White" ? "w" : color === "Black" ? "b" : null;
  if (!prefix) return null;

  const letterMap: Record<string, string> = {
    King: "K",
    Queen: "Q",
    Rook: "R",
    Bishop: "B",
    Knight: "N",
    Pawn: "P"
  };

  const letter = letterMap[pieceType];
  if (!letter) return null;

  return `${prefix}${letter}` as PieceCode;
}

function mapBoard(pieces: GameResponse["board"]): BoardMatrix {
  const board: BoardMatrix = Array.from({ length: 8 }, () =>
    Array(8).fill(null)
  );

  for (const piece of pieces) {
    const pos = squareToPosition(piece.square);
    if (!pos) continue;
    const code = mapPieceCode(piece.color, piece.pieceType);
    if (code !== null) board[pos.row][pos.col] = code;
  }

  return board;
}

function mapStatus(status: string, inCheck: boolean): GameStatus {
  if (status === "Checkmate") return "checkmate";
  if (status === "Draw") return "stalemate";
  return inCheck ? "check" : "active";
}

function mapMoveHistoryEntry(entry: MoveHistoryEntryDto, ply: number): MoveRecord {
  const notation = entry.promotion
    ? `${entry.from}${entry.to}${entry.promotion[0].toLowerCase()}`
    : `${entry.from}${entry.to}`;

  const record: MoveRecord = {
    ply,
    notation,
    from: entry.from,
    to: entry.to
  };

  if (entry.promotion) {
    const backendColor = ply % 2 === 1 ? "White" : "Black";
    const code = mapPieceCode(backendColor, entry.promotion);
    if (code !== null) record.promotion = code;
  }

  return record;
}

export function mapGameResponseToGameState(game: GameResponse): GameState {
  const moves = game.moveHistory.map((entry, index) =>
    mapMoveHistoryEntry(entry, index + 1)
  );

  return {
    id: game.gameId,
    board: mapBoard(game.board),
    activeColor: game.currentPlayer.toLowerCase() as PlayerColor,
    status: mapStatus(game.status, game.inCheck),
    fullMove: game.fullmoveNumber,
    halfMoveClock: game.halfmoveClock,
    lastMove: moves.length > 0 ? moves[moves.length - 1] : undefined,
    moves,
    captured: [],
    legalTargetsByFrom: game.legalTargetsByFrom
  };
}