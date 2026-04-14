package chess

import chess.adapter.gui.ChessApp
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MainSpec extends AnyFlatSpec with Matchers:

  "Main" should "wire a shared game in test mode" in {
    val original = ChessApp.sharedGame
    System.setProperty("chess.testMode", "true")

    try {
      Main.main(Array.empty)
      ChessApp.sharedGame should not be null
    } finally {
      if (original == null) ChessApp.sharedGame = null else ChessApp.sharedGame = original
      System.clearProperty("chess.testMode")
    }
  }
