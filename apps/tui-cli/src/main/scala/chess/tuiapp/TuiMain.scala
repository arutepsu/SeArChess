package chess.tuiapp

import chess.config.ConfigLoader

/** Entry point for the standalone TUI app.
 *
 *  Loads config from environment variables via [[ConfigLoader]], delegates
 *  composition to [[TuiWiring]], then blocks the main thread so the JVM
 *  stays alive while the TUI daemon thread runs.
 *
 *  Does not start the GUI.  TUI is a self-contained runtime shape.
 *  Shutdown is driven by the user issuing the quit command; the TUI
 *  callback calls `System.exit(0)`.
 *
 *  To run:
 *  {{{
 *    sbt "tuiCli/run"
 *    sbt "tuiCli/runMain chess.tuiapp.TuiMain"
 *  }}}
 */
object TuiMain:

  def main(args: Array[String]): Unit =
    val config = ConfigLoader.loadOrExit()
    TuiWiring.start(config)
    // TUI runs on a daemon thread; block main so the JVM does not exit.
    // System.exit(0) in onUserQuit terminates everything when the user quits.
    Thread.currentThread().join()
