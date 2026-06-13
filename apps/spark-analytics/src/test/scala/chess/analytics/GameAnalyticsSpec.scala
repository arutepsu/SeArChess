package chess.analytics

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class GameAnalyticsSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private var spark: SparkSession = _

  override def beforeAll(): Unit = {
    spark = SparkSession.builder()
      .appName("GameAnalyticsSpec")
      .master("local")
      .config("spark.sql.shuffle.partitions", "1")
      .config("spark.ui.enabled", "false")
      .config("spark.driver.bindAddress", "127.0.0.1")
      .getOrCreate()
    spark.sparkContext.setLogLevel("ERROR")
  }

  override def afterAll(): Unit = {
    if (spark != null) {
      spark.stop()
      spark = null
    }
  }

  // (whiteBotId, blackBotId, result, totalPly, terminationReason)
  private def gfDF(rows: Seq[(String, String, String, Long, String)]): DataFrame = {
    val s = spark; import s.implicits._
    rows.toDF("whiteBotId", "blackBotId", "result", "totalPly", "terminationReason")
  }

  // (whiteBotId, blackBotId, result, totalPly, durationMillis, terminationReason)
  private def gfDFWithDuration(rows: Seq[(String, String, String, Long, Long, String)]): DataFrame = {
    val s = spark; import s.implicits._
    rows.toDF("whiteBotId", "blackBotId", "result", "totalPly", "durationMillis", "terminationReason")
  }

  // (whiteBotFamily, blackBotFamily, result) — shared by family comparison and family matchups tests
  private def gfFamilyDF(rows: Seq[(String, String, String)]): DataFrame = {
    val s = spark; import s.implicits._
    rows.toDF("whiteBotFamily", "blackBotFamily", "result")
  }

  // (whiteStrategyType, blackStrategyType, result)
  private def gfStrategyDF(rows: Seq[(String, String, String)]): DataFrame = {
    val s = spark; import s.implicits._
    rows.toDF("whiteStrategyType", "blackStrategyType", "result")
  }

  // (result, winnerBotId, totalPly, durationMillis) — for fastest wins tests
  private def gfWinsDF(rows: Seq[(String, String, Long, Long)]): DataFrame = {
    val s = spark; import s.implicits._
    rows.toDF("result", "winnerBotId", "totalPly", "durationMillis")
  }

  // (whiteBotId, whiteBotFamily, whiteEngineType, whiteStrategyType,
  //  blackBotId, blackBotFamily, blackEngineType, blackStrategyType,
  //  result, totalPly)
  private def gfSfDF(
    rows: Seq[(String, String, String, String, String, String, String, String, String, Long)]
  ): DataFrame = {
    val s = spark; import s.implicits._
    rows.toDF(
      "whiteBotId", "whiteBotFamily", "whiteEngineType", "whiteStrategyType",
      "blackBotId", "blackBotFamily", "blackEngineType", "blackStrategyType",
      "result", "totalPly"
    )
  }

  // (whiteBotId, whiteBotFamily, blackBotId, blackBotFamily, result, totalPly) — for SearchessAI comparison tests
  private def gfAiDF(rows: Seq[(String, String, String, String, String, Long)]): DataFrame = {
    val s = spark; import s.implicits._
    rows.toDF("whiteBotId", "whiteBotFamily", "blackBotId", "blackBotFamily", "result", "totalPly")
  }

  private val sampleFixture: String = {
    val root = System.getProperty("projectRoot", System.getProperty("user.dir"))
    java.nio.file.Paths.get(root, "docs", "samples", "generated-heuristic-tournament.jsonl").toString
  }

  // ── loadGameFinished ─────────────────────────────────────────────────────────

  "loadGameFinished" should "return only GameFinished rows from a JSONL file" in {
    val df = GameAnalytics.loadGameFinished(spark, sampleFixture)
    df.count() shouldBe 2L
  }

  it should "exclude GameStarted and MovePlayed rows" in {
    val df = GameAnalytics.loadGameFinished(spark, sampleFixture)
    df.filter(col("eventType") =!= "GameFinished").count() shouldBe 0L
  }

  // ── computeLeaderboard ───────────────────────────────────────────────────────

  "computeLeaderboard" should "score a white win as 1.0 for white and 0.0 for black" in {
    val lb = GameAnalytics.computeLeaderboard(gfDF(Seq(
      ("A", "B", "white", 10L, "checkmate")
    ))).collect().map(r => r.getAs[String]("botId") -> r.getAs[Double]("totalScore")).toMap

    lb("A") shouldBe 1.0
    lb("B") shouldBe 0.0
  }

  it should "score a draw as 0.5 for both players" in {
    val lb = GameAnalytics.computeLeaderboard(gfDF(Seq(
      ("A", "B", "draw", 5L, "stalemate")
    ))).collect().map(r => r.getAs[String]("botId") -> r.getAs[Double]("totalScore")).toMap

    lb("A") shouldBe 0.5
    lb("B") shouldBe 0.5
  }

  it should "rank bots by total score descending" in {
    val gf = gfDF(Seq(
      ("A", "B", "white", 10L, "checkmate"),
      ("B", "A", "black",  8L, "checkmate"),
      ("A", "B", "draw",  15L, "stalemate")
    ))
    val lbIds = GameAnalytics.computeLeaderboard(gf).collect().map(_.getAs[String]("botId")).toList
    lbIds.head shouldBe "A"
    lbIds.last shouldBe "B"
  }

  it should "compute correct wins, draws, losses counts" in {
    val gf = gfDF(Seq(
      ("A", "B", "white", 10L, "checkmate"),
      ("B", "A", "black",  8L, "checkmate"),
      ("A", "B", "draw",  15L, "stalemate")
    ))
    val lb = GameAnalytics.computeLeaderboard(gf).collect()
      .map(r => r.getAs[String]("botId") -> r).toMap

    lb("A").getAs[Long]("wins")   shouldBe 2L
    lb("A").getAs[Long]("draws")  shouldBe 1L
    lb("A").getAs[Long]("losses") shouldBe 0L
    lb("B").getAs[Long]("wins")   shouldBe 0L
    lb("B").getAs[Long]("draws")  shouldBe 1L
    lb("B").getAs[Long]("losses") shouldBe 2L
  }

  // ── computeHeadToHead ────────────────────────────────────────────────────────

  "computeHeadToHead" should "count wins per side and accumulate scores for a pairing" in {
    val gf = gfDF(Seq(
      ("A", "B", "white", 10L, "checkmate"),
      ("A", "B", "white", 12L, "checkmate"),
      ("A", "B", "draw",   8L, "stalemate")
    ))
    val h2h = GameAnalytics.computeHeadToHead(gf).collect()
    h2h should have length 1
    val row = h2h.head
    row.getAs[Long]("whiteWins")    shouldBe 2L
    row.getAs[Long]("blackWins")    shouldBe 0L
    row.getAs[Long]("draws")        shouldBe 1L
    row.getAs[Double]("whiteScore") shouldBe 2.5
    row.getAs[Double]("blackScore") shouldBe 0.5
  }

  // ── computeTerminations ──────────────────────────────────────────────────────

  "computeTerminations" should "count occurrences of each termination reason" in {
    val gf = gfDF(Seq(
      ("A", "B", "white", 10L, "checkmate"),
      ("A", "B", "white", 12L, "checkmate"),
      ("A", "B", "draw",   8L, "stalemate")
    ))
    val terms = GameAnalytics.computeTerminations(gf).collect()
      .map(r => r.getAs[String]("terminationReason") -> r.getAs[Long]("count")).toMap

    terms("checkmate") shouldBe 2L
    terms("stalemate") shouldBe 1L
  }

  // ── computeAvgGameLength ─────────────────────────────────────────────────────

  "computeAvgGameLength" should "compute average totalPly per pairing" in {
    val gf = gfDFWithDuration(Seq(
      ("A", "B", "white", 10L, 100L, "checkmate"),
      ("A", "B", "draw",  20L, 200L, "stalemate")
    ))
    val avgs = GameAnalytics.computeAvgGameLength(gf).collect()
    avgs should have length 1
    avgs.head.getAs[Double]("avgTotalPly") shouldBe 15.0 +- 0.1
  }

  // ── computeBotFamilyComparison ────────────────────────────────────────────────

  "computeBotFamilyComparison" should "aggregate wins and losses by bot family" in {
    val gf = gfFamilyDF(Seq(
      ("Heuristic", "UciEngine", "white"),
      ("Heuristic", "UciEngine", "white")
    ))
    val result = GameAnalytics.computeBotFamilyComparison(gf).collect()
      .map(r => r.getAs[String]("family") -> r.getAs[Long]("wins")).toMap

    result("Heuristic") shouldBe 2L
    result("UciEngine")  shouldBe 0L
  }

  it should "compute win rate per family" in {
    val gf = gfFamilyDF(Seq(
      ("Heuristic", "UciEngine", "white"),
      ("Heuristic", "UciEngine", "white")
    ))
    val result = GameAnalytics.computeBotFamilyComparison(gf).collect()
      .map(r => r.getAs[String]("family") -> r.getAs[Double]("winRate")).toMap

    // Heuristic: 2 wins / 2 games (white-view only) = 1.0
    result("Heuristic") shouldBe 1.0 +- 0.001
    result("UciEngine")  shouldBe 0.0 +- 0.001
  }

  // ── computeStrategyComparison ────────────────────────────────────────────────

  "computeStrategyComparison" should "aggregate wins by strategy type" in {
    val gf = gfStrategyDF(Seq(
      ("depth-3", "random", "white"),
      ("depth-3", "random", "white")
    ))
    val result = GameAnalytics.computeStrategyComparison(gf).collect()
      .map(r => r.getAs[String]("strategyType") -> r.getAs[Long]("wins")).toMap

    result("depth-3") shouldBe 2L
    result("random")  shouldBe 0L
  }

  it should "include draws in the score of both strategy types" in {
    val gf = gfStrategyDF(Seq(
      ("depth-1", "capture-first", "draw")
    ))
    val result = GameAnalytics.computeStrategyComparison(gf).collect()
      .map(r => r.getAs[String]("strategyType") -> r.getAs[Long]("draws")).toMap

    result("depth-1")       shouldBe 1L
    result("capture-first") shouldBe 1L
  }

  // ── computeColorPerformance ──────────────────────────────────────────────────

  "computeColorPerformance" should "count games and wins as white and black separately" in {
    val gf = gfDF(Seq(
      ("A", "B", "white", 10L, "checkmate"),  // A wins as white
      ("B", "A", "black",  8L, "checkmate"),  // A wins as black
      ("A", "B", "draw",  15L, "stalemate")   // draw
    ))
    val result = GameAnalytics.computeColorPerformance(gf).collect()
      .map(r => r.getAs[String]("botId") -> r).toMap

    result("A").getAs[Long]("gamesAsWhite") shouldBe 2L
    result("A").getAs[Long]("whiteWins")    shouldBe 1L
    result("A").getAs[Long]("gamesAsBlack") shouldBe 1L
    result("A").getAs[Long]("blackWins")    shouldBe 1L

    result("B").getAs[Long]("gamesAsWhite") shouldBe 1L
    result("B").getAs[Long]("whiteWins")    shouldBe 0L
    result("B").getAs[Long]("gamesAsBlack") shouldBe 2L
    result("B").getAs[Long]("blackWins")    shouldBe 0L
  }

  // ── computeFastestWins ───────────────────────────────────────────────────────

  "computeFastestWins" should "exclude draw results" in {
    val gf = gfWinsDF(Seq(
      ("white", "A", 10L, 100L),
      ("draw",  "",  20L, 200L),
      ("white", "A", 30L, 300L)
    ))
    val result = GameAnalytics.computeFastestWins(gf).collect()
    result should have length 1
    result.head.getAs[Long]("decisiveGames") shouldBe 2L
  }

  it should "compute average win ply per winning bot" in {
    val gf = gfWinsDF(Seq(
      ("white", "A", 10L, 100L),
      ("white", "A", 20L, 200L),
      ("black", "B", 30L, 300L)
    ))
    val result = GameAnalytics.computeFastestWins(gf).collect()
      .map(r => r.getAs[String]("winnerBotId") -> r.getAs[Double]("avgWinPly")).toMap

    result("A") shouldBe 15.0 +- 0.1
    result("B") shouldBe 30.0 +- 0.1
  }

  it should "track minimum win ply" in {
    val gf = gfWinsDF(Seq(
      ("white", "A", 10L, 50L),
      ("white", "A", 40L, 200L)
    ))
    val result = GameAnalytics.computeFastestWins(gf).collect()
    result should have length 1
    result.head.getAs[Long]("minWinPly") shouldBe 10L
  }

  // ── computeStockfishComparison ───────────────────────────────────────────────

  "computeStockfishComparison" should "include UCI-engine/stockfish bots and exclude heuristic bots" in {
    val gf = gfSfDF(Seq(
      // stockfish-depth-1 (white, uci-engine/stockfish) beats random-bot (black, heuristic)
      ("stockfish-depth-1", "uci-engine", "stockfish", "depth-1",
       "random-bot",        "heuristic",  "none",      "random",       "white", 10L),
      // random-bot (white, heuristic) loses to stockfish-fast (black, uci-engine/stockfish)
      ("random-bot",        "heuristic",  "none",      "random",
       "stockfish-fast",    "uci-engine", "stockfish", "movetime-100", "black", 20L)
    ))
    val botIds = GameAnalytics.computeStockfishComparison(gf).collect()
      .map(_.getAs[String]("botId")).toSet

    botIds should contain("stockfish-depth-1")
    botIds should contain("stockfish-fast")
    botIds should not contain "random-bot"
  }

  it should "compute win totals per Stockfish configuration using metadata filter" in {
    val gf = gfSfDF(Seq(
      ("stockfish-depth-1", "uci-engine", "stockfish", "depth-1",
       "stockfish-fast",    "uci-engine", "stockfish", "movetime-100", "white", 10L),
      ("stockfish-depth-1", "uci-engine", "stockfish", "depth-1",
       "stockfish-fast",    "uci-engine", "stockfish", "movetime-100", "white", 12L)
    ))
    val result = GameAnalytics.computeStockfishComparison(gf).collect()
      .map(r => r.getAs[String]("botId") -> r.getAs[Long]("wins")).toMap

    result("stockfish-depth-1") shouldBe 2L
    result("stockfish-fast")    shouldBe 0L
  }

  // ── computeFamilyMatchups ────────────────────────────────────────────────────

  "computeFamilyMatchups" should "aggregate game count and draws by family pairing" in {
    val gf = gfFamilyDF(Seq(
      ("Heuristic", "UciEngine", "white"),
      ("Heuristic", "UciEngine", "black"),
      ("Heuristic", "UciEngine", "draw")
    ))
    val result = GameAnalytics.computeFamilyMatchups(gf).collect()
    result should have length 1
    val row = result.head
    row.getAs[Long]("games") shouldBe 3L
    row.getAs[Long]("draws") shouldBe 1L
  }

  it should "accumulate white and black scores by family pairing" in {
    val gf = gfFamilyDF(Seq(
      ("Heuristic", "UciEngine", "white"),
      ("Heuristic", "UciEngine", "white"),
      ("Heuristic", "UciEngine", "draw")
    ))
    val result = GameAnalytics.computeFamilyMatchups(gf).collect()
    result should have length 1
    val row = result.head
    row.getAs[Double]("whiteScore") shouldBe 2.5 +- 0.01
    row.getAs[Double]("blackScore") shouldBe 0.5 +- 0.01
  }

  // ── computeSearchessAiComparison ─────────────────────────────────────────────

  "computeSearchessAiComparison" should "count wins and losses versus each opponent" in {
    val gf = gfAiDF(Seq(
      ("searchess-ai-v1", "ai-service", "random-bot",    "heuristic", "white", 10L),
      ("searchess-ai-v1", "ai-service", "random-bot",    "heuristic", "white", 12L),
      ("random-bot",      "heuristic",  "searchess-ai-v1", "ai-service", "white", 8L)
    ))
    val result = GameAnalytics.computeSearchessAiComparison(gf).collect()
      .map(r => r.getAs[String]("opponentBotId") -> r).toMap

    result("random-bot").getAs[Long]("searchessAiWins") shouldBe 2L
    result("random-bot").getAs[Long]("losses")          shouldBe 1L
    result("random-bot").getAs[Long]("games")           shouldBe 3L
  }

  it should "exclude games that do not involve searchess-ai-v1" in {
    val gf = gfAiDF(Seq(
      ("searchess-ai-v1", "ai-service", "random-bot", "heuristic", "white", 10L),
      ("random-bot",      "heuristic",  "capture-first", "heuristic", "white", 15L)  // AI not involved
    ))
    val result = GameAnalytics.computeSearchessAiComparison(gf).collect()
    result should have length 1
    result.head.getAs[String]("opponentBotId") shouldBe "random-bot"
  }

  it should "count draws correctly" in {
    val gf = gfAiDF(Seq(
      ("searchess-ai-v1", "ai-service", "random-bot", "heuristic", "draw", 200L),
      ("searchess-ai-v1", "ai-service", "random-bot", "heuristic", "draw", 200L)
    ))
    val result = GameAnalytics.computeSearchessAiComparison(gf).collect()
    result should have length 1
    result.head.getAs[Long]("draws")          shouldBe 2L
    result.head.getAs[Long]("searchessAiWins") shouldBe 0L
  }
}
