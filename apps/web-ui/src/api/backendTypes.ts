export type Color = "White" | "Black";
export type GameStatus = "Ongoing" | "Checkmate" | "Draw" | "Resigned";
export type SessionMode = "HumanVsHuman" | "HumanVsAI" | "AIVsAI";
export type SessionLifecycle =
  | "Created"
  | "Active"
  | "AwaitingPromotion"
  | "Finished"
  | "Cancelled";
export type InboundController = "HumanLocal" | "HumanRemote";
export type OutboundController = "HumanLocal" | "HumanRemote" | "AI";
export type PieceType = "King" | "Queen" | "Rook" | "Bishop" | "Knight" | "Pawn";
export type PromotionPiece = "Queen" | "Rook" | "Bishop" | "Knight";
export type NotationFormat = "FEN" | "PGN";

export interface MoveHistoryEntryDto {
  from: string;
  to: string;
  promotion: PromotionPiece | null;
}

export interface PieceDto {
  square: string;
  color: Color;
  pieceType: PieceType;
}

export interface GameSnapshot {
  gameId: string;
  currentPlayer: Color;
  status: GameStatus;
  inCheck: boolean;
  winner: Color | null;
  drawReason: string | null;
  fullmoveNumber: number;
  halfmoveClock: number;
  board: PieceDto[];
  moveHistory: MoveHistoryEntryDto[];
  lastMove: MoveHistoryEntryDto | null;
  promotionPending: boolean;
  legalTargetsByFrom: Record<string, string[]>;
}

export interface CreateGameRequest {
  mode?: SessionMode;
  whiteController?: InboundController;
  blackController?: InboundController;
}

export interface ImportNotationRequest {
  format: NotationFormat;
  notation: string;
  mode?: SessionMode;
  whiteController?: InboundController;
  blackController?: InboundController;
}

export interface SubmitMoveRequest {
  from: string;
  to: string;
  promotion?: PromotionPiece | null;
  controller?: InboundController | null;
}

export interface ResignRequest {
  side: Color;
}

export interface SessionResponse {
  sessionId: string;
  gameId: string;
  mode: SessionMode;
  lifecycle: SessionLifecycle;
  whiteController: OutboundController;
  blackController: OutboundController;
  createdAt: string;
  updatedAt: string;
}

export interface CreateGameResponse {
  session: SessionResponse;
  game: GameSnapshot;
}

export interface CommandGameResponse {
  game: GameSnapshot;
  sessionLifecycle: SessionLifecycle;
}

export interface GameNotationResponse {
  fen: string;
  pgn: string;
}

export interface HealthResponse {
  status: string;
  mode: string;
}

export interface ErrorResponse {
  code: string;
  message: string;
}
