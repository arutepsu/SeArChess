// $COVERAGE-OFF$
package chess.adapter.gui

import chess.adapter.gui.scene.ChessScene
import scalafx.application.JFXApp3
import scalafx.application.JFXApp3.PrimaryStage
import scalafx.scene.paint.Color as FxColor
import chess.application.ObservableGame

/** ScalaFX application entry point.
 *
 *  Constructs [[ChessScene]] and sets it as the primary stage's scene.
 *  All chess logic is handled by [[scene.ChessScene]] and its collaborators.
 */
object ChessApp extends JFXApp3:

  var sharedGame: ObservableGame = null

  override def start(): Unit =
    // Fallback to a local game if none provided (e.g. if run directly)
    if sharedGame == null then sharedGame = new ObservableGame()

    val sceneController = new ChessScene(sharedGame)

    stage = new PrimaryStage:
      title  = "Searchess"
      scene  = sceneController.scene
      width  = 620
      height = 680
      resizable = false
