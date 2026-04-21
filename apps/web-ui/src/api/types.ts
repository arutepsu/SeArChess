export type PlayerColor = "white" | "black";
export type GameStatus = "active" | "check" | "checkmate" | "draw" | "resigned";
export type SessionMode = "HumanVsHuman" | "HumanVsAI";

export type PieceCode =
  | "wK"
  | "wQ"
  | "wR"
  | "wB"
  | "wN"
  | "wP"
  | "bK"
  | "bQ"
  | "bR"
  | "bB"
  | "bN"
  | "bP";

export type BoardSquare = PieceCode | null;
export type BoardMatrix = BoardSquare[][];

export interface MoveRecord {
  ply: number;
  notation: string;
  from: string;
  to: string;
  captured?: PieceCode;
  promotion?: PieceCode;
}

export interface GameState {
  id: string;
  board: BoardMatrix;
  activeColor: PlayerColor;
  status: GameStatus;
  winner?: PlayerColor;
  drawReason?: string;
  fullMove: number;
  halfMoveClock: number;
  lastMove?: MoveRecord;
  moves: MoveRecord[];
  captured: PieceCode[];
  message?: string;
  legalTargetsByFrom: Record<string, string[]>;
}

export interface NewGameRequest {
  mode?: SessionMode;
  startingFen?: string;
}

export interface MoveRequest {
  from: string;
  to: string;
  promotion?: string;
}

export interface LegalMovesResponse {
  from: string;
  moves: string[];
}

export interface ApiStatus {
  ok: boolean;
  serviceName: string;
  version: string;
}
