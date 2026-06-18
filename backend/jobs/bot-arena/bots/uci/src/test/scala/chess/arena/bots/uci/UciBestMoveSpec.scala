package chess.arena.bots.uci

import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class UciBestMoveSpec extends AnyFlatSpec with Matchers with EitherValues:

  "UciBestMove.parseLine" should "parse normal bestmove lines" in {
    UciBestMove.parseLine("bestmove e2e4").value shouldBe UciBestMove("e2e4")
  }

  it should "parse bestmove lines that include ponder output" in {
    UciBestMove.parseLine("bestmove g1f3 ponder g8f6").value shouldBe UciBestMove("g1f3")
  }

  it should "handle promotion moves" in {
    UciBestMove.parseLine("bestmove e7e8q").value shouldBe UciBestMove("e7e8q")
  }

  it should "reject malformed bestmove output" in {
    UciBestMove.parseLine("info depth 1 score cp 20") shouldBe a[Left[?, ?]]
    UciBestMove.parseLine("bestmove e9e4") shouldBe a[Left[?, ?]]
    UciBestMove.parseLine("bestmove e2e4k") shouldBe a[Left[?, ?]]
  }
