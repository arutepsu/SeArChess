package chess.streaming

import chess.domain.state.GameStateFactory
import chess.streaming.GameProcessingResult.*
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Await
import scala.concurrent.duration.*

final class EventEnvelopeFlowSpec extends AnyFlatSpec with Matchers:

  "EventEnvelopeFlow" should "wrap every processing result with Kafka-ready metadata" in {
    implicit val system: ActorSystem = ActorSystem("EventEnvelopeFlowSpec")

    try {
      val envelopes = Await.result(
        Source(List[GameProcessingResult](
          SessionStarted(1, "demo"),
          PlayersRegistered(2, "demo", "Alice", "Bob"),
          MoveAccepted(3, "demo", "Alice", "e2e4", 1, 0, GameStateFactory.initial()),
          MoveRejected(4, Some("demo"), "Alice", "e2e5", 1, 1, "illegal"),
          StatusSnapshot(5, Some("demo"), GameStateFactory.initial(), 1, 1, finished = false),
          GameResigned(6, Some("demo"), "Bob", Some("Alice")),
          GameFinished(6, Some("demo"), "Bob resigned"),
          ParseFailed(DslParseError(7, "bad", "bad input")),
          ValidationFailed(ValidationError(8, "bad", "bad command"), Some("demo"))
        ))
          .via(SearchessReactiveStreams.eventEnvelopeFlow)
          .runWith(Sink.seq),
        5.seconds
      )

      envelopes.map(_.eventType) should contain allOf (
        "SessionStarted",
        "PlayersRegistered",
        "MoveAccepted",
        "MoveRejected",
        "StatusSnapshot",
        "GameResigned",
        "GameFinished",
        "ParseFailed",
        "ValidationFailed"
      )
      all(envelopes.map(_.eventId)) should startWith("searchess-")
      all(envelopes.map(_.version)) shouldBe 1
      all(envelopes.map(_.payload)) should not be empty
      envelopes.foreach(_.occurredAt.toString should not be empty)
      SearchessReactiveStreams.envelopeToJson(envelopes.head) should include(""""eventType":"SessionStarted"""")
    } finally {
      Await.result(system.terminate(), 5.seconds)
    }
  }
