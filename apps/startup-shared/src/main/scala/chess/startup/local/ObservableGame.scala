package chess.startup.local

<<<<<<< HEAD
import chess.application.{GameStateCommandService, GameStateObservable}
=======
import chess.application.{ChessService, GameStateObservable}
>>>>>>> f7a07f01 (runnable mains, hardered event contracts)
import chess.domain.state.GameState
import scala.collection.mutable

/** Concrete synchronized implementation of [[GameStateObservable]] for local clients. */
<<<<<<< HEAD
class ObservableGame(initialState: GameState = GameStateCommandService.createNewGame())
    extends GameStateObservable:
  private var state: GameState = initialState
  private val observers = mutable.ListBuffer[GameState => Unit]()
=======
class ObservableGame(initialState: GameState = ChessService.createNewGame()) extends GameStateObservable:
  private var state: GameState = initialState
  private val observers        = mutable.ListBuffer[GameState => Unit]()
>>>>>>> f7a07f01 (runnable mains, hardered event contracts)

  def getState: GameState = synchronized { state }

  def updateState(newState: GameState): Unit =
    val callbacks = synchronized {
      state = newState
      observers.toList
    }
    callbacks.foreach(_(newState))

  def addObserver(callback: GameState => Unit): Unit = synchronized {
    observers += callback
  }
