// $COVERAGE-OFF$
package chess.adapter.textui

import chess.application.ObservableGame
import scalafx.application.Platform

/** Starts a [[TextUI]] session on a named daemon thread.
 *
 *  Must only be called after the JavaFX toolkit is initialized (i.e., from
 *  within [[chess.adapter.gui.ChessApp.start()]]), because the UserQuit
 *  shutdown path uses [[Platform.runLater]].
 *
 *  === Shutdown policy ===
 *  - [[TuiExitReason.UserQuit]]  → requests GUI shutdown via `Platform.runLater`
 *  - [[TuiExitReason.EndOfInput]] → logs a note; GUI keeps running
 *  - Unhandled exception in TUI → logged to stderr; GUI keeps running
 */
object TuiRunner:

  def start(game: ObservableGame): Unit =
    val t = new Thread(() => run(game), "searchess-tui")
    t.setDaemon(true)   // never prevent JVM exit when GUI closes first
    t.start()

  private def run(game: ObservableGame): Unit =
    try
      new TextUI(ConsoleIO, game).run() match
        case TuiExitReason.UserQuit =>
          // Explicit quit: close the GUI cleanly on the FX thread.
          Platform.runLater { Platform.exit() }
        case TuiExitReason.EndOfInput =>
          // No stdin available (forked process, IDE run, pipe).
          // GUI must stay alive — do NOT call Platform.exit().
          System.err.println("[searchess] TUI: no stdin available — continuing without TUI.")
    catch
      case ex: Exception =>
        System.err.println(s"[searchess] TUI error: ${ex.getMessage}")
        // GUI keeps running; do not close it on an unexpected exception.
