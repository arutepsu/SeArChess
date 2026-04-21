package chess.adapter.websocket

import chess.application.event.AppEvent
import chess.application.session.model.{SessionLifecycle, SessionMode, SideController}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.domain.model.{Color, Move, Position}
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable

class WebSocketEventPublisherSpec extends AnyFlatSpec with Matchers with EitherValues:

  // ── Test infrastructure ────────────────────────────────────────────────────

  /** Stub connection that accumulates sent messages. */
  class RecordingConnection(val id: String) extends WebSocketConnection:
    val received: mutable.ArrayBuffer[String] = mutable.ArrayBuffer()
    def send(message: String): Unit = received += message

  /** Stub connection whose send always throws. */
  class FailingConnection(val id: String) extends WebSocketConnection:
    def send(message: String): Unit = throw RuntimeException("network error")

  private val move = Move(Position.from(4, 1).value, Position.from(4, 3).value) // e2-e4

  private def event(gameId: GameId): AppEvent =
    AppEvent.MoveApplied(SessionId.random(), gameId, move, Color.White)

  private def freshPublisher() =
    val registry = WebSocketConnectionRegistry()
    val publisher = WebSocketEventPublisher(registry)
    (publisher, registry)

  // ── delivery to subscribers ────────────────────────────────────────────────

  "WebSocketEventPublisher.publish" should "send the event JSON to a subscribed connection" in {
    val (publisher, registry) = freshPublisher()
    val gameId = GameId.random()
    val conn = RecordingConnection("c1")
    registry.subscribe(conn, gameId)
    publisher.publish(event(gameId))
    conn.received should have size 1
    ujson.read(conn.received.head)("eventType").str shouldBe "MoveApplied"
  }

  it should "deliver to all connections subscribed to the same gameId" in {
    val (publisher, registry) = freshPublisher()
    val gameId = GameId.random()
    val c1 = RecordingConnection("c1")
    val c2 = RecordingConnection("c2")
    registry.subscribe(c1, gameId)
    registry.subscribe(c2, gameId)
    publisher.publish(event(gameId))
    c1.received should have size 1
    c2.received should have size 1
  }

  // ── isolation by gameId ────────────────────────────────────────────────────

  it should "NOT send to a connection subscribed to a different gameId" in {
    val (publisher, registry) = freshPublisher()
    val gameA = GameId.random()
    val gameB = GameId.random()
    val connA = RecordingConnection("cA")
    val connB = RecordingConnection("cB")
    registry.subscribe(connA, gameA)
    registry.subscribe(connB, gameB)
    publisher.publish(event(gameA))
    connA.received should have size 1
    connB.received shouldBe empty
  }

  it should "send nothing when there are no subscribers for the gameId" in {
    val (publisher, _) = freshPublisher()
    // Just verify no exception is thrown
    noException should be thrownBy publisher.publish(event(GameId.random()))
  }

  // ── disconnected / failing connections ────────────────────────────────────

  it should "continue delivering to other connections when one send throws" in {
    val (publisher, registry) = freshPublisher()
    val gameId = GameId.random()
    val failing = FailingConnection("bad")
    val good = RecordingConnection("good")
    registry.subscribe(failing, gameId)
    registry.subscribe(good, gameId)
    noException should be thrownBy publisher.publish(event(gameId))
    good.received should have size 1
  }

  // ── message content ───────────────────────────────────────────────────────

  it should "not alter the normal return value of the service (publish is fire-and-forget)" in {
    // Confirm that publish returns Unit and does not throw even with no subscribers
    val (publisher, _) = freshPublisher()
    val result: Unit = publisher.publish(event(GameId.random()))
    result shouldBe ()
  }
