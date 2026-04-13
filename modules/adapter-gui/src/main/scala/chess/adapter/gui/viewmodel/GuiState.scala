package chess.adapter.gui.viewmodel

import chess.domain.model.{GameStatus, Position}

/** Explicit GUI state machine.
 *
 *  WaitingForSelection — no piece is selected; the board accepts piece clicks.
 *  PieceSelected       — one piece is selected; legal target squares are highlighted.
 *  AwaitingPromotion   — a pawn reached the last rank; the promotion overlay is active.
 *                        The `from` and `to` positions of the pending move are stored
 *                        so the controller can submit the full move with a promotion piece.
 *  Animating           — a move animation is running; all board input is blocked.
 *  GameFinished        — the game ended; board interaction is disabled.
 */
enum GuiState:
  case WaitingForSelection
  case PieceSelected(from: Position, legalTargets: Set[Position])
  case AwaitingPromotion(from: Position, to: Position)
  case Animating
  case GameFinished(status: GameStatus)
