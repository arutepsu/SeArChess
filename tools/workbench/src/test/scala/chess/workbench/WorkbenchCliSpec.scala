package chess.workbench

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class WorkbenchCliSpec extends AnyFlatSpec with Matchers:

  "WorkbenchCli.dispatch" should "route review to the project review command" in {
    val printed = Vector.newBuilder[String]

    val code = WorkbenchCli.dispatch(
      args = List("review"),
      printLine = line => printed += line,
      printError = _ => ()
    )

    code shouldBe 0
    val output = printed.result().mkString("\n")
    output should include("AI Review")
    output should include("project")
  }

  it should "route review game to the game review command" in {
    val printed = Vector.newBuilder[String]

    val code = WorkbenchCli.dispatch(
      args = List("review", "game"),
      printLine = line => printed += line,
      printError = _ => ()
    )

    code shouldBe 0
    val output = printed.result().mkString("\n")
    output should include("AI Review")
    output should include("game")
  }

  it should "route review tests to the tests review command" in {
    val printed = Vector.newBuilder[String]

    val code = WorkbenchCli.dispatch(
      args = List("review", "tests"),
      printLine = line => printed += line,
      printError = _ => ()
    )

    code shouldBe 0
    val output = printed.result().mkString("\n")
    output should include("AI Review")
    output should include("tests")
  }

  it should "delegate non-review commands to the fallback" in {
    val printed = Vector.newBuilder[String]
    val errors = Vector.newBuilder[String]

    val code = WorkbenchCli.dispatch(
      args = List("status"),
      printLine = line => printed += line,
      printError = line => errors += line,
      fallback = (args, out, _) =>
        out(s"fallback handled ${args.mkString(" ")}")
        7
    )

    code shouldBe 7
    printed.result() should contain("fallback handled status")
    errors.result() shouldBe empty
  }

  it should "keep unknown command behavior consistent" in {
    val printed = Vector.newBuilder[String]
    val errors = Vector.newBuilder[String]

    val code = WorkbenchCli.dispatch(
      args = List("unknown"),
      printLine = line => printed += line,
      printError = line => errors += line
    )

    code shouldBe 1
    errors.result().mkString("\n") should include("Unknown workbench command: unknown")
    printed.result().mkString("\n") should include("Usage: workbench")
  }
