package chess.tournamentservice

import chess.arena.bots.heuristic.{MaterialGreedyBot, RandomBot}
import chess.arena.core.BotProfile
import chess.arena.events.BotFamily
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PublicBotMoveGeneratorSpec extends AnyFlatSpec with Matchers:

  private val StartFen   = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
  private val AfterE4Fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"

  private def profile(id: String) =
    BotProfile(id, BotFamily.Heuristic, "random", "none", "none")

  private val UciPattern = "[a-h][1-8][a-h][1-8][qrbn]?".r

  private def assertValidUci(result: Either[MoveGenerationError, String]): Unit =
    result match
      case Left(err) => fail(s"Expected a UCI move but got error: ${err.describe}")
      case Right(uci) =>
        withClue(s"UCI '$uci' is malformed") {
          UciPattern.matches(uci) shouldBe true
        }

  // ── RandomBot ────────────────────────────────────────────────────────────────

  "PublicBotMoveGenerator with RandomBot" should "return a valid UCI move from the start position" in {
    val bot      = RandomBot(profile("random-bot"), seed = Some(42L))
    val snapshot = PublicGameSnapshot(fen = StartFen, status = "ongoing")
    assertValidUci(PublicBotMoveGenerator.generate(bot, snapshot))
  }

  it should "return a valid UCI move from a mid-game FEN (black to move)" in {
    val bot      = RandomBot(profile("random-bot"), seed = Some(7L))
    val snapshot = PublicGameSnapshot(fen = AfterE4Fen, status = "ongoing")
    assertValidUci(PublicBotMoveGenerator.generate(bot, snapshot))
  }

  it should "return GameNotOngoing for status 'finished'" in {
    val bot      = RandomBot(profile("random-bot"), seed = Some(0L))
    val snapshot = PublicGameSnapshot(fen = StartFen, status = "finished")
    PublicBotMoveGenerator.generate(bot, snapshot) shouldBe
      Left(MoveGenerationError.GameNotOngoing("finished"))
  }

  it should "return GameNotOngoing for status 'aborted'" in {
    val bot      = RandomBot(profile("random-bot"), seed = Some(0L))
    val snapshot = PublicGameSnapshot(fen = StartFen, status = "aborted")
    PublicBotMoveGenerator.generate(bot, snapshot) shouldBe
      Left(MoveGenerationError.GameNotOngoing("aborted"))
  }

  it should "return InvalidFen for a garbage FEN string" in {
    val bot      = RandomBot(profile("random-bot"), seed = Some(0L))
    val snapshot = PublicGameSnapshot(fen = "not-a-fen", status = "ongoing")
    PublicBotMoveGenerator.generate(bot, snapshot) match
      case Left(_: MoveGenerationError.InvalidFen) => succeed
      case other => fail(s"Expected InvalidFen, got: $other")
  }

  it should "return InvalidFen for an empty FEN string" in {
    val bot      = RandomBot(profile("random-bot"), seed = Some(0L))
    val snapshot = PublicGameSnapshot(fen = "", status = "ongoing")
    PublicBotMoveGenerator.generate(bot, snapshot) match
      case Left(_: MoveGenerationError.InvalidFen) => succeed
      case other => fail(s"Expected InvalidFen, got: $other")
  }

  // ── MaterialGreedyBot ────────────────────────────────────────────────────────

  "PublicBotMoveGenerator with MaterialGreedyBot" should "return a valid UCI move from the start position" in {
    val materialProfile = BotProfile("material-greedy", BotFamily.Heuristic, "material-greedy", "none", "none")
    val bot      = MaterialGreedyBot(materialProfile, seed = Some(1L))
    val snapshot = PublicGameSnapshot(fen = StartFen, status = "ongoing")
    assertValidUci(PublicBotMoveGenerator.generate(bot, snapshot))
  }
