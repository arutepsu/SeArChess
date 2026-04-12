package chess

import chess.adapter.gui.ChessApp
import chess.adapter.textui.TuiRunner
import chess.application.ObservableGame
import scalafx.application.Platform

object Main:

  def main(args: Array[String]): Unit =
    val game = new ObservableGame()
    ChessApp.prepareWith(game)
    // Start the TUI after the FX toolkit is running so that the TUI's
    // UserQuit path can safely schedule GUI shutdown via Platform.runLater.
    ChessApp.prepareAfterStart(() =>
      TuiRunner.start(game, onUserQuit = () => Platform.runLater { Platform.exit() })
    )
    ChessApp.main(args)
