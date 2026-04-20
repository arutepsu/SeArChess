package chess.application.session.model

import chess.application.session.model.SessionIds.{GameId, SessionId}
import java.time.Instant

/** Immutable application model representing the orchestration context around a game.
 *
 *  === What GameSession is ===
 *  `GameSession` is the interaction truth of a match: who is playing, in what
 *  mode, and where the session stands in its lifecycle.  It does NOT contain
 *  chess board state, move legality, castling rights, or any domain rule logic.
 *  Those concerns live exclusively in [[chess.domain.state.GameState]].
 *
 *  === Relationship to GameState ===
 *  `GameSession` references its underlying game by [[gameId]] only.
 *  Retrieving the live board requires a separate lookup (e.g. a future
 *  `GameRepository.load(gameId)` call).  This keeps the session model light
 *  and prevents domain state from being duplicated or mutated outside the
 *  domain layer.
 *
 *  === Timestamps ===
 *  [[createdAt]] and [[updatedAt]] use `java.time.Instant` (UTC wall-clock).
 *  The session service is responsible for supplying and advancing these values;
 *  `GameSession` is a passive data holder.
 *
 *  @param sessionId       unique identifier for this session
 *  @param gameId          identifier of the associated game record
 *  @param mode            match configuration (HumanVsHuman, HumanVsAI, AIVsAI)
 *  @param whiteController who or what supplies moves for the White side
 *  @param blackController who or what supplies moves for the Black side
 *  @param lifecycle       orchestration lifecycle phase of the session
 *  @param createdAt       wall-clock instant when the session was created
 *  @param updatedAt       wall-clock instant of the most recent state change
 */
final case class GameSession(
  sessionId:       SessionId,
  gameId:          GameId,
  mode:            SessionMode,
  whiteController: SideController,
  blackController: SideController,
  lifecycle:       SessionLifecycle,
  createdAt:       Instant,
  updatedAt:       Instant
)

object GameSession:

  /** Create a fresh session in the [[SessionLifecycle.Created]] phase.
   *
   *  The caller supplies a [[GameId]] because game identity is assigned by
   *  whoever creates the underlying `GameState` record (e.g. a factory or
   *  repository).  The [[SessionId]] is generated fresh here.
   */
  def create(
    gameId:          GameId,
    mode:            SessionMode,
    whiteController: SideController,
    blackController: SideController,
    now:             Instant = Instant.now()
  ): GameSession =
    GameSession(
      sessionId       = SessionId.random(),
      gameId          = gameId,
      mode            = mode,
      whiteController = whiteController,
      blackController = blackController,
      lifecycle       = SessionLifecycle.Created,
      createdAt       = now,
      updatedAt       = now
    )

  /** Transition to a new lifecycle phase, updating [[updatedAt]]. */
  def withLifecycle(session: GameSession, next: SessionLifecycle, now: Instant = Instant.now()): GameSession =
    session.copy(lifecycle = next, updatedAt = now)
