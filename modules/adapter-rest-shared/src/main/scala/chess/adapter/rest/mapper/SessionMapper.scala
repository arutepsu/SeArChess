package chess.adapter.rest.mapper

import chess.adapter.rest.dto.{CreateSessionResponse, GameResponse, SessionResponse}
import chess.application.session.model.{GameSession, SessionLifecycle, SessionMode, SideController}
import chess.application.session.model.SessionIds.{GameId, SessionId}

/** Maps between session application models and REST DTOs.
 *
 *  Parsing defaults:
 *  - absent `mode`       → [[SessionMode.HumanVsHuman]]
 *  - absent `controller` → [[SideController.HumanLocal]]
 */
object SessionMapper:

  // ── inbound parsing ────────────────────────────────────────────────────────

  /** Parse a [[SessionMode]] from an optional request string.
   *  Absent or "HumanVsHuman" → [[SessionMode.HumanVsHuman]] (default).
   */
  def parseMode(s: Option[String]): Either[String, SessionMode] =
    s match
      case None | Some("HumanVsHuman") => Right(SessionMode.HumanVsHuman)
      case Some("HumanVsAI")           => Right(SessionMode.HumanVsAI)
      case Some("AIVsAI")              => Right(SessionMode.AIVsAI)
      case Some(other)                 => Left(s"Unknown mode: '$other'. Expected HumanVsHuman, HumanVsAI, or AIVsAI")

  /** Parse a [[SideController]] from an optional request string.
   *  Absent or "HumanLocal" → [[SideController.HumanLocal]] (default).
   */
  def parseController(s: Option[String]): Either[String, SideController] =
    s match
      case None | Some("HumanLocal")  => Right(SideController.HumanLocal)
      case Some("HumanRemote")        => Right(SideController.HumanRemote)
      case Some(other)                =>
        Left(s"Unknown controller: '$other'. Expected HumanLocal or HumanRemote")

  // ── outbound mapping ───────────────────────────────────────────────────────

  /** Map a [[GameSession]] to its transport representation. */
  def toSessionResponse(session: GameSession): SessionResponse =
    SessionResponse(
      sessionId       = session.sessionId.value.toString,
      gameId          = session.gameId.value.toString,
      mode            = session.mode.toString,
      lifecycle       = session.lifecycle.toString,
      whiteController = controllerToString(session.whiteController),
      blackController = controllerToString(session.blackController),
      createdAt       = session.createdAt.toString,
      updatedAt       = session.updatedAt.toString
    )

  /** Combine session metadata with the initial game state into a creation response. */
  def toCreateSessionResponse(
    session:  GameSession,
    gameId:   GameId,
    gameResp: GameResponse
  ): CreateSessionResponse =
    CreateSessionResponse(
      session = toSessionResponse(session),
      game    = gameResp
    )

  // ── private helpers ────────────────────────────────────────────────────────

  /** Serialize a [[SideController]] to a stable transport string.
   *
   *  AI engine ids are not surfaced in Phase 7.
   */
  private[mapper] def controllerToString(c: SideController): String =
    c match
      case SideController.HumanLocal  => "HumanLocal"
      case SideController.HumanRemote => "HumanRemote"
      case SideController.AI(_)       => "AI"
