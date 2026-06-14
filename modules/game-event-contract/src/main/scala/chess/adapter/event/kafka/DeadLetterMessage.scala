package chess.adapter.event.kafka

import java.time.Instant

enum FailureCategory:
  case Retryable, NonRetryable

final case class DeadLetterMessage(
    originalTopic: String,
    originalPartition: Int,
    originalOffset: Long,
    eventId: Option[String],
    eventType: Option[String],
    consumerGroup: String,
    failureCategory: FailureCategory,
    failureReason: String,
    failedAt: Instant,
    originalPayload: String
)

object DeadLetterMessage:
  def write(message: DeadLetterMessage): String =
    ujson.write(
      ujson.Obj(
        "originalTopic" -> message.originalTopic,
        "originalPartition" -> message.originalPartition,
        "originalOffset" -> message.originalOffset,
        "eventId" -> message.eventId.fold[ujson.Value](ujson.Null)(ujson.Str.apply),
        "eventType" -> message.eventType.fold[ujson.Value](ujson.Null)(ujson.Str.apply),
        "consumerGroup" -> message.consumerGroup,
        "failureCategory" -> message.failureCategory.toString,
        "failureReason" -> message.failureReason,
        "failedAt" -> message.failedAt.toString,
        "originalPayload" -> message.originalPayload
      )
    )
