package chess.startup.local

import chess.application.{ChessService, GameStateObservable}
import chess.domain.state.GameState
import scala.collection.mutable

/** Concrete synchronized implementation of [[GameStateObservable]] for local clients. */
class ObservableGame(initialState: GameState = ChessService.createNewGame()) extends GameStateObservable:
  private var state: GameState = initialState
  private val observers        = mutable.ListBuffer[GameState => Unit]()

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
