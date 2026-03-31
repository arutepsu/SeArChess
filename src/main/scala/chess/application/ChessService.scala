package chess.application

import chess.application.ApplicationError.*
import chess.application.ChessCommand.*
import chess.domain.error.DomainError
import chess.domain.event.{CaptureInfo, CastlingSide, DomainEvent, Monoid, given}
import chess.domain.model.{Board, Color, GameStatus, Move, MoveResult, Piece, PieceType, Position}
import chess.domain.state.{CastlingRights, EnPassantState, GameState, PendingPromotion}
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
    else if state.board.pieceAt(move.from).exists(_.color != state.currentPlayer) then
      Left(NotPlayersTurn)
    else
      applyMoveWithEvents(state, move).left.map(DomainFailure(_)).map(_.state)

  def applyPromotion(state: GameState, pieceType: PieceType): Either[ApplicationError, GameState] =
    state.pendingPromotion match
      case None => Left(NoPromotionPending)
      case Some(_) =>
        applyPromotionWithEvents(state, pieceType).left.map(DomainFailure(_)).map(_.state)

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
            val promotedEvt  = List(DomainEvent.Promoted(square, color, pieceType))
            val checkEvt     = if nextStatus == GameStatus.Check then List(DomainEvent.CheckDeclared(nextPlayer)) else Nil
            val statusEvt    = if nextStatus != state.status then List(DomainEvent.GameStatusChanged(nextStatus)) else Nil
            // MoveExecuted for the promotion half-move — appended last, after all legacy events.
            val promotionCapture = capturedPiece.map(p => CaptureInfo(p.pieceType, p.color, move.to))
            val executedEvt  = List(buildMoveExecuted(
              Piece(color, PieceType.Pawn), promotionCapture, Some(pieceType), None, false, nextStatus, move
            ))
            ApplyMoveResult(newState, promotedEvt |+| checkEvt |+| statusEvt |+| executedEvt)
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

  /** All squares the piece at `from` can legally move to in the given state.
   *  Returns an empty set if there is no current-player piece at `from`,
   *  or if a promotion choice is still pending.
   */
  def legalMovesFrom(state: GameState, from: Position): Set[Position] =
    if state.pendingPromotion.isDefined then Set.empty
    else
      state.board.pieceAt(from) match
        case Some(piece) if piece.color == state.currentPlayer =>
          (for
            f   <- 0 to 7
            r   <- 0 to 7
            to  <- Position.from(f, r).toOption
            if MoveApplier.applyMove(
              state.board, Move(from, to), state.castlingRights, state.enPassantState
            ).isRight
          yield to).toSet
        case _ => Set.empty

  def handleCommand(state: GameState, command: ChessCommand): Either[ApplicationError, GameState] =
    command match
      case NewGame            => Right(createNewGame())
      case MakeMove(move)     => applyMove(state, move)
      case Promote(pieceType) => applyPromotion(state, pieceType)

  // ── Event-emitting move application ────────────────────────────────────────

  /** Apply a move to the full [[GameState]] and return the updated state together
   *  with the domain events that resulted from the transition.
   *
   *  Returns a [[DomainError]] directly (without wrapping in [[ApplicationError]])
   *  so callers that only need the domain result can consume it cleanly.
   *  Application-layer guards (turn checking, pending promotion) remain in
   *  [[applyMove]] and are the caller's responsibility here.
   *
   *  Note: [[DomainEvent.PieceCaptured]] is emitted for both regular captures and
   *  en passant captures.  For en passant the captured-pawn square is used, not move.to.
   */
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
          ApplyMoveResult(newState, moveAppliedEvents(move, movedPiece, capturedOpt, state.enPassantState, state.status, nextStatus, nextPlayer))

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

  private def moveAppliedEvents(
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
    val base        = List(DomainEvent.MoveApplied(move))
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

  /** Assemble a [[DomainEvent.MoveExecuted]] from pre-computed data.
   *
   *  All callers must resolve capture, castling, and en-passant BEFORE calling
   *  this helper.  The helper only assembles — it does not query the board or
   *  perform any game logic.
   */
  private def buildMoveExecuted(
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

