package chess.guiapp

import chess.adapter.gui.ChessApp
import chess.config.ConfigLoader

/** Entry point for the standalone GUI app.
 *
 *  Loads config from environment variables via [[ConfigLoader]], delegates
 *  composition to [[GuiWiring]], then launches the JavaFX event loop.
 *
 *  Does not start the TUI.  GUI is a self-contained runtime shape.
 *  Shutdown is driven by the user closing the GUI window; the JavaFX platform
 *  exits and the JVM terminates normally.
 *
 *  To run:
 *  {{{
 *    sbt "desktopGui/run"
 *    sbt "desktopGui/runMain chess.guiapp.GuiMain"
 *  }}}
 */
object GuiMain:

  def main(args: Array[String]): Unit =
    val config = ConfigLoader.loadOrExit()
    GuiWiring.prepare(config)
    ChessApp.main(args)
