package chess

import chess.adapter.gui.ChessApp
import chess.application.ObservableGame

object Main:

  def main(args: Array[String]): Unit =
    val game = new ObservableGame()
    ChessApp.prepareWith(game)
    ChessApp.main(args)