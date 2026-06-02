package chess.application

import chess.application.ai.service.AITurnError
import chess.application.port.repository.RepositoryError
import chess.application.query.game.{GameArchiveSnapshot, GameView, LegalMovesView}
import chess.application.query.session.SessionView
import chess.application.session.model.{GameSession, SessionMode, SideController}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.application.session.service.{SessionError, SessionMoveError}
import chess.domain.model.{Color, Move}
import chess.domain.state.GameState
import java.time.Instant

/** The explicit public boundary of the Game Service.
  *
  * This trait is the single entry point for all inbound adapters (REST, WebSocket command channel,
  * future gRPC, etc.) that need to interact with the game-session capability. It intentionally
  * hides the internal coordination between
  * [[chess.application.session.service.SessionGameCommandService]],
  * [[chess.application.session.service.SessionLifecycleService]], and the underlying
  * repository/event ports from transport adapters.
  *
  * ===Command/query ownership===
  * Commands (state-mutating operations) load their own session and game state from the given
  * identifiers. This keeps transport adapters thin: they parse the request, call one method, and
  * map the result to a response.
  *
  * Queries return current state without side effects.
  *
  * ===Extraction boundary===
  * When the Game Service is extracted to a standalone process this trait becomes the service
  * contract. A remote implementation (HTTP client, gRPC stub) can replace [[DefaultGameService]]
  * without touching any adapter.
  *
  * ===What this interface does NOT cover===
  *   - Tournament bracket management
  *   - Long-term game archival / search (History Service)
  *   - Bot / platform integration (Bot Service)
  *   - Analytics
  */
trait GameServiceApi:

  // ── Commands ────────────────────────────────────────────────────────────────

  /** Create a new session containing a fresh game at the starting position.
    *
    * Atomically persists session metadata and the initial [[GameState]], then publishes
    * [[chess.application.event.AppEvent.SessionCreated]].
    */
  def createGame(
      mode: SessionMode,
      whiteController: SideController,
      blackController: SideController,
      now: Instant = Instant.now()
  ): Either[SessionError, (GameState, GameSession)]

  /** Submit a move for the game identified by [[gameId]].
    *
    * Loads the session and game state, validates the move against session policy and domain rules,
    * then persists atomically. On domain rejection, publishes
    * [[chess.application.event.AppEvent.MoveRejected]]. On success, publishes
    * [[chess.application.event.AppEvent.MoveApplied]] (and
    * [[chess.application.event.AppEvent.GameFinished]] if the position is terminal).
    */
  def submitMove(
      gameId: GameId,
      move: Move,
      controller: SideController,
      now: Instant = Instant.now()
  ): Either[SessionMoveError, (GameState, GameSession)]

  /** Resign the game on behalf of [[resigningSide]].
    *
    * Records [[chess.domain.model.GameStatus.Resigned]] on the game state, advances the session to
    * [[chess.application.session.model.SessionLifecycle.Finished]], and persists atomically.
    * Publishes [[chess.application.event.AppEvent.GameResigned]].
    *
    * Either player may resign at any time while the session is active.
    */
  def resignGame(
      sessionId: SessionId,
      resigningSide: Color,
      now: Instant = Instant.now()
  ): Either[SessionError, (GameState, GameSession)]

  /** Cancel a session administratively.
    *
    * '''Administrative termination''' — not a game result. The underlying
    * [[chess.domain.state.GameState]] is intentionally left unchanged: no winner is recorded and no
    * [[chess.domain.model.GameStatus]] transition occurs. Only the session lifecycle advances to
    * [[chess.application.session.model.SessionLifecycle.Finished]].
    *
    * This is distinct from [[resignGame]], which modifies the game state to
    * [[chess.domain.model.GameStatus.Resigned]] and records a winner before finishing the session.
    *
    * Publishes [[chess.application.event.AppEvent.SessionCancelled]]. Returns
    * [[SessionError.InvalidLifecycleTransition]] when the session is already finished.
    */
  def cancelSession(
      sessionId: SessionId,
      now: Instant = Instant.now()
  ): Either[SessionError, GameSession]

  /** Ask the configured AI client to suggest and submit a move for the current player.
    *
    * The AI move passes through the same domain-validation and persistence path as a human move.
    *
    * This overload takes a [[SessionId]] for callers (e.g. WebSocket channel) that already hold the
    * session identity. REST adapters that resolve a game ID from the URL should prefer
    * [[triggerAIMoveByGameId]] to avoid a redundant lookup.
    *
    * ===Error taxonomy===
    *   - [[AITurnError.NotConfigured]] — no AI service is wired; operation unavailable
    *   - [[AITurnError.NotAITurn]] — current side is not AI-controlled (policy)
    *   - [[AITurnError.SessionLookupFailed]] — session not found or storage error
    *   - [[AITurnError.GameStateLookupFailed]] — game state not found or storage error
    *   - [[AITurnError.ProviderFailure]] — AI engine could not produce a candidate
    *   - [[AITurnError.MoveFailed]] — AI's proposed move rejected by domain/session
    */
  def triggerAIMove(
      sessionId: SessionId,
      now: Instant = Instant.now()
  ): Either[AITurnError, (GameState, GameSession)]

  /** Ask the configured AI client to suggest and submit a move, resolving the session from a
    * [[GameId]].
    *
    * Equivalent to [[triggerAIMove]] but accepts a [[GameId]] directly, which is the natural
    * identifier available to REST adapters. The game-to-session mapping is handled internally, so
    * the route performs no lookup before calling this method.
    *
    * The error taxonomy is identical to [[triggerAIMove]].
    */
  def triggerAIMoveByGameId(
      gameId: GameId,
      now: Instant = Instant.now()
  ): Either[AITurnError, (GameState, GameSession)]

  // ── Queries ─────────────────────────────────────────────────────────────────

  /** Load a session view by its [[SessionId]]. */
  def getSession(id: SessionId): Either[SessionError, SessionView]

  /** Load the session view associated with a [[GameId]]. */
  def getSessionByGameId(id: GameId): Either[SessionError, SessionView]

  /** Load the current game state as an application read model by [[GameId]].
    *
    * Returns a [[GameView]] that includes pre-computed legal moves so that adapter mappers do not
    * depend on [[chess.domain.rules.GameStateRules]] directly.
    */
  def getGame(id: GameId): Either[RepositoryError, GameView]

  /** Return the legal moves available to the current player in the active game.
    *
    * This query is part of the Game Service application boundary. Transport adapters map the
    * returned [[LegalMovesView]] into their own DTOs.
    */
  def getLegalMoves(id: GameId): Either[RepositoryError, LegalMovesView]

  /** Return all session views that have not yet reached the Finished lifecycle phase. */
  def listActiveSessions(): Either[SessionError, List[SessionView]]

  /** Build an archive snapshot for a finished game session.
    *
    * Returns [[ArchiveError.GameNotFound]] if no session is associated with the given [[GameId]],
    * [[ArchiveError.GameNotClosed]] if the session has not yet reached `SessionLifecycle.Finished`,
    * or [[ArchiveError.StorageFailure]] on infrastructure error.
    *
    * This is the intended entry point for a future History / Notation service that consumes
    * terminal game events.
    */
  def getArchiveSnapshot(id: GameId): Either[ArchiveError, GameArchiveSnapshot]

  /** Reconstruct an exact replay frame by applying raw persisted moves up to [[ply]].
    *
    * Returns a [[GameView]] representing the board state at the requested ply, where `ply = 0`
    * means the initial position and `ply = totalPlies` means the current persisted state.
    */
  def getReplayFrame(id: GameId, ply: Int): Either[ReplayError, GameView]
