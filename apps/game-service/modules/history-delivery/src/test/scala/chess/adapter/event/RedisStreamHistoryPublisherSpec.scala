package chess.adapter.event

import chess.application.event.AppEvent
import chess.application.session.model.{SessionMode, SideController}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.domain.model.{Color, DrawReason, GameStatus}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.testcontainers.containers.GenericContainer
import redis.clients.jedis.{Jedis, JedisPooled, StreamEntryID}
import redis.clients.jedis.params.XReadParams
import scala.jdk.CollectionConverters.*

class RedisStreamHistoryPublisherSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private class RedisC extends GenericContainer[RedisC]("redis:7.4-alpine"):
    withExposedPorts(6379)

  private val container = new RedisC

  override def beforeAll(): Unit = container.start()
  override def afterAll(): Unit  = container.stop()

  private def host = container.getHost
  private def port = container.getMappedPort(6379)

  private val sid = SessionId.random()
  private val gid = GameId.random()

  private def readAll(probe: Jedis, streamKey: String): Seq[java.util.Map[String, String]] =
    val result = probe.xread(
      XReadParams.xReadParams().count(100),
      java.util.Map.of(streamKey, new StreamEntryID(0, 0))
    )
    if result == null then Seq.empty
    else result.asScala.flatMap(_.getValue.asScala.map(_.getFields)).toSeq

  "RedisStreamHistoryPublisher" should "publish a terminal event to the stream" in {
    val jedisPooled = JedisPooled(host, port)
    val probe       = new Jedis(host, port)
    val streamKey   = s"test:terminal-${gid.value}"
    try
      RedisStreamHistoryPublisher(jedisPooled, streamKey)
        .publish(AppEvent.GameFinished(sid, gid, GameStatus.Draw(DrawReason.Stalemate)))

      probe.xlen(streamKey) shouldBe 1L
      val fields = readAll(probe, streamKey).head
      fields.get("eventType") shouldBe "history.archive.requested"
      fields.get("eventVersion") shouldBe "1"
      fields.get("gameId") shouldBe gid.value.toString
      fields.get("payloadJson") should include("game.finished.v1")
      fields.get("sourceEventType") shouldBe "game.finished.v1"
    finally
      jedisPooled.close()
      probe.close()
  }

  it should "publish a GameResigned event with correct type tag" in {
    val jedisPooled = JedisPooled(host, port)
    val probe       = new Jedis(host, port)
    val streamKey   = s"test:resigned-${gid.value}"
    try
      RedisStreamHistoryPublisher(jedisPooled, streamKey)
        .publish(AppEvent.GameResigned(sid, gid, Color.White))

      probe.xlen(streamKey) shouldBe 1L
      val fields = readAll(probe, streamKey).head
      fields.get("eventType") shouldBe "history.archive.requested"
      fields.get("sourceEventType") shouldBe "game.resigned.v1"
    finally
      jedisPooled.close()
      probe.close()
  }

  it should "not publish a SessionCreated event" in {
    val jedisPooled = JedisPooled(host, port)
    val probe       = new Jedis(host, port)
    val streamKey   = s"test:created-${gid.value}"
    try
      RedisStreamHistoryPublisher(jedisPooled, streamKey)
        .publish(AppEvent.SessionCreated(sid, gid, SessionMode.HumanVsHuman, SideController.HumanLocal, SideController.HumanLocal))

      probe.xlen(streamKey) shouldBe 0L
    finally
      jedisPooled.close()
      probe.close()
  }

  it should "not publish internal events" in {
    val jedisPooled = JedisPooled(host, port)
    val probe       = new Jedis(host, port)
    val streamKey   = s"test:internal-${gid.value}"
    try
      RedisStreamHistoryPublisher(jedisPooled, streamKey)
        .publish(AppEvent.AITurnRequested(sid, gid, Color.White))
      RedisStreamHistoryPublisher(jedisPooled, streamKey)
        .publish(AppEvent.AITurnFailed(sid, gid, "timeout"))

      probe.xlen(streamKey) shouldBe 0L
    finally
      jedisPooled.close()
      probe.close()
  }

  it should "absorb Redis connection errors without throwing" in {
    val brokenJedis = JedisPooled("127.0.0.1", 19999)
    noException should be thrownBy
      RedisStreamHistoryPublisher(brokenJedis, "test:unreachable")
        .publish(AppEvent.SessionCancelled(sid, gid))
    brokenJedis.close()
  }
