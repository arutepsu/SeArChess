package chess.adapter.event

import chess.application.event.AppEvent
import chess.application.session.model.SessionIds.GameId

import java.time.Instant
import java.util.UUID

final case class HistoryArchiveStreamEvent(
    eventId: String,
    eventType: String,
    eventVersion: Int,
    occurredAt: Instant,
    gameId: GameId,
    payloadJson: String
)

object HistoryArchiveStreamEvent:
  val StreamName: String    = "searchess.history.archives"
  val EventType: String     = "history.archive.requested"
  val EventVersion: Int     = 1
  val PayloadJsonField      = "payloadJson"
  val EventTypeField        = "eventType"
  val EventIdField          = "eventId"
  val EventVersionField     = "eventVersion"
  val OccurredAtField       = "occurredAt"
  val GameIdField           = "gameId"
  val SourceEventTypeField  = "sourceEventType"

  def fromAppEvent(event: AppEvent, occurredAt: Instant = Instant.now()): Option[HistoryArchiveStreamEvent] =
    sourceEventType(event).flatMap { _ =>
      AppEventSerializer.serialize(event).map { payload =>
        HistoryArchiveStreamEvent(
          eventId      = UUID.randomUUID().toString,
          eventType    = EventType,
          eventVersion = EventVersion,
          occurredAt   = occurredAt,
          gameId       = event.gameId,
          payloadJson  = payload
        )
      }
    }

  def sourceEventType(event: AppEvent): Option[String] = event match
    case _: AppEvent.GameFinished     => Some("game.finished.v1")
    case _: AppEvent.GameResigned     => Some("game.resigned.v1")
    case _: AppEvent.SessionCancelled => Some("game.session.cancelled.v1")
    case _                            => None

  def toFields(event: HistoryArchiveStreamEvent): java.util.Map[String, String] =
    val fields = new java.util.HashMap[String, String]()
    fields.put(EventIdField, event.eventId)
    fields.put(EventTypeField, event.eventType)
    fields.put(EventVersionField, event.eventVersion.toString)
    fields.put(OccurredAtField, event.occurredAt.toString)
    fields.put(GameIdField, event.gameId.value.toString)
    fields.put(PayloadJsonField, event.payloadJson)
    TerminalType.fromPayload(event.payloadJson).foreach(fields.put(SourceEventTypeField, _))
    fields

  def fromFields(fields: java.util.Map[String, String]): Either[String, HistoryArchiveStreamEvent] =
    for
      eventId <- required(fields, EventIdField)
      eventType <- required(fields, EventTypeField).flatMap {
        case EventType => Right(EventType)
        case other     => Left(s"unsupported eventType: $other")
      }
      version <- required(fields, EventVersionField).flatMap { raw =>
        raw.toIntOption.filter(_ == EventVersion).toRight(s"unsupported eventVersion: $raw")
      }
      occurredAt <- required(fields, OccurredAtField).flatMap { raw =>
        try Right(Instant.parse(raw))
        catch case _: java.time.format.DateTimeParseException => Left(s"invalid occurredAt: $raw")
      }
      gameId <- required(fields, GameIdField).flatMap { raw =>
        try Right(GameId(UUID.fromString(raw)))
        catch case _: IllegalArgumentException => Left(s"invalid gameId: $raw")
      }
      payload <- required(fields, PayloadJsonField)
      payloadGameId <- TerminalType.gameIdFromPayload(payload)
      _ <- Either.cond(payloadGameId == gameId, (), "gameId does not match payloadJson")
    yield HistoryArchiveStreamEvent(eventId, eventType, version, occurredAt, gameId, payload)

  private def required(fields: java.util.Map[String, String], name: String): Either[String, String] =
    Option(fields.get(name)).map(_.trim).filter(_.nonEmpty).toRight(s"missing field: $name")

  private object TerminalType:
    def fromPayload(payloadJson: String): Option[String] =
      try Some(ujson.read(payloadJson)("type").str)
      catch case _: Exception => None

    def gameIdFromPayload(payloadJson: String): Either[String, GameId] =
      try Right(GameId(UUID.fromString(ujson.read(payloadJson)("gameId").str)))
      catch case e: Exception => Left(s"invalid payloadJson: ${e.getMessage}")
