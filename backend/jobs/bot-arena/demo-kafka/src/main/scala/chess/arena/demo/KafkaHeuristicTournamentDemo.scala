package chess.arena.demo

import chess.arena.bots.heuristic.{CaptureFirstBot, MaterialGreedyBot, RandomBot}
import chess.arena.core.{BotPlayer, BotProfile, Matchup, RoundRobinScheduler, Tournament, TournamentRunner}
import chess.arena.events.{BotFamily, EventEmitter}
import chess.arena.writer.jsonl.JsonlFileWriter
import chess.arena.writer.kafka.{CompositeEventEmitter, KafkaEventEmitter, KafkaEventEmitterConfig}

import java.nio.file.{Files, Path, Paths}
import scala.util.Using

object KafkaHeuristicTournamentDemo:

  def main(args: Array[String]): Unit =
    val bootstrapServers = argOrEnv(args, 0, "KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
    val topic            = argOrEnv(args, 1, "KAFKA_GAME_EVENTS_TOPIC", "game-events")
    val jsonlPath        = if args.length > 2 && args(2).nonEmpty then Some(Paths.get(args(2))) else None
    val repetitions      = if args.length > 3 then args(3).toInt else 1
    val maxPly           = if args.length > 4 then args(4).toInt else 100

    jsonlPath.foreach(prepareOutputFile)

    val bots: List[BotPlayer] = List(
      RandomBot(BotProfile("random-bot", BotFamily.Heuristic, "random", "none", "none")),
      CaptureFirstBot(BotProfile("capture-first", BotFamily.Heuristic, "capture-first", "none", "none")),
      MaterialGreedyBot(BotProfile("material-greedy", BotFamily.Heuristic, "material-greedy", "none", "none"))
    )

    val tid = "kafka-heuristic-demo"
    val tournament = new Tournament:
      val tournamentId: String          = tid
      val participants: List[BotPlayer] = bots
      val matchups: List[Matchup]       = RoundRobinScheduler.schedule(tid, bots, repetitions)

    val config = KafkaEventEmitterConfig(
      bootstrapServers = bootstrapServers,
      topic = topic,
      clientId = "searchess-arena-kafka-demo"
    )

    Using.resource(KafkaEventEmitter(config)) { kafka =>
      val emitter: EventEmitter = jsonlPath match
        case Some(path) => CompositeEventEmitter(List(JsonlFileWriter(path), kafka))
        case None       => kafka

      println("=== Kafka Heuristic Tournament Demo ===")
      println(s"Bootstrap: $bootstrapServers")
      println(s"Topic    : $topic")
      println(s"Bots     : ${bots.map(_.profile.botId).mkString(", ")}")
      println(s"Matchups : ${tournament.matchups.length}")
      println(s"Max ply  : $maxPly")
      jsonlPath.foreach(path => println(s"JSONL    : $path"))
      println()

      TournamentRunner(emitter, maxPlyPerGame = maxPly).run(tournament)
      kafka.flush()
    }

    println("\nDone. Events published to Kafka.")

  private def argOrEnv(args: Array[String], index: Int, envName: String, defaultValue: String): String =
    args.lift(index).filter(_.nonEmpty)
      .orElse(sys.env.get(envName).filter(_.nonEmpty))
      .getOrElse(defaultValue)

  private def prepareOutputFile(outputPath: Path): Unit =
    Option(outputPath.getParent).foreach(Files.createDirectories(_))

    if Files.isDirectory(outputPath) then
      throw IllegalArgumentException(s"JSONL output path is a directory: $outputPath")

    if Files.exists(outputPath) then
      if Files.isRegularFile(outputPath) then Files.delete(outputPath)
      else throw IllegalArgumentException(s"JSONL output path exists but is not a regular file: $outputPath")
