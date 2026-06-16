package chess.arena.bots.heuristic

import chess.arena.core.{BotProfile, RoundRobinScheduler, Tournament, TournamentRunner}
import chess.arena.core.BotPlayer
import chess.arena.core.Matchup
import chess.arena.events.{BotFamily, GameEventJson, GameFinished, GameStarted, MovePlayed}
import chess.arena.writer.jsonl.JsonlFileWriter
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.Files
import scala.io.Source
import scala.util.Using

class TournamentIntegrationSpec extends AnyFlatSpec with Matchers:

  private def mkProfile(id: String, strategy: String): BotProfile =
    BotProfile(id, BotFamily.Heuristic, strategy, "none", "none")

  "TournamentRunner with heuristic bots" should "write a valid multi-game JSONL file" in {
    val path = Files.createTempFile("arena-tournament-", ".jsonl")
    try
      val bots: List[BotPlayer] = List(
        RandomBot(mkProfile("random-w", "random"),          seed = Some(10L)),
        CaptureFirstBot(mkProfile("capture-b", "capture-first"), seed = Some(20L)),
        MaterialGreedyBot(mkProfile("greedy-c", "material-greedy"), seed = Some(30L))
      )
      val tournament = new Tournament:
        val tournamentId: String          = "heuristic-3bot"
        val participants: List[BotPlayer] = bots
        val matchups: List[Matchup]       = RoundRobinScheduler.schedule(tournamentId, participants)

      TournamentRunner(JsonlFileWriter(path), maxPlyPerGame = 50).run(tournament)

      val lines = Using(Source.fromFile(path.toFile))(_.getLines().filter(_.nonEmpty).toList)
        .fold(e => fail(s"Could not read JSONL: $e"), identity)

      val events = lines.map(line => GameEventJson.decode(line).fold(e => fail(s"Decode error '$e' on: $line"), identity))

      val starts   = events.collect { case gs: GameStarted  => gs }
      val finishes = events.collect { case gf: GameFinished => gf }

      // 3 bots × (3-1) × 1 rep = 6 games
      starts.length   shouldBe 6
      finishes.length shouldBe 6

      // All events carry the same tournamentId
      events.foreach { event => event.tournamentId shouldBe "heuristic-3bot" }

      // Each game has a unique gameId
      starts.map(_.gameId).distinct.length shouldBe 6

      // Each game's events are contiguous: GameStarted ... MovePlayed* ... GameFinished
      val gameIds = starts.map(_.gameId)
      gameIds.foreach { gid =>
        val gameEvents = events.filter(_.gameId == gid)
        gameEvents.head shouldBe a[GameStarted]
        gameEvents.last shouldBe a[GameFinished]
        gameEvents.drop(1).dropRight(1).foreach { e => e shouldBe a[MovePlayed] }
      }

      // GameFinished.totalPly matches the MovePlayed count for each game
      gameIds.foreach { gid =>
        val played = events.collect { case mp: MovePlayed if mp.gameId == gid => mp }
        events.collectFirst { case gf: GameFinished if gf.gameId == gid => gf } match
          case Some(gf) => gf.totalPly shouldBe played.length
          case None     => fail(s"Missing GameFinished for game $gid")
      }

      // All termination reasons are valid
      val validReasons = Set("checkmate", "stalemate", "50-move-rule", "max-ply", "illegal-move")
      finishes.foreach { gf => validReasons should contain(gf.terminationReason) }

    finally
      Files.deleteIfExists(path)
      ()
  }
