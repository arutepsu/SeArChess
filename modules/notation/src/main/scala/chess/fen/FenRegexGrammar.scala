package chess.notation.fen

import chess.notation.api.ParseFailure

/** Internal regex-based grammar for strict FEN syntax.
  *
  * Responsibilities:
  *   - parse the full six-field FEN grammar using regex/manual parsing
  *   - construct a parser-local [[FenRecord]]
  *   - enforce syntax and structural constraints only
  *
  * Intentionally NOT responsible for:
  *   - semantic chess validation
  *   - mapping to domain objects
  */
private[fen] object FenRegexGrammar extends FenGrammar:

  private val FenPattern =
    raw"^(\S+) (w|b) (\S+) (\S+) (\S+) (\S+)$$".r

  private val CastlingPattern =
    raw"^[KQkq]+$$".r

  private val EnPassantPattern =
    raw"^([a-h])([1-8])$$".r

  private val UnsignedIntPattern =
    raw"^\d+$$".r

  def parseRecord(input: String): Either[ParseFailure, FenRecord] =
    if input.isEmpty then Left(ParseFailure.UnexpectedEndOfInput("FEN string is empty"))
    else
      input match
        case FenPattern(
              piecePlacement,
              activeColor,
              castling,
              enPassant,
              halfmoveClock,
              fullmoveNumber
            ) =>
          for
            ranks <- parsePiecePlacement(piecePlacement)
            color <- parseActiveColor(activeColor)
            castle <- parseCastling(castling)
            ep <- parseEnPassant(enPassant)
            halfmove <- parseHalfmoveClock(halfmoveClock)
            fullmove <- parseFullmoveNumber(fullmoveNumber)
          yield FenRecord(
            ranks = ranks,
            activeColor = color,
            castling = castle,
            enPassant = ep,
            halfmoveClock = halfmove,
            fullmoveNumber = fullmove
          )

        case _ =>
          Left(
            ParseFailure.SyntaxError(
              message = "[line 1, column 1] failed parsing FEN: expected 6 space-separated fields",
              line = Some(1),
              column = Some(1)
            )
          )

  // ── Piece placement ───────────────────────────────────────────────────────

  private def parsePiecePlacement(s: String): Either[ParseFailure, Vector[Vector[FenSquare]]] =
    val rankStrings = s.split("/", -1).toVector
    if rankStrings.length != 8 then
      Left(syntaxError(s"piece placement must contain exactly 8 ranks; got ${rankStrings.length}"))
    else sequence(rankStrings.map(parseRank))

  private def parseRank(rankStr: String): Either[ParseFailure, Vector[FenSquare]] =
    val expanded =
      rankStr.toVector.foldLeft[Either[ParseFailure, Vector[FenSquare]]](Right(Vector.empty)) {
        case (acc, c) =>
          for
            squares <- acc
            next <- rankAtomToSquares(c)
          yield squares ++ next
      }

    expanded.flatMap { squares =>
      if squares.length == 8 then Right(squares)
      else Left(syntaxError(s"rank expands to ${squares.length} squares; expected 8"))
    }

  private def rankAtomToSquares(c: Char): Either[ParseFailure, Vector[FenSquare]] =
    c match
      case '1' | '2' | '3' | '4' | '5' | '6' | '7' | '8' =>
        Right(Vector.fill(c.asDigit)(FenSquare.Empty))

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
        Left(syntaxError(s"illegal FEN piece symbol: '$other'"))

  // ── Active color ──────────────────────────────────────────────────────────

  private def parseActiveColor(s: String): Either[ParseFailure, FenColor] =
    s match
      case "w" => Right(FenColor.White)
      case "b" => Right(FenColor.Black)
      case other =>
        Left(syntaxError(s"invalid active color: '$other'; expected 'w' or 'b'"))

  // ── Castling ──────────────────────────────────────────────────────────────

  private def parseCastling(s: String): Either[ParseFailure, FenCastlingAvailability] =
    if s == "-" then Right(FenCastlingAvailability.none)
    else
      s match
        case CastlingPattern() =>
          if s.distinct.length != s.length then
            Left(syntaxError(s"castling field '$s' contains duplicate symbols"))
          else
            Right(
              FenCastlingAvailability(
                whiteKingSide = s.contains('K'),
                whiteQueenSide = s.contains('Q'),
                blackKingSide = s.contains('k'),
                blackQueenSide = s.contains('q')
              )
            )

        case _ =>
          Left(syntaxError(s"invalid castling field: '$s'; expected '-' or one or more of KQkq"))

  // ── En passant ────────────────────────────────────────────────────────────

  private def parseEnPassant(s: String): Either[ParseFailure, FenEnPassantTarget] =
    if s == "-" then Right(FenEnPassantTarget.Absent)
    else
      s match
        case EnPassantPattern(fileStr, rankStr) =>
          Right(
            FenEnPassantTarget.Square(
              file = fileStr.head - 'a',
              rank = rankStr.head - '1'
            )
          )

        case _ =>
          Left(syntaxError(s"invalid en passant square: '$s'; expected '-' or a square like 'e3'"))

  // ── Numeric fields ────────────────────────────────────────────────────────

  private def parseHalfmoveClock(s: String): Either[ParseFailure, Int] =
    s match
      case UnsignedIntPattern() =>
        Right(s.toInt)
      case _ =>
        Left(syntaxError(s"halfmove clock is not a non-negative integer: '$s'"))

  private def parseFullmoveNumber(s: String): Either[ParseFailure, Int] =
    s match
      case UnsignedIntPattern() =>
        val n = s.toInt
        if n >= 1 then Right(n)
        else Left(syntaxError(s"fullmove number must be a positive integer: '$s'"))
      case _ =>
        Left(syntaxError(s"fullmove number is not a positive integer: '$s'"))

  // ── Helpers ───────────────────────────────────────────────────────────────

  private def syntaxError(message: String): ParseFailure.SyntaxError =
    ParseFailure.SyntaxError(
      message = s"[line 1, column 1] failed parsing FEN: $message",
      line = Some(1),
      column = Some(1)
    )

  private def sequence[A](
      values: Vector[Either[ParseFailure, A]]
  ): Either[ParseFailure, Vector[A]] =
    values.foldLeft[Either[ParseFailure, Vector[A]]](Right(Vector.empty)) { (acc, next) =>
      for
        xs <- acc
        x <- next
      yield xs :+ x
    }
