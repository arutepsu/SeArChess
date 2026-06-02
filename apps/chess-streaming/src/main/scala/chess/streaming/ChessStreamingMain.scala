package chess.streaming

import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Keep, Sink, Source}
import scala.concurrent.{ExecutionContext, Future}

object ChessStreamingMain {

  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem = ActorSystem("ChessStreamingSystem")
    implicit val ec: ExecutionContext = system.dispatcher

    println("=========================================================")
    println("      REACTIVE CHESS STREAMING FLOW (PEKKO STREAMS)     ")
    println("=========================================================")

    // 1. Source: Continuously outputs moves in DSL format (valid & invalid)
    val movesSource: Source[String, _] = Source(List(
      "e2-e4",         // Valid
      "e7-e5",         // Valid
      "invalid_move",  // Invalid parser format (fails Parse Flow)
      "d1-h5",         // Valid
      "b8-c6",         // Valid
      "f1-c4",         // Valid
      "g8-f6",         // Valid (attempts to block mate)
      "h5-f7",         // Valid checkmate capture (Scholar's Mate!)
      "a8-a6",         // Illegal (rejected because game is already over)
      "e5-e4",         // Illegal move
      "not-even-close" // Invalid parser format
    ))

    // ASCII printer utility for GameState
    def printBoard(state: GameState): Unit = {
      val horizontalLine = "   +---+---+---+---+---+---+---+---+"
      println("\n   [ Aktueller Zustand des Schachbretts ]")
      println(s"   Am Zug: ${state.activeColor.toUpperCase} | Gespielte Zuege: ${state.moveHistory.length}")
      println(horizontalLine)
      for (rank <- 8 to 1 by -1) {
        print(s" $rank |")
        for (fileChar <- 'a' to 'h') {
          val pos = s"$fileChar$rank"
          val symbol = state.board.get(pos) match {
            case Some("wK") => "wK"
            case Some("wQ") => "wQ"
            case Some("wR") => "wR"
            case Some("wB") => "wB"
            case Some("wN") => "wN"
            case Some("wP") => "wP"
            case Some("bK") => "bK"
            case Some("bQ") => "bQ"
            case Some("bR") => "bR"
            case Some("bB") => "bB"
            case Some("bN") => "bN"
            case Some("bP") => "bP"
            case _          => "  "
          }
          print(s" $symbol |")
        }
        println(s"\n$horizontalLine")
      }
      println("     a   b   c   d   e   f   g   h\n")
    }

    // 2. Sink: Prints ASCII board states or Red error warnings
    val consoleSink: Sink[Either[String, GameState], Future[Done]] = Sink.foreach {
      case Right(state) =>
        printBoard(state)
        // If last move was checkmate:
        if (state.moveHistory.lastOption.exists(m => m.to == "f7" && state.board.get("f7").contains("wQ"))) {
          println("\u001b[32m[STATUS] SCHACHMATT! Weiss gewinnt durch das Schaefermatt! [GAME OVER]\u001b[0m")
          println("---------------------------------------------------------")
        }
      case Left(error) =>
        println(s"\u001b[31m[PROZESSOR-FEHLER] $error\u001b[0m")
        println("---------------------------------------------------------")
    }

    // Run the pipeline: Source -> Flow 1 (Parser) -> Flow 2 (Validator) -> Sink
    val runningStream = movesSource
      .via(ChessStreamingEngine.parserFlow)
      .via(ChessStreamingEngine.validatorFlow)
      .toMat(consoleSink)(Keep.right)
      .run()

    // Shutdown ActorSystem on stream completion
    runningStream.onComplete { _ =>
      println("\nReaktiver Schach-Datenstrom beendet. Beende ActorSystem...")
      system.terminate()
    }
  }
}
