package chess.application.session.service

import chess.application.event.AppEvent
import chess.application.port.event.{
  EventPublisher,
  NoOpTerminalEventJsonSerializer,
  TerminalEventJsonSerializer
}
import chess.application.port.repository.{RepositoryError, SessionGameStore}
import chess.application.session.model.{GameSession, SessionLifecycle, SessionMode, SideController}
import chess.application.session.model.SessionIds.GameId
import chess.domain.model.{Color, GameStatus, Move}
import chess.domain.state.{GameState, GameStateFactory}
import java.time.Instant

/** Authoritative command orchestrator for session-aware gameplay writes. This service owns normal
  * gameplay mutations and persists session + game-state changes through [[SessionGameStore]].
  *
  * Implements [[GameSessionCommands]]: [[newGame]], [[submitMove]], and [[resignGame]] are the
  * authoritative write operations. Each operation:
  *   - validates a command against domain rules and session policy
  *   - persists session metadata and game state atomically via [[SessionGameStore]]
  *   - emits post-persistence [[chess.application.event.AppEvent]]s
  *
  * Lifecycle and query operations belong to [[SessionLifecycleService]]; callers that need those
  * must depend on it directly.
  *
  * @param sessionLifecycleService
  *   pure validation, lifecycle computation, and session-only writes
  * @param store
  *   combined persistence port for session + game state (command surface)
  * @param publisher
  *   post-persistence event publication (command surface)
  * @param serializer
  *   serialises terminal events to JSON for transactional outbox writes; defaults to no-op when no
  *   durable outbox is configured
  */
class SessionGameCommandService(
    sessionLifecycleService: SessionLifecycleService,
    store: SessionGameStore,
    publisher: EventPublisher,
    serializer: TerminalEventJsonSerializer = NoOpTerminalEventJsonSerializer
) extends GameSessionCommands:

  /** Apply a move through the session boundary and persist both the updated session and the new
    * [[GameState]] before publishing any events.
    *
    * For terminal moves (checkmate, draw) the session write, the game-state write, and the outbox
    * payload are submitted together via [[SessionGameStore.saveTerminal]] so that either all three
    * land or none do.
    *
    * On success:
    *   - [[AppEvent.MoveApplied]] is published.
    *   - [[AppEvent.GameFinished]] is published when the game is terminal.
    *   - [[AppEvent.SessionLifecycleChanged]] is published when the lifecycle advances (e.g.
    *     Created → Active on the first move).
    *
    * On any failure the store is left unchanged and no events are published.
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
  ): Either[SessionMoveError, (GameState, GameSession)] =
    for
      (nextState, nextSession) <- sessionLifecycleService.applyMove(session, state, move, controller, now)
      outboxPayloads = terminalOutboxPayloads(session, nextState)
      _ <- store
        .saveTerminal(nextSession, nextState, outboxPayloads)
        .left
        .map(e => SessionMoveError.PersistenceFailed(SessionError.PersistenceFailed(e)))
    yield
      publisher.publish(
        AppEvent.MoveApplied(session.sessionId, session.gameId, move, state.currentPlayer)
      )
      if isTerminalStatus(nextState.status) then
        publisher.publish(
          AppEvent.GameFinished(session.sessionId, session.gameId, nextState.status)
        )
      if nextSession.lifecycle != session.lifecycle then
        publisher.publish(
          AppEvent.SessionLifecycleChanged(
            session.sessionId,
            session.gameId,
            session.lifecycle,
            nextSession.lifecycle
          )
        )
      (nextState, nextSession)

  /** Create a new session and persist the initial [[GameState]] as one write.
    *
    * Creates a fresh starting position and a new [[GameSession]], saves both via
    * [[SessionGameStore]] (one logical write), then publishes [[AppEvent.SessionCreated]]. Events
    * are only published after the combined write succeeds.
    *
    * Intended for reset flows (GUI/TUI "New Game") and REST session creation where both a valid
    * session and a persisted initial state are required.
    *
    * @return
    *   `(GameState, GameSession)` — the fresh state and its new session
    */
  def newGame(
      mode: SessionMode,
      whiteController: SideController,
      blackController: SideController,
      now: Instant = Instant.now()
  ): Either[SessionError, (GameState, GameSession)] =
    val fresh = GameStateFactory.initial()
    val gameId = GameId.random()
    val session = GameSession.create(gameId, mode, whiteController, blackController, now)
    store
      .save(session, fresh)
      .left
      .map(SessionError.PersistenceFailed(_))
      .map { _ =>
        publisher.publish(
          AppEvent.SessionCreated(
            session.sessionId,
            session.gameId,
            session.mode,
            session.whiteController,
            session.blackController
          )
        )
        (fresh, session)
      }

  /** Create a new session seeded with an imported [[GameState]].
    *
    * The imported state is persisted as-is; session lifecycle is derived from the imported status
    * (Active for ongoing games, Finished for terminal states).
    */
  def newGameFromState(
      state: GameState,
      mode: SessionMode,
      whiteController: SideController,
      blackController: SideController,
      now: Instant = Instant.now()
  ): Either[SessionError, (GameState, GameSession)] =
    val gameId = GameId.random()
    val baseSession = GameSession.create(gameId, mode, whiteController, blackController, now)
    val targetLifecycle = lifecycleForImportedState(state)
    val session =
      if targetLifecycle == baseSession.lifecycle then baseSession
      else GameSession.withLifecycle(baseSession, targetLifecycle, now)

    store
      .save(session, state)
      .left
      .map(SessionError.PersistenceFailed(_))
      .map { _ =>
        publisher.publish(
          AppEvent.SessionCreated(
            session.sessionId,
            session.gameId,
            session.mode,
            session.whiteController,
            session.blackController
          )
        )
        if session.lifecycle != baseSession.lifecycle then
          publisher.publish(
            AppEvent.SessionLifecycleChanged(
              session.sessionId,
              session.gameId,
              baseSession.lifecycle,
              session.lifecycle
            )
          )
        (state, session)
      }

  /** Record a resignation: set [[GameStatus.Resigned]] on the game state, advance the session
    * lifecycle to [[SessionLifecycle.Finished]], and persist both together with the outbox payload
    * atomically via [[SessionGameStore.saveTerminal]].
    *
    * On success: publishes [[AppEvent.GameResigned]] followed by
    * [[AppEvent.SessionLifecycleChanged]]. On any failure the store is left unchanged and no events
    * are published.
    */
  def resignGame(
      session: GameSession,
      state: GameState,
      resigningSide: Color,
      now: Instant = Instant.now()
  ): Either[SessionError, (GameState, GameSession)] =
    if session.lifecycle == SessionLifecycle.Finished || session.lifecycle == SessionLifecycle.Cancelled
    then Left(SessionError.InvalidLifecycleTransition("Session is already finished"))
    else
      val winner = resigningSide.opposite
      val resignedState = state.copy(status = GameStatus.Resigned(winner))
      val finishedSess = GameSession.withLifecycle(session, SessionLifecycle.Finished, now)
      val event = AppEvent.GameResigned(session.sessionId, session.gameId, winner)
      val payloads = serializer.serialize(event).toList
      store
        .saveTerminal(finishedSess, resignedState, payloads)
        .left
        .map(SessionError.PersistenceFailed(_))
        .map { _ =>
          publisher.publish(AppEvent.GameResigned(session.sessionId, session.gameId, winner))
          publisher.publish(
            AppEvent.SessionLifecycleChanged(
              session.sessionId,
              session.gameId,
              session.lifecycle,
              SessionLifecycle.Finished
            )
          )
          (resignedState, finishedSess)
        }

  // ── private helpers ────────────────────────────────────────────────────────

  private def terminalOutboxPayloads(session: GameSession, nextState: GameState): List[String] =
    nextState.status match
      case GameStatus.Checkmate(_) | GameStatus.Draw(_) =>
        val event = AppEvent.GameFinished(session.sessionId, session.gameId, nextState.status)
        serializer.serialize(event).toList
      case _ => Nil

  private def isTerminalStatus(status: GameStatus): Boolean = status match
    case GameStatus.Checkmate(_) | GameStatus.Draw(_) | GameStatus.Resigned(_) => true
    case _                                                                     => false

  private def lifecycleForImportedState(state: GameState): SessionLifecycle =
    state.status match
      case GameStatus.Checkmate(_) | GameStatus.Draw(_) | GameStatus.Resigned(_) =>
        SessionLifecycle.Finished
      case _ =>
        SessionLifecycle.Active
