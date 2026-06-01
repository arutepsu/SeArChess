package chess.streaming

import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Flow, Keep, Sink, Source}
import scala.concurrent.{ExecutionContext, Future}

// 1. Domain Model
case class Move(from: String, to: String)

case class GameState(
    board: Map[String, String], // e.g. "e2" -> "wP", "e8" -> "bK"
    activeColor: String,        // "white" or "black"
    moveHistory: List[Move]
)

object GameState {
  val initial: GameState = GameState(
    board = Map(
      // White pieces
      "a1" -> "wR", "b1" -> "wN", "c1" -> "wB", "d1" -> "wQ", "e1" -> "wK", "f1" -> "wB", "g1" -> "wN", "h1" -> "wR",
      "a2" -> "wP", "b2" -> "wP", "c2" -> "wP", "d2" -> "wP", "e2" -> "wP", "f2" -> "wP", "g2" -> "wP", "h2" -> "wP",
      // Black pieces
      "a7" -> "bP", "b7" -> "bP", "c7" -> "bP", "d7" -> "bP", "e7" -> "bP", "f7" -> "bP", "g7" -> "bP", "h7" -> "bP",
      "a8" -> "bR", "b8" -> "bN", "c8" -> "bB", "d8" -> "bQ", "e8" -> "bK", "f8" -> "bB", "g8" -> "bN", "h8" -> "bR"
    ),
    activeColor = "white",
    moveHistory = Nil
  )
}

object ChessStreamingMain {

  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem = ActorSystem("ChessStreamingSystem")
    implicit val ec: ExecutionContext = system.dispatcher

    println("=========================================================")
    println("      REACTIVE CHESS STREAMING FLOW (PEKKO STREAMS)     ")
    println("=========================================================")

    // 2. Source: Continuously outputs moves in DSL format (valid & invalid)
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

    // 3. Flow 1: Parser (String -> Either[Throwable, Move])
    val parserFlow: Flow[String, Either[Throwable, Move], _] = Flow[String].map { dsl =>
      val pattern = "^([a-h][1-8])-([a-h][1-8])$".r
      dsl.trim match {
        case pattern(from, to) => Right(Move(from, to))
        case _ => Left(new IllegalArgumentException(s"Ungueltiges Format fuer Schachzug: '$dsl'"))
      }
    }

    // Helper for validation & application of a move
    def validateAndApply(state: GameState, move: Move): Either[String, GameState] = {
      state.board.get(move.from) match {
        case None => Left(s"Keine Figur auf Feld ${move.from}")
        case Some(piece) =>
          val pieceColor = if (piece.startsWith("w")) "white" else "black"
          if (pieceColor != state.activeColor) {
            Left(s"Nicht am Zug (Aktive Farbe: ${state.activeColor}, Farbe der gewaehlten Figur: $pieceColor)")
          } else {
            // Validate movement geometry
            val fileFrom = move.from.charAt(0)
            val rankFrom = move.from.charAt(1).asDigit
            val fileTo = move.to.charAt(0)
            val rankTo = move.to.charAt(1).asDigit

            val dx = Math.abs(fileTo - fileFrom)
            val dy = Math.abs(rankTo - rankFrom)

            val isValidMove = piece.substring(1) match {
              case "P" => // Pawn: 1 or 2 squares forward, or 1 diagonal for capture
                if (pieceColor == "white") {
                  (fileFrom == fileTo && rankTo - rankFrom == 1 && !state.board.contains(move.to)) ||
                  (fileFrom == fileTo && rankFrom == 2 && rankTo == 4 && !state.board.contains(move.to) && !state.board.contains(s"${fileFrom}3")) ||
                  (dx == 1 && rankTo - rankFrom == 1 && state.board.contains(move.to))
                } else {
                  (fileFrom == fileTo && rankFrom - rankTo == 1 && !state.board.contains(move.to)) ||
                  (fileFrom == fileTo && rankFrom == 7 && rankTo == 5 && !state.board.contains(move.to) && !state.board.contains(s"${fileFrom}6")) ||
                  (dx == 1 && rankFrom - rankTo == 1 && state.board.contains(move.to))
                }
              case "N" => (dx == 1 && dy == 2) || (dx == 2 && dy == 1) // Knight: L-shape
              case "B" => dx == dy                                    // Bishop: diagonal
              case "R" => dx == 0 || dy == 0                          // Rook: straight line
              case "Q" => dx == 0 || dy == 0 || dx == dy              // Queen: straight or diagonal
              case "K" => dx <= 1 && dy <= 1                          // King: 1 step
              case _   => false
            }

            if (!isValidMove) {
              Left(s"Figur '$piece' kann sich nicht von ${move.from} nach ${move.to} bewegen")
            } else {
              val nextBoard = state.board - move.from + (move.to -> piece)
              val nextColor = if (state.activeColor == "white") "black" else "white"
              Right(GameState(nextBoard, nextColor, state.moveHistory :+ move))
            }
          }
      }
    }

    // 3. Flow 2: Validator/Processor (Either[Throwable, Move] -> Either[String, GameState])
    // Keeps internal GameState thread-safely via statefulMapConcat
    val validatorFlow: Flow[Either[Throwable, Move], Either[String, GameState], _] = {
      Flow[Either[Throwable, Move]].statefulMapConcat { () =>
        var currentState = GameState.initial
        var gameOver = false

        {
          case Left(err) =>
            List(Left(s"Parsing-Fehler: ${err.getMessage}"))
          case Right(move) =>
            if (gameOver) {
              List(Left(s"Zug abgelehnt: Spiel ist bereits beendet. (Zug: ${move.from}-${move.to})"))
            } else {
              validateAndApply(currentState, move) match {
                case Right(nextState) =>
                  currentState = nextState
                  // Check for Scholar's checkmate (White Queen on f7 captures, Black King on e8)
                  if (move.to == "f7" && currentState.board.get("f7").contains("wQ") && currentState.board.get("e8").contains("bK")) {
                    gameOver = true
                  }
                  List(Right(currentState))
                case Left(error) =>
                  List(Left(s"Ungueltiger Zug: $error"))
              }
            }
        }
      }
    }

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

    // 4. Sink: Prints ASCII board states or Red error warnings
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

    // Run the pipeline: Source -> Flow 1 -> Flow 2 -> Sink
    val runningStream = movesSource
      .via(parserFlow)
      .via(validatorFlow)
      .toMat(consoleSink)(Keep.right)
      .run()

    // Shutdown ActorSystem on stream completion
    runningStream.onComplete { _ =>
      println("\nReaktiver Schach-Datenstrom beendet. Beende ActorSystem...")
      system.terminate()
    }
  }
}
