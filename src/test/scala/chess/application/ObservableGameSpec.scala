package chess.application

import chess.application.ChessService
import chess.domain.state.GameState
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.collection.mutable

class ObservableGameSpec extends AnyFlatSpec with Matchers:

  private def freshGame = new ObservableGame()

  // ── Initial state ────────────────────────────────────────────────────────────

  "ObservableGame.getState" should "return the standard starting position" in {
    freshGame.getState shouldBe ChessService.createNewGame()
  }

  // ── updateState ──────────────────────────────────────────────────────────────

  "ObservableGame.updateState" should "reflect the new state in a subsequent getState call" in {
    val game     = freshGame
    val newState = ChessService.createNewGame()
    game.updateState(newState)
    game.getState shouldBe newState
  }

  // ── Observer registration ────────────────────────────────────────────────────

  "ObservableGame.addObserver" should "invoke the callback with the new state on updateState" in {
    val game     = freshGame
    var received = Option.empty[GameState]
    game.addObserver(s => received = Some(s))

    val newState = ChessService.createNewGame()
    game.updateState(newState)

    received shouldBe Some(newState)
  }

  it should "invoke all registered observers" in {
    val game    = freshGame
    val results = mutable.ListBuffer.empty[Int]
    game.addObserver(_ => results += 1)
    game.addObserver(_ => results += 2)

    game.updateState(ChessService.createNewGame())

    results.toList shouldBe List(1, 2)
  }

  it should "invoke observers in registration order" in {
    val game  = freshGame
    val order = mutable.ListBuffer.empty[String]
    game.addObserver(_ => order += "first")
    game.addObserver(_ => order += "second")
    game.addObserver(_ => order += "third")

    game.updateState(ChessService.createNewGame())

    order.toList shouldBe List("first", "second", "third")
  }

  it should "not call an observer registered after updateState was already called" in {
    val game    = freshGame
    var called  = false
    game.updateState(ChessService.createNewGame())
    game.addObserver(_ => called = true)

    called shouldBe false
  }

  // ── Lock released before notification (deadlock safety) ──────────────────────

  "ObservableGame observer" should "be able to call getState without deadlocking" in {
    // If notifyObservers() were called inside the synchronized block, an
    // observer that calls getState on the same thread would re-enter the
    // lock (which is re-entrant in the JVM, so no deadlock).  But if it
    // called getState from a different thread, that thread would block
    // until the lock was released.  This test verifies that an observer
    // running on a separate thread can read the state immediately.
    val game    = freshGame
    val latch   = new CountDownLatch(1)
    var visible = Option.empty[GameState]

    game.addObserver { _ =>
      // run the read on a new thread to prove the lock is not held
      val t = new Thread(() => {
        visible = Some(game.getState)
        latch.countDown()
      })
      t.start()
    }

    val updated = ChessService.createNewGame()
    game.updateState(updated)

    latch.await(2, TimeUnit.SECONDS) shouldBe true
    visible shouldBe Some(updated)
  }

  it should "receive the correct state snapshot even when called from a background thread" in {
    val game   = freshGame
    val latch  = new CountDownLatch(1)
    val seen   = mutable.ListBuffer.empty[GameState]

    game.addObserver { s => seen += s; latch.countDown() }

    val newState = ChessService.createNewGame()
    val writer   = new Thread(() => game.updateState(newState))
    writer.start()

    latch.await(2, TimeUnit.SECONDS) shouldBe true
    seen.toList shouldBe List(newState)
  }

  // ── Multiple updates ─────────────────────────────────────────────────────────

  it should "fire once per updateState call" in {
    val game  = freshGame
    var count = 0
    game.addObserver(_ => count += 1)

    game.updateState(ChessService.createNewGame())
    game.updateState(ChessService.createNewGame())
    game.updateState(ChessService.createNewGame())

    count shouldBe 3
  }
