package chess.history.redis

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
    streamName: String = RedisStreamHistoryConsumer.DefaultStreamName
):
  private val GroupName     = "history-service"
  private val ConsumerName  = "history-service-1"
  private val TerminalTypes = Set("game.finished.v1", "game.resigned.v1", "game.session.cancelled.v1")
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
      "group"    -> GroupName,
      "consumer" -> ConsumerName
    )

  def stop(): Unit =
    running = false
    val t = consumerThread
    if t != null then t.join(5000L)
    StructuredLog.info("history-service", "redis_consumer_stopped")

  private def ensureConsumerGroup(jedis: Jedis): Unit =
    try
      jedis.xgroupCreate(streamName, GroupName, StreamEntryID.LAST_ENTRY, true)
    catch
      case e: Exception if Option(e.getMessage).exists(_.contains("BUSYGROUP")) => ()

  private def runLoop(jedis: Jedis): Unit =
    while running do
      try
        val params  = XReadGroupParams.xReadGroupParams().count(batchSize).block(PollBlockMillis)
        val streams = java.util.Map.of(streamName, StreamEntryID.UNRECEIVED_ENTRY)
        val result  = jedis.xreadGroup(GroupName, ConsumerName, params, streams)
        if result != null then
          for streamEntry <- result.asScala do
            for entry <- streamEntry.getValue.asScala do
              processEntry(jedis, entry)
      catch
        case _: InterruptedException => ()
        case NonFatal(e) if running =>
          StructuredLog.warn("history-service", "redis_consumer_loop_error", "error" -> e.getMessage)

  private def processEntry(jedis: Jedis, entry: StreamEntry): Unit =
    val fields    = entry.getFields
    val eventType = fields.get("eventType")
    val payload   = fields.get("payload")

    if eventType == null || payload == null then
      jedis.xack(streamName, GroupName, entry.getID)
      StructuredLog.warn(
        "history-service",
        "redis_consumer_missing_fields",
        "entryId" -> entry.getID.toString
      )
    else if !TerminalTypes.contains(eventType) then
      jedis.xack(streamName, GroupName, entry.getID)
    else
      ingest(payload) match
        case Right(_) =>
          jedis.xack(streamName, GroupName, entry.getID)
          StructuredLog.info("history-service", "redis_consumer_ingested", "eventType" -> eventType)
        case Left(_: HistoryIngestionError.InvalidEvent) =>
          jedis.xack(streamName, GroupName, entry.getID)
          StructuredLog.warn("history-service", "redis_consumer_invalid_acked", "eventType" -> eventType)
        case Left(_: HistoryIngestionError.MaterializationFailed) =>
          jedis.xack(streamName, GroupName, entry.getID)
          StructuredLog.warn(
            "history-service",
            "redis_consumer_materialize_failed_acked",
            "eventType" -> eventType
          )
        case Left(err) =>
          StructuredLog.warn(
            "history-service",
            "redis_consumer_left_in_pel",
            "eventType" -> eventType,
            "error"     -> err.toString
          )

object RedisStreamHistoryConsumer:
  val DefaultStreamName = "searchess:game-events"
