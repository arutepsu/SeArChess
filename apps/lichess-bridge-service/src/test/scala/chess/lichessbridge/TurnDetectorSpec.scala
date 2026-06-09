package chess.lichessbridge

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TurnDetectorSpec extends AnyFlatSpec with Matchers:

  // ── isBotTurn ─────────────────────────────────────────────────────────────────

  "TurnDetector.isBotTurn" should "return true for white when move count is 0 (first move)" in {
    TurnDetector.isBotTurn("white", 0, "started") shouldBe true
  }

  it should "return false for white when move count is 1 (black to move)" in {
    TurnDetector.isBotTurn("white", 1, "started") shouldBe false
  }

  it should "return true for black when move count is 1" in {
    TurnDetector.isBotTurn("black", 1, "started") shouldBe true
  }

  it should "return false for black when move count is 0" in {
    TurnDetector.isBotTurn("black", 0, "started") shouldBe false
  }

  it should "return true for white when move count is even and non-zero" in {
    TurnDetector.isBotTurn("white", 4, "started") shouldBe true
  }

  it should "return false for any terminal status regardless of side and move count" in {
    TurnDetector.isBotTurn("white", 0, "mate")      shouldBe false
    TurnDetector.isBotTurn("black", 1, "resign")    shouldBe false
    TurnDetector.isBotTurn("white", 2, "stalemate") shouldBe false
    TurnDetector.isBotTurn("black", 3, "draw")      shouldBe false
    TurnDetector.isBotTurn("white", 0, "outoftime") shouldBe false
    TurnDetector.isBotTurn("white", 0, "aborted")   shouldBe false
  }

  it should "return false for an unknown side string" in {
    TurnDetector.isBotTurn("spectator", 0, "started") shouldBe false
  }

  it should "be case-insensitive for the botSide argument" in {
    TurnDetector.isBotTurn("WHITE", 0, "started") shouldBe true
    TurnDetector.isBotTurn("Black", 1, "started") shouldBe true
  }

  // ── countMoves ───────────────────────────────────────────────────────────────

  "TurnDetector.countMoves" should "return 0 for an empty string" in {
    TurnDetector.countMoves("") shouldBe 0
  }

  it should "return 0 for a blank string" in {
    TurnDetector.countMoves("   ") shouldBe 0
  }

  it should "return 1 for a single move" in {
    TurnDetector.countMoves("e2e4") shouldBe 1
  }

  it should "return 2 for two moves" in {
    TurnDetector.countMoves("e2e4 e7e5") shouldBe 2
  }

  it should "return 5 for five moves" in {
    TurnDetector.countMoves("e2e4 e7e5 d2d4 d7d5 e4d5") shouldBe 5
  }

  // ── determineBotSide ─────────────────────────────────────────────────────────

  "TurnDetector.determineBotSide" should "return Some(white) when bot is the white player" in {
    TurnDetector.determineBotSide("testbot", "testbot", "human") shouldBe Some("white")
  }

  it should "return Some(black) when bot is the black player" in {
    TurnDetector.determineBotSide("testbot", "human", "testbot") shouldBe Some("black")
  }

  it should "return None when bot is not in the game" in {
    TurnDetector.determineBotSide("testbot", "alice", "bob") shouldBe None
  }

  it should "be case-insensitive when matching bot username" in {
    TurnDetector.determineBotSide("TestBot", "testbot", "human") shouldBe Some("white")
    TurnDetector.determineBotSide("TestBot", "human", "TESTBOT") shouldBe Some("black")
  }
