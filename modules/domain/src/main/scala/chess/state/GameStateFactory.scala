package chess.domain.state

import chess.domain.model.{Board, Color}
import chess.domain.rules.evaluation.GameStatusEvaluator

object GameStateFactory:

  def initial(): GameState =
    val board = Board.initial
    GameState(
      board = board,
      currentPlayer = Color.White,
      moveHistory = Nil,
      status = GameStatusEvaluator.evaluate(board, Color.White, CastlingRights.full),
      castlingRights = CastlingRights.full,
      enPassantState = None,
      halfmoveClock = 0,
      fullmoveNumber = 1
    )
