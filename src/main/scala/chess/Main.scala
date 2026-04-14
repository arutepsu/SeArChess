package chess

import chess.adapter.gui.ChessApp
import chess.adapter.textui.{ConsoleIO, TextUI}
import chess.application.ObservableGame

object Main {
  def main(args: Array[String]): Unit = {
    if (System.getProperty("chess.testMode") == "true") {
      ChessApp.sharedGame = new ObservableGame()
      return
    }

    // 1. Initialize the shared game state
    val game = new ObservableGame()

    // 2. Start the TUI in a background thread 
    // This allows `readLine()` to block without blocking the JavaFX thread
    val tuiThread = new Thread(() => {
      val tui = new TextUI(ConsoleIO, game)
      tui.run()
      
      // If TUI exits, maybe also close the GUI? 
      // Platform.exit() is an option if we want 'quit' to close everything.
      // scalafx.application.Platform.exit()
      System.exit(0)
    })
    tuiThread.setDaemon(true) // So it doesn't keep the JVM alive if GUI closes
    tuiThread.start()

    // 3. Setup the GUI to use the same ObservableGame instance
    ChessApp.sharedGame = game

    // 4. Start the GUI on the main thread
    ChessApp.main(args)
  }
}
