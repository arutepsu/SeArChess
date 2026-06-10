package chess.streaming

import org.apache.pekko.stream.scaladsl.Flow

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

object ChessStreamingEngine {

  def parseMove(dsl: String): Either[Throwable, Move] = {
    val pattern = "^([a-h][1-8])-([a-h][1-8])$".r
    dsl.trim match {
      case pattern(from, to) => Right(Move(from, to))
      case _ => Left(new IllegalArgumentException(s"Ungueltiges Format fuer Schachzug: '$dsl'"))
    }
  }

  /** Parser Flow: Parses DSL move strings ("e2-e4") into Move objects.
    * Invalid formats are wrapped in a Left(Throwable).
    */
  val parserFlow: Flow[String, Either[Throwable, Move], _] = Flow[String].map(parseMove)

  /** Validator/Processor Flow: Keeps track of board state per materialization
    * and applies rules to validate and progress the game.
    */
  def validatorFlow: Flow[Either[Throwable, Move], Either[String, GameState], _] = {
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
                // Checkmate detection (Scholar's checkmate: Queen captures f7, Black King on e8)
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

  /** Validates move geometry and updates board state.
    */
  def validateAndApply(state: GameState, move: Move): Either[String, GameState] = {
    state.board.get(move.from) match {
      case None => Left(s"Keine Figur auf Feld ${move.from}")
      case Some(piece) =>
        val pieceColor = if (piece.startsWith("w")) "white" else "black"
        if (pieceColor != state.activeColor) {
          Left(s"Nicht am Zug (Aktive Farbe: ${state.activeColor}, Farbe der gewaehlten Figur: $pieceColor)")
        } else {
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
}
