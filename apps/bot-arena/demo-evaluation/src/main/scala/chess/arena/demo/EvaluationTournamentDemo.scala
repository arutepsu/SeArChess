package chess.arena.demo

import chess.arena.bots.ai.{AiServiceBotConfig, HttpSearchessAiClient, SearchessAiBot}
import chess.arena.bots.heuristic.{CaptureFirstBot, MaterialGreedyBot, RandomBot}
import chess.arena.bots.uci.StockfishBot
import chess.arena.core.{BotPlayer, BotProfile, Matchup, RoundRobinScheduler, Tournament, TournamentRunner}
import chess.arena.events.BotFamily
import chess.arena.writer.jsonl.JsonlFileWriter
import java.io.File
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path, Paths}
import java.time.Duration

object EvaluationTournamentDemo:

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
      else Paths.get("target", "arena", "evaluation-tournament", "game-events.jsonl")
    val repetitions  = if args.length > 2 then args(2).toInt  else 1
    val maxPly       = if args.length > 3 then args(3).toInt  else 200
    val includeSlow  = if args.length > 4 then args(4).toBoolean else false

    val aiConfig = AiServiceBotConfig.fromEnv()

    preflightStockfish(enginePath)
    preflightAiService(aiConfig.baseUrl)

    prepareOutputFile(outputPath)

    val coreBots: List[BotPlayer] = List(
      RandomBot(BotProfile("random-bot",      BotFamily.Heuristic, "random",         "none", "none")),
      CaptureFirstBot(BotProfile("capture-first",  BotFamily.Heuristic, "capture-first", "none", "none")),
      MaterialGreedyBot(BotProfile("material-greedy", BotFamily.Heuristic, "material-greedy", "none", "none")),
      StockfishBot.depth1(enginePath),
      StockfishBot.fast(enginePath),
      SearchessAiBot(aiConfig, HttpSearchessAiClient(aiConfig))
    )
    val bots: List[BotPlayer] =
      if includeSlow then coreBots :+ StockfishBot.slow(enginePath) else coreBots

    val tid = "evaluation-demo"
    val tournament = new Tournament:
      val tournamentId: String          = tid
      val participants: List[BotPlayer] = bots
      val matchups: List[Matchup]       = RoundRobinScheduler.schedule(tid, bots, repetitions)

    println("=== Evaluation Tournament Demo ===")
    println(s"AI service   : ${aiConfig.baseUrl}")
    println(s"Stockfish    : $enginePath")
    println(s"Bots (${bots.length})     : ${bots.map(_.profile.botId).mkString(", ")}")
    println(s"Matchups     : ${tournament.matchups.length}")
    println(s"Repetitions  : $repetitions")
    println(s"Max ply      : $maxPly")
    println(s"Include slow : $includeSlow")
    println(s"Output       : $outputPath")
    println()

    TournamentRunner(JsonlFileWriter(outputPath), maxPlyPerGame = maxPly).run(tournament)

    val bytes = Files.size(outputPath)
    println(s"\nDone. $bytes bytes written to $outputPath")
    println(
      "Next: sbt \"sparkAnalytics/run target/arena/evaluation-tournament/game-events.jsonl target/spark-analytics-evaluation\""
    )
    println("  or: sbt demoEvaluationSparkAnalytics")

  private def preflightStockfish(enginePath: String): Unit =
    val file = new File(enginePath)
    if !file.exists() then
      throw IllegalStateException(
        s"Stockfish binary not found at '$enginePath'. " +
          "Set STOCKFISH_PATH or pass the path as the first argument."
      )
    if !file.canExecute then
      throw IllegalStateException(
        s"Stockfish binary at '$enginePath' is not executable."
      )
    println(s"Stockfish OK : $enginePath")

  private def preflightAiService(baseUrl: String): Unit =
    val healthUrl = s"$baseUrl/health"
    print(s"AI service   : checking $healthUrl ... ")
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
        println("OK")
      else
        throw IllegalStateException(
          s"AI service responded HTTP ${resp.statusCode()} on $healthUrl. " +
            "Ensure ai-service is running or set SEARCHESS_AI_BASE_URL."
        )
    catch
      case e: java.net.ConnectException =>
        throw IllegalStateException(
          s"Searchess AI service not reachable at $healthUrl.\n" +
            "  Start: sbt \"aiService/run\"\n" +
            "  Or set SEARCHESS_AI_BASE_URL to a reachable instance.",
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
      else throw IllegalArgumentException(s"Output path exists and is not a regular file: $outputPath")
