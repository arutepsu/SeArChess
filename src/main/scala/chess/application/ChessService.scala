package chess.application

import chess.application.ApplicationError.*
import chess.application.ChessCommand.*
import chess.domain.error.DomainError
import chess.domain.model.{Move, Position}
import chess.domain.rules.GameStateRules
import chess.domain.state.{GameState, GameStateFactory}

/** Application façade for the chess game.
 *
 *  Responsibilities:
 *  - application-level guard checks (turn enforcement)
 *  - command routing
 *  - delegation to [[GameTransitionService]] for state transitions
 *  - mapping [[DomainError]] to [[ApplicationError]]
 *
 *  Does NOT contain state-transition orchestration or event-building logic.
 *  Those concerns live in [[GameTransitionService]] and [[EventBuilder]] respectively.
 */
object ChessService:

  def createNewGame(): GameState =
    GameStateFactory.initial()

  def applyMove(state: GameState, move: Move): Either[ApplicationError, GameState] =
    if state.board.pieceAt(move.from).exists(_.color != state.currentPlayer) then
      Left(NotPlayersTurn)
    else
      toAppState(GameTransitionService.applyMoveWithEvents(state, move))

  private def toAppState(result: Either[DomainError, ApplyMoveResult]): Either[ApplicationError, GameState] =
    result.left.map(DomainFailure(_)).map(_.state)

  /** All legal target squares that the piece at `from` can move to in the given state.
   *  Returns an empty set if there is no current-player piece at `from`.
   */
  def legalTargetsFrom(state: GameState, from: Position): Set[Position] =
    GameStateRules.legalTargetsFrom(state, from)

  def handleCommand(state: GameState, command: ChessCommand): Either[ApplicationError, GameState] =
    command match
      case NewGame        => Right(createNewGame())
      case MakeMove(move) => applyMove(state, move)

  /** Apply a move to the full [[GameState]] and return the updated state together
   *  with the domain events that resulted from the transition.
   *
   *  Returns a [[DomainError]] directly (without wrapping in [[ApplicationError]])
   *  so callers that only need the domain result can consume it cleanly.
   *  Application-layer guard (turn checking) remains in [[applyMove]] and is
   *  the caller's responsibility here.
   */
  def applyMoveWithEvents(state: GameState, move: Move): Either[DomainError, ApplyMoveResult] =
    GameTransitionService.applyMoveWithEvents(state, move)