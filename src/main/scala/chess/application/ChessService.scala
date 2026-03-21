package chess.application

import chess.application.ApplicationError.*
import chess.domain.model.{Board, Color, GameStatus, Move, MoveResult, Piece, PieceType, Position}
import chess.domain.model.positionstate.{CastlingRights, EnPassantState}
import chess.domain.rules.application.{MoveApplier, PromotionApplier}
import chess.domain.rules.evaluation.GameStatusEvaluator
import chess.domain.rules.state.CastlingRightsUpdater

object ChessService:

  def createNewGame(): GameState =
    val board = Board.initial
    GameState(
      board          = board,
      currentPlayer  = Color.White,
      moveHistory    = Nil,
      status         = GameStatusEvaluator.evaluate(board, Color.White, CastlingRights.full),
      castlingRights = CastlingRights.full
    )

  def applyMove(state: GameState, move: Move): Either[ApplicationError, GameState] =
    if state.pendingPromotion.isDefined then
      Left(PromotionChoiceRequired)
    else
      state.board.pieceAt(move.from) match
        case Some(piece) if piece.color != state.currentPlayer =>
          Left(NotPlayersTurn)
        case _ =>
          MoveApplier.applyMove(state.board, move, state.castlingRights, state.enPassantState).fold(
            err => Left(DomainFailure(err)),
            {
              case MoveResult.Applied(newBoard) =>
                val nextRights    = CastlingRightsUpdater.update(state.castlingRights, state.board, move)
                val nextEnPassant = computeEnPassantState(move, state.board)
                val nextPlayer    = opponent(state.currentPlayer)
                val nextStatus    = GameStatusEvaluator.evaluate(newBoard, nextPlayer, nextRights, nextEnPassant)
                Right(state.copy(
                  board            = newBoard,
                  currentPlayer    = nextPlayer,
                  moveHistory      = state.moveHistory :+ move,
                  status           = nextStatus,
                  castlingRights   = nextRights,
                  pendingPromotion = None,
                  enPassantState   = nextEnPassant
                ))

              case MoveResult.PromotionRequired(newBoard, square, color) =>
                // A pawn advancing to the promotion rank is a one-square move and
                // can never create en passant availability, so clear it immediately.
                val nextRights = CastlingRightsUpdater.update(state.castlingRights, state.board, move)
                Right(state.copy(
                  board            = newBoard,
                  castlingRights   = nextRights,
                  pendingPromotion = Some(PendingPromotion(square, color, move)),
                  enPassantState   = None
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
            val nextStatus = GameStatusEvaluator.evaluate(promotedBoard, nextPlayer, state.castlingRights)
            Right(state.copy(
              board            = promotedBoard,
              currentPlayer    = nextPlayer,
              moveHistory      = state.moveHistory :+ move,
              status           = nextStatus,
              pendingPromotion = None,
              enPassantState   = None
            ))
        )

  // ── en passant lifecycle ────────────────────────────────────────────────────

  /** Derive en passant state from a just-completed move.
   *
   *  A two-square pawn advance creates an EnPassantState for the opponent's
   *  immediate reply.  Every other move produces None, which clears any
   *  existing en passant availability.
   */
  private def computeEnPassantState(move: Move, boardBefore: Board): Option[EnPassantState] =
    boardBefore.pieceAt(move.from) match
      case Some(Piece(Color.White, PieceType.Pawn)) if move.from.rank == 1 && move.to.rank == 3 =>
        Position.from(move.from.file, 2).toOption.map { target =>
          EnPassantState(target, move.to, Color.White)
        }
      case Some(Piece(Color.Black, PieceType.Pawn)) if move.from.rank == 6 && move.to.rank == 4 =>
        Position.from(move.from.file, 5).toOption.map { target =>
          EnPassantState(target, move.to, Color.Black)
        }
      case _ => None

  def handleCommand(state: GameState, command: ChessCommand): Either[ApplicationError, GameState] =
    command match
      case NewGame            => Right(createNewGame())
      case MakeMove(move)     => applyMove(state, move)
      case Promote(pieceType) => applyPromotion(state, pieceType)

  private def opponent(color: Color): Color = color match
    case Color.White => Color.Black
    case Color.Black => Color.White
