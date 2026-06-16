package chess.arena.bots.heuristic

import chess.arena.core.{BotProfile, GameRunner}
import chess.arena.events.{BotFamily, GameEventJson, GameFinished, GameStarted, MovePlayed}
import chess.arena.writer.jsonl.JsonlFileWriter
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.Files
import scala.io.Source
import scala.util.Using

class HeuristicGameIntegrationSpec extends AnyFlatSpec with Matchers:

  private def mkProfile(id: String) =
    BotProfile(id, BotFamily.Heuristic, "random", "none", "none")

  "GameRunner with RandomBot vs RandomBot" should "write valid JSONL events to a file" in {
    val path = Files.createTempFile("arena-integration-", ".jsonl")
    try
      val white  = RandomBot(mkProfile("white-random"), seed = Some(1L))
      val black  = RandomBot(mkProfile("black-random"), seed = Some(2L))
      val writer = JsonlFileWriter(path)
      val runner = GameRunner(white, black, writer, "integration-test", maxPly = 200)
      runner.run("integration-game-1")

      val lines = Using(Source.fromFile(path.toFile))(_.getLines().filter(_.nonEmpty).toList)
        .fold(e => fail(s"Could not read JSONL: $e"), identity)

      lines should not be empty

      val events = lines.map(line => GameEventJson.decode(line).fold(e => fail(s"Decode error: $e"), identity))

      events.head shouldBe a[GameStarted]
      events.last shouldBe a[GameFinished]
      events.drop(1).dropRight(1).foreach { event =>
        event shouldBe a[MovePlayed]
      }

      events.last match
        case gf: GameFinished =>
          val validReasons = Set("checkmate", "stalemate", "50-move-rule", "max-ply")
          validReasons should contain(gf.terminationReason)
          gf.totalPly shouldBe events.drop(1).dropRight(1).length
        case other => fail(s"Expected GameFinished, got $other")
    finally
      Files.deleteIfExists(path)
      ()
  }
