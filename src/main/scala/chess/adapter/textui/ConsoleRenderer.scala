package chess.adapter.textui

import chess.application.{ApplicationError, GameState}
import chess.domain.error.DomainError
import chess.domain.model.{Color, GameStatus, Piece, PieceType, Position}

object ConsoleRenderer:

  // Beispiel: partially applied function für ein Präfix
  val errorPrefix: String => String = withPrefix("[ERROR] ")

  // Beispiel: partially applied function für Board-Rendering mit Symbolfunktion
  val renderBoardStars: GameState => String = renderBoardWith(_ => "*")

  // Beispiel: partially applied function für Status-Ausgabe mit fixem Spieler
  def statusForPlayer(player: String): GameState => String =
    (state: GameState) => s"Player: $player | Status: ${statusName(state.status)} | Moves: ${state.moveHistory.size}"

  // Beispiel für eine Closure: Funktion, die einen Zähler aus dem äußeren Scope verwendet
  def makeCountingRenderer(): GameState => String =
    var count = 0
    (state: GameState) =>
      count += 1
      s"[Render count: $count]\n" + renderBoard(state)

  // Beispiel für Currying: Funktion, die ein Präfix und dann eine Nachricht nimmt
  def withPrefix(prefix: String)(msg: String): String = s"$prefix$msg"

  // Currying für Board-Rendering: Funktion, die ein Symbol-Mapping nimmt und dann das Board rendert
  def renderBoardWith(symbolFn: Piece => String)(state: GameState): String =
    val rows = (7 to 0 by -1).map { rank =>
      val rankLabel = s"${rank + 1} "
      val row = (0 to 7).map { file =>
        Position.from(file, rank).toOption
          .flatMap(state.board.pieceAt)
          .map(symbolFn)
          .getOrElse(".")
      }.mkString(" ")
      rankLabel + row
    }
    (rows :+ "  a b c d e f g h").mkString("\n")

  // Beispiel für partielle Anwendung mit Currying
  val renderBoardLowercase: GameState => String = renderBoardWith(piece => pieceSymbol(piece).toLowerCase)

  def renderBoard(state: GameState): String =
    val rows = (7 to 0 by -1).map { rank =>
      val rankLabel = s"${rank + 1} "
      val row = (0 to 7).map { file =>
        Position.from(file, rank).toOption
          .flatMap(state.board.pieceAt)
          .map(pieceSymbol)
          .getOrElse(".")
      }.mkString(" ")
      rankLabel + row
    }
    (rows :+ "  a b c d e f g h").mkString("\n")

  def renderStatus(state: GameState): String =
    val player = colorName(state.currentPlayer)
    val status = statusName(state.status)
    s"Player: $player | Status: $status | Moves: ${state.moveHistory.size}"

  def renderPromotionRequired(): String =
    "Pawn promotion! Choose a piece: promote q (Queen) | r (Rook) | b (Bishop) | n (Knight)"

  def renderWelcome(): String =
    """|Welcome to SeArChess!
       |Type 'help' for available commands.""".stripMargin

  def renderHelp(): String =
    """|Commands:
       |  new          - start a new game
       |  move e2 e4   - move a piece (algebraic notation)
       |  promote q    - choose promotion piece: q r b n (queen/rook/bishop/knight)
       |  show         - redisplay the board
       |  help         - show this help
       |  quit         - exit the game""".stripMargin

  def renderParseError(err: InputParseError): String = err match
    case InputParseError.EmptyInput                  => "Please enter a command."
    case InputParseError.UnknownCommand(cmd)         => s"Unknown command: '$cmd'. Type 'help' for options."
    case InputParseError.WrongArgumentCount("promote") => "Usage: promote <q|r|b|n>"
    case InputParseError.WrongArgumentCount(cmd)       => s"Usage: $cmd <from> <to>  (e.g. move e2 e4)"
    case InputParseError.InvalidPromotionToken(token)=> s"Invalid promotion choice: '$token'. Use: q r b n"

  def renderApplicationError(err: ApplicationError): String = err match
    case ApplicationError.NotPlayersTurn         => "It is not your turn."
    case ApplicationError.PromotionChoiceRequired => "Promotion required. Enter: promote q | r | b | n"
    case ApplicationError.NoPromotionPending      => "No promotion is pending."
    case ApplicationError.DomainFailure(de)       => renderDomainError(de)

  private def renderDomainError(err: DomainError): String = err match
    case DomainError.EmptySourceSquare(pos)    => s"No piece at $pos."
    case DomainError.IllegalMove(from, to)     => s"Illegal move: $from to $to."
    case DomainError.BlockedPath(from, to)     => s"Path blocked: $from to $to."
    case DomainError.OccupiedByOwnPiece(pos)   => s"Your own piece is on $pos."
    case DomainError.SameSquare               => "Source and target squares are the same."
    case DomainError.KingInCheck              => "That move would leave your king in check."
    case DomainError.OutOfBounds(f, r)         => s"Position out of bounds: file=$f rank=$r."
    case DomainError.InvalidPositionString(s)  => s"Invalid position: '$s'."
    case DomainError.InvalidPromotionPiece     => "Invalid promotion piece. Choose: q r b n"
    case DomainError.InvalidPromotionState     => "No promotable pawn at the expected square."
    case DomainError.CastleNotAllowed          => "Castling is no longer allowed on that side."
    case DomainError.MissingCastlingRook       => "Rook is not on its original square."
    case DomainError.CastlePathBlocked         => "Castling path is blocked."
    case DomainError.CastleThroughCheck        => "Cannot castle through or out of check."
    case DomainError.InvalidEnPassant          => "Illegal en passant capture."

  private def colorName(color: Color): String = color match
    case Color.White => "White"
    case Color.Black => "Black"

  private def statusName(status: GameStatus): String = status match
    case GameStatus.Ongoing   => "Ongoing"
    case GameStatus.Check     => "Check!"
    case GameStatus.Checkmate => "Checkmate!"
    case GameStatus.Stalemate => "Stalemate"

  private def pieceSymbol(piece: Piece): String =
    val ch = piece.pieceType match
      case PieceType.King   => 'K'
      case PieceType.Queen  => 'Q'
      case PieceType.Rook   => 'R'
      case PieceType.Bishop => 'B'
      case PieceType.Knight => 'N'
      case PieceType.Pawn   => 'P'
    piece.color match
      case Color.White => ch.toString
      case Color.Black => ch.toLower.toString
