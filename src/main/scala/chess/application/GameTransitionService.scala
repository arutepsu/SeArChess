package chess.application

import chess.domain.error.DomainError
import chess.domain.event.DomainEvent
import chess.domain.model.{Board, Color, Move, MoveResult, Piece, PieceType, Position}
import chess.domain.state.{CastlingRights, EnPassantState, GameState, PendingPromotion}
import chess.domain.rules.application.{MoveApplier, PromotionApplier}
import chess.domain.rules.evaluation.GameStatusEvaluator
import chess.domain.rules.state.CastlingRightsUpdater

/** Orchestrates move and promotion state transitions for a [[GameState]].
 *
 *  Responsibilities:
 *  - apply domain rules via `MoveApplier` / `PromotionApplier`
 *  - compute derived state fields (castling rights, en passant, game status)
 *  - delegate event construction to [[EventBuilder]]
 *  - return [[ApplyMoveResult]] on success or [[DomainError]] on failure
 *
 *  Does NOT:
 *  - perform application-layer guard checks (turn enforcement, pending promotion)
 *  - map errors to [[ApplicationError]] — that is the façade's responsibility
 */
object GameTransitionService:

  def applyMoveWithEvents(state: GameState, move: Move): Either[DomainError, ApplyMoveResult] =
    val movedPiece  = state.board.pieceAt(move.from)
    val capturedOpt = state.board.pieceAt(move.to)
    val nextRights  = CastlingRightsUpdater.update(state.castlingRights, state.board, move)
    MoveApplier.applyMove(state.board, move, state.castlingRights, state.enPassantState)
      .map {
        case MoveResult.Applied(newBoard) =>
          val nextEnPassant = computeEnPassantState(move, state.board)
          val nextPlayer    = state.currentPlayer.opposite
          val nextStatus    = GameStatusEvaluator.evaluate(newBoard, nextPlayer, nextRights, nextEnPassant)
          val newState = state.copy(
            board            = newBoard,
            currentPlayer    = nextPlayer,
            moveHistory      = state.moveHistory :+ move,
            status           = nextStatus,
            castlingRights   = nextRights,
            pendingPromotion = None,
            enPassantState   = nextEnPassant
          )
          val ctx = MoveTransitionContext(move, movedPiece, capturedOpt, state.enPassantState, state.status, nextStatus, nextPlayer)
          ApplyMoveResult(newState, EventBuilder.buildMoveEvents(ctx))

        // A promotion pawn advance is one square and cannot create en passant.
        // currentPlayer and moveHistory are updated only after the piece is chosen.
        case MoveResult.PromotionRequired(newBoard, square, color) =>
          val newState = state.copy(
            board            = newBoard,
            castlingRights   = nextRights,
            pendingPromotion = Some(PendingPromotion(square, color, move, capturedOpt)),
            enPassantState   = None
          )
          val events = List(
            DomainEvent.MoveApplied(move),
            DomainEvent.PromotionRequired(square, color)
          )
          ApplyMoveResult(newState, events)
      }

  def applyPromotionWithEvents(state: GameState, pieceType: PieceType): Either[DomainError, ApplyMoveResult] =
    state.pendingPromotion match
      case None =>
        Left(DomainError.InvalidPromotionState)
      case Some(PendingPromotion(square, color, move, capturedPiece)) =>
        PromotionApplier.applyPromotion(state.board, square, color, pieceType)
          .map { promotedBoard =>
            val nextPlayer = state.currentPlayer.opposite
            val nextStatus = GameStatusEvaluator.evaluate(promotedBoard, nextPlayer, state.castlingRights)
            val newState = state.copy(
              board            = promotedBoard,
              currentPlayer    = nextPlayer,
              moveHistory      = state.moveHistory :+ move,
              status           = nextStatus,
              pendingPromotion = None,
              enPassantState   = None
            )
            val ctx = PromotionTransitionContext(square, color, move, capturedPiece, pieceType, state.status, nextStatus, nextPlayer)
            val events = EventBuilder.buildPromotionEvents(ctx)
            ApplyMoveResult(newState, events)
          }

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
