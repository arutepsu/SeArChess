package chess.tuiapp

import chess.adapter.textui.{ConsoleIO, ConsoleRenderer, TuiRunner}
import chess.application.session.model.{DesktopSessionContext, SessionMode, SideController}
import chess.application.session.model.SessionIds.GameId
import chess.startup.local.{LocalGameAssembly, LocalRuntimeConfig, ObservableGame}

/** Assembles the TUI-only runtime from [[LocalRuntimeConfig]] and starts the TUI loop.
  *
  * Owns everything specific to the standalone TUI deployment:
  *
  *   1. Local application runtime via [[LocalGameAssembly.build]] (in-process, no-op event
  *      publisher — no HTTP or WebSocket server) 2. Mode selection prompt (HumanVsHuman, HumanVsAI,
  *      AIVsAI) 3. One TUI-local session created with the selected mode 4. The [[ObservableGame]]
  *      notification bridge for the TUI adapter 5. [[TuiRunner]] startup on a daemon thread
  *
  * GUI is **not** started here. TUI is a standalone app. [[start]] returns after launching the
  * daemon thread; the caller is responsible for blocking the main thread so the JVM does not exit
  * before the user quits.
  */
object TuiWiring:

  /** Assemble the TUI runtime and start the TUI loop.
    *
    * The TUI runs on a daemon thread. When the user issues the quit command, `System.exit(0)` is
    * called which terminates the JVM.
    *
    * Throws if the session cannot be created (e.g. repository failure).
    */
  def start(config: LocalRuntimeConfig): Unit =

    // ── Shared application context ───────────────────────────────────────────
    val ctx = LocalGameAssembly.build(config)

    // ── Mode selection ───────────────────────────────────────────────────────
    val (mode, whiteController, blackController) = promptForMode()

    // ── TUI-local session ────────────────────────────────────────────────────
    val session = ctx.sessionLifecycleService
      .createSession(
        GameId.random(),
        mode,
        whiteController,
        blackController
      )
      .fold(err => throw RuntimeException(s"[TuiApp] Failed to create session: $err"), identity)
    val sessionContext = new DesktopSessionContext(session)

    // ── Notification bridge ──────────────────────────────────────────────────
    val game = new ObservableGame()

    // ── TUI startup ──────────────────────────────────────────────────────────
    // Runs on a daemon thread; System.exit(0) on user quit terminates the JVM.
    TuiRunner.start(game, ctx.commands, sessionContext, onUserQuit = () => System.exit(0))

  /** Prompt the user to select a game mode via console input.
    *
    * Returns: (SessionMode, whiteController, blackController)
    */
  private def promptForMode(): (SessionMode, SideController, SideController) =
    ConsoleIO.printLine(ConsoleRenderer.renderModeMenu())

    @scala.annotation.tailrec
    def readChoice(): (SessionMode, SideController, SideController) =
      val input = ConsoleIO.readLine()
      input.trim match
        case "1" =>
          ConsoleIO.printLine("You selected: Human vs Human")
          (SessionMode.HumanVsHuman, SideController.HumanLocal, SideController.HumanLocal)
        case "2" =>
          ConsoleIO.printLine("You selected: Human vs AI (you play as White)")
          (SessionMode.HumanVsAI, SideController.HumanLocal, SideController.AI())
        case "3" =>
          ConsoleIO.printLine("You selected: AI vs AI")
          (SessionMode.AIVsAI, SideController.AI(), SideController.AI())
        case other =>
          ConsoleIO.printLine(ConsoleRenderer.renderInvalidModeChoice(other))
          readChoice()

    readChoice()
