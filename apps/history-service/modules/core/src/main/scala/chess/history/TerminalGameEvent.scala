package chess.history

import chess.application.session.model.SessionIds.{GameId, SessionId}
import java.util.UUID

final case class TerminalGameEvent(eventType: String, sessionId: SessionId, gameId: GameId)

object TerminalGameEvent:
  private val SupportedTypes = Set(
    "game.finished.v1",
    "game.resigned.v1",
    "game.session.cancelled.v1"
  )

  def fromJson(body: String): Either[String, TerminalGameEvent] =
    try
      val json = ujson.read(body)
      val tpe = json("type").str
      if !SupportedTypes.contains(tpe) then
        Left(s"Unsupported event type for archive trigger: $tpe")
      else
        Right(
          TerminalGameEvent(
            eventType = tpe,
            sessionId = SessionId(UUID.fromString(json("sessionId").str)),
            gameId = GameId(UUID.fromString(json("gameId").str))
          )
        )
    catch
      case e: NoSuchElementException   => Left(s"Missing required event field: ${e.getMessage}")
      case e: IllegalArgumentException => Left(s"Invalid terminal event JSON: ${e.getMessage}")
      case e: Exception                => Left(s"Malformed terminal event JSON: ${e.getMessage}")
