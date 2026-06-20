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

  // ── /api/analytics/live/game-results ─────────────────────────────────────

  "GET /api/analytics/live/game-results" should "return 200 with live game result rows" in {
    val resp = get(InMemoryAnalyticsRepository.withSampleData, uri"/api/analytics/live/game-results")
    resp.status shouldBe Status.Ok
    val rows = bodyJson(resp)("rows").arr
    rows should have size 1
    val row = rows.head
    row("eventId").str shouldBe "550e8400-e29b-41d4-a716-446655440abc"
    row("aggregateId").str shouldBe "game-001"
    row("sessionId").str shouldBe "sess-001"
    row("occurredAt").str shouldBe "2026-06-17T10:00:00Z"
    row("result").str shouldBe "Checkmate"
    row("winner").str shouldBe "White"
    row("drawReason") shouldBe ujson.Null
    row("correlationId").str shouldBe "corr-001"
    row("ingestedAt").str shouldBe "2026-06-17T10:00:01Z"
  }

  it should "return 200 with empty rows when no data" in {
    val resp = get(InMemoryAnalyticsRepository.empty, uri"/api/analytics/live/game-results")
    resp.status shouldBe Status.Ok
    bodyJson(resp)("rows").arr shouldBe empty
  }

  it should "return 503 when repo fails" in {
    val resp = get(InMemoryAnalyticsRepository.withError("stream error"), uri"/api/analytics/live/game-results")
    resp.status shouldBe Status.ServiceUnavailable
    val json = bodyJson(resp)
    json("code").str shouldBe "ANALYTICS_UNAVAILABLE"
    json("message").str shouldBe "stream error"
  }

  it should "return 400 INVALID_LIMIT for non-numeric limit" in {
    val resp = get(InMemoryAnalyticsRepository.empty,
      Uri.unsafeFromString("/api/analytics/live/game-results?limit=foo"))
    resp.status shouldBe Status.BadRequest
    val json = bodyJson(resp)
    json("code").str shouldBe "INVALID_LIMIT"
    json("message").str should include("foo")
  }

  it should "default limit to 50 when not specified" in {
    val repo = new InMemoryAnalyticsRepository(
      liveGameResults = Right(List.fill(100)(InMemoryAnalyticsRepository.sampleLiveGameResultRow))
    )
    val resp = get(repo, uri"/api/analytics/live/game-results")
    resp.status shouldBe Status.Ok
    // InMemory double ignores limit param — just verifies route accepts no-limit
    bodyJson(resp)("rows").arr should have size 100
  }

  it should "clamp out-of-range limit to valid bounds" in {
    val resp = get(InMemoryAnalyticsRepository.withSampleData,
      Uri.unsafeFromString("/api/analytics/live/game-results?limit=999"))
    resp.status shouldBe Status.Ok
  }

  // ── Run-specific: head-to-head ────────────────────────────────────────────

  s"GET /api/analytics/runs/:runId/head-to-head" should "return 200 with head-to-head rows" in {
    val resp = get(InMemoryAnalyticsRepository.withSampleData,
      Uri.unsafeFromString(s"/api/analytics/runs/$validRunId/head-to-head"))
    resp.status shouldBe Status.Ok
    val rows = bodyJson(resp)("rows").arr
    rows should have size 1
    rows.head("whiteBotId").str shouldBe "alpha"
    rows.head("blackBotId").str shouldBe "beta"
    rows.head("gamesPlayed").num.toLong shouldBe 2L
    rows.head("whiteWins").num.toLong shouldBe 1L
    rows.head("draws").num.toLong shouldBe 1L
    rows.head("whiteScore").num shouldBe 1.5
    rows.head("blackScore").num shouldBe 0.5
  }

  it should "return 200 with empty rows when no data" in {
    val resp = get(InMemoryAnalyticsRepository.empty,
      Uri.unsafeFromString(s"/api/analytics/runs/$validRunId/head-to-head"))
    resp.status shouldBe Status.Ok
    bodyJson(resp)("rows").arr shouldBe empty
  }

  it should "return 400 INVALID_RUN_ID for invalid runId" in {
    val resp = get(InMemoryAnalyticsRepository.empty,
      Uri.unsafeFromString(s"/api/analytics/runs/$invalidRunId/head-to-head"))
    resp.status shouldBe Status.BadRequest
    bodyJson(resp)("code").str shouldBe "INVALID_RUN_ID"
  }

  it should "return 503 when repo fails" in {
    val resp = get(InMemoryAnalyticsRepository.withError("db err"),
      Uri.unsafeFromString(s"/api/analytics/runs/$validRunId/head-to-head"))
    resp.status shouldBe Status.ServiceUnavailable
    bodyJson(resp)("code").str shouldBe "ANALYTICS_UNAVAILABLE"
  }

  // ── Run-specific: family-matchups ─────────────────────────────────────────

  s"GET /api/analytics/runs/:runId/family-matchups" should "return 200 with family matchup rows" in {
    val resp = get(InMemoryAnalyticsRepository.withSampleData,
      Uri.unsafeFromString(s"/api/analytics/runs/$validRunId/family-matchups"))
    resp.status shouldBe Status.Ok
    val rows = bodyJson(resp)("rows").arr
    rows should have size 1
    rows.head("whiteBotFamily").str shouldBe "heuristic"
    rows.head("blackBotFamily").str shouldBe "heuristic"
    rows.head("games").num.toLong shouldBe 3L
    rows.head("whiteScore").num shouldBe 1.5
    rows.head("draws").num.toLong shouldBe 1L
  }

  it should "return 400 INVALID_RUN_ID for invalid runId" in {
    val resp = get(InMemoryAnalyticsRepository.empty,
      Uri.unsafeFromString(s"/api/analytics/runs/$invalidRunId/family-matchups"))
    resp.status shouldBe Status.BadRequest
    bodyJson(resp)("code").str shouldBe "INVALID_RUN_ID"
  }

  it should "return 503 when repo fails" in {
    val resp = get(InMemoryAnalyticsRepository.withError("db err"),
      Uri.unsafeFromString(s"/api/analytics/runs/$validRunId/family-matchups"))
    resp.status shouldBe Status.ServiceUnavailable
    bodyJson(resp)("code").str shouldBe "ANALYTICS_UNAVAILABLE"
  }

  // ── Run-specific: game-results ────────────────────────────────────────────

  s"GET /api/analytics/runs/:runId/game-results" should "return 200 with game result rows" in {
    val resp = get(InMemoryAnalyticsRepository.withSampleData,
      Uri.unsafeFromString(s"/api/analytics/runs/$validRunId/game-results"))
    resp.status shouldBe Status.Ok
    val rows = bodyJson(resp)("rows").arr
    rows should have size 2
    val decisive = rows.head
    decisive("gameId").str shouldBe "game-01"
    decisive("whiteBotId").str shouldBe "alpha"
    decisive("blackBotId").str shouldBe "beta"
    decisive("result").str shouldBe "white"
    decisive("winnerBotId").str shouldBe "alpha"
    decisive("loserBotId").str shouldBe "beta"
    decisive("terminationReason").str shouldBe "checkmate"
    decisive("totalPly").num.toLong shouldBe 5L
    decisive("durationMillis").num.toLong shouldBe 45000L
    decisive("endedAt").str shouldBe "2025-01-01T10:02:00Z"
  }

  it should "return null winnerBotId and loserBotId for draw games" in {
    val resp = get(InMemoryAnalyticsRepository.withSampleData,
      Uri.unsafeFromString(s"/api/analytics/runs/$validRunId/game-results"))
    resp.status shouldBe Status.Ok
    val drawRow = bodyJson(resp)("rows").arr(1)
    drawRow("result").str shouldBe "draw"
    drawRow("winnerBotId") shouldBe ujson.Null
    drawRow("loserBotId") shouldBe ujson.Null
    drawRow("terminationReason").str shouldBe "stalemate"
  }

  it should "return 400 INVALID_RUN_ID for invalid runId" in {
    val resp = get(InMemoryAnalyticsRepository.empty,
      Uri.unsafeFromString(s"/api/analytics/runs/$invalidRunId/game-results"))
    resp.status shouldBe Status.BadRequest
    bodyJson(resp)("code").str shouldBe "INVALID_RUN_ID"
  }

  it should "return 503 when repo fails" in {
    val resp = get(InMemoryAnalyticsRepository.withError("db err"),
      Uri.unsafeFromString(s"/api/analytics/runs/$validRunId/game-results"))
    resp.status shouldBe Status.ServiceUnavailable
    bodyJson(resp)("code").str shouldBe "ANALYTICS_UNAVAILABLE"
  }

  // ── Run-specific: public-tournament bundle ────────────────────────────────

  private val bundleRepo = new InMemoryAnalyticsRepository(
    leaderboard    = Right(List(
      LeaderboardRow("alpha", 2.5, 2L, 1L, 0L, 3L, 6.3, 0.667),
      LeaderboardRow("beta",  0.5, 0L, 1L, 2L, 3L, 6.3, 0.0)
    )),
    eloRatings     = Right(List(InMemoryAnalyticsRepository.sampleEloRatingsRow)),
    headToHead     = Right(List(InMemoryAnalyticsRepository.sampleHeadToHeadRow)),
    avgGameLength  = Right(List(InMemoryAnalyticsRepository.sampleAvgGameLengthRow)),
    terminations   = Right(List(
      TerminationReasonRow("checkmate", 2L),
      TerminationReasonRow("stalemate", 1L)
    )),
    colorPerf      = Right(List(InMemoryAnalyticsRepository.sampleColorPerformanceRow)),
    fastestWins    = Right(List(InMemoryAnalyticsRepository.sampleFastestWinRow)),
    gameResults    = Right(List(
      InMemoryAnalyticsRepository.sampleGameResultRow,
      InMemoryAnalyticsRepository.sampleDrawGameResultRow
    )),
    botFamilies    = Right(List(InMemoryAnalyticsRepository.sampleBotFamilyRow)),
    strategies     = Right(List(InMemoryAnalyticsRepository.sampleStrategyRow))
  )

  s"GET /api/analytics/runs/:runId/public-tournament" should "return 200 with all top-level sections" in {
    val resp = get(bundleRepo, Uri.unsafeFromString(s"/api/analytics/runs/$validRunId/public-tournament"))
    resp.status shouldBe Status.Ok
    val body = bodyJson(resp)
    body.obj.keys.toSet shouldBe Set(
      "summary", "botStatistics", "leaderboard", "eloRatings",
      "games", "matchups", "behavior", "metadata", "medals"
    )
  }

  it should "compute summary with correct totalGames, botCount, winner, decisiveGames, drawGames" in {
    val resp = get(bundleRepo, Uri.unsafeFromString(s"/api/analytics/runs/$validRunId/public-tournament"))
    val summary = bodyJson(resp)("summary")
    summary("totalGames").num.toLong shouldBe 3L      // sum of termination counts
    summary("botCount").num.toLong shouldBe 2L        // distinct leaderboard rows
    summary("winner").str shouldBe "alpha"            // rank-1 bot
    summary("decisiveGames").num.toLong shouldBe 2L   // sum(wins) across all bots
    summary("drawGames").num.toLong shouldBe 1L       // sum(draws)/2
  }

  it should "include rank and avgPly in botStatistics" in {
    val resp = get(bundleRepo, Uri.unsafeFromString(s"/api/analytics/runs/$validRunId/public-tournament"))
    val bots = bodyJson(resp)("botStatistics").arr
    bots should have size 2
    bots.head("rank").num.toLong shouldBe 1L
    bots.head("botId").str shouldBe "alpha"
    bots.head("score").num shouldBe 2.5
    bots.head("avgPly").num shouldBe 6.3
    bots(1)("rank").num.toLong shouldBe 2L
    bots(1)("botId").str shouldBe "beta"
  }

  it should "derive medals for top 3 bots" in {
    val resp = get(bundleRepo, Uri.unsafeFromString(s"/api/analytics/runs/$validRunId/public-tournament"))
    val medals = bodyJson(resp)("medals").arr
    medals should have size 2   // only 2 bots in fixture
    medals.head("medal").str shouldBe "gold"
    medals.head("botId").str shouldBe "alpha"
    medals(1)("medal").str shouldBe "silver"
    medals(1)("botId").str shouldBe "beta"
  }

  it should "include game results with winner and termination reason" in {
    val resp = get(bundleRepo, Uri.unsafeFromString(s"/api/analytics/runs/$validRunId/public-tournament"))
    val games = bodyJson(resp)("games").arr
    games should have size 2
    games.head("result").str shouldBe "white"
    games.head("terminationReason").str shouldBe "checkmate"
    games.head("totalPly").num.toLong shouldBe 5L
    games(1)("result").str shouldBe "draw"
    games(1)("winnerBotId") shouldBe ujson.Null
  }

  it should "include matchups section with headToHead and avgGameLengthByPairing" in {
    val resp = get(bundleRepo, Uri.unsafeFromString(s"/api/analytics/runs/$validRunId/public-tournament"))
    val matchups = bodyJson(resp)("matchups")
    matchups("headToHead").arr should have size 1
    matchups("avgGameLengthByPairing").arr should have size 1
    matchups("headToHead").arr.head("whiteBotId").str shouldBe "alpha"
  }

  it should "include behavior section with terminations, colorPerformance, fastestWins" in {
    val resp = get(bundleRepo, Uri.unsafeFromString(s"/api/analytics/runs/$validRunId/public-tournament"))
    val behavior = bodyJson(resp)("behavior")
    behavior("terminations").arr should have size 2
    behavior("terminations").arr.head("terminationReason").str shouldBe "checkmate"
    behavior("colorPerformance").arr should have size 1
    behavior("fastestWins").arr should have size 1
  }

  it should "include metadata with botFamilies and strategies when available, engineVersions always empty" in {
    val resp = get(bundleRepo, Uri.unsafeFromString(s"/api/analytics/runs/$validRunId/public-tournament"))
    val meta = bodyJson(resp)("metadata")
    meta("botFamilies").arr should have size 1
    meta("strategies").arr should have size 1
    meta("engineVersions").arr shouldBe empty
  }

  it should "return empty arrays for optional metadata when not available" in {
    val repoNoMeta = new InMemoryAnalyticsRepository(
      leaderboard   = Right(List(LeaderboardRow("alpha", 1.0, 1L, 0L, 0L, 1L, 5.0, 1.0))),
      eloRatings    = Right(List(InMemoryAnalyticsRepository.sampleEloRatingsRow)),
      headToHead    = Right(List(InMemoryAnalyticsRepository.sampleHeadToHeadRow)),
      avgGameLength = Right(List(InMemoryAnalyticsRepository.sampleAvgGameLengthRow)),
      terminations  = Right(List(TerminationReasonRow("checkmate", 1L))),
      colorPerf     = Right(List(InMemoryAnalyticsRepository.sampleColorPerformanceRow)),
      fastestWins   = Right(List(InMemoryAnalyticsRepository.sampleFastestWinRow)),
      gameResults   = Right(List(InMemoryAnalyticsRepository.sampleGameResultRow)),
      // botFamilies and strategies left as Right(Nil) (default)
    )
    val resp = get(repoNoMeta, Uri.unsafeFromString(s"/api/analytics/runs/$validRunId/public-tournament"))
    resp.status shouldBe Status.Ok
    val meta = bodyJson(resp)("metadata")
    meta("botFamilies").arr shouldBe empty
    meta("strategies").arr shouldBe empty
    meta("engineVersions").arr shouldBe empty
  }

  it should "return 503 when a required repo call fails" in {
    val repoFail = new InMemoryAnalyticsRepository(
      leaderboard = Left("db down")
    )
    val resp = get(repoFail, Uri.unsafeFromString(s"/api/analytics/runs/$validRunId/public-tournament"))
    resp.status shouldBe Status.ServiceUnavailable
    bodyJson(resp)("code").str shouldBe "ANALYTICS_UNAVAILABLE"
  }

  it should "return 400 INVALID_RUN_ID for invalid runId" in {
    val resp = get(InMemoryAnalyticsRepository.empty,
      Uri.unsafeFromString(s"/api/analytics/runs/$invalidRunId/public-tournament"))
    resp.status shouldBe Status.BadRequest
    bodyJson(resp)("code").str shouldBe "INVALID_RUN_ID"
  }

  // ── existing run-specific endpoints still covered by the INVALID_RUN_ID bulk test ──
  // (the sections list in the existing test includes new endpoints — verify below)

  it should "be covered by the INVALID_RUN_ID bulk validator for new sections" in {
    val newSections = List("head-to-head", "family-matchups", "game-results", "public-tournament")
    newSections.foreach { section =>
      val resp = get(InMemoryAnalyticsRepository.empty,
        Uri.unsafeFromString(s"/api/analytics/runs/$invalidRunId/$section"))
      withClue(s"/$section with invalid runId:") {
        resp.status shouldBe Status.BadRequest
        bodyJson(resp)("code").str shouldBe "INVALID_RUN_ID"
      }
    }
  }

  // ── unknown route ─────────────────────────────────────────────────────────

  "GET /unknown" should "return 404" in {
    val resp = get(InMemoryAnalyticsRepository.empty, uri"/unknown")
    resp.status shouldBe Status.NotFound
  }
