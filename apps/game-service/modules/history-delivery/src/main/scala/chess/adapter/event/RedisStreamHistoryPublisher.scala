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
    GameStreamEvent.eventTypeTag(event).foreach { tag =>
      AppEventSerializer.serialize(event).foreach { json =>
        val fields = new java.util.HashMap[String, String]()
        fields.put("eventType", tag)
        fields.put("payload", json)
        try
          jedis.xadd(streamName, StreamEntryID.NEW_ENTRY, fields)
          StructuredLog.info(
            "game-service",
            "history_stream_published",
            "eventType" -> tag,
            "gameId"    -> event.gameId.value.toString,
            "sessionId" -> event.sessionId.value.toString
          )
        catch
          case NonFatal(e) =>
            StructuredLog.warn(
              "game-service",
              "history_stream_publish_failed",
              "eventType" -> tag,
              "gameId"    -> event.gameId.value.toString,
              "sessionId" -> event.sessionId.value.toString,
              "error"     -> e.getMessage
            )
      }
    }
