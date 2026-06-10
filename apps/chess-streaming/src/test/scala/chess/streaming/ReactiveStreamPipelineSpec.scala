package chess.streaming

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Await
import scala.concurrent.duration.*

final class ReactiveStreamPipelineSpec extends AnyFlatSpec with Matchers:

  "Reactive stream pipeline" should "process a valid DSL file shape successfully" in {
    val summary = runSummary(List(
      "session demo-game-1",
      "players Alice Bob",
      "move Alice e2e4",
      "move Bob e7e5",
      "status",
      "resign Bob"
    ))

    summary.parsedCommands shouldBe 6
    summary.acceptedMoves shouldBe 2
    summary.rejectedMoves shouldBe 0
    summary.validationFailures shouldBe 0
    summary.parseFailures shouldBe 0
    summary.finishedGames shouldBe 1
  }

  it should "report malformed lines and invalid command order" in {
    val summary = runSummary(List(
      "unknown abc",
      "move Alice e2e4",
      "session demo-game-1",
      "players Alice Bob"
    ))

    summary.parseFailures shouldBe 1
    summary.validationFailures shouldBe 1
  }

  it should "group envelopes into batches" in {
    implicit val system: ActorSystem = ActorSystem("ReactiveStreamBatchingSpec")

    try {
      val batches = Await.result(
        Source(List(
          "session demo-game-1",
          "players Alice Bob",
          "move Alice e2e4",
          "move Bob e7e5",
          "status",
          "resign Bob"
        ))
          .via(SearchessReactiveStreams.parseDslFlow)
          .via(SearchessReactiveStreams.parsedValidationFlow)
          .via(SearchessReactiveStreams.processingInputFlow)
          .via(SearchessReactiveStreams.eventEnvelopeFlow)
          .via(SearchessReactiveStreams.backpressureBufferFlow)
          .via(SearchessReactiveStreams.batchBackpressureFlow)
          .runWith(Sink.seq),
        5.seconds
      )

      batches.map(_.size) shouldBe Seq(5, 2)
    } finally {
      Await.result(system.terminate(), 5.seconds)
    }
  }

  private def runSummary(lines: List[String]): StreamSummary =
    implicit val system: ActorSystem = ActorSystem("ReactiveStreamPipelineSpec")
    try {
      Await.result(
        Source(lines)
          .via(SearchessReactiveStreams.parseDslFlow)
          .via(SearchessReactiveStreams.parsedValidationFlow)
          .via(SearchessReactiveStreams.processingInputFlow)
          .via(SearchessReactiveStreams.eventEnvelopeFlow)
          .via(SearchessReactiveStreams.backpressureBufferFlow)
          .via(SearchessReactiveStreams.batchBackpressureFlow)
          .runWith(SearchessReactiveStreams.summarySink),
        5.seconds
      )
    } finally {
      Await.result(system.terminate(), 5.seconds)
    }
