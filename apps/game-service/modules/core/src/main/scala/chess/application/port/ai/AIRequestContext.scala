package chess.application.port.ai

import chess.application.session.model.{GameSession, SessionMode}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.domain.model.Color
import chess.domain.state.GameState
import java.util.UUID

/** Application-owned request context for AI move suggestion.
  *
  * This is the stable AI port input. It deliberately carries Game Service context that a remote AI
  * collaborator needs for diagnostics, correlation, and engine selection, while keeping authority
  * in Game Service: the returned move is still only a candidate and must be validated/applied by
  * the normal move path.
  */
final case class AIRequestContext(
    requestId: String,
    sessionId: SessionId,
    gameId: GameId,
    mode: SessionMode,
    sideToMove: Color,
    state: GameState,
    engineId: Option[String]
)

object AIRequestContext:

  def fromSession(
      session: GameSession,
      state: GameState,
      requestId: String = UUID.randomUUID().toString
  ): AIRequestContext =
    AIRequestContext(
      requestId = requestId,
      sessionId = session.sessionId,
      gameId = session.gameId,
      mode = session.mode,
      sideToMove = state.currentPlayer,
      state = state,
      engineId = engineIdFor(session, state.currentPlayer)
    )

  private def engineIdFor(session: GameSession, side: Color): Option[String] =
    val controller = side match
      case Color.White => session.whiteController
      case Color.Black => session.blackController

    controller match
      case chess.application.session.model.SideController.AI(engineId) => engineId
      case _                                                           => None
