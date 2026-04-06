// $COVERAGE-OFF$
package chess.adapter.gui

import chess.adapter.gui.scene.ChessScene
import scalafx.application.JFXApp3
import scalafx.application.JFXApp3.PrimaryStage
import scalafx.scene.paint.Color as FxColor

/** ScalaFX application entry point.
 *
 *  Constructs [[ChessScene]] and sets it as the primary stage's scene.
 *  All chess logic is handled by [[scene.ChessScene]] and its collaborators.
 */
object ChessApp extends JFXApp3:

  override def start(): Unit =
    val chess = new ChessScene

    stage = new PrimaryStage:
      title  = "Searchess"
      scene  = chess.scene
      width  = 900
      height = 680
      resizable = true
