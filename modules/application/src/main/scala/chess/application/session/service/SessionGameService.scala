package chess.application.session.service

import chess.application.event.AppEvent
import chess.application.port.event.EventPublisher
import chess.application.port.repository.{RepositoryError, SessionGameStore}
import chess.application.session.model.{GameSession, SessionLifecycle, SessionMode, SideController}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.domain.model.{Color, GameStatus, Move}
import chess.domain.state.{GameState, GameStateFactory}
import java.time.Instant

/** Concrete implementation of [[GameSessionCommands]] and the composition root for
 *  the game-session capability inside the modular monolith.
 *
 *  This class has three distinct roles, each occupying its own section:
 *
 *  === 1. Game-session command surface ([[GameSessionCommands]]) ===
 *  [[newGame]] and [[submitMove]] are the authoritative write operations.
 *  Both operations:
 *  - validate a command against domain rules and session policy
 *  - persist session metadata and game state atomically via [[SessionGameStore]]
 *  - emit post-persistence [[chess.application.event.AppEvent]]s
 *  These are the operations the future extracted game-session command service
 *  would own.  Adapters that depend only on [[GameSessionCommands]] are
 *  already extraction-ready.
 *
 *  === 2. Session lifecycle management ===
 *  [[createSession]], [[preparePromotion]], and [[updateLifecycle]] mutate session
 *  state only (not game state) and delegate to [[SessionService]].  They write only
 *  to [[chess.application.port.repository.SessionRepository]] — not
 *  [[SessionGameStore]].  These operations are used by desktop adapters (GUI,
 *  desktop-session import) that manage session context locally.
 *
 *  === 3. Query delegation ===
 *  [[getSession]] and [[getSessionByGameId]] are pure reads forwarded to
 *  [[SessionService]].  Callers that only need reads may depend on
 *  [[SessionService]] directly; these delegating methods exist so that adapters
 *  that need both reads and commands can use a single dependency.
 *  In a future extraction, these reads could remain on the command service
 *  (if it is the read authority) or be served by a separate read model.
 *
 *  @param sessionService pure validation, lifecycle computation, and session-only writes
 *  @param store          combined persistence port for session + game state (command surface)
 *  @param publisher      post-persistence event publication (command surface)
 */
class SessionGameService(
  sessionService: SessionService,
  store:          SessionGameStore,
  publisher:      EventPublisher
) extends GameSessionCommands:

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
  // ── Game-session command surface (GameSessionCommands) ────────────────────
  // Both methods use SessionGameStore (combined write) and publish post-persistence events.
  // These are the operations the future extracted service would own.

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

  /** Record a resignation: set [[GameStatus.Resigned]] on the game state, advance
   *  the session lifecycle to [[SessionLifecycle.Finished]], and persist both
   *  atomically via [[SessionGameStore]].
   *
   *  On success: publishes [[AppEvent.GameResigned]] followed by
   *  [[AppEvent.SessionLifecycleChanged]].
   *  On any failure the store is left unchanged and no events are published.
   */
  def resignGame(
    session:       GameSession,
    state:         GameState,
    resigningSide: Color,
    now:           Instant = Instant.now()
  ): Either[SessionError, (GameState, GameSession)] =
    if session.lifecycle == SessionLifecycle.Finished then
      Left(SessionError.InvalidLifecycleTransition("Session is already finished"))
    else
      val winner        = resigningSide.opposite
      val resignedState = state.copy(status = GameStatus.Resigned(winner))
      val finishedSess  = GameSession.withLifecycle(session, SessionLifecycle.Finished, now)
      store.save(finishedSess, resignedState)
        .left.map(SessionError.PersistenceFailed(_))
        .map { _ =>
          publisher.publish(AppEvent.GameResigned(session.sessionId, session.gameId, winner))
          publisher.publish(AppEvent.SessionLifecycleChanged(
            session.sessionId, session.gameId, session.lifecycle, SessionLifecycle.Finished))
          (resignedState, finishedSess)
        }

  // ── Query delegation ──────────────────────────────────────────────────────
  // Pure reads forwarded to SessionService.
  // Adapters that only need reads can depend on SessionService directly.
  // Adapters that need both reads and commands can use this class as their
  // single dependency (it covers all three roles above).

  def getSession(id: SessionId): Either[SessionError, GameSession] =
    sessionService.getSession(id)

  def getSessionByGameId(gameId: GameId): Either[SessionError, GameSession] =
    sessionService.getSessionByGameId(gameId)

  // ── Session lifecycle management ──────────────────────────────────────────
  // Delegates to SessionService; writes only to SessionRepository (not SessionGameStore).
  // Used by desktop adapters (GUI) that manage the shared desktop session context.

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

  // ── private helpers ────────────────────────────────────────────────────────

  private def isTerminalStatus(status: GameStatus): Boolean = status match
    case GameStatus.Checkmate(_) | GameStatus.Draw(_) | GameStatus.Resigned(_) => true
    case _                                                                      => false
