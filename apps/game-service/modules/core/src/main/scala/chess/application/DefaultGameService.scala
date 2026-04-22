package chess.application

import chess.application.ai.service.{AITurnError, AITurnService}
import chess.application.event.AppEvent
import chess.application.port.event.EventPublisher
import chess.application.port.repository.{GameRepository, RepositoryError, SessionGameStore}
import chess.application.session.model.SessionLifecycle
import chess.application.query.game.{GameArchiveSnapshot, GameClosure, GameView, LegalMovesView}
import chess.application.query.session.SessionView
import chess.application.session.model.{GameSession, SessionMode, SideController}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.application.session.service.{
  GameSessionCommands,
  SessionError,
  SessionMoveError,
  SessionService
}
import chess.domain.model.{Color, Move}
import chess.domain.rules.GameStateRules
import chess.domain.state.GameState
import java.time.Instant

/** Default implementation of [[GameServiceApi]].
  *
  * Thin orchestration facade: delegates every command and query to the appropriate existing
  * application service. Business logic and event policy stay inside
  * [[chess.application.session.service.SessionGameService]] and
  * [[chess.application.session.service.SessionService]]; this class only routes calls and maps
  * errors for the transport boundary.
  *
  * ===Dependency map===
  *   - [[commands]] — authoritative write boundary for moves and new games
  *   - [[sessionService]] — session queries, lifecycle writes, cancel, list
  *   - [[gameRepository]] — read-only game state access
  *   - [[publisher]] — audit event publication (see [[submitMove]] for scope)
  *   - [[aiService]] — optional AI move orchestration; [[AITurnError.NotConfigured]] is returned
  *     when `None`
  *
  * ===Note: exhaustive match coupling from GameStatus.Resigned===
  * Adding [[chess.domain.model.GameStatus.Resigned]] required updates to many exhaustive match
  * sites in adapters, mappers, and notation modules. This is a symptom of the sealed enum being
  * matched directly throughout the stack rather than going through a dedicated mapper boundary. A
  * future improvement would route all `GameStatus` translation through a single presentation mapper
  * so that new statuses only require one touch point. Not addressed here.
  *
  * @param commands
  *   session command boundary (newGame, submitMove, resignGame)
  * @param sessionService
  *   session lifecycle, queries, cancel, list
  * @param gameRepository
  *   read-only game state port
  * @param publisher
  *   fire-and-forget event publisher for [[chess.application.event.AppEvent.MoveRejected]]
  * @param aiService
  *   optional AI turn orchestration service
  */
class DefaultGameService(
    commands: GameSessionCommands,
    sessionService: SessionService,
    gameRepository: GameRepository,
    publisher: EventPublisher,
    aiService: Option[AITurnService] = None
) extends GameServiceApi:

  // ── Commands ────────────────────────────────────────────────────────────────

  def createGame(
      mode: SessionMode,
      whiteController: SideController,
      blackController: SideController,
      now: Instant = Instant.now()
  ): Either[SessionError, (GameState, GameSession)] =
    commands.newGame(mode, whiteController, blackController, now)

  def createGameFromState(
      state: GameState,
      mode: SessionMode,
      whiteController: SideController,
      blackController: SideController,
      now: Instant = Instant.now()
  ): Either[SessionError, (GameState, GameSession)] =
    commands.newGameFromState(state, mode, whiteController, blackController, now)

  /** Load session and state, then route through the session command boundary.
    *
    * [[AppEvent.MoveRejected]] is an '''interaction/audit event''', not a state- transition event:
    * it records that a move was attempted and rejected, but the game state does not change. It is
    * published here — at the facade level — because
    * [[chess.application.session.service.SessionGameService]] only sees successful paths and cannot
    * observe rejections. The facade is the only place that holds both the session identity (needed
    * for the event) and the domain rejection error.
    *
    * Only [[SessionMoveError.DomainRejection]] triggers [[AppEvent.MoveRejected]]. Infrastructure
    * failures (persistence errors, session not found) and unauthorized-controller rejections are
    * caller errors, not game events, and are returned as-is without publishing.
    */
  def submitMove(
      gameId: GameId,
      move: Move,
      controller: SideController,
      now: Instant = Instant.now()
  ): Either[SessionMoveError, (GameState, GameSession)] =
    // Load session and state up-front so we have the sessionId available for
    // MoveRejected publication even when the move itself is rejected.
    val sessionResult = sessionService.getSessionByGameId(gameId)
    val stateResult = gameRepository.load(gameId)

    val result =
      for
        session <- sessionResult.left.map(e => SessionMoveError.PersistenceFailed(e))
        state <- stateResult.left.map(e =>
          SessionMoveError.PersistenceFailed(SessionError.PersistenceFailed(e))
        )
        pair <- commands.submitMove(session, state, move, controller, now)
      yield pair

    // Publish MoveRejected only when both session and state were loaded
    // successfully but the domain or session policy rejected the move.
    (sessionResult, result) match
      case (Right(session), Left(SessionMoveError.DomainRejection(appErr))) =>
        publisher.publish(AppEvent.MoveRejected(session.sessionId, gameId, move, appErr.toString))
      case _ => ()

    result

  def resignGame(
      sessionId: SessionId,
      resigningSide: Color,
      now: Instant = Instant.now()
  ): Either[SessionError, (GameState, GameSession)] =
    for
      session <- sessionService.getSession(sessionId)
      state <- gameRepository.load(session.gameId).left.map(SessionError.PersistenceFailed(_))
      result <- commands.resignGame(session, state, resigningSide, now)
    yield result

  /** Cancel a session administratively.
    *
    * Session cancellation is an '''administrative termination''', not a game result. The underlying
    * [[chess.domain.state.GameState]] is intentionally left unchanged: no winner is recorded, no
    * [[chess.domain.model.GameStatus]] transition occurs. Only the session lifecycle advances to
    * [[chess.application.session.model.SessionLifecycle.Cancelled]].
    *
    * This is distinct from [[resignGame]], which modifies the game state to
    * [[chess.domain.model.GameStatus.Resigned]] and records a winner before finishing the session.
    */
  def cancelSession(
      sessionId: SessionId,
      now: Instant = Instant.now()
  ): Either[SessionError, GameSession] =
    sessionService.cancelSession(sessionId, now)

  def triggerAIMove(
      sessionId: SessionId,
      now: Instant = Instant.now()
  ): Either[AITurnError, (GameState, GameSession)] =
    aiService match
      case None => Left(AITurnError.NotConfigured)
      case Some(ai) =>
        for
          session <- sessionService
            .getSession(sessionId)
            .left
            .map(AITurnError.SessionLookupFailed(_))
          state <- gameRepository
            .load(session.gameId)
            .left
            .map(AITurnError.GameStateLookupFailed(_))
          result <- ai.requestAIMove(session, state, now)
        yield result

  /** Resolves session from [[gameId]] then delegates to [[triggerAIMove]].
    *
    * REST adapters carry game IDs in the URL; calling this avoids a redundant session lookup that
    * would otherwise happen once in the route (to convert game ID to session ID) and once inside
    * [[triggerAIMove]] (to reload the session). The service boundary owns the mapping; the route
    * stays thin.
    */
  def triggerAIMoveByGameId(
      gameId: GameId,
      now: Instant = Instant.now()
  ): Either[AITurnError, (GameState, GameSession)] =
    aiService match
      case None => Left(AITurnError.NotConfigured)
      case Some(ai) =>
        for
          session <- sessionService
            .getSessionByGameId(gameId)
            .left
            .map(AITurnError.SessionLookupFailed(_))
          state <- gameRepository.load(gameId).left.map(AITurnError.GameStateLookupFailed(_))
          result <- ai.requestAIMove(session, state, now)
        yield result

  // ── Queries ─────────────────────────────────────────────────────────────────

  def getSession(id: SessionId): Either[SessionError, SessionView] =
    sessionService.getSession(id).map(SessionView.fromSession)

  def getSessionByGameId(id: GameId): Either[SessionError, SessionView] =
    sessionService.getSessionByGameId(id).map(SessionView.fromSession)

  def getGame(id: GameId): Either[RepositoryError, GameView] =
    gameRepository.load(id).map(GameView.fromState(id, _))

  def getLegalMoves(id: GameId): Either[RepositoryError, LegalMovesView] =
    gameRepository.load(id).map { state =>
      LegalMovesView(
        gameId = id,
        currentPlayer = state.currentPlayer,
        moves = GameStateRules.legalMoves(state)
      )
    }

  def listActiveSessions(): Either[SessionError, List[SessionView]] =
    sessionService.listActiveSessions().map(_.map(SessionView.fromSession))

  def getArchiveSnapshot(id: GameId): Either[ArchiveError, GameArchiveSnapshot] =
    for
      session <- sessionService.getSessionByGameId(id).left.map {
        case SessionError.GameSessionNotFound(_) => ArchiveError.GameNotFound(id)
        case e                                   => ArchiveError.StorageFailure(e.toString)
      }
      _ <- Either.cond(
        session.lifecycle == SessionLifecycle.Finished ||
          session.lifecycle == SessionLifecycle.Cancelled,
        (),
        ArchiveError.GameNotClosed(id)
      )
      state <- gameRepository.load(id).left.map {
        case RepositoryError.NotFound(_)         => ArchiveError.GameNotFound(id)
        case RepositoryError.StorageFailure(msg) => ArchiveError.StorageFailure(msg)
      }
    yield GameArchiveSnapshot(
      sessionId = session.sessionId,
      gameId = id,
      mode = session.mode,
      whiteController = session.whiteController,
      blackController = session.blackController,
      closure = GameClosure.fromStatus(state.status),
      finalState = GameView.fromState(id, state),
      createdAt = session.createdAt,
      closedAt = session.updatedAt
    )
