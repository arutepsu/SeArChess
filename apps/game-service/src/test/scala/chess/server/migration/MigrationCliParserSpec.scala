package chess.server.migration

import chess.application.migration.MigrationMode
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MigrationCliParserSpec extends AnyFlatSpec with Matchers with EitherValues:

  "MigrationCliParser" should "keep execute behavior unchanged by default" in {
    val command = MigrationCliParser
      .parse(List("--from", "postgres", "--to", "mongo", "--mode", "execute"))
      .value

    command.mode shouldBe MigrationMode.Execute
    command.validateAfterExecute shouldBe false
  }

  it should "accept validate-after-execute for execute mode" in {
    val command = MigrationCliParser
      .parse(
        List(
          "--from",
          "postgres",
          "--to",
          "mongo",
          "--mode",
          "execute",
          "--validate-after-execute"
        )
      )
      .value

    command.validateAfterExecute shouldBe true
  }

  it should "reject validate-after-execute for dry-run mode" in {
    val result = MigrationCliParser.parse(
      List("--from", "postgres", "--to", "mongo", "--mode", "dry-run", "--validate-after-execute")
    )

    result.left.value shouldBe "--validate-after-execute can only be used with --mode execute"
  }

  it should "reject validate-after-execute for validate-only mode" in {
    val result = MigrationCliParser.parse(
      List(
        "--from",
        "postgres",
        "--to",
        "mongo",
        "--mode",
        "validate-only",
        "--validate-after-execute"
      )
    )

    result.left.value shouldBe "--validate-after-execute is invalid with --mode validate-only"
  }
