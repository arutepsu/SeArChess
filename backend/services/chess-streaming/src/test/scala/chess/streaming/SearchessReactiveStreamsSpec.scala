package chess.streaming

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Await
import scala.concurrent.duration.*

final class SearchessReactiveStreamsSpec extends AnyFlatSpec with Matchers:

  "SearchessReactiveStreams" should "materialize the Searchess DSL pipeline into envelopes" in {
    implicit val system: ActorSystem = ActorSystem("SearchessReactiveStreamsSpec")

    try {
      val lines = List(
        "# comment",
        "",
        "session demo-game-1",
        "players Alice Bob",
        "move Alice e2e4",
        "move Bob e7e5",
        "status",
        "resign Bob"
      )

      val envelopes = Await.result(
        Source(lines)
          .via(SearchessReactiveStreams.parseDslFlow)
          .via(SearchessReactiveStreams.parsedValidationFlow)
          .via(SearchessReactiveStreams.processingInputFlow)
          .via(SearchessReactiveStreams.eventEnvelopeFlow)
          .runWith(Sink.seq),
        5.seconds
      )

      envelopes.map(_.eventType) should contain allOf (
        "SessionStarted",
        "PlayersRegistered",
        "MoveAccepted",
        "StatusSnapshot",
        "GameResigned",
        "GameFinished"
      )
      envelopes.exists(_.eventType == "ParseFailed") shouldBe false
      envelopes.exists(_.eventType == "ValidationFailed") shouldBe false
    } finally {
      Await.result(system.terminate(), 5.seconds)
    }
  }

  it should "keep parse failures in the stream as data" in {
    implicit val system: ActorSystem = ActorSystem("SearchessReactiveStreamsParseFailureSpec")

    try {
      val envelopes = Await.result(
        Source(List("unknown abc"))
          .via(SearchessReactiveStreams.parseDslFlow)
          .via(SearchessReactiveStreams.parsedValidationFlow)
          .via(SearchessReactiveStreams.processingInputFlow)
          .via(SearchessReactiveStreams.eventEnvelopeFlow)
          .runWith(Sink.seq),
        5.seconds
      )

      envelopes.map(_.eventType) shouldBe Seq("ParseFailed")
      envelopes.head.payload should include("unknown command")
    } finally {
      Await.result(system.terminate(), 5.seconds)
    }
  }
