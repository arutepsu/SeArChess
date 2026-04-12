// $COVERAGE-OFF$
package chess.adapter.textui

import chess.application.ObservableGame

/** Starts a [[TextUI]] session on a named daemon thread.
 *
 *  === Shutdown policy ===
 *  - [[TuiExitReason.UserQuit]]   → invokes `onUserQuit`; the caller decides
 *                                    what that means (e.g. shut down a GUI, exit
 *                                    the process, do nothing)
 *  - [[TuiExitReason.EndOfInput]] → logs a note; no callback is invoked
 *  - Unhandled exception in TUI   → logged to stderr; no callback is invoked
 *
 *  @param onUserQuit action to run when the user explicitly requests quit;
 *                    called from the TUI daemon thread, not the FX thread.
 *                    The caller is responsible for any required thread-hopping
 *                    (e.g. `Platform.runLater` for JavaFX shutdown).
 */
object TuiRunner:

  def start(game: ObservableGame, onUserQuit: () => Unit): Unit =
    val t = new Thread(() => run(game, onUserQuit), "searchess-tui")
    t.setDaemon(true)   // never prevent JVM exit when the GUI closes first
    t.start()

  private def run(game: ObservableGame, onUserQuit: () => Unit): Unit =
    try
      new TextUI(ConsoleIO, game).run() match
        case TuiExitReason.UserQuit =>
          // Explicit quit: delegate shutdown to the registered callback.
          onUserQuit()
        case TuiExitReason.EndOfInput =>
          // No stdin available (forked process, IDE run, pipe).
          // Do not call onUserQuit — the companion process must stay alive.
          System.err.println("[searchess] TUI: no stdin available — continuing without TUI.")
    catch
      case ex: Exception =>
        System.err.println(s"[searchess] TUI error: ${ex.getMessage}")
        // Do not invoke onUserQuit on an unexpected exception.
