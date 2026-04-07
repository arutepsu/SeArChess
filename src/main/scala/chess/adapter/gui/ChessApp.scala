// $COVERAGE-OFF$
package chess.adapter.gui

import chess.adapter.gui.scene.ChessScene
import chess.adapter.textui.TuiRunner
import chess.application.ObservableGame
import scalafx.application.JFXApp3
import scalafx.application.JFXApp3.PrimaryStage

/** ScalaFX application entry point.
 *
 *  `JFXApp3` requires a singleton object; dependencies cannot be passed as
 *  constructor arguments because the JavaFX runtime controls when `start()`
 *  fires.  Use [[prepareWith]] to supply a shared [[ObservableGame]] before
 *  calling `ChessApp.main(args)`.
 *
 *  The TUI background thread is started here, inside `start()`, so that
 *  [[scalafx.application.Platform]] is guaranteed to be initialized before
 *  the TUI's shutdown path calls `Platform.runLater`.
 */
object ChessApp extends JFXApp3:

  private var providedGame: Option[ObservableGame] = None

  /** Supply the shared game instance before launching.  Called by [[chess.Main]]. */
  def prepareWith(game: ObservableGame): Unit =
    providedGame = Some(game)

  override def start(): Unit =
    // Resolve game; fall back to a fresh one for stand-alone development launches.
    val game = providedGame.getOrElse(new ObservableGame())
    providedGame = None   // release; ChessScene takes ownership from here

    val sceneController = new ChessScene(game)

    stage = new PrimaryStage:
      title     = "Searchess"
      scene     = sceneController.scene
      width     = 900
      height    = 680
      resizable = true

    // Start TUI only after the stage is set up and the FX toolkit is fully
    // running, so Platform.runLater inside TuiRunner.run is always safe.
    TuiRunner.start(game)
