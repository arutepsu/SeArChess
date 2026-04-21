package chess.adapter.http4s.mapper

import chess.adapter.rest.contract.dto.{CreateSessionResponse, GameResponse, SessionResponse}
import chess.application.query.session.SessionView
import chess.application.session.model.{GameSession, SessionLifecycle, SessionMode, SideController}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.domain.model.Color

/** Maps between session application models and REST DTOs.
  *
  * Session creation defaults:
  *   - HumanVsHuman: both sides HumanLocal unless explicitly set to a human controller
  *   - HumanVsAI: White human, Black server AI
  *   - AIVsAI: both sides server AI
  *
  * REST v1 does not accept the literal "AI" as an inbound controller value. AI controllers are
  * derived from [[SessionMode]] by [[resolveCreateControllers]].
  */
object SessionMapper:

  // ── inbound parsing ───────────────────────────────────────────────────────

  /** Parse a [[SessionMode]] from an optional request string. Absent or "HumanVsHuman" ->
    * [[SessionMode.HumanVsHuman]] (default).
    */
  def parseMode(s: Option[String]): Either[String, SessionMode] =
    s match
      case None | Some("HumanVsHuman") => Right(SessionMode.HumanVsHuman)
      case Some("HumanVsAI")           => Right(SessionMode.HumanVsAI)
      case Some("AIVsAI")              => Right(SessionMode.AIVsAI)
      case Some(other) =>
        Left(s"Unknown mode: '$other'. Expected HumanVsHuman, HumanVsAI, or AIVsAI")

  /** Parse a human [[SideController]] from an optional request string. Absent or "HumanLocal" ->
    * [[SideController.HumanLocal]] (default).
    *
    * "AI" is intentionally not accepted here. REST v1 derives AI-controlled seats from
    * [[SessionMode]] in [[resolveCreateControllers]].
    */
  def parseController(s: Option[String]): Either[String, SideController] =
    s match
      case None | Some("HumanLocal") => Right(SideController.HumanLocal)
      case Some("HumanRemote")       => Right(SideController.HumanRemote)
      case Some(other) =>
        Left(s"Unknown controller: '$other'. Expected HumanLocal or HumanRemote")

  /** Resolve session-creation controllers from REST v1 mode and optional fields.
    *
    * Rule: mode determines AI seats server-side. Controller fields are accepted only for
    * human-controlled seats; clients cannot request AI engine identity or override an AI seat
    * through the REST contract.
    */
  def resolveCreateControllers(
      mode: SessionMode,
      white: Option[String],
      black: Option[String]
  ): Either[String, (SideController, SideController)] =
    mode match
      case SessionMode.HumanVsHuman =>
        for
          whiteController <- parseController(white)
          blackController <- parseController(black)
        yield (whiteController, blackController)

      case SessionMode.HumanVsAI =>
        black match
          case Some(_) =>
            Left("blackController is server-controlled AI for HumanVsAI; omit blackController")
          case None =>
            parseController(white).map(whiteController => (whiteController, SideController.AI()))

      case SessionMode.AIVsAI =>
        if white.isDefined || black.isDefined then
          Left(
            "Controllers are server-controlled AI for AIVsAI; omit whiteController and blackController"
          )
        else Right((SideController.AI(), SideController.AI()))

  /** Parse a [[Color]] side from a request string.
    *
    * Expected values: "White" or "Black".
    */
  def parseSide(s: String): Either[String, Color] =
    Color.values
      .find(_.toString == s)
      .toRight(s"Unknown side: '$s'. Expected 'White' or 'Black'")

  // ── outbound mapping ──────────────────────────────────────────────────────

  /** Map a [[GameSession]] to its transport representation. */
  def toSessionResponse(session: GameSession): SessionResponse =
    toSessionResponse(SessionView.fromSession(session))

  /** Map a Game Service session read model to its transport representation. */
  def toSessionResponse(session: SessionView): SessionResponse =
    SessionResponse(
      sessionId = session.sessionId.value.toString,
      gameId = session.gameId.value.toString,
      mode = session.mode.toString,
      lifecycle = session.lifecycle.toString,
      whiteController = controllerToString(session.whiteController),
      blackController = controllerToString(session.blackController),
      createdAt = session.createdAt.toString,
      updatedAt = session.updatedAt.toString
    )

  /** Combine session metadata with the initial game state into a creation response. */
  def toCreateSessionResponse(
      session: GameSession,
      gameId: GameId,
      gameResp: GameResponse
  ): CreateSessionResponse =
    CreateSessionResponse(
      session = toSessionResponse(session),
      game = gameResp
    )

  // ── private helpers ───────────────────────────────────────────────────────

  /** Serialize a [[SideController]] to a stable transport string.
    *
    * AI engine ids are not surfaced in Phase 7.
    */
  private[mapper] def controllerToString(c: SideController): String =
    c match
      case SideController.HumanLocal  => "HumanLocal"
      case SideController.HumanRemote => "HumanRemote"
      case SideController.AI(_)       => "AI"
