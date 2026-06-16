package chess.analyticsservice

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.http4s.*
import org.http4s.implicits.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AnalyticsRoutesSpec extends AnyFlatSpec with Matchers:

  private def app(repo: AnalyticsRepository): HttpApp[IO] =
    new AnalyticsRoutes(repo).routes.orNotFound

  private def get(repo: AnalyticsRepository, path: Uri): Response[IO] =
    app(repo).run(Request[IO](Method.GET, path)).unsafeRunSync()

  private def bodyJson(resp: Response[IO]): ujson.Value =
    ujson.read(resp.bodyText.compile.string.unsafeRunSync())

  private val validRunId  = InMemoryAnalyticsRepository.sampleRunId
  private val validRunId2 = InMemoryAnalyticsRepository.sampleRunId2
  private val invalidRunId = "not-a-uuid"

  // ── /health ───────────────────────────────────────────────────────────────

  "GET /health" should "return 200 with status ok" in {
    val resp = get(InMemoryAnalyticsRepository.empty, uri"/health")
    resp.status shouldBe Status.Ok
    val json = bodyJson(resp)
    json("status").str shouldBe "ok"
    json("service").str shouldBe "searchess-analytics-service"
  }

  // ── /api/analytics/runs ───────────────────────────────────────────────────

  "GET /api/analytics/runs" should "return 200 with empty runs array when no data" in {
    val resp = get(InMemoryAnalyticsRepository.empty, uri"/api/analytics/runs")
    resp.status shouldBe Status.Ok
    bodyJson(resp)("runs").arr shouldBe empty
  }

  it should "return 200 with run entries when data exists" in {
    val resp = get(InMemoryAnalyticsRepository.withSampleData, uri"/api/analytics/runs")
    resp.status shouldBe Status.Ok
    val runs = bodyJson(resp)("runs").arr
    runs should have size 1
    runs.head("runId").str shouldBe validRunId
    runs.head("sourcePath").str shouldBe "/data/games.jsonl"
    runs.head("createdAt").str shouldBe "2026-06-13T00:00:00Z"
  }

  it should "return 200 with multiple runs newest first" in {
    val resp = get(InMemoryAnalyticsRepository.withTwoRuns, uri"/api/analytics/runs")
    resp.status shouldBe Status.Ok
    val runs = bodyJson(resp)("runs").arr
    runs should have size 2
    runs.head("runId").str shouldBe validRunId2
    runs(1)("runId").str shouldBe validRunId
  }

  it should "return 503 when repo fails" in {
    val resp = get(InMemoryAnalyticsRepository.withError("db down"), uri"/api/analytics/runs")
    resp.status shouldBe Status.ServiceUnavailable
    val json = bodyJson(resp)
    json("code").str shouldBe "ANALYTICS_UNAVAILABLE"
    json("message").str shouldBe "db down"
  }

  // ── /api/analytics/latest/leaderboard ────────────────────────────────────

  "GET /api/analytics/latest/leaderboard" should "return 200 with leaderboard rows" in {
    val resp = get(InMemoryAnalyticsRepository.withSampleData, uri"/api/analytics/latest/leaderboard")
    resp.status shouldBe Status.Ok
    val rows = bodyJson(resp)("rows").arr
    rows should have size 1
    rows.head("botId").str shouldBe "stockfish-fast"
    rows.head("totalScore").num shouldBe 10.0
    rows.head("wins").num.toLong shouldBe 5L
    rows.head("winRate").num shouldBe 1.0
  }

  it should "return 200 with empty rows when no data" in {
    val resp = get(InMemoryAnalyticsRepository.empty, uri"/api/analytics/latest/leaderboard")
    resp.status shouldBe Status.Ok
    bodyJson(resp)("rows").arr shouldBe empty
  }

  it should "return 503 when repo fails" in {
    val resp = get(InMemoryAnalyticsRepository.withError("timeout"), uri"/api/analytics/latest/leaderboard")
    resp.status shouldBe Status.ServiceUnavailable
    bodyJson(resp)("code").str shouldBe "ANALYTICS_UNAVAILABLE"
  }

  // ── /api/analytics/latest/bot-families ───────────────────────────────────

  "GET /api/analytics/latest/bot-families" should "return 200 with bot family rows" in {
    val resp = get(InMemoryAnalyticsRepository.withSampleData, uri"/api/analytics/latest/bot-families")
    resp.status shouldBe Status.Ok
    val rows = bodyJson(resp)("rows").arr
    rows should have size 1
    rows.head("family").str shouldBe "stockfish"
    rows.head("totalScore").num shouldBe 8.0
  }

  it should "return 503 when repo fails" in {
    val resp = get(InMemoryAnalyticsRepository.withError("err"), uri"/api/analytics/latest/bot-families")
    resp.status shouldBe Status.ServiceUnavailable
    bodyJson(resp)("code").str shouldBe "ANALYTICS_UNAVAILABLE"
  }

  // ── /api/analytics/latest/strategies ─────────────────────────────────────

  "GET /api/analytics/latest/strategies" should "return 200 with strategy rows" in {
    val resp = get(InMemoryAnalyticsRepository.withSampleData, uri"/api/analytics/latest/strategies")
    resp.status shouldBe Status.Ok
    val rows = bodyJson(resp)("rows").arr
    rows should have size 1
    rows.head("strategyType").str shouldBe "minimax"
  }

  it should "return 503 when repo fails" in {
    val resp = get(InMemoryAnalyticsRepository.withError("err"), uri"/api/analytics/latest/strategies")
    resp.status shouldBe Status.ServiceUnavailable
    bodyJson(resp)("code").str shouldBe "ANALYTICS_UNAVAILABLE"
  }

  // ── /api/analytics/latest/searchess-ai ───────────────────────────────────

  "GET /api/analytics/latest/searchess-ai" should "return 200 with searchess AI comparison rows" in {
    val resp = get(InMemoryAnalyticsRepository.withSampleData, uri"/api/analytics/latest/searchess-ai")
    resp.status shouldBe Status.Ok
    val rows = bodyJson(resp)("rows").arr
    rows should have size 1
    rows.head("opponentBotId").str shouldBe "random-bot"
    rows.head("searchessAiWins").num.toLong shouldBe 4L
    rows.head("score").num shouldBe 4.5
  }

  it should "return 503 when repo fails" in {
    val resp = get(InMemoryAnalyticsRepository.withError("err"), uri"/api/analytics/latest/searchess-ai")
    resp.status shouldBe Status.ServiceUnavailable
    bodyJson(resp)("code").str shouldBe "ANALYTICS_UNAVAILABLE"
  }

  // ── /api/analytics/latest/stockfish ──────────────────────────────────────

  "GET /api/analytics/latest/stockfish" should "return 200 with stockfish comparison rows" in {
    val resp = get(InMemoryAnalyticsRepository.withSampleData, uri"/api/analytics/latest/stockfish")
    resp.status shouldBe Status.Ok
    val rows = bodyJson(resp)("rows").arr
    rows should have size 1
    rows.head("botId").str shouldBe "stockfish-fast"
    rows.head("strategyType").str shouldBe "stockfish"
    rows.head("avgGameLength").num shouldBe 38.0
  }

  it should "return 503 when repo fails" in {
    val resp = get(InMemoryAnalyticsRepository.withError("err"), uri"/api/analytics/latest/stockfish")
    resp.status shouldBe Status.ServiceUnavailable
    bodyJson(resp)("code").str shouldBe "ANALYTICS_UNAVAILABLE"
  }

  // ── /api/analytics/latest/avg-game-length ────────────────────────────────

  "GET /api/analytics/latest/avg-game-length" should "return 200 with avg game length rows" in {
    val resp = get(InMemoryAnalyticsRepository.withSampleData, uri"/api/analytics/latest/avg-game-length")
    resp.status shouldBe Status.Ok
    val rows = bodyJson(resp)("rows").arr
    rows should have size 1
    rows.head("whiteBotId").str shouldBe "stockfish-fast"
    rows.head("blackBotId").str shouldBe "random-bot"
    rows.head("gamesPlayed").num.toLong shouldBe 5L
    rows.head("avgTotalPly").num shouldBe 42.0
    rows.head("avgDurationMs").num shouldBe 3200.0
  }

  it should "return 200 with empty rows when no data" in {
    val resp = get(InMemoryAnalyticsRepository.empty, uri"/api/analytics/latest/avg-game-length")
    resp.status shouldBe Status.Ok
    bodyJson(resp)("rows").arr shouldBe empty
  }

  it should "return 503 when repo fails" in {
    val resp = get(InMemoryAnalyticsRepository.withError("err"), uri"/api/analytics/latest/avg-game-length")
    resp.status shouldBe Status.ServiceUnavailable
    bodyJson(resp)("code").str shouldBe "ANALYTICS_UNAVAILABLE"
  }

  // â”€â”€ /api/analytics/latest/elo-ratings â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

  "GET /api/analytics/latest/elo-ratings" should "return 200 with Elo rating rows" in {
    val resp = get(InMemoryAnalyticsRepository.withSampleData, uri"/api/analytics/latest/elo-ratings")
    resp.status shouldBe Status.Ok
    val rows = bodyJson(resp)("rows").arr
    rows should have size 1
    rows.head("botId").str shouldBe "stockfish-fast"
    rows.head("rating").num shouldBe 1055.4
    rows.head("ratingChange").num shouldBe 55.4
    rows.head("averageOpponentRating").num shouldBe 998.2
  }

  it should "return 503 when repo fails" in {
    val resp = get(InMemoryAnalyticsRepository.withError("err"), uri"/api/analytics/latest/elo-ratings")
    resp.status shouldBe Status.ServiceUnavailable
    bodyJson(resp)("code").str shouldBe "ANALYTICS_UNAVAILABLE"
  }

  "GET /api/analytics/latest/terminations" should "return 200 with termination rows" in {
    val resp = get(InMemoryAnalyticsRepository.withSampleData, uri"/api/analytics/latest/terminations")
    resp.status shouldBe Status.Ok
    val rows = bodyJson(resp)("rows").arr
    rows should have size 1
    rows.head("terminationReason").str shouldBe "checkmate"
    rows.head("count").num.toLong shouldBe 7L
  }

  it should "return 503 when repo fails" in {
    val resp = get(InMemoryAnalyticsRepository.withError("err"), uri"/api/analytics/latest/terminations")
    resp.status shouldBe Status.ServiceUnavailable
    bodyJson(resp)("code").str shouldBe "ANALYTICS_UNAVAILABLE"
  }

  "GET /api/analytics/latest/color-performance" should "return 200 with color performance rows" in {
    val resp = get(InMemoryAnalyticsRepository.withSampleData, uri"/api/analytics/latest/color-performance")
    resp.status shouldBe Status.Ok
    val rows = bodyJson(resp)("rows").arr
    rows should have size 1
    rows.head("botId").str shouldBe "stockfish-fast"
    rows.head("gamesAsWhite").num.toLong shouldBe 3L
    rows.head("blackScore").num shouldBe 2.0
  }

  it should "return 503 when repo fails" in {
    val resp = get(InMemoryAnalyticsRepository.withError("err"), uri"/api/analytics/latest/color-performance")
    resp.status shouldBe Status.ServiceUnavailable
    bodyJson(resp)("code").str shouldBe "ANALYTICS_UNAVAILABLE"
  }

  "GET /api/analytics/latest/fastest-wins" should "return 200 with fastest win rows" in {
    val resp = get(InMemoryAnalyticsRepository.withSampleData, uri"/api/analytics/latest/fastest-wins")
    resp.status shouldBe Status.Ok
    val rows = bodyJson(resp)("rows").arr
    rows should have size 1
    rows.head("winnerBotId").str shouldBe "stockfish-fast"
    rows.head("decisiveGames").num.toLong shouldBe 4L
    rows.head("avgWinPly").num shouldBe 28.5
  }

  it should "return 503 when repo fails" in {
    val resp = get(InMemoryAnalyticsRepository.withError("err"), uri"/api/analytics/latest/fastest-wins")
    resp.status shouldBe Status.ServiceUnavailable
    bodyJson(resp)("code").str shouldBe "ANALYTICS_UNAVAILABLE"
  }

  // ── Run-specific: invalid runId ───────────────────────────────────────────

  "GET /api/analytics/runs/:runId/leaderboard with invalid runId" should "return 400 INVALID_RUN_ID" in {
    val resp = get(InMemoryAnalyticsRepository.withSampleData,
      Uri.unsafeFromString(s"/api/analytics/runs/$invalidRunId/leaderboard"))
    resp.status shouldBe Status.BadRequest
    val json = bodyJson(resp)
    json("code").str shouldBe "INVALID_RUN_ID"
    json("message").str should include(invalidRunId)
  }

  it should "return 400 for all run-specific endpoints" in {
    val sections = List(
      "leaderboard", "bot-families", "strategies", "searchess-ai", "stockfish",
      "avg-game-length", "elo-ratings", "terminations", "color-performance", "fastest-wins"
    )
    sections.foreach { section =>
      val resp = get(InMemoryAnalyticsRepository.empty,
        Uri.unsafeFromString(s"/api/analytics/runs/$invalidRunId/$section"))
      withClue(s"/$section with invalid runId:") {
        resp.status shouldBe Status.BadRequest
        bodyJson(resp)("code").str shouldBe "INVALID_RUN_ID"
      }
    }
  }

  // ── Run-specific: leaderboard ─────────────────────────────────────────────

  s"GET /api/analytics/runs/:runId/leaderboard" should "return 200 with leaderboard rows for valid runId" in {
    val resp = get(InMemoryAnalyticsRepository.withSampleData,
      Uri.unsafeFromString(s"/api/analytics/runs/$validRunId/leaderboard"))
    resp.status shouldBe Status.Ok
    val rows = bodyJson(resp)("rows").arr
    rows should have size 1
    rows.head("botId").str shouldBe "stockfish-fast"
    rows.head("totalScore").num shouldBe 10.0
  }

  it should "return 503 when repo fails" in {
    val resp = get(InMemoryAnalyticsRepository.withError("db err"),
      Uri.unsafeFromString(s"/api/analytics/runs/$validRunId/leaderboard"))
    resp.status shouldBe Status.ServiceUnavailable
    bodyJson(resp)("code").str shouldBe "ANALYTICS_UNAVAILABLE"
  }

  // ── Run-specific: bot-families ────────────────────────────────────────────

  s"GET /api/analytics/runs/:runId/bot-families" should "return 200 with bot family rows for valid runId" in {
    val resp = get(InMemoryAnalyticsRepository.withSampleData,
      Uri.unsafeFromString(s"/api/analytics/runs/$validRunId/bot-families"))
    resp.status shouldBe Status.Ok
    val rows = bodyJson(resp)("rows").arr
    rows should have size 1
    rows.head("family").str shouldBe "stockfish"
  }

  it should "return 503 when repo fails" in {
    val resp = get(InMemoryAnalyticsRepository.withError("db err"),
      Uri.unsafeFromString(s"/api/analytics/runs/$validRunId/bot-families"))
    resp.status shouldBe Status.ServiceUnavailable
    bodyJson(resp)("code").str shouldBe "ANALYTICS_UNAVAILABLE"
  }

  // ── Run-specific: strategies ──────────────────────────────────────────────

  s"GET /api/analytics/runs/:runId/strategies" should "return 200 with strategy rows for valid runId" in {
    val resp = get(InMemoryAnalyticsRepository.withSampleData,
      Uri.unsafeFromString(s"/api/analytics/runs/$validRunId/strategies"))
    resp.status shouldBe Status.Ok
    val rows = bodyJson(resp)("rows").arr
    rows should have size 1
    rows.head("strategyType").str shouldBe "minimax"
  }

  it should "return 503 when repo fails" in {
    val resp = get(InMemoryAnalyticsRepository.withError("db err"),
      Uri.unsafeFromString(s"/api/analytics/runs/$validRunId/strategies"))
    resp.status shouldBe Status.ServiceUnavailable
    bodyJson(resp)("code").str shouldBe "ANALYTICS_UNAVAILABLE"
  }

  // ── Run-specific: searchess-ai ────────────────────────────────────────────

  s"GET /api/analytics/runs/:runId/searchess-ai" should "return 200 with searchess AI rows for valid runId" in {
    val resp = get(InMemoryAnalyticsRepository.withSampleData,
      Uri.unsafeFromString(s"/api/analytics/runs/$validRunId/searchess-ai"))
    resp.status shouldBe Status.Ok
    val rows = bodyJson(resp)("rows").arr
    rows should have size 1
    rows.head("opponentBotId").str shouldBe "random-bot"
    rows.head("score").num shouldBe 4.5
  }

  it should "return 503 when repo fails" in {
    val resp = get(InMemoryAnalyticsRepository.withError("db err"),
      Uri.unsafeFromString(s"/api/analytics/runs/$validRunId/searchess-ai"))
    resp.status shouldBe Status.ServiceUnavailable
    bodyJson(resp)("code").str shouldBe "ANALYTICS_UNAVAILABLE"
  }

  // ── Run-specific: stockfish ───────────────────────────────────────────────

  s"GET /api/analytics/runs/:runId/stockfish" should "return 200 with stockfish rows for valid runId" in {
    val resp = get(InMemoryAnalyticsRepository.withSampleData,
      Uri.unsafeFromString(s"/api/analytics/runs/$validRunId/stockfish"))
    resp.status shouldBe Status.Ok
    val rows = bodyJson(resp)("rows").arr
    rows should have size 1
    rows.head("botId").str shouldBe "stockfish-fast"
    rows.head("totalScore").num shouldBe 3.5
  }

  it should "return 503 when repo fails" in {
    val resp = get(InMemoryAnalyticsRepository.withError("db err"),
      Uri.unsafeFromString(s"/api/analytics/runs/$validRunId/stockfish"))
    resp.status shouldBe Status.ServiceUnavailable
    bodyJson(resp)("code").str shouldBe "ANALYTICS_UNAVAILABLE"
  }

  // ── Run-specific: avg-game-length ─────────────────────────────────────────

  s"GET /api/analytics/runs/:runId/avg-game-length" should "return 200 with avg game length rows for valid runId" in {
    val resp = get(InMemoryAnalyticsRepository.withSampleData,
      Uri.unsafeFromString(s"/api/analytics/runs/$validRunId/avg-game-length"))
    resp.status shouldBe Status.Ok
    val rows = bodyJson(resp)("rows").arr
    rows should have size 1
    rows.head("whiteBotId").str shouldBe "stockfish-fast"
    rows.head("avgTotalPly").num shouldBe 42.0
  }

  it should "return 503 when repo fails" in {
    val resp = get(InMemoryAnalyticsRepository.withError("db err"),
      Uri.unsafeFromString(s"/api/analytics/runs/$validRunId/avg-game-length"))
    resp.status shouldBe Status.ServiceUnavailable
    bodyJson(resp)("code").str shouldBe "ANALYTICS_UNAVAILABLE"
  }

  // â”€â”€ Run-specific: elo-ratings â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

  s"GET /api/analytics/runs/:runId/elo-ratings" should "return 200 with Elo rating rows for valid runId" in {
    val resp = get(InMemoryAnalyticsRepository.withSampleData,
      Uri.unsafeFromString(s"/api/analytics/runs/$validRunId/elo-ratings"))
    resp.status shouldBe Status.Ok
    val rows = bodyJson(resp)("rows").arr
    rows should have size 1
    rows.head("botId").str shouldBe "stockfish-fast"
    rows.head("rating").num shouldBe 1055.4
  }

  it should "return 503 when repo fails" in {
    val resp = get(InMemoryAnalyticsRepository.withError("db err"),
      Uri.unsafeFromString(s"/api/analytics/runs/$validRunId/elo-ratings"))
    resp.status shouldBe Status.ServiceUnavailable
    bodyJson(resp)("code").str shouldBe "ANALYTICS_UNAVAILABLE"
  }

  s"GET /api/analytics/runs/:runId/terminations" should "return 200 with termination rows for valid runId" in {
    val resp = get(InMemoryAnalyticsRepository.withSampleData,
      Uri.unsafeFromString(s"/api/analytics/runs/$validRunId/terminations"))
    resp.status shouldBe Status.Ok
    val rows = bodyJson(resp)("rows").arr
    rows should have size 1
    rows.head("terminationReason").str shouldBe "checkmate"
    rows.head("count").num.toLong shouldBe 7L
  }

  it should "return 503 when repo fails" in {
    val resp = get(InMemoryAnalyticsRepository.withError("db err"),
      Uri.unsafeFromString(s"/api/analytics/runs/$validRunId/terminations"))
    resp.status shouldBe Status.ServiceUnavailable
    bodyJson(resp)("code").str shouldBe "ANALYTICS_UNAVAILABLE"
  }

  s"GET /api/analytics/runs/:runId/color-performance" should "return 200 with color performance rows for valid runId" in {
    val resp = get(InMemoryAnalyticsRepository.withSampleData,
      Uri.unsafeFromString(s"/api/analytics/runs/$validRunId/color-performance"))
    resp.status shouldBe Status.Ok
    val rows = bodyJson(resp)("rows").arr
    rows should have size 1
    rows.head("botId").str shouldBe "stockfish-fast"
    rows.head("whiteScore").num shouldBe 2.5
  }

  it should "return 503 when repo fails" in {
    val resp = get(InMemoryAnalyticsRepository.withError("db err"),
      Uri.unsafeFromString(s"/api/analytics/runs/$validRunId/color-performance"))
    resp.status shouldBe Status.ServiceUnavailable
    bodyJson(resp)("code").str shouldBe "ANALYTICS_UNAVAILABLE"
  }

  s"GET /api/analytics/runs/:runId/fastest-wins" should "return 200 with fastest win rows for valid runId" in {
    val resp = get(InMemoryAnalyticsRepository.withSampleData,
      Uri.unsafeFromString(s"/api/analytics/runs/$validRunId/fastest-wins"))
    resp.status shouldBe Status.Ok
    val rows = bodyJson(resp)("rows").arr
    rows should have size 1
    rows.head("winnerBotId").str shouldBe "stockfish-fast"
    rows.head("minWinPly").num.toLong shouldBe 18L
  }

  it should "return 503 when repo fails" in {
    val resp = get(InMemoryAnalyticsRepository.withError("db err"),
      Uri.unsafeFromString(s"/api/analytics/runs/$validRunId/fastest-wins"))
    resp.status shouldBe Status.ServiceUnavailable
    bodyJson(resp)("code").str shouldBe "ANALYTICS_UNAVAILABLE"
  }

  // ── unknown route ─────────────────────────────────────────────────────────

  "GET /unknown" should "return 404" in {
    val resp = get(InMemoryAnalyticsRepository.empty, uri"/unknown")
    resp.status shouldBe Status.NotFound
  }
