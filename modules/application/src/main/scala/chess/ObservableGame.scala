package chess.application

import chess.domain.state.GameState
import scala.collection.mutable

/** Centralised state holder for the application across different UI adapters.
 *
 *  Provides synchronized access to the current [[GameState]] and notifies
 *  registered observers whenever state changes.
 *
 *  === Thread-safety contract ===
 *  - `getState` and `updateState` are synchronized on `this`.
 *  - Observer callbacks are invoked **outside** the lock so that:
 *    (a) slow or blocking observers do not starve other threads waiting to
 *        read or update state, and
 *    (b) observers that call `getState` or `updateState` themselves will not
 *        attempt to re-enter the lock from within a notification.
 *  - Callbacks receive a snapshot of the state at the moment `updateState`
 *    was called; they may observe a newer state if another update arrives
 *    concurrently, which is acceptable given the UI's eventual-consistency
 *    rendering model.
 *
 *  === Observer threading ===
 *  Observers are called on whatever thread invokes `updateState`.  Each
 *  observer is responsible for marshalling work to its own thread if needed
 *  (e.g., [[chess.adapter.gui.controller.GameController]] uses
 *  `Platform.runLater` for JavaFX safety).
 */
class ObservableGame(initialState: GameState = ChessService.createNewGame()):
  private var state: GameState = initialState
  private val observers        = mutable.ListBuffer[GameState => Unit]()

  /** Returns the current game state. */
  def getState: GameState = synchronized { state }

  /** Replaces the current state and notifies all registered observers.
   *
   *  Observers are called after the lock is released with a snapshot of the
   *  new state.
   */
  def updateState(newState: GameState): Unit =
    val callbacks = synchronized {
      state = newState
      observers.toList   // snapshot while locked, release before notifying
    }
    callbacks.foreach(_(newState))

  /** Registers a callback invoked with the new state on every [[updateState]]. */
  def addObserver(callback: GameState => Unit): Unit = synchronized {
    observers += callback
  }
