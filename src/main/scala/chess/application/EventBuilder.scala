package chess.application

import chess.domain.event.{CaptureInfo, CastlingSide, DomainEvent, Monoid, |+|, given}
import chess.domain.model.{Color, GameStatus, Move, Piece, PieceType, Position}
import chess.domain.state.EnPassantState

/** Assembles domain event lists for move and promotion transitions.
 *
 *  This object only builds events from already-known facts.
 *  It does NOT perform chess rule validation, call rule engines, or query the board.
 *
 *  Event ordering is stable and must not change without a deliberate decision:
 *  - Move path:      MoveApplied → PieceCaptured? → CheckDeclared? → GameStatusChanged? → MoveExecuted
 *  - Promotion path: Promoted → CheckDeclared? → GameStatusChanged? → MoveExecuted
 */
object EventBuilder:

  /** Build the full event list for a successfully applied (non-promotion) move. */
  def buildMoveEvents(ctx: MoveTransitionContext): List[DomainEvent] =
    buildMoveEvents(ctx.move, ctx.movedPiece, ctx.captured, ctx.enPassantState, ctx.prevStatus, ctx.nextStatus, ctx.nextPlayer)

  /** Build the full event list for a successfully applied (non-promotion) move. */
  def buildMoveEvents(
      move:           Move,
      movedPiece:     Option[Piece],
      captured:       Option[Piece],
      enPassantState: Option[EnPassantState],
      prevStatus:     GameStatus,
      nextStatus:     GameStatus,
      nextPlayer:     Color
  ): List[DomainEvent] =
    val epCapture = detectEnPassantCapture(enPassantState, movedPiece, move)
    // A move can have at most one capture source: regular OR en passant — never both.
    require(epCapture.isEmpty || captured.isEmpty,
      s"Move $move has both a regular capture and an en passant capture — impossible in chess")
    val captureInfo = epCapture.orElse(captured.map(c => CaptureInfo(c.pieceType, c.color, move.to)))
    val base: List[DomainEvent] = List(DomainEvent.MoveApplied(move))
    // PieceCaptured uses the correct square: move.to for regular, capturablePawnSquare for en passant.
    val captureEvt  = captureInfo.map(ci => DomainEvent.PieceCaptured(Piece(ci.color, ci.piece), ci.at)).toList
    val checkEvt    = if nextStatus == GameStatus.Check
                      then List(DomainEvent.CheckDeclared(nextPlayer)) else Nil
    val statusEvt   = if nextStatus != prevStatus
                      then List(DomainEvent.GameStatusChanged(nextStatus)) else Nil
    val castling    = detectCastling(movedPiece, move)
    // MoveExecuted is appended last — stable position for callers that need
    // the self-contained replay record after all legacy events.
    val executedEvt = movedPiece.map { p =>
      buildMoveExecuted(p, captureInfo, None, castling, epCapture.isDefined, nextStatus, move)
    }.toList
    base |+| captureEvt |+| checkEvt |+| statusEvt |+| executedEvt

  /** Build the full event list for a completed promotion. */
  def buildPromotionEvents(ctx: PromotionTransitionContext): List[DomainEvent] =
    buildPromotionEvents(ctx.square, ctx.color, ctx.move, ctx.capturedPiece, ctx.pieceType, ctx.prevStatus, ctx.nextStatus, ctx.nextPlayer)

  /** Build the full event list for a completed promotion. */
  def buildPromotionEvents(
      square:        Position,
      color:         Color,
      move:          Move,
      capturedPiece: Option[Piece],
      pieceType:     PieceType,
      prevStatus:    GameStatus,
      nextStatus:    GameStatus,
      nextPlayer:    Color
  ): List[DomainEvent] =
    val promotedEvt: List[DomainEvent] = List(DomainEvent.Promoted(square, color, pieceType))
    val checkEvt         = if nextStatus == GameStatus.Check then List(DomainEvent.CheckDeclared(nextPlayer)) else Nil
    val statusEvt        = if nextStatus != prevStatus then List(DomainEvent.GameStatusChanged(nextStatus)) else Nil
    val promotionCapture = capturedPiece.map(p => CaptureInfo(p.pieceType, p.color, move.to))
    // MoveExecuted is appended last, after all legacy events.
    val executedEvt      = List(buildMoveExecuted(
      Piece(color, PieceType.Pawn), promotionCapture, Some(pieceType), None, false, nextStatus, move
    ))
    promotedEvt |+| checkEvt |+| statusEvt |+| executedEvt

  /** Assemble a [[DomainEvent.MoveExecuted]] from pre-computed data.
   *
   *  All callers must resolve capture, castling, and en-passant BEFORE calling
   *  this helper.  The helper only assembles — it does not query the board or
   *  perform any game logic.
   */
  def buildMoveExecuted(
      movedPiece: Piece,
      capture:    Option[CaptureInfo],
      promotion:  Option[PieceType],
      castling:   Option[CastlingSide],
      enPassant:  Boolean,
      nextStatus: GameStatus,
      move:       Move
  ): DomainEvent.MoveExecuted =
    DomainEvent.MoveExecuted(
      move      = move,
      piece     = movedPiece.pieceType,
      color     = movedPiece.color,
      from      = move.from,
      to        = move.to,
      capture   = capture,
      promotion = promotion,
      castling  = castling,
      enPassant = enPassant,
      check     = nextStatus == GameStatus.Check,
      checkmate = nextStatus == GameStatus.Checkmate,
      stalemate = nextStatus == GameStatus.Stalemate
    )

  private def detectCastling(movedPiece: Option[Piece], move: Move): Option[CastlingSide] =
    if movedPiece.exists(_.pieceType == PieceType.King) && Math.abs(move.to.file - move.from.file) == 2
    then Some(if move.to.file > move.from.file then CastlingSide.KingSide else CastlingSide.QueenSide)
    else None

  private def detectEnPassantCapture(
      enPassantState: Option[EnPassantState],
      movedPiece:     Option[Piece],
      move:           Move
  ): Option[CaptureInfo] =
    enPassantState match
      case Some(ep) if movedPiece.exists(_.pieceType == PieceType.Pawn) && move.to == ep.targetSquare =>
        Some(CaptureInfo(PieceType.Pawn, ep.pawnColor, ep.capturablePawnSquare))
      case _ => None
