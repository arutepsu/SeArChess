package chess

import chess.adapter.gui.ChessApp
import chess.application.ObservableGame

/** Composition root.
 *
 *  Creates the shared game, hands it to [[ChessApp]], and starts the JavaFX
 *  lifecycle.  The TUI is started later, inside [[ChessApp.start()]], once the
 *  JavaFX toolkit is fully initialized.
 */
object Main:
  def main(args: Array[String]): Unit =
    val game = new ObservableGame()
    ChessApp.prepareWith(game)
    ChessApp.main(args)   // blocks until JavaFX exits; JVM then exits normally
