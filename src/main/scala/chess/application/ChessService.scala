package chess.application

import chess.application.ApplicationError.*
import chess.domain.model.{Board, Color, GameStatus, Move, MoveResult, PieceType}
import chess.domain.rules.{GameStatusEvaluator, MoveApplier, PromotionApplier}

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
    if state.pendingPromotion.isDefined then
      Left(PromotionChoiceRequired)
    else
      state.board.pieceAt(move.from) match
        case Some(piece) if piece.color != state.currentPlayer =>
          Left(NotPlayersTurn)
        case _ =>
          MoveApplier.applyMove(state.board, move).fold(
            err => Left(DomainFailure(err)),
            {
              case MoveResult.Applied(newBoard) =>
                val nextPlayer = opponent(state.currentPlayer)
                val nextStatus = GameStatusEvaluator.evaluate(newBoard, nextPlayer)
                Right(state.copy(
                  board            = newBoard,
                  currentPlayer    = nextPlayer,
                  moveHistory      = state.moveHistory :+ move,
                  status           = nextStatus,
                  pendingPromotion = None
                ))

              case MoveResult.PromotionRequired(newBoard, square, color) =>
                Right(state.copy(
                  board            = newBoard,
                  pendingPromotion = Some(PendingPromotion(square, color, move))
                ))
            }
          )

  def applyPromotion(state: GameState, pieceType: PieceType): Either[ApplicationError, GameState] =
    state.pendingPromotion match
      case None =>
        Left(NoPromotionPending)
      case Some(PendingPromotion(square, color, move)) =>
        PromotionApplier.applyPromotion(state.board, square, color, pieceType).fold(
          err => Left(DomainFailure(err)),
          promotedBoard =>
            val nextPlayer = opponent(state.currentPlayer)
            val nextStatus = GameStatusEvaluator.evaluate(promotedBoard, nextPlayer)
            Right(state.copy(
              board            = promotedBoard,
              currentPlayer    = nextPlayer,
              moveHistory      = state.moveHistory :+ move,
              status           = nextStatus,
              pendingPromotion = None
            ))
        )

  def handleCommand(state: GameState, command: ChessCommand): Either[ApplicationError, GameState] =
    command match
      case NewGame            => Right(createNewGame())
      case MakeMove(move)     => applyMove(state, move)
      case Promote(pieceType) => applyPromotion(state, pieceType)

  private def opponent(color: Color): Color = color match
    case Color.White => Color.Black
    case Color.Black => Color.White
