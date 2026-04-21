package chess.notation.fen

import chess.notation.api.ParseFailure
import scala.util.parsing.combinator.RegexParsers

private[fen] object FenCombinatorGrammar extends RegexParsers, FenGrammar:

  override val skipWhitespace: Boolean = false

  def parseRecord(input: String): Either[ParseFailure, FenRecord] =
    if input.isEmpty then Left(ParseFailure.UnexpectedEndOfInput("FEN string is empty"))
    else
      parseAll(fenRecord, input) match
        case Success(record, _) =>
          Right(record)
        case NoSuccess(msg, next) =>
          val pos = next.pos
          Left(
            ParseFailure.SyntaxError(
              s"[line ${pos.line}, column ${pos.column}] failed parsing FEN: $msg"
            )
          )

  private def fenRecord: Parser[FenRecord] =
    piecePlacement ~ space ~ activeColor ~ space ~ castling ~ space ~ enPassant ~ space ~ halfmoveClock ~ space ~ fullmoveNumber ^^ {
      case ranks ~ _ ~ color ~ _ ~ castlingRights ~ _ ~ ep ~ _ ~ halfmove ~ _ ~ fullmove =>
        FenRecord(
          ranks = ranks,
          activeColor = color,
          castling = castlingRights,
          enPassant = ep,
          halfmoveClock = halfmove,
          fullmoveNumber = fullmove
        )
    }

  private def space: Parser[String] =
    " "

  private def piecePlacement: Parser[Vector[Vector[FenSquare]]] =
    rank ~ ("/" ~> rank) ~ ("/" ~> rank) ~ ("/" ~> rank) ~
      ("/" ~> rank) ~ ("/" ~> rank) ~ ("/" ~> rank) ~ ("/" ~> rank) ^^ {
        case r1 ~ r2 ~ r3 ~ r4 ~ r5 ~ r6 ~ r7 ~ r8 =>
          Vector(r1, r2, r3, r4, r5, r6, r7, r8)
      }

  private def rank: Parser[Vector[FenSquare]] =
    rep1(rankAtom) >> { atoms =>
      val squares = atoms.flatten.toVector
      if squares.length == 8 then success(squares)
      else failure(s"rank expands to ${squares.length} squares; expected 8")
    }

  private def rankAtom: Parser[List[FenSquare]] =
    pieceSquare ^^ (sq => List(sq)) |
      emptyRun

  private def emptyRun: Parser[List[FenSquare]] =
    """[1-8]""".r ^^ { s =>
      List.fill(s.toInt)(FenSquare.Empty)
    }

  private def pieceSquare: Parser[FenSquare] =
    "K" ^^^ FenSquare.Occupied(FenColor.White, FenPieceSymbol.King) |
      "Q" ^^^ FenSquare.Occupied(FenColor.White, FenPieceSymbol.Queen) |
      "R" ^^^ FenSquare.Occupied(FenColor.White, FenPieceSymbol.Rook) |
      "B" ^^^ FenSquare.Occupied(FenColor.White, FenPieceSymbol.Bishop) |
      "N" ^^^ FenSquare.Occupied(FenColor.White, FenPieceSymbol.Knight) |
      "P" ^^^ FenSquare.Occupied(FenColor.White, FenPieceSymbol.Pawn) |
      "k" ^^^ FenSquare.Occupied(FenColor.Black, FenPieceSymbol.King) |
      "q" ^^^ FenSquare.Occupied(FenColor.Black, FenPieceSymbol.Queen) |
      "r" ^^^ FenSquare.Occupied(FenColor.Black, FenPieceSymbol.Rook) |
      "b" ^^^ FenSquare.Occupied(FenColor.Black, FenPieceSymbol.Bishop) |
      "n" ^^^ FenSquare.Occupied(FenColor.Black, FenPieceSymbol.Knight) |
      "p" ^^^ FenSquare.Occupied(FenColor.Black, FenPieceSymbol.Pawn)

  private def activeColor: Parser[FenColor] =
    "w" ^^^ FenColor.White |
      "b" ^^^ FenColor.Black

  private def castling: Parser[FenCastlingAvailability] =
    "-" ^^^ FenCastlingAvailability.none |
      """[KQkq]+""".r ^? (
        { case s if s.distinct.length == s.length => castlingFromString(s) },
        s => s"castling field '$s' contains duplicate symbols"
      )

  private def castlingFromString(s: String): FenCastlingAvailability =
    FenCastlingAvailability(
      whiteKingSide = s.contains('K'),
      whiteQueenSide = s.contains('Q'),
      blackKingSide = s.contains('k'),
      blackQueenSide = s.contains('q')
    )

  private def enPassant: Parser[FenEnPassantTarget] =
    "-" ^^^ FenEnPassantTarget.Absent |
      """[a-h][1-8]""".r ^^ { s =>
        FenEnPassantTarget.Square(
          file = s.charAt(0) - 'a',
          rank = s.charAt(1) - '1'
        )
      }

  private def halfmoveClock: Parser[Int] =
    """\d+""".r ^? (
      { case s if s.toInt >= 0 => s.toInt },
      s => s"halfmove clock must be non-negative: '$s'"
    )

  private def fullmoveNumber: Parser[Int] =
    """\d+""".r ^? (
      { case s if s.toInt >= 1 => s.toInt },
      s => s"fullmove number must be a positive integer: '$s'"
    )
