package chess.application

import chess.application.ApplicationError.*
import chess.domain.model.{Board, Color, GameStatus, Move}
import chess.domain.rules.{GameStatusEvaluator, MoveApplier}

object ChessService:

  def createNewGame(): GameState =
    val board = Board.initial
    GameState(
      board         = board,
      currentPlayer = Color.White,
      moveHistory   = Nil,
      status        = GameStatusEvaluator.evaluate(board, Color.White)
    )

  def applyMove(state: GameState, move: Move): Either[ApplicationError, GameState] =
    state.board.pieceAt(move.from) match
      case Some(piece) if piece.color != state.currentPlayer =>
        Left(NotPlayersTurn)
      case _ =>
        MoveApplier.applyMove(state.board, move)
          .map { newBoard =>
            val nextPlayer = opponent(state.currentPlayer)
            val nextStatus = GameStatusEvaluator.evaluate(newBoard, nextPlayer)
            state.copy(
              board         = newBoard,
              currentPlayer = nextPlayer,
              moveHistory   = state.moveHistory :+ move,
              status        = nextStatus
            )
          }
          .left.map(DomainFailure(_))

  def handleCommand(state: GameState, command: ChessCommand): Either[ApplicationError, GameState] =
    command match
      case NewGame        => Right(createNewGame())
      case MakeMove(move) => applyMove(state, move)

  private def opponent(color: Color): Color = color match
    case Color.White => Color.Black
    case Color.Black => Color.White
