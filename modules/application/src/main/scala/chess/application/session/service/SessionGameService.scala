package chess.application.session.service

import chess.application.event.AppEvent
import chess.application.port.event.EventPublisher
import chess.application.port.repository.{RepositoryError, SessionGameStore}
import chess.application.session.model.{GameSession, SessionLifecycle, SessionMode, SideController}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.domain.model.{GameStatus, Move}
import chess.domain.state.{GameState, GameStateFactory}
import java.time.Instant

/** Unified application mutation boundary for all game-move adapters.
 *
 *  Composes [[SessionService]] (pure move validation and session-lifecycle
 *  computation) with [[SessionGameStore]] (combined session + game-state
 *  persistence) and [[EventPublisher]] (post-persistence event publication)
 *  so every adapter — GUI, TUI, REST — calls one entry point and is guaranteed
 *  that after a successful move:
 *
 *  1. the move is validated by the domain rules engine
 *  2. the updated session lifecycle and the new [[GameState]] are persisted
 *     atomically via [[SessionGameStore]]
 *  3. [[chess.application.event.AppEvent.MoveApplied]] is published
 *  4. [[chess.application.event.AppEvent.GameFinished]] is published when the
 *     move is terminal
 *  5. [[chess.application.event.AppEvent.SessionLifecycleChanged]] is published
 *     when the lifecycle advances
 *
 *  Events (steps 3–5) are only published after both persistence writes have
 *  succeeded, eliminating the partial-failure window that existed when session
 *  and game-state writes were independent.
 *
 *  Session lifecycle operations that do not involve a move (session creation,
 *  lifecycle transitions, promotion setup) are delegated transparently to the
 *  underlying [[SessionService]]; adapters may call them through this façade so
 *  they only need one dependency.
 *
 *  @param sessionService pure validation, lifecycle computation, and session-only writes
 *  @param store          combined persistence port for session + game state
 *  @param publisher      post-persistence event publication
 */
class SessionGameService(
  sessionService: SessionService,
  store:          SessionGameStore,
  publisher:      EventPublisher
):

  /** Apply a move through the session boundary and persist both the updated
   *  session and the new [[GameState]] before publishing any events.
   *
   *  On success:
   *  - [[SessionGameStore.save]] is called once with the updated session and
   *    new game state.
   *  - [[AppEvent.MoveApplied]] is published.
   *  - [[AppEvent.GameFinished]] is published when the game is terminal.
   *  - [[AppEvent.SessionLifecycleChanged]] is published when the lifecycle
   *    advances (e.g. Created → Active on the first move).
   *
   *  On any failure the store is left unchanged and no events are published.
   *
   *  @param session    current live session (caller must have loaded it)
   *  @param state      current game state (caller must have loaded it)
   *  @param move       the move to attempt
   *  @param controller the controller submitting the move
   *  @param now        wall-clock instant for lifecycle timestamps
   *  @return updated (GameState, GameSession) or the first error encountered
   */
  def submitMove(
    session:    GameSession,
    state:      GameState,
    move:       Move,
    controller: SideController,
    now:        Instant = Instant.now()
  ): Either[SessionMoveError, (GameState, GameSession)] =
    for
      (nextState, nextSession) <- sessionService.applyMove(session, state, move, controller, now)
      _                        <- store.save(nextSession, nextState)
                                    .left.map(e => SessionMoveError.PersistenceFailed(SessionError.PersistenceFailed(e)))
    yield
      publisher.publish(AppEvent.MoveApplied(session.sessionId, session.gameId, move, state.currentPlayer))
      if isTerminalStatus(nextState.status) then
        publisher.publish(AppEvent.GameFinished(session.sessionId, session.gameId, nextState.status))
      if nextSession.lifecycle != session.lifecycle then
        publisher.publish(AppEvent.SessionLifecycleChanged(
          session.sessionId, session.gameId, session.lifecycle, nextSession.lifecycle))
      (nextState, nextSession)

  // ── Session lifecycle delegation ────────────────────────────────────────────
  // Adapters that previously depended on SessionService directly can switch to
  // this façade as their sole dependency.

  def getSession(id: SessionId): Either[SessionError, GameSession] =
    sessionService.getSession(id)

  def getSessionByGameId(gameId: GameId): Either[SessionError, GameSession] =
    sessionService.getSessionByGameId(gameId)

  def createSession(
    gameId:          GameId,
    mode:            SessionMode,
    whiteController: SideController,
    blackController: SideController,
    now:             Instant = Instant.now()
  ): Either[SessionError, GameSession] =
    sessionService.createSession(gameId, mode, whiteController, blackController, now)

  def preparePromotion(sessionId: SessionId, now: Instant = Instant.now()): Either[SessionError, GameSession] =
    sessionService.preparePromotion(sessionId, now)

  def updateLifecycle(
    id:  SessionId,
    next: SessionLifecycle,
    now:  Instant = Instant.now()
  ): Either[SessionError, GameSession] =
    sessionService.updateLifecycle(id, next, now)

  /** Create a new session and persist the initial [[GameState]] as one write.
   *
   *  Creates a fresh starting position and a new [[GameSession]], saves both
   *  via [[SessionGameStore]] (one logical write), then publishes
   *  [[AppEvent.SessionCreated]].  Events are only published after the
   *  combined write succeeds.
   *
   *  Intended for reset flows (GUI/TUI "New Game") and REST session creation
   *  where both a valid session and a persisted initial state are required.
   *
   *  @return `(GameState, GameSession)` — the fresh state and its new session
   */
  def newGame(
    mode:            SessionMode,
    whiteController: SideController,
    blackController: SideController,
    now:             Instant = Instant.now()
  ): Either[SessionError, (GameState, GameSession)] =
    val fresh   = GameStateFactory.initial()
    val gameId  = GameId.random()
    val session = GameSession.create(gameId, mode, whiteController, blackController, now)
    store.save(session, fresh)
      .left.map(SessionError.PersistenceFailed(_))
      .map { _ =>
        publisher.publish(AppEvent.SessionCreated(
          session.sessionId, session.gameId, session.mode,
          session.whiteController, session.blackController))
        (fresh, session)
      }

  // ── private helpers ────────────────────────────────────────────────────────

  private def isTerminalStatus(status: GameStatus): Boolean = status match
    case GameStatus.Checkmate(_) | GameStatus.Draw(_) => true
    case _                                            => false
