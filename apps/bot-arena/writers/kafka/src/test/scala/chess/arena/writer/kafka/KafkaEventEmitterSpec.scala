package chess.arena.writer.kafka

import chess.arena.events.{EventEmitter, GameEvent, GameEventJson, GameFinished}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable.ListBuffer

class KafkaEventEmitterSpec extends AnyFlatSpec with Matchers:

  "KafkaEventEmitter" should "use gameId as the Kafka key" in {
    val producer = RecordingProducer()
    val emitter  = KafkaEventEmitter(KafkaEventEmitterConfig(topic = "game-events-test"), producer)
    val event    = sampleFinished()

    emitter.emit(event)

    producer.records.map(_.key) shouldBe List("game-1")
  }

  it should "serialize values with GameEventJson.encode" in {
    val producer = RecordingProducer()
    val emitter  = KafkaEventEmitter(KafkaEventEmitterConfig(topic = "game-events-test"), producer)
    val event    = sampleFinished()

    emitter.emit(event)

    producer.records.map(_.value) shouldBe List(GameEventJson.encode(event))
  }

  it should "use Kafka config defaults" in {
    KafkaEventEmitterConfig() shouldBe KafkaEventEmitterConfig(
      bootstrapServers = "localhost:9092",
      topic = "game-events",
      clientId = "searchess-arena-kafka-emitter"
    )
  }

  "CompositeEventEmitter" should "forward events to all child emitters" in {
    val left  = RecordingEmitter()
    val right = RecordingEmitter()
    val event = sampleFinished()

    CompositeEventEmitter(List(left, right)).emit(event)

    left.events shouldBe List(event)
    right.events shouldBe List(event)
  }

  private def sampleFinished(): GameFinished =
    GameFinished(
      eventId = "event-1",
      timestamp = "2026-06-13T12:00:00Z",
      tournamentId = "tournament-1",
      gameId = "game-1",
      whiteBotId = "white-bot",
      blackBotId = "black-bot",
      winnerBotId = Some("white-bot"),
      loserBotId = Some("black-bot"),
      result = "white",
      whiteBotFamily = "heuristic",
      blackBotFamily = "heuristic",
      whiteStrategyType = "random",
      blackStrategyType = "capture-first",
      whiteEngineType = "none",
      blackEngineType = "none",
      whiteModelVersion = "none",
      blackModelVersion = "none",
      totalMoves = 10,
      totalPly = 20,
      durationMillis = 100,
      terminationReason = "checkmate"
    )

private final case class KafkaRecord(topic: String, key: String, value: String)

private final class RecordingProducer extends KafkaProducerClient:
  private val buffer = ListBuffer.empty[KafkaRecord]

  def records: List[KafkaRecord] = buffer.toList

  def send(topic: String, key: String, value: String): Unit =
    buffer += KafkaRecord(topic, key, value)
    ()

  def flush(): Unit = ()

  def close(): Unit = ()

private final class RecordingEmitter extends EventEmitter:
  private val buffer = ListBuffer.empty[GameEvent]

  def events: List[GameEvent] = buffer.toList

  def emit(event: GameEvent): Unit =
    buffer += event
    ()
