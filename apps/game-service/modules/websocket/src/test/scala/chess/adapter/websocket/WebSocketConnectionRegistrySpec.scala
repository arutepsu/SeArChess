package chess.adapter.websocket

import chess.application.session.model.SessionIds.GameId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable

class WebSocketConnectionRegistrySpec extends AnyFlatSpec with Matchers:

  private def registry = WebSocketConnectionRegistry()

  private def stubConn(connId: String): WebSocketConnection = new WebSocketConnection:
    val id: String = connId
    def send(message: String): Unit = ()

  private val gameA = GameId.random()
  private val gameB = GameId.random()

  // ── subscribersFor ─────────────────────────────────────────────────────────

  "WebSocketConnectionRegistry.subscribersFor" should "return empty set for an unknown gameId" in {
    registry.subscribersFor(gameA) shouldBe empty
  }

  it should "return a registered connection after subscribe" in {
    val reg = registry
    val conn = stubConn("c1")
    reg.subscribe(conn, gameA)
    reg.subscribersFor(gameA) shouldBe Set(conn)
  }

  it should "return all connections subscribed to the same gameId" in {
    val reg = registry
    val c1 = stubConn("c1")
    val c2 = stubConn("c2")
    reg.subscribe(c1, gameA)
    reg.subscribe(c2, gameA)
    reg.subscribersFor(gameA) should contain allOf (c1, c2)
  }

  it should "not return connections subscribed to a different gameId" in {
    val reg = registry
    val conn = stubConn("c1")
    reg.subscribe(conn, gameB)
    reg.subscribersFor(gameA) shouldBe empty
  }

  // ── unsubscribe ────────────────────────────────────────────────────────────

  "WebSocketConnectionRegistry.unsubscribe" should "remove a subscribed connection" in {
    val reg = registry
    val conn = stubConn("c1")
    reg.subscribe(conn, gameA)
    reg.unsubscribe(conn)
    reg.subscribersFor(gameA) shouldBe empty
  }

  it should "not affect other connections when unsubscribing one" in {
    val reg = registry
    val c1 = stubConn("c1")
    val c2 = stubConn("c2")
    reg.subscribe(c1, gameA)
    reg.subscribe(c2, gameA)
    reg.unsubscribe(c1)
    reg.subscribersFor(gameA) shouldBe Set(c2)
  }

  it should "be a no-op when the connection was never registered" in {
    val reg = registry
    val conn = stubConn("unknown")
    noException should be thrownBy reg.unsubscribe(conn)
    reg.subscribersFor(gameA) shouldBe empty
  }

  it should "remove a connection from all game subscriptions" in {
    val reg = registry
    val conn = stubConn("c1")
    reg.subscribe(conn, gameA)
    reg.subscribe(conn, gameB)
    reg.unsubscribe(conn)
    reg.subscribersFor(gameA) shouldBe empty
    reg.subscribersFor(gameB) shouldBe empty
  }
