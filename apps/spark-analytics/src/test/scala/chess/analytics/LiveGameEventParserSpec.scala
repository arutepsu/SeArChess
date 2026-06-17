package chess.analytics

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import chess.analytics.ingestion.LiveGameEventParser
import chess.analytics.schema.GameEventSchemas

class LiveGameEventParserSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private var spark: SparkSession = _

  override def beforeAll(): Unit = {
    spark = SparkSession.builder()
      .appName("LiveGameEventParserSpec")
      .master("local")
      .config("spark.sql.shuffle.partitions", "1")
      .config("spark.ui.enabled", "false")
      .config("spark.driver.bindAddress", "127.0.0.1")
      .getOrCreate()
    spark.sparkContext.setLogLevel("ERROR")
  }

  override def afterAll(): Unit = {
    if (spark != null) { spark.stop(); spark = null }
  }

  private val gameFinishedJson =
    """{"eventId":"evt-001","eventType":"GameFinished","eventVersion":1,"occurredAt":"2026-06-17T10:00:00Z","producer":"game-service","correlationId":"corr-001","causationId":null,"aggregateType":"Game","aggregateId":"game-abc","payload":{"type":"game.finished.v1","sessionId":"sess-001","gameId":"game-abc","result":"Checkmate","winner":"White","drawReason":null}}"""

  private val drawGameJson =
    """{"eventId":"evt-003","eventType":"GameFinished","eventVersion":1,"occurredAt":"2026-06-17T11:00:00Z","producer":"game-service","correlationId":"corr-002","causationId":null,"aggregateType":"Game","aggregateId":"game-xyz","payload":{"type":"game.finished.v1","sessionId":"sess-002","gameId":"game-xyz","result":"Draw","winner":null,"drawReason":"Stalemate"}}"""

  private val moveAppliedJson =
    """{"eventId":"evt-002","eventType":"MoveApplied","eventVersion":1,"occurredAt":"2026-06-17T09:59:00Z","producer":"game-service","correlationId":"corr-001","causationId":null,"aggregateType":"Game","aggregateId":"game-abc","payload":{"type":"game.move.v1","from":"e2","to":"e4"}}"""

  private def kafkaDf(jsons: String*): DataFrame = {
    val s = spark; import s.implicits._
    jsons.toSeq.toDF("value")
  }

  "GameEventSchemas.envelopeSchema" should "include camelCase field names matching the wire format" in {
    val fieldNames = GameEventSchemas.envelopeSchema.fieldNames.toSet
    fieldNames should contain("eventId")
    fieldNames should contain("eventType")
    fieldNames should contain("occurredAt")
    fieldNames should contain("aggregateId")
    fieldNames should contain("correlationId")
    fieldNames should contain("payload")
  }

  "LiveGameEventParser.parseAndFilter" should "extract one row from a GameFinished envelope" in {
    LiveGameEventParser.parseAndFilter(kafkaDf(gameFinishedJson)).count() shouldBe 1L
  }

  it should "produce snake_case output columns" in {
    val cols = LiveGameEventParser.parseAndFilter(kafkaDf(gameFinishedJson)).columns.toSet
    cols shouldBe Set("event_id", "aggregate_id", "session_id", "occurred_at",
                      "result", "winner", "draw_reason", "correlation_id")
  }

  it should "correctly map fields from a checkmate envelope" in {
    val row = LiveGameEventParser.parseAndFilter(kafkaDf(gameFinishedJson)).head()
    row.getAs[String]("event_id")       shouldBe "evt-001"
    row.getAs[String]("aggregate_id")   shouldBe "game-abc"
    row.getAs[String]("session_id")     shouldBe "sess-001"
    row.getAs[String]("occurred_at")    shouldBe "2026-06-17T10:00:00Z"
    row.getAs[String]("result")         shouldBe "Checkmate"
    row.getAs[String]("winner")         shouldBe "White"
    row.getAs[String]("draw_reason")    shouldBe null
    row.getAs[String]("correlation_id") shouldBe "corr-001"
  }

  it should "correctly map fields from a draw envelope (null winner, non-null drawReason)" in {
    val row = LiveGameEventParser.parseAndFilter(kafkaDf(drawGameJson)).head()
    row.getAs[String]("result")      shouldBe "Draw"
    row.getAs[String]("winner")      shouldBe null
    row.getAs[String]("draw_reason") shouldBe "Stalemate"
  }

  it should "filter out non-GameFinished events" in {
    val result = LiveGameEventParser.parseAndFilter(kafkaDf(gameFinishedJson, moveAppliedJson))
    result.count() shouldBe 1L
    result.head().getAs[String]("event_id") shouldBe "evt-001"
  }

  it should "return an empty DataFrame when only non-GameFinished events are present" in {
    LiveGameEventParser.parseAndFilter(kafkaDf(moveAppliedJson)).count() shouldBe 0L
  }

  it should "return an empty DataFrame on empty input" in {
    LiveGameEventParser.parseAndFilter(kafkaDf()).count() shouldBe 0L
  }

  it should "silently drop malformed JSON and still return valid GameFinished rows" in {
    val result = LiveGameEventParser.parseAndFilter(kafkaDf("not-json", gameFinishedJson))
    result.count() shouldBe 1L
    result.head().getAs[String]("event_id") shouldBe "evt-001"
  }

  it should "process multiple GameFinished events correctly" in {
    val result = LiveGameEventParser.parseAndFilter(kafkaDf(gameFinishedJson, drawGameJson))
    result.count() shouldBe 2L
    val ids = result.collect().map(_.getAs[String]("event_id")).toSet
    ids shouldBe Set("evt-001", "evt-003")
  }
}
