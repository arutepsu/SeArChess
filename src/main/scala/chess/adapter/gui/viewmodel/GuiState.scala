package chess.adapter.gui.viewmodel

import chess.domain.model.{GameStatus, Position}

/** Explicit GUI state machine.
 *
 *  WaitingForSelection — no piece is selected; the board accepts piece clicks.
 *  PieceSelected       — one piece is selected; legal target squares are highlighted.
 *  AwaitingPromotion   — a pawn reached the last rank; the promotion overlay is active.
 *  Animating           — a move animation is running; all board input is blocked.
 *  GameFinished        — the game ended; board interaction is disabled.
 */
enum GuiState:
  case WaitingForSelection
  case PieceSelected(from: Position, legalTargets: Set[Position])
  case AwaitingPromotion
  case Animating
  case GameFinished(status: GameStatus)
