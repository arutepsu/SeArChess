package chess.application.session.service

import chess.application.ChessService
import chess.application.event.AppEvent
import chess.application.port.event.{EventPublisher, NoOpTerminalEventJsonSerializer, TerminalEventJsonSerializer}
import chess.application.port.repository.{RepositoryError, SessionRepository}
import chess.application.session.model.{GameSession, SessionLifecycle, SessionMode, SideController}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.application.session.policy.{ActorControlPolicy, SessionLifecyclePolicy}
import chess.domain.model.{Color, GameStatus, Move}
import chess.domain.state.GameState
import java.time.Instant

/** Application service for session lifecycle management.
 *
 *  Responsibilities:
 *  - create new sessions and persist them via [[SessionRepository]]
 *  - retrieve existing sessions
 *  - advance session lifecycle phases (with [[SessionLifecyclePolicy]] enforcement)
 *  - answer which [[SideController]] is responsible for a given [[Color]]
 *  - provide a session-aware move entry point that checks controller ownership
 *    before delegating to the chess engine
 *  - publish [[AppEvent]]s via [[EventPublisher]] after successful state changes
 *
 *  Does NOT:
 *  - validate chess move legality (that belongs in [[chess.domain.rules.GameStateRules]])
 *  - generate AI moves (that belongs in `application.ai.service` once introduced)
 *  - hold or mutate [[GameState]] directly
 *
 *  @param repository outbound port for session persistence
 *  @param publisher  outbound port for application-layer event publication
 *  @param serializer serialises terminal events to JSON for transactional outbox
 *                    writes; defaults to no-op when no durable outbox is configured
 */
class SessionService(
  repository: SessionRepository,
  publisher:  EventPublisher,
  serializer: TerminalEventJsonSerializer = NoOpTerminalEventJsonSerializer
):

  /** Create a new [[GameSession]] in the [[SessionLifecycle.Created]] phase
   *  and persist it immediately.
   *
   *  Publishes [[AppEvent.SessionCreated]] on success.
   *
   *  The caller must supply a [[GameId]] obtained from wherever the underlying
   *  game state is initialised (e.g. `ChessService.createNewGame`).
   */
  def createSession(
    gameId:          GameId,
    mode:            SessionMode,
    whiteController: SideController,
    blackController: SideController,
    now:             Instant = Instant.now()
  ): Either[SessionError, GameSession] =
    val session = GameSession.create(gameId, mode, whiteController, blackController, now)
    save(session).map { saved =>
      publisher.publish(AppEvent.SessionCreated(
        saved.sessionId, saved.gameId, saved.mode, saved.whiteController, saved.blackController))
      saved
    }

  /** Retrieve a session by its [[SessionId]]. */
  def getSession(id: SessionId): Either[SessionError, GameSession] =
    repository.load(id).left.map:
      case RepositoryError.NotFound(_)         => SessionError.SessionNotFound(id)
      case err: RepositoryError.StorageFailure => SessionError.PersistenceFailed(err)

  /** Transition the session to a new [[SessionLifecycle]] phase and persist the change.
   *
   *  The transition is validated by [[SessionLifecyclePolicy]] before any
   *  persistence call is made.  Invalid transitions return
   *  [[SessionError.InvalidLifecycleTransition]] immediately.
   *
   *  Returns the updated [[GameSession]] on success.
   */
  def updateLifecycle(
    id:   SessionId,
    next: SessionLifecycle,
    now:  Instant = Instant.now()
  ): Either[SessionError, GameSession] =
    for
      session <- getSession(id)
      _       <- SessionLifecyclePolicy.validateTransition(session.lifecycle, next)
                   .left.map(SessionError.InvalidLifecycleTransition(_))
      updated  = GameSession.withLifecycle(session, next, now)
      _       <- save(updated)
    yield updated

  /** Signal that the session is paused waiting for a promotion piece choice.
   *
   *  Call this when the application detects that the current player's pawn has
   *  reached the back rank and no promotion piece has been chosen yet.
   *  Transitions the session from [[SessionLifecycle.Active]] to
   *  [[SessionLifecycle.AwaitingPromotion]] and persists the change.
   *
   *  Publishes [[AppEvent.PromotionPending]] on success.
   *
   *  The transition is validated by [[chess.application.session.policy.SessionLifecyclePolicy]];
   *  calling this from a non-[[SessionLifecycle.Active]] session will return
   *  [[SessionError.InvalidLifecycleTransition]].
   */
  def preparePromotion(
    sessionId: SessionId,
    now:       Instant = Instant.now()
  ): Either[SessionError, GameSession] =
    updateLifecycle(sessionId, SessionLifecycle.AwaitingPromotion, now).map { updated =>
      publisher.publish(AppEvent.PromotionPending(updated.sessionId, updated.gameId))
      updated
    }

  /** Retrieve the session associated with a given [[GameId]].
   *
   *  Useful when the caller knows the game id (e.g. from a REST path segment)
   *  but has not yet resolved the session id.  Delegates to
   *  [[SessionRepository.loadByGameId]].
   *
   *  Returns [[SessionError.GameSessionNotFound]] when no session references
   *  that game, keeping the "not found" distinction clean for callers.
   */
  def getSessionByGameId(gameId: GameId): Either[SessionError, GameSession] =
    repository.loadByGameId(gameId).left.map:
      case RepositoryError.NotFound(_)         => SessionError.GameSessionNotFound(gameId)
      case err: RepositoryError.StorageFailure => SessionError.PersistenceFailed(err)

  /** Cancel a session by advancing its lifecycle to [[SessionLifecycle.Finished]].
   *
   *  Valid for sessions in [[SessionLifecycle.Created]] or [[SessionLifecycle.Active]].
   *  The underlying game state is left unchanged (no winner is recorded).
   *  Publishes [[chess.application.event.AppEvent.SessionCancelled]] on success.
   *
   *  In SQLite mode, the session row update and the outbox payload are committed
   *  in the same JDBC transaction via [[SessionRepository.saveCancelWithOutbox]].
   *
   *  Returns [[SessionError.InvalidLifecycleTransition]] when the session is
   *  already [[SessionLifecycle.Finished]].
   */
  def cancelSession(
    sessionId: SessionId,
    now:       Instant = Instant.now()
  ): Either[SessionError, GameSession] =
    for
      session <- getSession(sessionId)
      _       <- SessionLifecyclePolicy.validateTransition(session.lifecycle, SessionLifecycle.Finished)
                   .left.map(SessionError.InvalidLifecycleTransition(_))
      updated  = GameSession.withLifecycle(session, SessionLifecycle.Finished, now)
      payload  = serializer.serialize(AppEvent.SessionCancelled(updated.sessionId, updated.gameId))
      _       <- repository.saveCancelWithOutbox(updated, payload)
                   .left.map(SessionError.PersistenceFailed(_))
    yield
      publisher.publish(AppEvent.SessionCancelled(updated.sessionId, updated.gameId))
      updated

  /** Return all sessions that have not yet reached [[SessionLifecycle.Finished]].
   *
   *  Returns an empty list (not an error) when no active sessions exist.
   */
  def listActiveSessions(): Either[SessionError, List[GameSession]] =
    repository.listActive().left.map(SessionError.PersistenceFailed(_))

  /** Return the [[SideController]] responsible for the given [[Color]].
   *
   *  Pure lookup — does not touch the repository.
   */
  def controllerFor(session: GameSession, color: Color): SideController =
    color match
      case Color.White => session.whiteController
      case Color.Black => session.blackController

  /** Session-aware move entry point (pure computation).
   *
   *  Performs session-level ownership and lifecycle checks before delegating to
   *  the chess engine.  Returns the new [[GameState]] and an updated
   *  [[GameSession]] (with the correct lifecycle) without touching any
   *  repository or publishing any events.
   *
   *  The caller ([[chess.application.session.service.SessionGameService.submitMove]])
   *  is responsible for:
   *  1. Persisting both the updated [[GameSession]] and the new [[GameState]]
   *     via [[chess.application.port.repository.SessionGameStore]].
   *  2. Publishing [[chess.application.event.AppEvent]]s after persistence succeeds.
   *
   *  Keeping this method pure eliminates the partial-failure window that existed
   *  when session persistence and event publication were interleaved with the
   *  separate game-state save.
   *
   *  Flow:
   *  1. Reject immediately if session is [[SessionLifecycle.Finished]].
   *  2. Check [[ActorControlPolicy.canAct]] — reject if the requesting controller
   *     does not own the side to move.
   *  3. Delegate to [[ChessService.applyMove]] for chess legality.
   *  4. Compute the correct next lifecycle phase (no repository write).
   *  5. Return the updated domain state and the (possibly updated) session.
   *
   *  @param session              current session (loaded by caller; not re-fetched)
   *  @param state                current chess state
   *  @param move                 the move to apply
   *  @param requestingController the controller that is submitting the move
   *  @param now                  wall-clock instant for lifecycle timestamps
   *  @return updated (GameState, GameSession) or a [[SessionMoveError]]
   */
  def applyMove(
    session:              GameSession,
    state:                GameState,
    move:                 Move,
    requestingController: SideController,
    now:                  Instant = Instant.now()
  ): Either[SessionMoveError, (GameState, GameSession)] =
    for
      _         <- rejectIfFinished(session)
      _         <- checkController(session, requestingController, state.currentPlayer)
      nextState <- ChessService.applyMove(state, move)
                     .left.map(SessionMoveError.DomainRejection(_))
      result    <- computePostMoveLifecycle(session, nextState, now)
    yield result

  // ── private helpers ────────────────────────────────────────────────────────

  private def save(session: GameSession): Either[SessionError, GameSession] =
    repository.save(session)
      .left.map(SessionError.PersistenceFailed(_))
      .map(_ => session)

  private def rejectIfFinished(session: GameSession): Either[SessionMoveError, Unit] =
    if session.lifecycle == SessionLifecycle.Finished then Left(SessionMoveError.SessionFinished)
    else Right(())

  private def checkController(
    session:    GameSession,
    requesting: SideController,
    sideToMove: Color
  ): Either[SessionMoveError, Unit] =
    if ActorControlPolicy.canAct(session, requesting, sideToMove) then Right(())
    else Left(SessionMoveError.UnauthorizedController(requesting, sideToMove))

  /** After a successful move, compute the correct next lifecycle phase (pure).
   *
   *  Returns an updated [[GameSession]] with the new lifecycle applied but does
   *  NOT write to any repository.  The caller is responsible for persisting.
   *
   *  Transition table:
   *  - Any lifecycle  + terminal game (checkmate/draw)  → [[SessionLifecycle.Finished]]
   *  - [[SessionLifecycle.Created]]           + non-terminal → [[SessionLifecycle.Active]]
   *    (first move activates the session)
   *  - [[SessionLifecycle.AwaitingPromotion]] + non-terminal → [[SessionLifecycle.Active]]
   *    (promotion choice was just supplied; resume normal play)
   *  - [[SessionLifecycle.Active]]            + non-terminal → no change
   *
   *  Transitions are validated by [[SessionLifecyclePolicy]].
   */
  private def computePostMoveLifecycle(
    session:   GameSession,
    nextState: GameState,
    now:       Instant
  ): Either[SessionMoveError, (GameState, GameSession)] =
    val isTerminal = nextState.status match
      case GameStatus.Checkmate(_) | GameStatus.Draw(_) | GameStatus.Resigned(_) => true
      case _                                                                      => false

    val nextLifecycle: Option[SessionLifecycle] = (session.lifecycle, isTerminal) match
      case (_, true)                                   => Some(SessionLifecycle.Finished)
      case (SessionLifecycle.Created, false)           => Some(SessionLifecycle.Active)
      case (SessionLifecycle.AwaitingPromotion, false) => Some(SessionLifecycle.Active)
      case _                                           => None  // Active → Active: no change needed

    nextLifecycle match
      case None       => Right((nextState, session))
      case Some(next) =>
        SessionLifecyclePolicy.validateTransition(session.lifecycle, next)
          .left.map(msg => SessionMoveError.PersistenceFailed(SessionError.InvalidLifecycleTransition(msg)))
          .map(next => (nextState, GameSession.withLifecycle(session, next, now)))
