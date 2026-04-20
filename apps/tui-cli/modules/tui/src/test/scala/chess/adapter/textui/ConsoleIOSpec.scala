package chess.adapter.textui

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ConsoleIOSpec extends AnyFlatSpec with Matchers {
  "ConsoleIO" should "implement Console trait" in {
    ConsoleIO shouldBe a [Console]
  }

  it should "print and printLine without error" in {
    noException should be thrownBy ConsoleIO.print("test")
    noException should be thrownBy ConsoleIO.printLine("test")
  }
}
