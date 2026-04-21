package chess.notation.fen

import chess.notation.api.ParseFailure
import fastparse.*
import NoWhitespace.*

/** Internal FastParse grammar for strict FEN syntax.
  *
  * Responsibilities:
  *   - parse the full six-field FEN grammar
  *   - construct a parser-local [[FenRecord]]
  *   - enforce syntax and structural constraints only
  *
  * Intentionally NOT responsible for:
  *   - semantic chess validation
  *   - mapping to domain objects
  */
private[fen] object FenFastParseGrammar extends FenGrammar:

  def parseRecord(input: String): Either[ParseFailure, FenRecord] =
    if input.isEmpty then Left(ParseFailure.UnexpectedEndOfInput("FEN string is empty"))
    else
      fastparse.parse(input, fenRecord(using _)) match
        case Parsed.Success(record, _) =>
          Right(record)
        case f: Parsed.Failure =>
          Left(
            ParseFailure.SyntaxError(
              message = s"[line 1, column ${f.index + 1}] failed parsing FEN: ${f.trace().longMsg}",
              line = Some(1),
              column = Some(f.index + 1)
            )
          )

  // ── Top-level grammar ─────────────────────────────────────────────────────

  private def fenRecord[$: P]: P[FenRecord] =
    P(
      piecePlacement ~ space ~
        activeColor ~ space ~
        castling ~ space ~
        enPassant ~ space ~
        halfmoveClock ~ space ~
        fullmoveNumber ~ End
    ).map { case (ranks, color, castlingRights, ep, halfmove, fullmove) =>
      FenRecord(
        ranks = ranks,
        activeColor = color,
        castling = castlingRights,
        enPassant = ep,
        halfmoveClock = halfmove,
        fullmoveNumber = fullmove
      )
    }

  private def space[$: P]: P[Unit] =
    P(" ")

  // ── Piece placement ───────────────────────────────────────────────────────

  private def piecePlacement[$: P]: P[Vector[Vector[FenSquare]]] =
    P(
      rank ~ "/" ~ rank ~ "/" ~ rank ~ "/" ~ rank ~
        "/" ~ rank ~ "/" ~ rank ~ "/" ~ rank ~ "/" ~ rank
    ).map { case (r1, r2, r3, r4, r5, r6, r7, r8) =>
      Vector(r1, r2, r3, r4, r5, r6, r7, r8)
    }

  private def rank[$: P]: P[Vector[FenSquare]] =
    P(rankAtom.rep(1)).flatMap { atoms =>
      val squares = atoms.flatten.toVector
      if squares.length == 8 then Pass.map(_ => squares)
      else Fail.opaque(s"rank expands to ${squares.length} squares; expected 8")
    }

  private def rankAtom[$: P]: P[List[FenSquare]] =
    P(pieceSquare.map(sq => List(sq)) | emptyRun)

  private def emptyRun[$: P]: P[List[FenSquare]] =
    P(CharIn("1-8").!).map { s =>
      List.fill(s.toInt)(FenSquare.Empty)
    }

  private def pieceSquare[$: P]: P[FenSquare] =
    P(
      "K".!.map(_ => FenSquare.Occupied(FenColor.White, FenPieceSymbol.King)) |
        "Q".!.map(_ => FenSquare.Occupied(FenColor.White, FenPieceSymbol.Queen)) |
        "R".!.map(_ => FenSquare.Occupied(FenColor.White, FenPieceSymbol.Rook)) |
        "B".!.map(_ => FenSquare.Occupied(FenColor.White, FenPieceSymbol.Bishop)) |
        "N".!.map(_ => FenSquare.Occupied(FenColor.White, FenPieceSymbol.Knight)) |
        "P".!.map(_ => FenSquare.Occupied(FenColor.White, FenPieceSymbol.Pawn)) |
        "k".!.map(_ => FenSquare.Occupied(FenColor.Black, FenPieceSymbol.King)) |
        "q".!.map(_ => FenSquare.Occupied(FenColor.Black, FenPieceSymbol.Queen)) |
        "r".!.map(_ => FenSquare.Occupied(FenColor.Black, FenPieceSymbol.Rook)) |
        "b".!.map(_ => FenSquare.Occupied(FenColor.Black, FenPieceSymbol.Bishop)) |
        "n".!.map(_ => FenSquare.Occupied(FenColor.Black, FenPieceSymbol.Knight)) |
        "p".!.map(_ => FenSquare.Occupied(FenColor.Black, FenPieceSymbol.Pawn))
    )

  // ── Active color ──────────────────────────────────────────────────────────

  private def activeColor[$: P]: P[FenColor] =
    P(
      "w".!.map(_ => FenColor.White) |
        "b".!.map(_ => FenColor.Black)
    )

  // ── Castling ──────────────────────────────────────────────────────────────

  private def castling[$: P]: P[FenCastlingAvailability] =
    P(
      "-".map(_ => FenCastlingAvailability.none) |
        CharsWhileIn("KQkq", min = 1).!.flatMap { s =>
          if s.distinct.length == s.length then Pass.map(_ => castlingFromString(s))
          else Fail.opaque(s"castling field '$s' contains duplicate symbols")
        }
    )

  private def castlingFromString(s: String): FenCastlingAvailability =
    FenCastlingAvailability(
      whiteKingSide = s.contains('K'),
      whiteQueenSide = s.contains('Q'),
      blackKingSide = s.contains('k'),
      blackQueenSide = s.contains('q')
    )

  // ── En passant ────────────────────────────────────────────────────────────

  private def enPassant[$: P]: P[FenEnPassantTarget] =
    P(
      "-".map(_ => FenEnPassantTarget.Absent) |
        (CharIn("a-h").! ~ CharIn("1-8").!).map { case (file, rank) =>
          FenEnPassantTarget.Square(
            file = file.head - 'a',
            rank = rank.head - '1'
          )
        }
    )

  // ── Numeric fields ────────────────────────────────────────────────────────

  private def halfmoveClock[$: P]: P[Int] =
    P(CharsWhileIn("0-9", min = 1).!).map(_.toInt)

  private def fullmoveNumber[$: P]: P[Int] =
    P(CharsWhileIn("0-9", min = 1).!).flatMap { s =>
      val n = s.toInt
      if n >= 1 then Pass.map(_ => n)
      else Fail.opaque(s"fullmove number must be a positive integer: '$s'")
    }
