package chess.application

import chess.domain.state.GameState
import scala.collection.mutable

/** Centralised state holder for the application across different UI adapters.
 *
 *  Provides an atomic reference-like hold over the core [[GameState]] and
 *  allows multiple observers (like TUI and GUI) to be notified whenever
 *  the game state changes.
 */
class ObservableGame(initialState: GameState = ChessService.createNewGame()):
  private var state: GameState = initialState
  private val observers = mutable.ListBuffer[GameState => Unit]()

  /** Returns the current game state. */
  def getState: GameState = synchronized { state }

  /** Replaces the current state and notifies all registered observers. */
  def updateState(newState: GameState): Unit = synchronized {
    state = newState
    notifyObservers()
  }

  /** Registers a callback that will be called with the new state
   *  whenever `updateState` is invoked.
   */
  def addObserver(callback: GameState => Unit): Unit = synchronized {
    observers += callback
  }

  private def notifyObservers(): Unit = 
    val snapshot = state
    val cbs = observers.toList
    cbs.foreach(cb => cb(snapshot))
