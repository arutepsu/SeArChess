package chess.arena.bots.uci

import chess.arena.bots.heuristic.RandomBot
import chess.arena.core.{BotProfile, GameRunner, MoveUci}
import chess.arena.events.{BotFamily, GameEventJson, GameFinished, GameStarted, MovePlayed}
import chess.arena.writer.jsonl.JsonlFileWriter
import chess.domain.rules.GameStateRules
import chess.domain.state.GameStateFactory
import java.nio.file.{Files, Path, Paths}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.io.Source
import scala.util.Using

class StockfishIntegrationSpec extends AnyFlatSpec with Matchers:

  "StockfishDepth1" should "select a legal move from the initial position" in withStockfish { path =>
    val state = GameStateFactory.initial()
    val move  = StockfishBot.depth1(path.toString).selectMove(state)
    GameStateRules.legalMoves(state).map(MoveUci.encode) should contain(MoveUci.encode(move))
  }

  "StockfishDepth3" should "select a legal move from the initial position" in withStockfish { path =>
    val state = GameStateFactory.initial()
    val move  = StockfishBot.depth3(path.toString).selectMove(state)
    GameStateRules.legalMoves(state).map(MoveUci.encode) should contain(MoveUci.encode(move))
  }

  "GameRunner with RandomBot vs StockfishDepth1" should "write GameStarted, MovePlayed, and GameFinished JSONL events" in withStockfish {
    path =>
      val output = Files.createTempFile("arena-stockfish-", ".jsonl")
      try
        val white = RandomBot(
          BotProfile("random-bot", BotFamily.Heuristic, "random", "none", "none"),
          seed = Some(1L)
        )
        val black = StockfishBot.depth1(path.toString)

        GameRunner(white, black, JsonlFileWriter(output), "stockfish-integration", maxPly = 20)
          .run("stockfish-integration-game-1")

        val lines = readLines(output)
        lines should not be empty

        val events = lines.map(line => GameEventJson.decode(line).fold(e => fail(e), identity))
        events.head shouldBe a[GameStarted]
        events.collect { case _: MovePlayed => () } should not be empty
        events.last shouldBe a[GameFinished]
      finally
        Files.deleteIfExists(output)
        ()
  }

  private def withStockfish(test: Path => Unit): Unit =
    stockfishPath match
      case Some(path) => test(path)
      case None =>
        info("STOCKFISH_PATH is not set to an executable file; skipping optional Stockfish integration test")
        pending

  private def stockfishPath: Option[Path] =
    sys.env.get("STOCKFISH_PATH").map(Paths.get(_)).filter(path =>
      Files.isRegularFile(path) && Files.isExecutable(path)
    )

  private def readLines(path: Path): List[String] =
    Using(Source.fromFile(path.toFile))(_.getLines().filter(_.nonEmpty).toList)
      .fold(e => fail(s"Could not read JSONL: $e"), identity)
