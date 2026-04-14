package chess

import cats.effect.unsafe.implicits.global
import chess.adapter.gui.ChessApp
import chess.adapter.textui.TuiRunner
import chess.application.session.model.{DesktopSessionContext, SessionMode, SideController}
import chess.application.session.model.SessionIds.GameId
import chess.config.{AppConfig, ConfigLoader}
import scalafx.application.Platform

/** Entry point for the desktop-combined composition (GUI + TUI + backend).
 *
 *  Loads config from environment variables via [[ConfigLoader]], then starts
 *  the full backend infrastructure via [[SharedWiring.start]], then layers the
 *  desktop UI on top:
 *  - one shared desktop session (HumanVsHuman, both sides local)
 *  - the ScalaFX GUI ([[chess.adapter.gui.ChessApp]])
 *  - the TUI ([[chess.adapter.textui.TuiRunner]]), launched after the GUI
 *    window is ready
 *
 *  The backend (HTTP + WebSocket) is shut down when the user quits via the
 *  TUI or closes the GUI window.
 *
 *  === Desktop session ===
 *  GUI and TUI share ONE authoritative game identity created at startup and
 *  injected into both adapters.  Moves from either adapter go through the
 *  same [[chess.application.session.service.GameSessionCommands]] write boundary →
 *  same repositories → same game state.
 *  [[ObservableGame]] acts only as a cross-adapter notification bridge.
 *
 *  To run:
 *  {{{
 *    sbt run          // default main class
 *    sbt "bootstrapServer/runMain chess.DesktopMain"
 *  }}}
 */
object DesktopMain:

  def main(args: Array[String]): Unit =
    val config = ConfigLoader.loadOrExit()
    run(args, config)

  /** Start the desktop composition with a pre-loaded config.
   *
   *  Separated from [[main]] so that [[Main]] can load config once and
   *  dispatch here without reloading.
   */
  private[chess] def run(args: Array[String], config: AppConfig): Unit =

    val wiring = SharedWiring.start(config)

    // ── One shared desktop session ───────────────────────────────────────────
    // Session creation goes through SessionService directly; commands (move
    // submission) are routed through the GameSessionCommands boundary.
    val desktopSession = wiring.sessionService
      .createSession(GameId.random(), SessionMode.HumanVsHuman, SideController.HumanLocal, SideController.HumanLocal)
      .fold(err => throw RuntimeException(s"[Desktop] Failed to create session: $err"), identity)
    val desktopContext = new DesktopSessionContext(desktopSession)

    // ── Desktop GUI + TUI ────────────────────────────────────────────────────
    val game = new ObservableGame()
    ChessApp.prepareWith(game)
    ChessApp.prepareSessionGame(wiring.commands, wiring.sessionService, desktopContext)
    ChessApp.prepareAfterStart(() =>
      TuiRunner.start(game, wiring.commands, desktopContext, onUserQuit = () => {
        wiring.shutdownHttp.unsafeRunSync()
        wiring.wsServer.foreach(_.stop(0))
        Platform.runLater { Platform.exit() }
      })
    )
    ChessApp.main(args)
