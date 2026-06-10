package chess.streaming

import chess.streaming.DslCommand.*
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Await
import scala.concurrent.duration.*

final class CommandValidationFlowSpec extends AnyFlatSpec with Matchers with OptionValues:

  "ValidateCommandFlow" should "reject move before session" in {
    validate(List(MoveCommand(1, "Alice", "e2e4"))).head.left.toOption.value.message should include("session")
  }

  it should "reject players before session" in {
    validate(List(PlayersCommand(1, "Alice", "Bob"))).head.left.toOption.value.message should include("session")
  }

  it should "reject move before players" in {
    val result = validate(List(SessionStartedCommand(1, "demo"), MoveCommand(2, "Alice", "e2e4")))
    result(1).left.toOption.value.message should include("registered players")
  }

  it should "reject move by unknown player" in {
    val result = validate(List(
      SessionStartedCommand(1, "demo"),
      PlayersCommand(2, "Alice", "Bob"),
      MoveCommand(3, "Mallory", "e2e4")
    ))
    result(2).left.toOption.value.message should include("unknown player")
  }

  it should "reject invalid move syntax" in {
    val result = validate(List(
      SessionStartedCommand(1, "demo"),
      PlayersCommand(2, "Alice", "Bob"),
      MoveCommand(3, "Alice", "not-uci")
    ))
    result(2).left.toOption.value.message should include("UCI")
  }

  it should "accept valid setup and move" in {
    val result = validate(List(
      SessionStartedCommand(1, "demo"),
      PlayersCommand(2, "Alice", "Bob"),
      MoveCommand(3, "Alice", "e2e4")
    ))

    result.collect { case Right(validated) => validated.command } shouldBe Seq(
      SessionStartedCommand(1, "demo"),
      PlayersCommand(2, "Alice", "Bob"),
      MoveCommand(3, "Alice", "e2e4")
    )
  }

  it should "allow status after session creation" in {
    val result = validate(List(SessionStartedCommand(1, "demo"), StatusCommand(2)))
    result(1) shouldBe Right(ValidatedCommand(StatusCommand(2)))
  }

  it should "reject resign by unknown player" in {
    val result = validate(List(
      SessionStartedCommand(1, "demo"),
      PlayersCommand(2, "Alice", "Bob"),
      ResignCommand(3, "Mallory")
    ))
    result(2).left.toOption.value.message should include("unknown player")
  }

  private def validate(commands: List[DslCommand]): Seq[Either[ValidationError, ValidatedCommand]] =
    implicit val system: ActorSystem = ActorSystem("CommandValidationFlowSpec")
    try {
      Await.result(
        Source(commands).via(SearchessReactiveStreams.validateCommandFlow).runWith(Sink.seq),
        5.seconds
      )
    } finally {
      Await.result(system.terminate(), 5.seconds)
    }
