package chess.history.redis

import chess.application.query.game.GameClosure
import chess.application.session.model.{SessionMode, SideController}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.domain.model.Color
import chess.history.{ArchiveRecord, GameArchiveClientError, HistoryIngestionError}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.testcontainers.containers.GenericContainer
import redis.clients.jedis.{Jedis, StreamEntryID}
import redis.clients.jedis.params.XReadGroupParams
import scala.jdk.CollectionConverters.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.{ArrayBlockingQueue, TimeUnit}

class RedisStreamHistoryConsumerSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll with Eventually:

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(5, Seconds), interval = Span(100, Millis))

  private class RedisC extends GenericContainer[RedisC]("redis:7.4-alpine"):
    withExposedPorts(6379)

  private val container = new RedisC

  override def beforeAll(): Unit = container.start()
  override def afterAll(): Unit  = container.stop()

  private def redisHost = container.getHost
  private def redisPort = container.getMappedPort(6379)

  private val GroupName    = "history-service"
  private val ConsumerName = "history-service-1"

  private val sampleRecord = ArchiveRecord(
    gameId          = GameId(UUID.fromString("00000000-0000-0000-0000-000000000001")),
    sessionId       = SessionId(UUID.fromString("00000000-0000-0000-0000-000000000002")),
    mode            = SessionMode.HumanVsHuman,
    whiteController = SideController.HumanLocal,
    blackController = SideController.HumanLocal,
    closure         = GameClosure.Resigned(Color.White),
    pgn             = Some("[Result \"1-0\"]\n\n1-0"),
    finalFen        = Some("8/8/8/8/8/8/8/4K3 w - - 0 1"),
    createdAt       = Instant.parse("2026-05-22T10:00:00Z"),
    closedAt        = Instant.parse("2026-05-22T10:01:00Z"),
    materializedAt  = Instant.parse("2026-05-22T10:02:00Z")
  )

  private val terminalPayload: String =
    """{"type":"game.finished.v1","sessionId":"00000000-0000-0000-0000-000000000002","gameId":"00000000-0000-0000-0000-000000000001","result":"Draw","winner":null,"drawReason":"Stalemate"}"""

  private val nonTerminalPayload: String =
    """{"type":"game.session.created.v1","sessionId":"00000000-0000-0000-0000-000000000002","gameId":"00000000-0000-0000-0000-000000000001","mode":"HumanVsHuman","whiteController":"HumanLocal","blackController":"HumanLocal"}"""

  private def xadd(jedis: Jedis, streamKey: String, eventType: String, payload: String): StreamEntryID =
    val fields = new java.util.HashMap[String, String]()
    fields.put("eventType", eventType)
    fields.put("payload", payload)
    jedis.xadd(streamKey, StreamEntryID.NEW_ENTRY, fields)

  // ACK with the given ID; returns how many entries were acknowledged (0 = already ACKed)
  private def tryAck(jedis: Jedis, streamKey: String, id: StreamEntryID): Long =
    jedis.xack(streamKey, GroupName, id)

  // Read consumer PEL (messages delivered but not yet ACKed) by re-reading with "0-0" start
  private def pelSize(jedis: Jedis, streamKey: String): Int =
    val params  = XReadGroupParams.xReadGroupParams().count(100)
    val streams = java.util.Map.of(streamKey, new StreamEntryID(0, 0))
    val result  = jedis.xreadGroup(GroupName, ConsumerName, params, streams)
    if result == null then 0
    else result.asScala.map(_.getValue.size()).sum

  private def uniqueStream(): String = s"test:game-events-${UUID.randomUUID()}"

  "RedisStreamHistoryConsumer" should "ACK a terminal event after successful ingest" in {
    val streamKey = uniqueStream()
    val results   = new ArrayBlockingQueue[Either[HistoryIngestionError, ArchiveRecord]](4)
    val ingest    = (json: String) => { val r = Right(sampleRecord); results.offer(r); r }
    val jedis     = new Jedis(redisHost, redisPort)
    val consumer  = RedisStreamHistoryConsumer(redisHost, redisPort, ingest, streamName = streamKey)

    try
      consumer.start()
      val id = xadd(jedis, streamKey, "game.finished.v1", terminalPayload)

      results.poll(5, TimeUnit.SECONDS) should not be null
      eventually { tryAck(jedis, streamKey, id) shouldBe 0L }
    finally
      consumer.stop()
      jedis.close()
  }

  it should "ACK a non-terminal event without calling ingest" in {
    val streamKey   = uniqueStream()
    val ingestCalls = new ArrayBlockingQueue[String](4)
    val ingest      = (json: String) => { ingestCalls.offer(json); Right(sampleRecord) }
    val jedis       = new Jedis(redisHost, redisPort)
    val consumer    = RedisStreamHistoryConsumer(redisHost, redisPort, ingest, streamName = streamKey)

    try
      consumer.start()
      val id = xadd(jedis, streamKey, "game.session.created.v1", nonTerminalPayload)

      eventually { tryAck(jedis, streamKey, id) shouldBe 0L }
      ingestCalls.poll() shouldBe null
    finally
      consumer.stop()
      jedis.close()
  }

  it should "ACK a terminal event even when ingest returns InvalidEvent" in {
    val streamKey = uniqueStream()
    val results   = new ArrayBlockingQueue[Either[HistoryIngestionError, ArchiveRecord]](4)
    val ingest    = (json: String) => {
      val r: Either[HistoryIngestionError, ArchiveRecord] = Left(HistoryIngestionError.InvalidEvent("bad json"))
      results.offer(r)
      r
    }
    val jedis    = new Jedis(redisHost, redisPort)
    val consumer = RedisStreamHistoryConsumer(redisHost, redisPort, ingest, streamName = streamKey)

    try
      consumer.start()
      val id = xadd(jedis, streamKey, "game.finished.v1", terminalPayload)

      results.poll(5, TimeUnit.SECONDS) should not be null
      eventually { tryAck(jedis, streamKey, id) shouldBe 0L }
    finally
      consumer.stop()
      jedis.close()
  }

  it should "leave a terminal event in the PEL when ingest returns ArchiveFetchFailed" in {
    val streamKey = uniqueStream()
    val results   = new ArrayBlockingQueue[Either[HistoryIngestionError, ArchiveRecord]](4)
    val ingest    = (json: String) => {
      val r: Either[HistoryIngestionError, ArchiveRecord] = Left(
        HistoryIngestionError.ArchiveFetchFailed(
          GameArchiveClientError.NotFound(GameId(UUID.fromString("00000000-0000-0000-0000-000000000001")))
        )
      )
      results.offer(r)
      r
    }
    val jedis    = new Jedis(redisHost, redisPort)
    val consumer = RedisStreamHistoryConsumer(redisHost, redisPort, ingest, streamName = streamKey)

    try
      consumer.start()
      xadd(jedis, streamKey, "game.resigned.v1", terminalPayload)

      results.poll(5, TimeUnit.SECONDS) should not be null
      Thread.sleep(300)
      pelSize(jedis, streamKey) shouldBe 1
    finally
      consumer.stop()
      jedis.close()
  }
