package chess.application.session.service

import chess.application.session.model.{GameSession, SessionMode, SideController}
import chess.application.session.service.SessionError
import chess.domain.model.{Color, Move}
import chess.domain.state.GameState
import java.time.Instant

/** Primary-port interface for the game-session command capability.
  *
  * Defines the authoritative write surface for session-aware game creation and move submission.
  * Every operation behind this interface:
  *
  *   1. validates a command against domain rules and session policy 2. persists session metadata
  *      and game state as one logical unit via
  *      [[chess.application.port.repository.SessionGameStore]] 3. emits post-persistence
  *      [[chess.application.event.AppEvent]]s
  *
  * ===Future extraction boundary===
  * This interface marks the first cleanly extractable service boundary. A future game-session
  * command service would implement this trait and own:
  *   - the write model for session metadata and game state (see
  *     [[chess.application.port.repository.SessionGameStore]])
  *   - the authoritative emission of [[chess.application.event.AppEvent.SessionCreated]],
  *     [[chess.application.event.AppEvent.MoveApplied]],
  *     [[chess.application.event.AppEvent.GameFinished]], and
  *     [[chess.application.event.AppEvent.SessionLifecycleChanged]]
  *
  * Adapters that depend on this interface — rather than on [[SessionGameService]] directly — can be
  * re-pointed to a remote implementation without changing their own logic.
  *
  * ===What this interface does NOT cover===
  *   - read / query access to session or game state (use [[SessionService]] or a dedicated query
  *     service)
  *   - session-only lifecycle writes that do not involve a [[GameState]] update (e.g.
  *     [[SessionService.preparePromotion]], [[SessionService.updateLifecycle]])
  *   - AI turn orchestration (that is a separate application concern in
  *     [[chess.application.ai.service.AITurnService]])
  */
trait GameSessionCommands:

  /** Create a new playable session and persist the initial game state atomically.
    *
    * On success: the session and the initial [[GameState]] are saved as one write via
    * [[chess.application.port.repository.SessionGameStore]], then
    * [[chess.application.event.AppEvent.SessionCreated]] is published.
    *
    * @return
    *   the fresh game state and its new session, or a [[SessionError]]
    */
  def newGame(
      mode: SessionMode,
      whiteController: SideController,
      blackController: SideController,
      now: Instant = Instant.now()
  ): Either[SessionError, (GameState, GameSession)]

  /** Apply a move through the session boundary and persist the result atomically.
    *
    * On success: session and game state are saved together via
    * [[chess.application.port.repository.SessionGameStore]], then
    * [[chess.application.event.AppEvent.MoveApplied]] is published, followed by
    * [[chess.application.event.AppEvent.GameFinished]] and/or
    * [[chess.application.event.AppEvent.SessionLifecycleChanged]] if applicable. On any failure the
    * store is left unchanged and no events are published.
    *
    * @param session
    *   current live session (caller must have loaded it)
    * @param state
    *   current game state (caller must have loaded it)
    * @param move
    *   the move to attempt
    * @param controller
    *   the controller submitting the move
    * @param now
    *   wall-clock instant for lifecycle timestamps
    * @return
    *   updated (GameState, GameSession) or the first error encountered
    */
  def submitMove(
      session: GameSession,
      state: GameState,
      move: Move,
      controller: SideController,
      now: Instant = Instant.now()
  ): Either[SessionMoveError, (GameState, GameSession)]

  /** Record a resignation: mark the game as won by the non-resigning side.
    *
    * Sets [[chess.domain.model.GameStatus.Resigned]] on the game state and transitions the session
    * to [[chess.application.session.model.SessionLifecycle.Finished]]. Both writes are committed
    * atomically via [[chess.application.port.repository.SessionGameStore]].
    *
    * Publishes [[chess.application.event.AppEvent.GameResigned]] and
    * [[chess.application.event.AppEvent.SessionLifecycleChanged]] after the write succeeds.
    *
    * @param session
    *   current live session (caller must have loaded it)
    * @param state
    *   current game state (caller must have loaded it)
    * @param resigningSide
    *   the [[chess.domain.model.Color]] that is conceding
    * @param now
    *   wall-clock instant for lifecycle timestamps
    * @return
    *   updated (GameState, GameSession) or a [[SessionError]]
    */
  def resignGame(
      session: GameSession,
      state: GameState,
      resigningSide: Color,
      now: Instant = Instant.now()
  ): Either[SessionError, (GameState, GameSession)]
