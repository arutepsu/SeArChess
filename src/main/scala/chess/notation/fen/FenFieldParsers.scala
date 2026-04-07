package chess.notation.fen

import chess.notation.api.ParseFailure

/** Parses each of the six FEN fields into parser-local model types.
 *
 *  Every method is a pure function that returns `Either[ParseFailure, T]`.
 *  No board state is produced here — conversion to domain objects is the
 *  importer's responsibility.
 *
 *  Validation scope:
 *  - syntax and structural shape only
 *  - no semantic chess-legality checks (king presence, reachability, etc.)
 */
object FenFieldParsers:

  // ── Piece placement ─────────────────────────────────────────────────────────

  /** Parse the piece placement field into a vector of 8 rank vectors.
   *
   *  The result is ordered from FEN rank 8 (index 0) to rank 1 (index 7),
   *  matching the order they appear in the FEN string.
   */
  def parsePiecePlacement(s: String): Either[ParseFailure, Vector[Vector[FenSquare]]] =
    val rankStrings = s.split("/", -1)
    if rankStrings.length != 8 then
      Left(ParseFailure.StructuralError(
        s"Piece placement must have exactly 8 ranks separated by '/'; got ${rankStrings.length}"
      ))
    else
      rankStrings.foldLeft(Right(Vector.empty): Either[ParseFailure, Vector[Vector[FenSquare]]]) {
        case (acc, rankStr) => acc.flatMap(ranks => parseRank(rankStr).map(ranks :+ _))
      }

  private def parseRank(rankStr: String): Either[ParseFailure, Vector[FenSquare]] =
    rankStr
      .foldLeft(Right(Vector.empty): Either[ParseFailure, Vector[FenSquare]]) {
        case (acc, c) => acc.flatMap(squares => charToSquares(c).map(squares ++ _))
      }
      .flatMap { squares =>
        if squares.length == 8 then Right(squares)
        else Left(ParseFailure.StructuralError(
          s"Rank '$rankStr' expands to ${squares.length} squares; expected 8"
        ))
      }

  private def charToSquares(c: Char): Either[ParseFailure, Vector[FenSquare]] = c match
    case '1' | '2' | '3' | '4' | '5' | '6' | '7' | '8' =>
      Right(Vector.fill(c - '0')(FenSquare.Empty))
    case 'K' => Right(Vector(FenSquare.Occupied(FenColor.White, FenPieceSymbol.King)))
    case 'Q' => Right(Vector(FenSquare.Occupied(FenColor.White, FenPieceSymbol.Queen)))
    case 'R' => Right(Vector(FenSquare.Occupied(FenColor.White, FenPieceSymbol.Rook)))
    case 'B' => Right(Vector(FenSquare.Occupied(FenColor.White, FenPieceSymbol.Bishop)))
    case 'N' => Right(Vector(FenSquare.Occupied(FenColor.White, FenPieceSymbol.Knight)))
    case 'P' => Right(Vector(FenSquare.Occupied(FenColor.White, FenPieceSymbol.Pawn)))
    case 'k' => Right(Vector(FenSquare.Occupied(FenColor.Black, FenPieceSymbol.King)))
    case 'q' => Right(Vector(FenSquare.Occupied(FenColor.Black, FenPieceSymbol.Queen)))
    case 'r' => Right(Vector(FenSquare.Occupied(FenColor.Black, FenPieceSymbol.Rook)))
    case 'b' => Right(Vector(FenSquare.Occupied(FenColor.Black, FenPieceSymbol.Bishop)))
    case 'n' => Right(Vector(FenSquare.Occupied(FenColor.Black, FenPieceSymbol.Knight)))
    case 'p' => Right(Vector(FenSquare.Occupied(FenColor.Black, FenPieceSymbol.Pawn)))
    case other =>
      Left(ParseFailure.SyntaxError(s"Illegal FEN piece symbol: '$other'"))

  // ── Active color ─────────────────────────────────────────────────────────────

  def parseActiveColor(s: String): Either[ParseFailure, FenColor] = s match
    case "w" => Right(FenColor.White)
    case "b" => Right(FenColor.Black)
    case _   => Left(ParseFailure.SyntaxError(
      s"Invalid active color: '$s'; expected 'w' or 'b'"
    ))

  // ── Castling availability ────────────────────────────────────────────────────

  private val ValidCastlingChars: Set[Char] = Set('K', 'Q', 'k', 'q')

  def parseCastling(s: String): Either[ParseFailure, FenCastlingAvailability] =
    if s == "-" then
      Right(FenCastlingAvailability.none)
    else if s.isEmpty then
      Left(ParseFailure.SyntaxError(
        "Castling field cannot be empty; use '-' to indicate no castling"
      ))
    else
      s.find(!ValidCastlingChars.contains(_)) match
        case Some(bad) =>
          Left(ParseFailure.SyntaxError(
            s"Invalid character in castling field: '$bad'; only K, Q, k, q are allowed"
          ))
        case None =>
          if s.distinct.length != s.length then
            Left(ParseFailure.StructuralError(
              s"Castling field '$s' contains duplicate symbols"
            ))
          else
            Right(FenCastlingAvailability(
              whiteKingSide  = s.contains('K'),
              whiteQueenSide = s.contains('Q'),
              blackKingSide  = s.contains('k'),
              blackQueenSide = s.contains('q')
            ))

  // ── En passant target ────────────────────────────────────────────────────────

  def parseEnPassant(s: String): Either[ParseFailure, FenEnPassantTarget] =
    if s == "-" then
      Right(FenEnPassantTarget.Absent)
    else
      s.toList match
        case List(file, rank)
            if file >= 'a' && file <= 'h'
            && rank >= '1' && rank <= '8' =>
          Right(FenEnPassantTarget.Square(file - 'a', rank - '1'))
        case _ =>
          Left(ParseFailure.SyntaxError(
            s"Invalid en passant square: '$s'; expected '-' or a square like 'e3'"
          ))

  // ── Numeric fields ───────────────────────────────────────────────────────────

  def parseHalfmoveClock(s: String): Either[ParseFailure, Int] =
    s.toIntOption match
      case None =>
        Left(ParseFailure.SyntaxError(s"Halfmove clock is not an integer: '$s'"))
      case Some(n) if n < 0 =>
        Left(ParseFailure.SyntaxError(s"Halfmove clock must be non-negative: $n"))
      case Some(n) =>
        Right(n)

  def parseFullmoveNumber(s: String): Either[ParseFailure, Int] =
    s.toIntOption match
      case None =>
        Left(ParseFailure.SyntaxError(s"Fullmove number is not an integer: '$s'"))
      case Some(n) if n < 1 =>
        Left(ParseFailure.SyntaxError(s"Fullmove number must be a positive integer: $n"))
      case Some(n) =>
        Right(n)
