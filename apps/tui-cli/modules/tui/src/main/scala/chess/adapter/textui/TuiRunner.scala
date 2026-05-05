// $COVERAGE-OFF$
package chess.adapter.textui

import chess.application.GameStateObservable
import chess.application.session.model.DesktopSessionContext
import chess.application.session.service.GameSessionCommands

/** Starts a [[TextUI]] session on a named daemon thread.
  *
  * Supports both execution modes of [[TextUI]]:
  *
  * ===Session-aware mode===
  * Uses [[GameSessionCommands]] and [[DesktopSessionContext]]. All moves go through the unified
  * application mutation boundary ([[GameSessionCommands.submitMove]]), ensuring consistent
  * validation, persistence, and event publication across adapters (GUI, REST, WebSocket).
  *
  * ===Local mode===
  * No session infrastructure is provided. Moves go through the pure domain path
  * ([[chess.application.GameStateCommandService]]), with no persistence or event publication. Intended for
  * standalone demo or testing scenarios.
  *
  * ===Shutdown policy===
  *   - [[TuiExitReason.UserQuit]] → invokes `onUserQuit`; the caller decides what that means (e.g.
  *     shut down a GUI, exit the process, do nothing)
  *   - [[TuiExitReason.EndOfInput]] → logs a note; no callback is invoked
  *   - Unhandled exception in TUI → logged to stderr; no callback is invoked
  *
  * @param onUserQuit
  *   action to run when the user explicitly requests quit; called from the TUI daemon thread, not
  *   the FX thread. The caller is responsible for any required thread-hopping (e.g.
  *   `Platform.runLater` for JavaFX shutdown).
  */
object TuiRunner:

  /** Start the TUI in session-aware mode.
    *
    * All moves go through [[GameSessionCommands.submitMove]] — the unified application mutation
    * boundary — so that domain validation, persistence, and event publication apply to TUI-driven
    * moves in exactly the same way as to GUI- and REST-driven moves.
    *
    * [[GameStateObservable]] is updated after each successful move as a notification bridge so
    * other adapters (e.g. GUI) observe the state change. It is not the mutation authority in this
    * mode.
    */
  def start(
      game: GameStateObservable,
      commands: GameSessionCommands,
      sessionContext: DesktopSessionContext,
      onUserQuit: () => Unit
  ): Unit =
    val t = new Thread(
      () => run(new TextUI(ConsoleIO, game, Some(commands), Some(sessionContext)), onUserQuit),
      "searchess-tui"
    )
    t.setDaemon(true)
    t.start()

  /** Start the TUI in local (non-persistent) mode.
    *
    * Moves go through the pure domain path ([[chess.application.GameStateCommandService]]) with no persistence
    * or event publication. Intended for standalone demo use or contexts where no session
    * infrastructure is available.
    */
  def start(game: GameStateObservable, onUserQuit: () => Unit): Unit =
    val t = new Thread(() => run(new TextUI(ConsoleIO, game), onUserQuit), "searchess-tui")
    t.setDaemon(true)
    t.start()

  private def run(ui: TextUI, onUserQuit: () => Unit): Unit =
    try
      ui.run() match
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
