package chess.arena.demo

import chess.arena.bots.ai.{AiServiceBotConfig, HttpSearchessAiClient, SearchessAiBot}
import chess.arena.bots.heuristic.{CaptureFirstBot, MaterialGreedyBot, RandomBot}
import chess.arena.bots.uci.StockfishBot
import chess.arena.core.{BotPlayer, BotProfile, Matchup, RoundRobinScheduler, Tournament, TournamentRunner}
import chess.arena.events.BotFamily
import chess.arena.writer.jsonl.JsonlFileWriter
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path, Paths}
import java.time.Duration

object SearchessAiTournamentDemo:

  def main(args: Array[String]): Unit =
    val enginePath = args.headOption
      .orElse(sys.env.get("STOCKFISH_PATH"))
      .getOrElse {
        throw IllegalArgumentException(
          "Provide Stockfish path as CLI arg 1 or set STOCKFISH_PATH"
        )
      }
    val outputPath =
      if args.length > 1 then Paths.get(args(1))
      else Paths.get("target", "arena", "searchess-ai-tournament", "game-events.jsonl")
    val repetitions = if args.length > 2 then args(2).toInt else 1
    val maxPly      = if args.length > 3 then args(3).toInt else 300

    val aiConfig = AiServiceBotConfig.fromEnv()
    checkAiServiceReachable(aiConfig.baseUrl)

    prepareOutputFile(outputPath)

    val bots: List[BotPlayer] = List(
      RandomBot(BotProfile("random-bot",      BotFamily.Heuristic, "random",        "none", "none")),
      CaptureFirstBot(BotProfile("capture-first",  BotFamily.Heuristic, "capture-first", "none", "none")),
      MaterialGreedyBot(BotProfile("material-greedy", BotFamily.Heuristic, "material-greedy", "none", "none")),
      StockfishBot.depth1(enginePath),
      SearchessAiBot(aiConfig, HttpSearchessAiClient(aiConfig))
    )

    val tid = "searchess-ai-demo"
    val tournament = new Tournament:
      val tournamentId: String          = tid
      val participants: List[BotPlayer] = bots
      val matchups: List[Matchup]       = RoundRobinScheduler.schedule(tid, bots, repetitions)

    println("=== SearchessAI Tournament Demo ===")
    println(s"AI service  : ${aiConfig.baseUrl}")
    println(s"Engine      : $enginePath")
    println(s"Bots        : ${bots.map(_.profile.botId).mkString(", ")}")
    println(s"Matchups    : ${tournament.matchups.length}")
    println(s"Max ply     : $maxPly")
    println(s"Output      : $outputPath")
    println()

    TournamentRunner(JsonlFileWriter(outputPath), maxPlyPerGame = maxPly).run(tournament)

    val bytes = Files.size(outputPath)
    println(s"\nDone. $bytes bytes written to $outputPath")
    println(
      "Next: sbt \"sparkAnalytics/run target/arena/searchess-ai-tournament/game-events.jsonl target/spark-analytics-searchess-ai\""
    )

  private def checkAiServiceReachable(baseUrl: String): Unit =
    val healthUrl = s"$baseUrl/health"
    println(s"Checking AI service health at $healthUrl ...")
    try
      val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build()
      val req = HttpRequest.newBuilder(URI.create(healthUrl))
        .GET()
        .timeout(Duration.ofSeconds(3))
        .build()
      val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
      if resp.statusCode() == 200 then
        println("AI service is reachable. Starting tournament.\n")
      else
        throw IllegalStateException(
          s"AI service responded HTTP ${resp.statusCode()} on $healthUrl. " +
            "Start ai-service or set SEARCHESS_AI_BASE_URL to a reachable instance."
        )
    catch
      case e: java.net.ConnectException =>
        throw IllegalStateException(
          s"Searchess AI service not reachable at $healthUrl. " +
            "Start ai-service (`sbt \"aiService/run\"`) or set SEARCHESS_AI_BASE_URL.",
          e
        )
      case e: IllegalStateException => throw e
      case e: Exception =>
        throw IllegalStateException(
          s"Could not reach AI service at $healthUrl: ${e.getMessage}",
          e
        )

  private def prepareOutputFile(outputPath: Path): Unit =
    Option(outputPath.getParent).foreach(Files.createDirectories(_))
    if Files.isDirectory(outputPath) then
      throw IllegalArgumentException(s"Output path is a directory: $outputPath")
    if Files.exists(outputPath) then
      if Files.isRegularFile(outputPath) then Files.delete(outputPath)
      else throw IllegalArgumentException(s"Output path exists but is not a regular file: $outputPath")
