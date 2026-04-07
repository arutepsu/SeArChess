package chess.adapter.textui

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.ByteArrayOutputStream

class ConsoleIOSpec extends AnyFlatSpec with Matchers:

  private def captureStdOut(block: => Unit): String =
    val out = new ByteArrayOutputStream()
    Console.withOut(out) {
      block
    }
    out.toString

  "ConsoleIO.print" should "write text without newline" in {
    val result = captureStdOut {
      ConsoleIO.print("hello")
    }

    result shouldBe "hello"
  }

  "ConsoleIO.readLine" should "delegate to input provider" in {
    ConsoleIO.in = () => "test-input"

    val result = ConsoleIO.readLine()

    result shouldBe "test-input"
    }

  "ConsoleIO.printLine" should "write text with newline" in {
    val result = captureStdOut {
      ConsoleIO.printLine("hello")
    }

    result shouldBe s"hello${System.lineSeparator()}"
  }

  it should "handle empty strings" in {
    val result = captureStdOut {
      ConsoleIO.printLine("")
    }

    result shouldBe System.lineSeparator()
  }

