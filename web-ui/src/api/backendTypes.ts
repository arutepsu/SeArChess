export interface MoveHistoryEntryDto {
  from: string;
  to: string;
  promotion: string | null;
}

export interface PieceDto {
  square: string;
  color: string;
  pieceType: string;
}

export interface GameResponse {
  gameId: string;
  currentPlayer: string;
  status: string;
  inCheck: boolean;
  winner: string | null;
  drawReason: string | null;
  fullmoveNumber: number;
  halfmoveClock: number;
  board: PieceDto[];
  moveHistory: MoveHistoryEntryDto[];
  lastMove: MoveHistoryEntryDto | null;
  promotionPending: boolean;
  legalTargetsByFrom: Record<string, string[]>;
}

export interface SessionResponse {
  sessionId: string;
  gameId: string;
  mode: string;
  lifecycle: string;
  whiteController: string;
  blackController: string;
  createdAt: string;
  updatedAt: string;
}

export interface CreateSessionResponse {
  session: SessionResponse;
  game: GameResponse;
}

export interface SubmitMoveResponse {
  game: GameResponse;
  sessionLifecycle: string;
}

export interface HealthResponse {
  status: string;
  mode: string;
}

export interface ErrorResponse {
  code: string;
  message: string;
}