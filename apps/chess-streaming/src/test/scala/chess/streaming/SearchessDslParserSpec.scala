package chess.streaming

import chess.streaming.DslCommand.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

final class SearchessDslParserSpec extends AnyFlatSpec with Matchers:

  "SearchessDslParser" should "parse session commands" in {
    SearchessDslParser.parseLine("session demo-1", 1) shouldBe
      Some(Right(SessionStartedCommand(1, "demo-1")))
  }

  it should "parse players commands" in {
    SearchessDslParser.parseLine("players Alice Bob", 2) shouldBe
      Some(Right(PlayersCommand(2, "Alice", "Bob")))
  }

  it should "parse move commands" in {
    SearchessDslParser.parseLine("move Alice e2e4", 3) shouldBe
      Some(Right(MoveCommand(3, "Alice", "e2e4")))
  }

  it should "parse status commands" in {
    SearchessDslParser.parseLine("status", 4) shouldBe
      Some(Right(StatusCommand(4)))
  }

  it should "parse resign commands" in {
    SearchessDslParser.parseLine("resign Bob", 5) shouldBe
      Some(Right(ResignCommand(5, "Bob")))
  }

  it should "ignore comments and empty lines" in {
    SearchessDslParser.parseLine("# comment", 6) shouldBe None
    SearchessDslParser.parseLine("   ", 7) shouldBe None
  }

  it should "return line-numbered parse errors for unknown commands" in {
    SearchessDslParser.parseLine("unknown abc", 8) shouldBe
      Some(Left(DslParseError(8, "unknown abc", "unknown command: unknown")))
  }
