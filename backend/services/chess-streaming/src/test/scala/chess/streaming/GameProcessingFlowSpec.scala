package chess.streaming

import chess.streaming.DslCommand.*
import chess.streaming.GameProcessingResult.*
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Await
import scala.concurrent.duration.*

final class GameProcessingFlowSpec extends AnyFlatSpec with Matchers with OptionValues:

  "ProcessGameFlow" should "maintain state and count accepted moves" in {
    val results = process(List(
      SessionStartedCommand(1, "demo"),
      PlayersCommand(2, "Alice", "Bob"),
      MoveCommand(3, "Alice", "e2e4"),
      MoveCommand(4, "Bob", "e7e5"),
      StatusCommand(5)
    ))

    results.collect { case move: MoveAccepted => move.acceptedMoves } shouldBe Seq(1, 2)
    val snapshot = results.collectFirst { case status: StatusSnapshot => status }.value
    snapshot.acceptedMoves shouldBe 2
    snapshot.rejectedMoves shouldBe 0
  }

  it should "count rejected moves from game processing" in {
    val results = process(List(
      SessionStartedCommand(1, "demo"),
      PlayersCommand(2, "Alice", "Bob"),
      MoveCommand(3, "Alice", "e2e5"),
      StatusCommand(4)
    ))

    results.collect { case rejected: MoveRejected => rejected }.size shouldBe 1
    val snapshot = results.collectFirst { case status: StatusSnapshot => status }.value
    snapshot.acceptedMoves shouldBe 0
    snapshot.rejectedMoves shouldBe 1
  }

  it should "emit snapshot for status" in {
    val results = process(List(SessionStartedCommand(1, "demo"), PlayersCommand(2, "Alice", "Bob"), StatusCommand(3)))
    results.collect { case status: StatusSnapshot => status }.size shouldBe 1
  }

  it should "end the game on resign" in {
    val results = process(List(
      SessionStartedCommand(1, "demo"),
      PlayersCommand(2, "Alice", "Bob"),
      ResignCommand(3, "Bob"),
      StatusCommand(4)
    ))

    results.collect { case resigned: GameResigned => resigned }.size shouldBe 1
    results.collect { case finished: GameFinished => finished }.size shouldBe 1
    results.collectFirst { case status: StatusSnapshot => status }.value.finished shouldBe true
  }

  private def process(commands: List[DslCommand]): Seq[GameProcessingResult] =
    implicit val system: ActorSystem = ActorSystem("GameProcessingFlowSpec")
    try {
      Await.result(
        Source(commands.map(ValidatedCommand.apply))
          .via(SearchessReactiveStreams.processGameFlow)
          .runWith(Sink.seq),
        5.seconds
      )
    } finally {
      Await.result(system.terminate(), 5.seconds)
    }
