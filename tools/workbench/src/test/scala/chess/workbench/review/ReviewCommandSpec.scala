package chess.workbench.review

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ReviewCommandSpec extends AnyFlatSpec with Matchers:

  "ReviewCommand.execute" should "execute without failure and print a formatted report" in {
    val printed = Vector.newBuilder[String]

    val report = ReviewCommand.execute(
      args = List("review", "tests"),
      printLine = line => printed += line
    )

    report.findings should not be empty
    printed.result().mkString("\n") should include("AI Review")
  }

  "ReviewCommand.contextFrom" should "map review to the project context" in {
    val context = ReviewCommand.contextFrom(List("review"))

    context.moduleName shouldBe "project"
    context.userQuestion shouldBe "Review the project."
    context.notes should not be empty
  }

  it should "map review game to the game context" in {
    val context = ReviewCommand.contextFrom(List("review", "game"))

    context.moduleName shouldBe "game"
    context.userQuestion shouldBe "Review game."
    context.notes should not be empty
  }

  it should "map review tests to the tests context" in {
    val context = ReviewCommand.contextFrom(List("review", "tests"))

    context.moduleName shouldBe "tests"
    context.userQuestion shouldBe "Review tests."
    context.notes should not be empty
  }
