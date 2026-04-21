package chess.application.query.session

import chess.application.session.model.{GameSession, SessionLifecycle, SessionMode, SideController}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import java.time.Instant

/** Application read model for session state exposed through the Game Service boundary.
  *
  * This is intentionally not a REST DTO. It represents the session facts that inbound adapters may
  * query without exposing the internal [[GameSession]] aggregate as the long-term process boundary
  * contract.
  */
final case class SessionView(
    sessionId: SessionId,
    gameId: GameId,
    mode: SessionMode,
    whiteController: SideController,
    blackController: SideController,
    lifecycle: SessionLifecycle,
    createdAt: Instant,
    updatedAt: Instant
)

object SessionView:
  def fromSession(session: GameSession): SessionView =
    SessionView(
      sessionId = session.sessionId,
      gameId = session.gameId,
      mode = session.mode,
      whiteController = session.whiteController,
      blackController = session.blackController,
      lifecycle = session.lifecycle,
      createdAt = session.createdAt,
      updatedAt = session.updatedAt
    )
