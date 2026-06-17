package chess.historyservice

import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class HistoryServiceConfigSpec extends AnyFlatSpec with Matchers with EitherValues:

  private val requiredEnv = Seq(
    "HISTORY_POSTGRES_URL" -> "jdbc:postgresql://localhost:5432/searchess"
  )

  private def load(values: (String, String)*): Either[String, HistoryServiceConfig] =
    val env = (requiredEnv ++ values).toMap
    HistoryServiceConfig.load(key => env.get(key))

  "HistoryServiceConfig" should "accept HISTORY_INGESTION_MODE=kafka" in {
    val config = load(
      "HISTORY_INGESTION_MODE"  -> "kafka",
      "KAFKA_BOOTSTRAP_SERVERS" -> "kafka:9092",
      "KAFKA_GAME_EVENTS_TOPIC" -> "searchess.game.events.v1",
      "HISTORY_KAFKA_GROUP"     -> "history-service-game-projector"
    ).value

    config.deliveryMode          shouldBe HistoryDeliveryMode.Kafka
    config.kafkaBootstrapServers shouldBe Some("kafka:9092")
    config.kafkaGameEventsTopic  shouldBe "searchess.game.events.v1"
    config.kafkaGroup            shouldBe "history-service-game-projector"
  }

  it should "accept HISTORY_INGESTION_MODE=http" in {
    val config = load("HISTORY_INGESTION_MODE" -> "http").value

    config.deliveryMode shouldBe HistoryDeliveryMode.Http
  }

  it should "accept HISTORY_INGESTION_MODE=redis-stream" in {
    val config = load(
      "HISTORY_INGESTION_MODE" -> "redis-stream",
      "HISTORY_REDIS_URL"      -> "redis://redis:6379",
      "HISTORY_REDIS_STREAM"   -> "searchess.history.archives",
      "HISTORY_REDIS_GROUP"    -> "history-service"
    ).value

    config.deliveryMode shouldBe HistoryDeliveryMode.RedisStream
    config.redisHost    shouldBe Some("redis")
    config.redisPort    shouldBe 6379
    config.redisStream  shouldBe "searchess.history.archives"
    config.redisGroup   shouldBe "history-service"
  }

  it should "reject HISTORY_INGESTION_MODE with an unknown value" in {
    val error = load("HISTORY_INGESTION_MODE" -> "grpc").left.value

    error should include("HISTORY_INGESTION_MODE")
    error should include("'http'")
    error should include("'redis-stream'")
    error should include("'kafka'")
  }

  it should "reject kafka mode without KAFKA_BOOTSTRAP_SERVERS" in {
    val error = load(
      "HISTORY_INGESTION_MODE"  -> "kafka",
      "KAFKA_GAME_EVENTS_TOPIC" -> "searchess.game.events.v1"
    ).left.value

    error should include("KAFKA_BOOTSTRAP_SERVERS")
  }

  it should "reject kafka mode without KAFKA_GAME_EVENTS_TOPIC" in {
    val error = load(
      "HISTORY_INGESTION_MODE"  -> "kafka",
      "KAFKA_BOOTSTRAP_SERVERS" -> "kafka:9092",
      "KAFKA_GAME_EVENTS_TOPIC" -> ""
    ).left.value

    error should include("KAFKA_GAME_EVENTS_TOPIC")
  }

  it should "default HISTORY_INGESTION_MODE to http" in {
    val config = load().value

    config.deliveryMode shouldBe HistoryDeliveryMode.Http
  }

  it should "use kafka defaults for group and topic when not overridden" in {
    val config = load(
      "HISTORY_INGESTION_MODE"  -> "kafka",
      "KAFKA_BOOTSTRAP_SERVERS" -> "kafka:9092"
    ).value

    config.kafkaGameEventsTopic shouldBe "searchess.game.events.v1"
    config.kafkaGroup           shouldBe "history-service-game-projector"
  }
