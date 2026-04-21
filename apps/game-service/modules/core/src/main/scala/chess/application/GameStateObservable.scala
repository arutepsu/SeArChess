package chess.application

import chess.domain.state.GameState

/** Read/notify contract for the cross-adapter state notification bridge.
  *
  * Adapters (GUI, TUI) depend on this trait to observe and propagate game state changes without
  * importing a concrete mutable class. The concrete implementation lives in the composition root
  * (game-service) so that no adapter depends on another adapter's infrastructure.
  *
  * ===Thread-safety contract===
  * Implementations must ensure that `getState` and `updateState` are safe to call from concurrent
  * threads. Observer callbacks should be invoked outside any lock so that: (a) slow observers do
  * not starve state readers, and (b) observers that call `getState` or `updateState` themselves do
  * not deadlock on re-entry.
  */
trait GameStateObservable:
  /** Returns the current game state. */
  def getState: GameState

  /** Replaces the current state and notifies all registered observers.
    *
    * Observers are called with a snapshot of the new state after any internal lock is released.
    */
  def updateState(newState: GameState): Unit

  /** Registers a callback invoked with the new state on every [[updateState]]. */
  def addObserver(callback: GameState => Unit): Unit
