import type {
  Color,
  GameSnapshot,
  GameStatus as BackendGameStatus,
  MoveHistoryEntryDto,
  PieceType
} from "./backendTypes";
import type {
  BoardMatrix,
  GameState,
  GameStatus,
  MoveRecord,
  PieceCode,
  PlayerColor
} from "./types";
import { squareToIndex } from "../domain/board";

function mapPieceCode(color: Color, pieceType: PieceType): PieceCode | null {
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

function mapBoard(pieces: GameSnapshot["board"]): BoardMatrix {
  const board: BoardMatrix = Array.from({ length: 8 }, () =>
    Array(8).fill(null)
  );

  for (const piece of pieces) {
    const pos = squareToIndex(piece.square);
    if (!pos) continue;
    const code = mapPieceCode(piece.color, piece.pieceType);
    if (code !== null) board[pos.row][pos.col] = code;
  }

  return board;
}

function mapStatus(status: BackendGameStatus, inCheck: boolean): GameStatus {
  if (status === "Checkmate") return "checkmate";
  if (status === "Draw") return "draw";
  if (status === "Resigned") return "resigned";
  return inCheck ? "check" : "active";
}

function mapColor(color: Color | null): PlayerColor | undefined {
  if (color === "White") return "white";
  if (color === "Black") return "black";
  return undefined;
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

function computeCapturedPieces(board: BoardMatrix): PieceCode[] {
  const initialCounts: Record<PieceCode, number> = {
    wK: 1,
    wQ: 1,
    wR: 2,
    wB: 2,
    wN: 2,
    wP: 8,
    bK: 1,
    bQ: 1,
    bR: 2,
    bB: 2,
    bN: 2,
    bP: 8
  };

  const presentCounts: Record<PieceCode, number> = {
    wK: 0,
    wQ: 0,
    wR: 0,
    wB: 0,
    wN: 0,
    wP: 0,
    bK: 0,
    bQ: 0,
    bR: 0,
    bB: 0,
    bN: 0,
    bP: 0
  };

  for (const row of board) {
    for (const square of row) {
      if (square) presentCounts[square] += 1;
    }
  }

  const order: PieceCode[] = [
    "wQ",
    "wR",
    "wB",
    "wN",
    "wP",
    "bQ",
    "bR",
    "bB",
    "bN",
    "bP"
  ];

  const captured: PieceCode[] = [];
  for (const code of order) {
    const missing = Math.max(0, initialCounts[code] - presentCounts[code]);
    for (let i = 0; i < missing; i += 1) {
      captured.push(code);
    }
  }

  return captured;
}

export function mapGameSnapshotToGameState(game: GameSnapshot): GameState {
  const moves = game.moveHistory.map((entry, index) =>
    mapMoveHistoryEntry(entry, index + 1)
  );

  const board = mapBoard(game.board);
  const captured = computeCapturedPieces(board);

  return {
    id: game.gameId,
    board,
    activeColor: game.currentPlayer.toLowerCase() as PlayerColor,
    status: mapStatus(game.status, game.inCheck),
    winner: mapColor(game.winner),
    drawReason: game.drawReason ?? undefined,
    fullMove: game.fullmoveNumber,
    halfMoveClock: game.halfmoveClock,
    lastMove: moves.length > 0 ? moves[moves.length - 1] : undefined,
    moves,
    captured,
    legalTargetsByFrom: game.legalTargetsByFrom
  };
}
