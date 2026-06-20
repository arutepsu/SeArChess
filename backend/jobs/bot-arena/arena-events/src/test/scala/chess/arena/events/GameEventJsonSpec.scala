package chess.arena.events

import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class GameEventJsonSpec extends AnyFlatSpec with Matchers with EitherValues:

  private val gameId       = "game-001"
  private val tournamentId = "eval-run-001"
  private val timestamp    = "2026-06-13T10:00:00Z"
  private val eventId      = "evt-001"

  // ── BotFamily ─────────────────────────────────────────────────────────────

  "BotFamily" should "enumerate all five families" in {
    BotFamily.values.length shouldBe 5
  }

  it should "include Heuristic, UciEngine, AiService, ImportedExternal, FutureTournamentApi" in {
    BotFamily.values.map(_.toString) should contain allOf (
      "Heuristic",
      "UciEngine",
      "AiService",
      "ImportedExternal",
      "FutureTournamentApi"
    )
  }

  it should "serialize to stable kebab-case JSON strings" in {
    BotFamily.Heuristic.toJsonString           shouldBe "heuristic"
    BotFamily.UciEngine.toJsonString           shouldBe "uci-engine"
    BotFamily.AiService.toJsonString           shouldBe "ai-service"
    BotFamily.ImportedExternal.toJsonString    shouldBe "imported-external"
    BotFamily.FutureTournamentApi.toJsonString shouldBe "future-tournament-api"
  }

  it should "round-trip all values via fromJsonString" in {
    BotFamily.values.foreach { family =>
      BotFamily.fromJsonString(family.toJsonString).value shouldBe family
    }
  }

  it should "return Left for an unknown family string" in {
    BotFamily.fromJsonString("unknown-family") shouldBe a[Left[?, ?]]
  }

  // ── GameStarted encode ────────────────────────────────────────────────────

  "GameEventJson.encode(GameStarted)" should "produce a flat JSON object with all envelope fields" in {
    val obj = encodeAndParse(sampleGameStarted)
    obj.value("schemaVersion").str  shouldBe "1"
    obj.value("eventType").str      shouldBe "GameStarted"
    obj.value("eventId").str        shouldBe eventId
    obj.value("tournamentId").str   shouldBe tournamentId
    obj.value("gameId").str         shouldBe gameId
  }

  it should "include all bot identity fields" in {
    val obj = encodeAndParse(sampleGameStarted)
    obj.value("whiteBotId").str        shouldBe "RandomBot"
    obj.value("blackBotId").str        shouldBe "StockfishDepth1"
    obj.value("whiteBotFamily").str    shouldBe "heuristic"
    obj.value("blackBotFamily").str    shouldBe "uci-engine"
    obj.value("whiteStrategyType").str shouldBe "random"
    obj.value("blackStrategyType").str shouldBe "depth-1"
    obj.value("whiteEngineType").str   shouldBe "none"
    obj.value("blackEngineType").str   shouldBe "Stockfish"
    obj.value("whiteModelVersion").str shouldBe "none"
    obj.value("blackModelVersion").str shouldBe "none"
  }

  // ── MovePlayed encode ─────────────────────────────────────────────────────

  "GameEventJson.encode(MovePlayed)" should "include plyNumber and durationMillis as numbers" in {
    val event = MovePlayed(
      eventId = eventId,
      timestamp = timestamp,
      tournamentId = tournamentId,
      gameId = gameId,
      botId = "RandomBot",
      moveUci = "e2e4",
      plyNumber = 1,
      durationMillis = 5L
    )
    val obj = encodeAndParse(event)
    obj.value("eventType").str         shouldBe "MovePlayed"
    obj.value("moveUci").str           shouldBe "e2e4"
    obj.value("plyNumber").num.toInt   shouldBe 1
    obj.value("durationMillis").num.toLong shouldBe 5L
  }

  // ── GameFinished encode ───────────────────────────────────────────────────

  "GameEventJson.encode(GameFinished)" should "omit winnerBotId and loserBotId on draw" in {
    val obj = encodeAndParse(sampleDrawFinished)
    obj.value("result").str shouldBe "draw"
    obj.value.contains("winnerBotId") shouldBe false
    obj.value.contains("loserBotId")  shouldBe false
  }

  it should "include winnerBotId and loserBotId for a decisive result" in {
    val obj = encodeAndParse(sampleDecisiveFinished)
    obj.value("result").str      shouldBe "black"
    obj.value("winnerBotId").str shouldBe "StockfishDepth1"
    obj.value("loserBotId").str  shouldBe "RandomBot"
  }

  it should "include all numeric analytics fields" in {
    val obj = encodeAndParse(sampleDecisiveFinished)
    obj.value("totalMoves").num.toInt      shouldBe 23
    obj.value("totalPly").num.toInt        shouldBe 46
    obj.value("durationMillis").num.toLong shouldBe 47100L
    obj.value("terminationReason").str     shouldBe "checkmate"
  }

  // ── Decode round-trips ────────────────────────────────────────────────────

  "GameEventJson.decode" should "round-trip a GameStarted event" in {
    roundTrip(sampleGameStarted)
  }

  it should "round-trip a MovePlayed event" in {
    roundTrip(
      MovePlayed(
        eventId = eventId,
        timestamp = timestamp,
        tournamentId = tournamentId,
        gameId = gameId,
        botId = "RandomBot",
        moveUci = "e2e4",
        plyNumber = 1,
        durationMillis = 5L
      )
    )
  }

  it should "round-trip a decisive GameFinished event" in {
    roundTrip(sampleDecisiveFinished)
  }

  it should "round-trip a draw GameFinished event" in {
    roundTrip(sampleDrawFinished)
  }

  it should "return Left for malformed JSON" in {
    GameEventJson.decode("{not valid json") shouldBe a[Left[?, ?]]
  }

  it should "return Left for an unknown eventType" in {
    val json =
      """{"schemaVersion":"1","eventType":"UnknownEvent","eventId":"x","timestamp":"t","tournamentId":"t","gameId":"g"}"""
    GameEventJson.decode(json) shouldBe a[Left[?, ?]]
  }

  it should "return Left when a required field is missing" in {
    val json =
      """{"schemaVersion":"1","eventType":"GameStarted","eventId":"x","timestamp":"t","tournamentId":"t","gameId":"g"}"""
    GameEventJson.decode(json) shouldBe a[Left[?, ?]]
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private def encodeAndParse(event: GameEvent): ujson.Obj =
    ujson.read(GameEventJson.encode(event)) match
      case obj: ujson.Obj => obj
      case other          => fail(s"Expected JSON object, got: $other")

  private def roundTrip(event: GameEvent): Unit =
    GameEventJson.decode(GameEventJson.encode(event)).value shouldBe event

  private def sampleGameStarted: GameStarted =
    GameStarted(
      eventId = eventId,
      timestamp = timestamp,
      tournamentId = tournamentId,
      gameId = gameId,
      whiteBotId = "RandomBot",
      blackBotId = "StockfishDepth1",
      whiteBotFamily = "heuristic",
      blackBotFamily = "uci-engine",
      whiteStrategyType = "random",
      blackStrategyType = "depth-1",
      whiteEngineType = "none",
      blackEngineType = "Stockfish",
      whiteModelVersion = "none",
      blackModelVersion = "none"
    )

  private def sampleDecisiveFinished: GameFinished =
    GameFinished(
      eventId = eventId,
      timestamp = timestamp,
      tournamentId = tournamentId,
      gameId = gameId,
      whiteBotId = "RandomBot",
      blackBotId = "StockfishDepth1",
      winnerBotId = Some("StockfishDepth1"),
      loserBotId = Some("RandomBot"),
      result = "black",
      whiteBotFamily = "heuristic",
      blackBotFamily = "uci-engine",
      whiteStrategyType = "random",
      blackStrategyType = "depth-1",
      whiteEngineType = "none",
      blackEngineType = "Stockfish",
      whiteModelVersion = "none",
      blackModelVersion = "none",
      totalMoves = 23,
      totalPly = 46,
      durationMillis = 47100L,
      terminationReason = "checkmate"
    )

  private def sampleDrawFinished: GameFinished =
    GameFinished(
      eventId = eventId,
      timestamp = timestamp,
      tournamentId = tournamentId,
      gameId = gameId,
      whiteBotId = "RandomBot",
      blackBotId = "StockfishDepth1",
      winnerBotId = None,
      loserBotId = None,
      result = "draw",
      whiteBotFamily = "heuristic",
      blackBotFamily = "uci-engine",
      whiteStrategyType = "random",
      blackStrategyType = "depth-1",
      whiteEngineType = "none",
      blackEngineType = "Stockfish",
      whiteModelVersion = "none",
      blackModelVersion = "none",
      totalMoves = 30,
      totalPly = 60,
      durationMillis = 5000L,
      terminationReason = "stalemate"
    )
