package chess.startup.assembly

import chess.application.{ChessService, GameStateObservable}
import chess.domain.state.GameState
import scala.collection.mutable

/** Concrete synchronized implementation of [[GameStateObservable]].
 *
 *  Lives in the composition root (startup-shared) so that no adapter module
 *  depends on this mutable class directly.  Adapters depend only on the
 *  [[GameStateObservable]] trait from the application module.
 *
 *  Used by both [[chess.guiapp.GuiWiring]] and [[chess.tuiapp.TuiWiring]] as
 *  the local runtime notification bridge for their respective standalone sessions.
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
class ObservableGame(initialState: GameState = ChessService.createNewGame()) extends GameStateObservable:
  private var state: GameState = initialState
  private val observers        = mutable.ListBuffer[GameState => Unit]()

  def getState: GameState = synchronized { state }

  def updateState(newState: GameState): Unit =
    val callbacks = synchronized {
      state = newState
      observers.toList   // snapshot while locked, release before notifying
    }
    callbacks.foreach(_(newState))

  def addObserver(callback: GameState => Unit): Unit = synchronized {
    observers += callback
  }
