package chess.application.session.service

import chess.application.port.repository.{GameRepository, RepositoryError}
import chess.application.session.model.{GameSession, SessionLifecycle, SessionMode, SideController}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.domain.model.Move
import chess.domain.state.{GameState, GameStateFactory}
import java.time.Instant

/** Unified application mutation boundary for all game-move adapters.
 *
 *  Composes [[SessionService]] (domain validation, application event publication,
 *  session-lifecycle persistence) with [[GameRepository]] (game-state persistence)
 *  so every adapter — GUI, TUI, REST — calls one entry point and is guaranteed
 *  that after a successful move:
 *
 *  1. the move is validated by the domain rules engine
 *  2. [[chess.application.event.AppEvent.MoveApplied]] is published via [[chess.application.port.event.EventPublisher]]
 *  3. [[chess.application.event.AppEvent.GameFinished]] is published when the move is terminal
 *  4. the updated session lifecycle is persisted to [[chess.application.port.repository.SessionRepository]]
 *  5. the new [[GameState]] is persisted to [[GameRepository]]
 *
 *  None of steps 1–5 are the adapter's concern.  The adapter supplies intent
 *  (session + current state + move + controller) and receives the new state.
 *
 *  Session lifecycle operations that do not involve a move (session creation,
 *  lifecycle transitions, promotion setup) are delegated transparently to the
 *  underlying [[SessionService]]; adapters may call them through this façade so
 *  they only need one dependency.
 *
 *  @param sessionService handles validation, event publication, and session persistence
 *  @param gameRepository handles game-state persistence
 */
class SessionGameService(
  sessionService: SessionService,
  gameRepository: GameRepository
):

  /** Apply a move through the session boundary and persist the resulting [[GameState]].
   *
   *  On success, both the updated session lifecycle and the new [[GameState]] are
   *  durably stored before returning.  On any failure the repositories are left
   *  unchanged (the session persistence inside [[SessionService.applyMove]] is the
   *  sole exception — a session lifecycle write that succeeds before a game-state
   *  save failure leaves the session advanced but the game state stale; this
   *  inconsistency is accepted for now and is the primary motivation to eventually
   *  wrap both writes in a single transaction).
   *
   *  @param session    current live session (caller must have loaded it; not re-fetched here)
   *  @param state      current game state (caller must have loaded it; not re-fetched here)
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
    sessionService.applyMove(session, state, move, controller, now).flatMap { (nextState, nextSession) =>
      gameRepository
        .save(nextSession.gameId, nextState)
        .left.map(e => SessionMoveError.PersistenceFailed(SessionError.PersistenceFailed(e)))
        .map(_ => (nextState, nextSession))
    }

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

  /** Create a new session and persist the initial [[GameState]] atomically.
   *
   *  Produces a fresh starting position, creates a session in
   *  [[SessionLifecycle.Created]], and saves the initial state to the
   *  [[GameRepository]] before returning.  Both operations succeed or the
   *  caller receives the first error; partial failure (session created but
   *  save failed) is possible and accepted as a known limitation pending
   *  transactional writes.
   *
   *  Intended for reset flows where the caller needs both a valid session
   *  and a persisted initial state in a single call.
   *
   *  @return `(GameState, GameSession)` — the fresh state and its new session
   */
  def newGame(
    mode:            SessionMode,
    whiteController: SideController,
    blackController: SideController,
    now:             Instant = Instant.now()
  ): Either[SessionError, (GameState, GameSession)] =
    val fresh  = GameStateFactory.initial()
    val gameId = GameId.random()
    for
      session <- sessionService.createSession(gameId, mode, whiteController, blackController, now)
      _       <- gameRepository.save(gameId, fresh)
                   .left.map(e => SessionError.PersistenceFailed(e))
    yield (fresh, session)
