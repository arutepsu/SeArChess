package chess.tournamentservice

import chess.tournamentservice.db.OutboxEvent
import ujson.{Obj, Str}

object KafkaEnvelopeBuilder:

  def build(event: OutboxEvent): Either[String, String] =
    try
      val payload = ujson.read(event.payloadJson)
      Right(ujson.write(Obj(
        "eventId"       -> Str(event.eventId),
        "eventType"     -> Str(event.eventType),
        "aggregateType" -> Str(event.aggregateType),
        "aggregateId"   -> Str(event.aggregateId),
        "occurredAt"    -> Str(event.createdAt.toString),
        "schemaVersion" -> 1.0,
        "payload"       -> payload
      )))
    catch
      case e: Exception =>
        Left(s"Failed to build Kafka envelope for event ${event.eventId} (${event.eventType}): ${e.getMessage}")
