package chess.adapter.event.kafka

import java.time.Instant

final case class EventEnvelope[T](
    eventId: String,
    eventType: String,
    eventVersion: Int,
    occurredAt: Instant,
    producer: String,
    correlationId: String,
    causationId: Option[String],
    aggregateType: String,
    aggregateId: String,
    payload: T
)

object EventEnvelope:
  val SupportedVersion: Int = 1

  def writePayloadEnvelope(envelope: EventEnvelope[ujson.Value]): String =
    ujson.write(
      ujson.Obj(
        "eventId" -> envelope.eventId,
        "eventType" -> envelope.eventType,
        "eventVersion" -> envelope.eventVersion,
        "occurredAt" -> envelope.occurredAt.toString,
        "producer" -> envelope.producer,
        "correlationId" -> envelope.correlationId,
        "causationId" -> envelope.causationId.fold[ujson.Value](ujson.Null)(ujson.Str.apply),
        "aggregateType" -> envelope.aggregateType,
        "aggregateId" -> envelope.aggregateId,
        "payload" -> envelope.payload
      )
    )

  def readPayloadEnvelope(json: String): Either[String, EventEnvelope[ujson.Value]] =
    try
      val value = ujson.read(json)
      Right(
        EventEnvelope(
          eventId       = requiredString(value, "eventId"),
          eventType     = requiredString(value, "eventType"),
          eventVersion  = requiredInt(value, "eventVersion"),
          occurredAt    = Instant.parse(requiredString(value, "occurredAt")),
          producer      = requiredString(value, "producer"),
          correlationId = requiredString(value, "correlationId"),
          causationId   = optionalString(value, "causationId"),
          aggregateType = requiredString(value, "aggregateType"),
          aggregateId   = requiredString(value, "aggregateId"),
          payload       = value("payload")
        )
      )
    catch
      case e: NoSuchElementException => Left(s"missing envelope field: ${e.getMessage}")
      case e: Exception              => Left(s"invalid envelope JSON: ${e.getMessage}")

  private def requiredString(value: ujson.Value, field: String): String =
    value(field).str.trim match
      case ""    => throw IllegalArgumentException(s"$field must not be blank")
      case value => value

  private def requiredInt(value: ujson.Value, field: String): Int =
    value(field).num.toInt

  private def optionalString(value: ujson.Value, field: String): Option[String] =
    value.obj.get(field).flatMap {
      case ujson.Null => None
      case value =>
        val text = value.str.trim
        Option.when(text.nonEmpty)(text)
    }
