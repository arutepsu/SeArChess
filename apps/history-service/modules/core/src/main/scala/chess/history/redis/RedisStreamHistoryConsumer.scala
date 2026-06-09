package chess.history.redis

import chess.adapter.event.HistoryArchiveStreamEvent
import chess.history.{ArchiveRecord, HistoryIngestionError}
import chess.observability.StructuredLog
import redis.clients.jedis.{Jedis, StreamEntryID}
import redis.clients.jedis.params.XReadGroupParams
import redis.clients.jedis.resps.StreamEntry
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

class RedisStreamHistoryConsumer(
    host: String,
    port: Int,
    ingest: String => Either[HistoryIngestionError, ArchiveRecord],
    batchSize: Int = 10,
    pollBlockMillis: Long = 2000L,
    streamName: String = RedisStreamHistoryConsumer.DefaultStreamName,
    groupName: String = RedisStreamHistoryConsumer.DefaultGroupName,
    consumerName: String = RedisStreamHistoryConsumer.defaultConsumerName()
):
  private val PollBlockMillis =
    require(pollBlockMillis >= 0L && pollBlockMillis <= Int.MaxValue, "pollBlockMillis must fit in an Int")
    pollBlockMillis.toInt

  @volatile private var running = false
  private var consumerThread: Thread = scala.compiletime.uninitialized

  def start(): Unit =
    running = true
    val jedis = new Jedis(host, port)
    ensureConsumerGroup(jedis)
    consumerThread = Thread(() => runLoop(jedis))
    consumerThread.setDaemon(true)
    consumerThread.start()
    StructuredLog.info(
      "history-service",
      "redis_consumer_started",
      "stream"   -> streamName,
      "group"    -> groupName,
      "consumer" -> consumerName
    )

  def stop(): Unit =
    running = false
    val t = consumerThread
    if t != null then t.join(5000L)
    StructuredLog.info("history-service", "redis_consumer_stopped")

  private def ensureConsumerGroup(jedis: Jedis): Unit =
    try
      jedis.xgroupCreate(streamName, groupName, StreamEntryID.LAST_ENTRY, true)
    catch
      case e: Exception if Option(e.getMessage).exists(_.contains("BUSYGROUP")) => ()

  private def runLoop(jedis: Jedis): Unit =
    while running do
      try
        val params  = XReadGroupParams.xReadGroupParams().count(batchSize).block(PollBlockMillis)
        val streams = java.util.Map.of(streamName, StreamEntryID.UNRECEIVED_ENTRY)
        val result  = jedis.xreadGroup(groupName, consumerName, params, streams)
        if result != null then
          for streamEntry <- result.asScala do
            for entry <- streamEntry.getValue.asScala do
              processEntry(jedis, entry)
      catch
        case _: InterruptedException => ()
        case NonFatal(e) if running =>
          StructuredLog.warn("history-service", "redis_consumer_loop_error", "error" -> e.getMessage)

  private def processEntry(jedis: Jedis, entry: StreamEntry): Unit =
    HistoryArchiveStreamEvent.fromFields(entry.getFields) match
      case Left(error) =>
        StructuredLog.warn(
          "history-service",
          "redis_consumer_invalid_envelope_left_in_pel",
          "entryId" -> entry.getID.toString,
          "error"   -> error
        )
      case Right(envelope) =>
        ingest(envelope.payloadJson) match
        case Right(_) =>
          jedis.xack(streamName, groupName, entry.getID)
          StructuredLog.info(
            "history-service",
            "redis_consumer_ingested",
            "eventId"   -> envelope.eventId,
            "eventType" -> envelope.eventType,
            "gameId"    -> envelope.gameId.value.toString
          )
        case Left(err) =>
          StructuredLog.warn(
            "history-service",
            "redis_consumer_left_in_pel",
            "eventId"   -> envelope.eventId,
            "eventType" -> envelope.eventType,
            "gameId"    -> envelope.gameId.value.toString,
            "error"     -> err.toString
          )

object RedisStreamHistoryConsumer:
  val DefaultStreamName = HistoryArchiveStreamEvent.StreamName
  val DefaultGroupName  = "history-service"

  def defaultConsumerName(): String =
    Option(System.getenv("HOSTNAME")).map(_.trim).filter(_.nonEmpty).getOrElse("history-service-1")
