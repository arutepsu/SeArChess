package chess.adapter.event

import chess.application.event.AppEvent
import chess.application.port.event.EventPublisher
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.domain.model.{Color, Move, Position}
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable

class FanOutEventPublisherSpec extends AnyFlatSpec with Matchers with EitherValues:

  // ── test infrastructure ────────────────────────────────────────────────────

  private class RecordingPublisher extends EventPublisher:
    val received: mutable.ArrayBuffer[AppEvent] = mutable.ArrayBuffer()
    def publish(event: AppEvent): Unit = received += event

  private class ThrowingPublisher extends EventPublisher:
    def publish(event: AppEvent): Unit = throw RuntimeException("publisher failure")

  private def sampleEvent: AppEvent.MoveApplied =
    val from = Position.from(4, 1).value  // e2
    val to   = Position.from(4, 3).value  // e4
    AppEvent.MoveApplied(SessionId.random(), GameId.random(), Move(from, to), Color.White)

  // ── delivery ───────────────────────────────────────────────────────────────

  "FanOutEventPublisher" should "deliver the event to a single downstream publisher" in {
    val p   = RecordingPublisher()
    val fan = FanOutEventPublisher(p)
    val e   = sampleEvent
    fan.publish(e)
    p.received shouldBe List(e)
  }

  it should "deliver the event to all downstream publishers" in {
    val p1  = RecordingPublisher()
    val p2  = RecordingPublisher()
    val fan = FanOutEventPublisher(p1, p2)
    val e   = sampleEvent
    fan.publish(e)
    p1.received shouldBe List(e)
    p2.received shouldBe List(e)
  }

  it should "deliver the exact same event instance to every publisher" in {
    val p1  = RecordingPublisher()
    val p2  = RecordingPublisher()
    val fan = FanOutEventPublisher(p1, p2)
    val e   = sampleEvent
    fan.publish(e)
    p1.received.head shouldBe e
    p2.received.head shouldBe e
  }

  it should "deliver multiple events in publication order" in {
    val p   = RecordingPublisher()
    val fan = FanOutEventPublisher(p)
    val e1  = sampleEvent
    val e2  = sampleEvent
    fan.publish(e1)
    fan.publish(e2)
    p.received.toList shouldBe List(e1, e2)
  }

  it should "be a no-op when there are no downstream publishers" in {
    val fan = FanOutEventPublisher()
    noException should be thrownBy fan.publish(sampleEvent)
  }

  // ── fault isolation ────────────────────────────────────────────────────────

  it should "not propagate exceptions from a downstream publisher that throws" in {
    val fan = FanOutEventPublisher(ThrowingPublisher())
    noException should be thrownBy fan.publish(sampleEvent)
  }

  it should "continue delivering to subsequent publishers when an earlier one throws" in {
    val good = RecordingPublisher()
    val fan  = FanOutEventPublisher(ThrowingPublisher(), good)
    fan.publish(sampleEvent)
    good.received should have size 1
  }

  it should "deliver to all non-throwing publishers even when multiple publishers throw" in {
    val good = RecordingPublisher()
    val fan  = FanOutEventPublisher(ThrowingPublisher(), good, ThrowingPublisher())
    fan.publish(sampleEvent)
    good.received should have size 1
  }

  it should "not propagate exceptions even when all publishers throw" in {
    val fan = FanOutEventPublisher(ThrowingPublisher(), ThrowingPublisher())
    noException should be thrownBy fan.publish(sampleEvent)
  }
