package chess.adapter.event

import chess.application.event.AppEvent
import chess.application.port.event.EventPublisher
import chess.observability.StructuredLog
import redis.clients.jedis.JedisPooled
import redis.clients.jedis.StreamEntryID
import scala.util.control.NonFatal

class RedisStreamHistoryPublisher(
    jedis: JedisPooled,
    streamName: String = GameStreamEvent.StreamName
) extends EventPublisher:

  override def publish(event: AppEvent): Unit =
    HistoryArchiveStreamEvent.fromAppEvent(event).foreach { envelope =>
      GameStreamEvent.eventTypeTag(event).foreach { tag =>
        try
          jedis.xadd(streamName, StreamEntryID.NEW_ENTRY, HistoryArchiveStreamEvent.toFields(envelope))
          StructuredLog.info(
            "game-service",
            "history_stream_published",
            "eventId"   -> envelope.eventId,
            "eventType" -> envelope.eventType,
            "sourceEventType" -> tag,
            "gameId"    -> event.gameId.value.toString,
            "sessionId" -> event.sessionId.value.toString
          )
        catch
          case NonFatal(e) =>
            StructuredLog.warn(
              "game-service",
              "history_stream_publish_failed",
              "eventId"   -> envelope.eventId,
              "eventType" -> envelope.eventType,
              "sourceEventType" -> tag,
              "gameId"    -> event.gameId.value.toString,
              "sessionId" -> event.sessionId.value.toString,
              "error"     -> e.getMessage
            )
      }
    }
