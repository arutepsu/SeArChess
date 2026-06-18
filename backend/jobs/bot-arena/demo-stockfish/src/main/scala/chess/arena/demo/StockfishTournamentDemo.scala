package chess.arena.demo

import chess.arena.bots.heuristic.{CaptureFirstBot, MaterialGreedyBot, RandomBot}
import chess.arena.bots.uci.StockfishBot
import chess.arena.core.{BotPlayer, BotProfile, Matchup, RoundRobinScheduler, Tournament, TournamentRunner}
import chess.arena.events.BotFamily
import chess.arena.writer.jsonl.JsonlFileWriter
import java.nio.file.{Files, Path, Paths}

object StockfishTournamentDemo:

  def main(args: Array[String]): Unit =
    val enginePath = args.headOption
      .orElse(sys.env.get("STOCKFISH_PATH"))
      .getOrElse {
        throw IllegalArgumentException(
          "Provide Stockfish with CLI arg 1 or STOCKFISH_PATH"
        )
      }
    val outputPath =
      if args.length > 1 then Paths.get(args(1))
      else Paths.get("target", "arena", "stockfish-tournament", "game-events.jsonl")
    val repetitions = if args.length > 2 then args(2).toInt else 1
    val maxPly      = if args.length > 3 then args(3).toInt else 200

    prepareOutputFile(outputPath)

    val bots: List[BotPlayer] = List(
      RandomBot(BotProfile("random-bot", BotFamily.Heuristic, "random", "none", "none")),
      CaptureFirstBot(BotProfile("capture-first", BotFamily.Heuristic, "capture-first", "none", "none")),
      MaterialGreedyBot(BotProfile("material-greedy", BotFamily.Heuristic, "material-greedy", "none", "none")),
      StockfishBot.depth1(enginePath),
      StockfishBot.depth3(enginePath),
      StockfishBot.fast(enginePath),
      StockfishBot.slow(enginePath)
    )

    val tid = "stockfish-demo"
    val tournament = new Tournament:
      val tournamentId: String          = tid
      val participants: List[BotPlayer] = bots
      val matchups: List[Matchup]       = RoundRobinScheduler.schedule(tid, bots, repetitions)

    println("=== Stockfish Tournament Demo ===")
    println(s"Engine  : $enginePath")
    println(s"Bots    : ${bots.map(_.profile.botId).mkString(", ")}")
    println(s"Matchups: ${tournament.matchups.length}")
    println(s"Max ply : $maxPly")
    println(s"Output  : $outputPath")
    println()

    TournamentRunner(JsonlFileWriter(outputPath), maxPlyPerGame = maxPly).run(tournament)

    val bytes = Files.size(outputPath)
    println(s"\nDone. $bytes bytes written to $outputPath")
    println(
      "Next   : sbt \"sparkAnalytics/run target/arena/stockfish-tournament/game-events.jsonl target/spark-analytics-stockfish\""
    )

  private def prepareOutputFile(outputPath: Path): Unit =
    Option(outputPath.getParent).foreach(Files.createDirectories(_))

    if Files.isDirectory(outputPath) then
      throw IllegalArgumentException(s"Output path is a directory: $outputPath")

    if Files.exists(outputPath) then
      if Files.isRegularFile(outputPath) then Files.delete(outputPath)
      else throw IllegalArgumentException(s"Output path exists but is not a regular file: $outputPath")
