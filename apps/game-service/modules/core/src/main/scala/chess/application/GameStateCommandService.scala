package chess.application

import chess.application.ApplicationError.*
import chess.application.ChessCommand.*
import chess.domain.error.DomainError
import chess.domain.model.{Color, Move, PieceType, Position}
import chess.domain.rules.GameStateRules
import chess.domain.state.{GameState, GameStateFactory}

/** Application service for command-style operations on GameState. This is not the top-level Game
  * Service facade; adapters should depend on [[GameServiceApi]] or narrower application services.
  *
  * Responsibilities:
  *   - application-level guard checks (turn enforcement)
  *   - command routing
  *   - delegation to [[GameTransitionService]] for state transitions
  *   - mapping [[DomainError]] to [[ApplicationError]]
  *
  * Does NOT contain state-transition orchestration or event-building logic. Those concerns live in
  * [[GameTransitionService]] and [[EventBuilder]] respectively.
  */
object GameStateCommandService:

  def createNewGame(): GameState =
    GameStateFactory.initial()

  def applyMove(state: GameState, move: Move): Either[ApplicationError, GameState] =
    if state.board.pieceAt(move.from).exists(_.color != state.currentPlayer) then
      Left(NotPlayersTurn)
    else toAppState(GameTransitionService.applyMoveWithEvents(state, move))

  private def toAppState(
      result: Either[DomainError, ApplyMoveResult]
  ): Either[ApplicationError, GameState] =
    result.left.map(DomainFailure(_)).map(_.state)

  /** All legal target squares that the piece at `from` can move to in the given state. Returns an
    * empty set if there is no current-player piece at `from`.
    */
  def legalTargetsFrom(state: GameState, from: Position): Set[Position] =
    GameStateRules.legalTargetsFrom(state, from)

  /** Returns `true` when the piece at `from` is a pawn belonging to the current player that would
    * reach the back rank by moving to `to`, requiring a promotion piece before the move can be
    * committed to the domain.
    *
    * This is an application-layer fact derived from [[GameState]] structure. It exists here so
    * adapters and session services can detect the promotion- pending condition without duplicating
    * the rank/color check.
    *
    * Does NOT call the domain rules engine; the check is a pure structural read.
    */
  def isPromotionPending(state: GameState, from: Position, to: Position): Boolean =
    state.board.pieceAt(from).exists { piece =>
      piece.pieceType == PieceType.Pawn &&
      piece.color == state.currentPlayer &&
      ((piece.color == Color.White && to.rank == 7) ||
        (piece.color == Color.Black && to.rank == 0))
    }

  def handleCommand(state: GameState, command: ChessCommand): Either[ApplicationError, GameState] =
    command match
      case NewGame        => Right(createNewGame())
      case MakeMove(move) => applyMove(state, move)

  /** Apply a move to the full [[GameState]] and return the updated state together with the domain
    * events that resulted from the transition.
    *
    * Returns a [[DomainError]] directly (without wrapping in [[ApplicationError]]) so callers that
    * only need the domain result can consume it cleanly. Application-layer guard (turn checking)
    * remains in [[applyMove]] and is the caller's responsibility here.
    */
  def applyMoveWithEvents(state: GameState, move: Move): Either[DomainError, ApplyMoveResult] =
    GameTransitionService.applyMoveWithEvents(state, move)
