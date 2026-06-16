package chess.arena.demo

import chess.arena.bots.heuristic.{CaptureFirstBot, MaterialGreedyBot, RandomBot}
import chess.arena.core.{BotPlayer, BotProfile, Matchup, RoundRobinScheduler, Tournament, TournamentRunner}
import chess.arena.events.BotFamily
import chess.arena.writer.jsonl.JsonlFileWriter
import java.nio.file.{Files, Paths}

object HeuristicTournamentDemo:

  def main(args: Array[String]): Unit =
    val outputPath  =
      if args.length > 0 then Paths.get(args(0))
      else Paths.get("target", "arena", "heuristic-tournament", "game-events.jsonl")
    val repetitions = if args.length > 1 then args(1).toInt else 1
    val maxPly      = if args.length > 2 then args(2).toInt else 500

    Files.createDirectories(outputPath.getParent)
    Files.deleteIfExists(outputPath)

    val bots: List[BotPlayer] = List(
      RandomBot(BotProfile("random-bot",        BotFamily.Heuristic, "random",          "none", "none")),
      CaptureFirstBot(BotProfile("capture-first",   BotFamily.Heuristic, "capture-first",   "none", "none")),
      MaterialGreedyBot(BotProfile("material-greedy", BotFamily.Heuristic, "material-greedy", "none", "none"))
    )

    val tid = "heuristic-demo"
    val tournament = new Tournament:
      val tournamentId: String          = tid
      val participants: List[BotPlayer] = bots
      val matchups: List[Matchup]       = RoundRobinScheduler.schedule(tid, bots, repetitions)

    println("=== Heuristic Tournament Demo ===")
    println(s"Bots    : ${bots.map(_.profile.botId).mkString(", ")}")
    println(s"Matchups: ${tournament.matchups.length}")
    println(s"Max ply : $maxPly")
    println(s"Output  : $outputPath")
    println()

    TournamentRunner(JsonlFileWriter(outputPath), maxPlyPerGame = maxPly).run(tournament)

    val bytes = Files.size(outputPath)
    println(s"\nDone. $bytes bytes written to $outputPath")
    println(s"Next   : sbt demoSparkAnalytics")
