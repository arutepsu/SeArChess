package chess.guiapp

import chess.adapter.gui.ChessApp
import chess.application.session.model.{DesktopSessionContext, SessionMode, SideController}
import chess.application.session.model.SessionIds.GameId
import chess.config.AppConfig
import chess.startup.assembly.{CoreAssembly, ObservableGame}

/** Assembles the GUI-only runtime from [[AppConfig]].
 *
 *  Owns everything specific to the standalone GUI deployment:
 *
 *   1. Shared application runtime via [[CoreAssembly.build(AppConfig)]]
 *      (in-process, no-op event publisher — no HTTP or WebSocket server)
 *   2. One GUI-local session (HumanVsHuman, both sides local)
 *   3. The [[ObservableGame]] notification bridge for the GUI adapter
 *   4. [[ChessApp]] preparation
 *
 *  TUI is **not** started here.  GUI is a standalone app.
 *  After [[prepare]] returns, the caller starts the JavaFX event loop by
 *  calling [[ChessApp.main]].
 */
object GuiWiring:

  /** Assemble and prepare the GUI runtime.
   *
   *  Returns [[Unit]]; no live server handles are created.
   *  Throws if the session cannot be created (e.g. repository failure).
   */
  def prepare(config: AppConfig): Unit =

    // ── Shared application context ───────────────────────────────────────────
    val ctx = CoreAssembly.build(config)

    // ── GUI-local session ────────────────────────────────────────────────────
    val session = ctx.sessionService
      .createSession(GameId.random(), SessionMode.HumanVsHuman, SideController.HumanLocal, SideController.HumanLocal)
      .fold(err => throw RuntimeException(s"[GuiApp] Failed to create session: $err"), identity)
    val sessionContext = new DesktopSessionContext(session)

    // ── Notification bridge ──────────────────────────────────────────────────
    val game = new ObservableGame()

    // ── GUI preparation ──────────────────────────────────────────────────────
    ChessApp.prepareWith(game)
    ChessApp.prepareSessionGame(ctx.commands, ctx.sessionService, sessionContext)
    // No post-start hook — GUI runs standalone without TUI
