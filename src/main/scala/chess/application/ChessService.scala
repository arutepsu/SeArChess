package chess.application

import chess.application.ApplicationError.*
import chess.domain.model.{Board, Color, Move}
import chess.domain.rules.MoveApplier

object ChessService:

  def createNewGame(): GameState =
    GameState(board = Board.initial, currentPlayer = Color.White, moveHistory = Nil)

  def applyMove(state: GameState, move: Move): Either[ApplicationError, GameState] =
    state.board.pieceAt(move.from) match
      case Some(piece) if piece.color != state.currentPlayer =>
        Left(NotPlayersTurn)
      case _ =>
        MoveApplier.applyMove(state.board, move)
          .map { newBoard =>
            state.copy(
              board         = newBoard,
              currentPlayer = opponent(state.currentPlayer),
              moveHistory   = state.moveHistory :+ move
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
