// $COVERAGE-OFF$
package chess.adapter.gui

import chess.adapter.gui.scene.ChessScene
import chess.application.GameStateObservable
import chess.application.session.model.DesktopSessionContext
import chess.application.session.service.{GameSessionCommands, SessionService}
import scalafx.application.JFXApp3
import scalafx.application.JFXApp3.PrimaryStage

/** ScalaFX application entry point.
 *
 *  `JFXApp3` requires a singleton object; dependencies cannot be passed as
 *  constructor arguments because the JavaFX runtime controls when `start()` fires.
 *  Use the prepare methods to supply shared dependencies from the composition root
 *  before calling `ChessApp.main(args)`.
 *
 *  === Required setup sequence ===
 *  1. [[prepareWith]] — supply the shared [[GameStateObservable]]
 *  2. [[prepareSessionGame]] — supply the [[GameSessionCommands]], [[SessionService]],
 *     and [[DesktopSessionContext]]
 *  3. [[prepareAfterStart]] — register optional post-start hook (e.g. TUI startup)
 *  4. [[main]] — launch the JavaFX toolkit
 *
 *  The command boundary ([[GameSessionCommands]]) and session context must be
 *  provided by the composition root (e.g. `game-service/Main`) so that all
 *  adapters (GUI, TUI, REST, WebSocket) operate on the same authoritative game
 *  identity and persistence layer.
 */

object ChessApp extends JFXApp3:

  private var providedGame:           Option[GameStateObservable]   = None
  private var providedCommands:       Option[GameSessionCommands]   = None
  private var providedSessionService: Option[SessionService]        = None
  private var providedContext:        Option[DesktopSessionContext] = None
  private var afterStart: () => Unit = () => ()

  /** Supply the shared game instance before launching.  Called by [[chess.Main]]. */
  def prepareWith(game: GameStateObservable): Unit =
    providedGame = Some(game)

  /** Supply the shared session dependencies before launching.
   *
   *  The [[GameSessionCommands]], [[SessionService]], and [[DesktopSessionContext]]
   *  must be the same instances provided to [[chess.adapter.textui.TuiRunner]] so
   *  that GUI and TUI share one authoritative game identity.  Called by [[chess.Main]].
   */
  def prepareSessionGame(
    commands:       GameSessionCommands,
    sessionService: SessionService,
    context:        DesktopSessionContext
  ): Unit =
    providedCommands       = Some(commands)
    providedSessionService = Some(sessionService)
    providedContext        = Some(context)

  /** Register a callback to invoke after [[start()]] has finished setting up
   *  the primary stage.  The callback runs on the JavaFX application thread,
   *  so `Platform.runLater` is safe to use inside it or from any thread it
   *  spawns.  Called by [[chess.Main]].
   */
  def prepareAfterStart(f: () => Unit): Unit =
    afterStart = f

  override def start(): Unit =
    val game           = providedGame.getOrElse(
      throw IllegalStateException("[ChessApp] prepareWith must be called before main")
    )
    val commands       = providedCommands.getOrElse(
      throw IllegalStateException("[ChessApp] prepareSessionGame must be called before main")
    )
    val sessionService = providedSessionService.getOrElse(
      throw IllegalStateException("[ChessApp] prepareSessionGame must be called before main")
    )
    val context        = providedContext.getOrElse(
      throw IllegalStateException("[ChessApp] prepareSessionGame must be called before main")
    )
    // Release references before handing off to ChessScene.
    providedGame           = None
    providedCommands       = None
    providedSessionService = None
    providedContext        = None

    val sceneController = new ChessScene(game, commands, sessionService, context)

    stage = new PrimaryStage:
      title     = "Searchess"
      scene     = sceneController.scene
      width     = 900
      height    = 680
      resizable = true

    // Fire the post-start hook now that the FX toolkit is fully running.
    val callback = afterStart
    afterStart = () => ()   // release
    callback()
