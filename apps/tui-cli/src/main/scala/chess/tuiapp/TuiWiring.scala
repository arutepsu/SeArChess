package chess.tuiapp

import chess.adapter.textui.TuiRunner
import chess.application.session.model.{DesktopSessionContext, SessionMode, SideController}
import chess.application.session.model.SessionIds.GameId
import chess.startup.local.{LocalGameAssembly, LocalRuntimeConfig, ObservableGame}

/** Assembles the TUI-only runtime from [[LocalRuntimeConfig]] and starts the TUI loop.
 *
 *  Owns everything specific to the standalone TUI deployment:
 *
 *   1. Local application runtime via [[LocalGameAssembly.build]]
 *      (in-process, no-op event publisher — no HTTP or WebSocket server)
 *   2. One TUI-local session (HumanVsHuman, both sides local)
 *   3. The [[ObservableGame]] notification bridge for the TUI adapter
 *   4. [[TuiRunner]] startup on a daemon thread
 *
 *  GUI is **not** started here.  TUI is a standalone app.
 *  [[start]] returns after launching the daemon thread; the caller is
 *  responsible for blocking the main thread so the JVM does not exit
 *  before the user quits.
 */
object TuiWiring:

  /** Assemble the TUI runtime and start the TUI loop.
   *
   *  The TUI runs on a daemon thread.  When the user issues the quit command,
   *  `System.exit(0)` is called which terminates the JVM.
   *
   *  Throws if the session cannot be created (e.g. repository failure).
   */
  def start(config: LocalRuntimeConfig): Unit =

    // ── Shared application context ───────────────────────────────────────────
    val ctx = LocalGameAssembly.build(config)

    // ── TUI-local session ────────────────────────────────────────────────────
    val session = ctx.sessionService
      .createSession(GameId.random(), SessionMode.HumanVsHuman, SideController.HumanLocal, SideController.HumanLocal)
      .fold(err => throw RuntimeException(s"[TuiApp] Failed to create session: $err"), identity)
    val sessionContext = new DesktopSessionContext(session)

    // ── Notification bridge ──────────────────────────────────────────────────
    val game = new ObservableGame()

    // ── TUI startup ──────────────────────────────────────────────────────────
    // Runs on a daemon thread; System.exit(0) on user quit terminates the JVM.
    TuiRunner.start(game, ctx.commands, sessionContext, onUserQuit = () => System.exit(0))
