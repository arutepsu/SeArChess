// $COVERAGE-OFF$
package chess.adapter.gui

import chess.adapter.gui.scene.ChessScene
import chess.application.ObservableGame
import chess.application.session.model.DesktopSessionContext
import chess.application.session.service.SessionGameService
import scalafx.application.JFXApp3
import scalafx.application.JFXApp3.PrimaryStage

/** ScalaFX application entry point.
 *
 *  `JFXApp3` requires a singleton object; dependencies cannot be passed as
 *  constructor arguments because the JavaFX runtime controls when `start()`
 *  fires.  Use the prepare methods to supply shared dependencies before
 *  calling `ChessApp.main(args)`.
 *
 *  === Required setup sequence ===
 *  1. [[prepareWith]] — supply the shared [[ObservableGame]]
 *  2. [[prepareSessionGame]] — supply the shared [[SessionGameService]] and [[DesktopSessionContext]]
 *  3. [[prepareAfterStart]] — register optional post-start hook (e.g. TUI startup)
 *  4. [[main]] — launch the JavaFX toolkit
 *
 *  The shared session and service must come from the composition root so that
 *  GUI and TUI operate on the same authoritative game identity.
 */
object ChessApp extends JFXApp3:

  private var providedGame:    Option[ObservableGame]       = None
  private var providedService: Option[SessionGameService]   = None
  private var providedContext: Option[DesktopSessionContext] = None
  private var afterStart: () => Unit = () => ()

  /** Supply the shared game instance before launching.  Called by [[chess.Main]]. */
  def prepareWith(game: ObservableGame): Unit =
    providedGame = Some(game)

  /** Supply the shared session context before launching.
   *
   *  The [[SessionGameService]] and [[DesktopSessionContext]] must be the same instances
   *  provided to [[chess.adapter.textui.TuiRunner]] so that GUI and TUI share
   *  one authoritative game identity.  Called by [[chess.Main]].
   */
  def prepareSessionGame(service: SessionGameService, context: DesktopSessionContext): Unit =
    providedService = Some(service)
    providedContext = Some(context)

  /** Register a callback to invoke after [[start()]] has finished setting up
   *  the primary stage.  The callback runs on the JavaFX application thread,
   *  so `Platform.runLater` is safe to use inside it or from any thread it
   *  spawns.  Called by [[chess.Main]].
   */
  def prepareAfterStart(f: () => Unit): Unit =
    afterStart = f

  override def start(): Unit =
    val game    = providedGame.getOrElse(new ObservableGame())
    val service = providedService.getOrElse(
      throw IllegalStateException("[ChessApp] prepareSessionGame must be called before main")
    )
    val context = providedContext.getOrElse(
      throw IllegalStateException("[ChessApp] prepareSessionGame must be called before main")
    )
    // Release references before handing off to ChessScene.
    providedGame    = None
    providedService = None
    providedContext = None

    val sceneController = new ChessScene(game, service, context)

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
