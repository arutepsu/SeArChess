package chess.application

import chess.application.ApplicationError.*
import chess.application.ChessCommand.*
import chess.domain.error.DomainError
import chess.domain.model.{Board, Color, Move, PieceType, Position}
import chess.domain.state.{CastlingRights, GameState}
import chess.domain.rules.application.MoveApplier
import chess.domain.rules.evaluation.GameStatusEvaluator

/** Application façade for the chess game.
 *
 *  Responsibilities:
 *  - application-level guard checks (turn enforcement, pending promotion)
 *  - command routing
 *  - delegation to [[GameTransitionService]] for state transitions
 *  - mapping [[DomainError]] to [[ApplicationError]]
 *
 *  Does NOT contain state-transition orchestration or event-building logic.
 *  Those concerns live in [[GameTransitionService]] and [[EventBuilder]] respectively.
 */
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
      toAppState(GameTransitionService.applyMoveWithEvents(state, move))

  def applyPromotion(state: GameState, pieceType: PieceType): Either[ApplicationError, GameState] =
    state.pendingPromotion match
      case None    => Left(NoPromotionPending)
      case Some(_) => toAppState(GameTransitionService.applyPromotionWithEvents(state, pieceType))

  private def toAppState(result: Either[DomainError, ApplyMoveResult]): Either[ApplicationError, GameState] =
    result.left.map(DomainFailure(_)).map(_.state)

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
   *  Note: [[chess.domain.event.DomainEvent.PieceCaptured]] is emitted for both
   *  regular captures and en passant captures.  For en passant the captured-pawn
   *  square is used, not move.to.
   */
  def applyMoveWithEvents(state: GameState, move: Move): Either[DomainError, ApplyMoveResult] =
    GameTransitionService.applyMoveWithEvents(state, move)

  def applyPromotionWithEvents(state: GameState, pieceType: PieceType): Either[DomainError, ApplyMoveResult] =
    GameTransitionService.applyPromotionWithEvents(state, pieceType)
