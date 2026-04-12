// $COVERAGE-OFF$
package chess.adapter.gui

import chess.adapter.gui.scene.ChessScene
import chess.application.ObservableGame
import scalafx.application.JFXApp3
import scalafx.application.JFXApp3.PrimaryStage

/** ScalaFX application entry point.
 *
 *  `JFXApp3` requires a singleton object; dependencies cannot be passed as
 *  constructor arguments because the JavaFX runtime controls when `start()`
 *  fires.  Use [[prepareWith]] to supply a shared [[ObservableGame]] and
 *  [[prepareAfterStart]] to register a callback that runs after the primary
 *  stage is set up, before calling `ChessApp.main(args)`.
 *
 *  The post-start hook fires after the FX toolkit is fully initialized, so
 *  any companion service that requires `Platform.runLater` (e.g. a TUI
 *  thread whose quit path shuts down the GUI) can safely be started there.
 *  That wiring is the caller's responsibility — this class does not know about
 *  the TUI or any other adapter.
 */
object ChessApp extends JFXApp3:

  private var providedGame: Option[ObservableGame] = None
  private var afterStart: () => Unit = () => ()

  /** Supply the shared game instance before launching.  Called by [[chess.Main]]. */
  def prepareWith(game: ObservableGame): Unit =
    providedGame = Some(game)

  /** Register a callback to invoke after [[start()]] has finished setting up
   *  the primary stage.  The callback runs on the JavaFX application thread,
   *  so `Platform.runLater` is safe to use inside it or from any thread it
   *  spawns.  Called by [[chess.Main]].
   */
  def prepareAfterStart(f: () => Unit): Unit =
    afterStart = f

  override def start(): Unit =
    val game = providedGame.getOrElse(new ObservableGame())
    providedGame = None   // release; ChessScene takes ownership from here

    val sceneController = new ChessScene(game)

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
