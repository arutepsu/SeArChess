package chess.application

import chess.application.ChessService
import chess.domain.state.GameState
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable

/** Contract spec for [[GameStateObservable]].
 *
 *  Uses a minimal local concrete implementation to verify the behavioural
 *  contract that any [[GameStateObservable]] implementation must satisfy.
 *  The production concrete class lives in bootstrapserver; tests for its
 *  thread-safety are in that module.
 */
class ObservableGameSpec extends AnyFlatSpec with Matchers:

  /** Minimal implementation of the trait's contract — used in tests only. */
  private class TestImpl(initial: GameState = ChessService.createNewGame())
      extends GameStateObservable:
    private var s   = initial
    private val cbs = mutable.ListBuffer.empty[GameState => Unit]
    def getState: GameState = synchronized(s)
    def updateState(n: GameState): Unit =
      val snapshot = synchronized { s = n; cbs.toList }
      snapshot.foreach(_(n))
    def addObserver(cb: GameState => Unit): Unit = synchronized { cbs += cb }

  private def freshGame: GameStateObservable = new TestImpl()

  // ── Initial state ────────────────────────────────────────────────────────────

  "GameStateObservable.getState" should "return the standard starting position" in {
    freshGame.getState shouldBe ChessService.createNewGame()
  }

  // ── updateState ──────────────────────────────────────────────────────────────

  "GameStateObservable.updateState" should "reflect the new state in a subsequent getState call" in {
    val game     = freshGame
    val newState = ChessService.createNewGame()
    game.updateState(newState)
    game.getState shouldBe newState
  }

  // ── Observer registration ────────────────────────────────────────────────────

  "GameStateObservable.addObserver" should "invoke the callback with the new state on updateState" in {
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

  it should "fire once per updateState call" in {
    val game  = freshGame
    var count = 0
    game.addObserver(_ => count += 1)

    game.updateState(ChessService.createNewGame())
    game.updateState(ChessService.createNewGame())
    game.updateState(ChessService.createNewGame())

    count shouldBe 3
  }
