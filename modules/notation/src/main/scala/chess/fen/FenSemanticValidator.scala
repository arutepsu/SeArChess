package chess.notation.fen

import chess.notation.api.{FenData, ValidationFailure}

/** Validates semantic chess constraints on a [[FenData]].
  *
  * Operates on the structured model produced by [[FenParser]]. All checks are pure functions; no
  * board state or domain objects are created here.
  *
  * Validation scope:
  *   - king counts (exactly one of each color)
  *   - castling rights consistency with king and rook placement
  *   - en passant rank plausibility relative to the active color
  *
  * Intentionally NOT validated here:
  *   - full position reachability or retrograde legality
  *   - whether the non-active side's king is currently in check
  *   - piece count plausibility (too many pawns, etc.)
  */
object FenSemanticValidator:

  def validate(data: FenData): Either[ValidationFailure, Unit] =
    for
      _ <- validateKingCount(data.ranks, FenData.ActiveColor.White)
      _ <- validateKingCount(data.ranks, FenData.ActiveColor.Black)
      _ <- validateCastlingRights(data.ranks, data.castling)
      _ <- validateEnPassant(data.enPassant, data.activeColor)
    yield ()

  // ── King count ───────────────────────────────────────────────────────────────

  private def validateKingCount(
      ranks: Vector[Vector[FenData.Square]],
      color: FenData.ActiveColor
  ): Either[ValidationFailure, Unit] =
    val fieldName = if color == FenData.ActiveColor.White then "white king" else "black king"
    val colorLabel = if color == FenData.ActiveColor.White then "white" else "black"
    countPieces(ranks, color, FenData.PieceSymbol.King) match
      case 1 => Right(())
      case 0 =>
        Left(
          ValidationFailure.MissingRequired(
            fieldName,
            s"Exactly one $colorLabel king is required; none found"
          )
        )
      case n =>
        Left(
          ValidationFailure.InvalidValue(
            fieldName,
            n.toString,
            s"Exactly one $colorLabel king is required; found $n"
          )
        )

  // ── Castling rights ──────────────────────────────────────────────────────────

  /** Castling rights are only consistent when the king and the corresponding rook are both on their
    * original squares. Standard FEN uses fixed home squares; Chess960 / X-FEN are out of scope for
    * this validator.
    */
  private def validateCastlingRights(
      ranks: Vector[Vector[FenData.Square]],
      castling: FenData.CastlingAvailability
  ): Either[ValidationFailure, Unit] =
    for
      _ <-
        if castling.whiteKingSide then
          validateCastlingRight(ranks, "K", 4, 0, 7, 0, FenData.ActiveColor.White)
        else Right(())
      _ <-
        if castling.whiteQueenSide then
          validateCastlingRight(ranks, "Q", 4, 0, 0, 0, FenData.ActiveColor.White)
        else Right(())
      _ <-
        if castling.blackKingSide then
          validateCastlingRight(ranks, "k", 4, 7, 7, 7, FenData.ActiveColor.Black)
        else Right(())
      _ <-
        if castling.blackQueenSide then
          validateCastlingRight(ranks, "q", 4, 7, 0, 7, FenData.ActiveColor.Black)
        else Right(())
    yield ()

  /** Verify that both the king and rook are on their required home squares for the given castling
    * right. King is checked first; only if the king is present is the rook square inspected.
    *
    * @param kingFile
    *   0-based file of the expected king square (always 4 = 'e')
    * @param kingRank
    *   0-based rank of the expected king square (0 = rank 1, 7 = rank 8)
    * @param rookFile
    *   0-based file of the expected rook square (0 = 'a', 7 = 'h')
    * @param rookRank
    *   0-based rank of the expected rook square
    */
  private def validateCastlingRight(
      ranks: Vector[Vector[FenData.Square]],
      rightLabel: String,
      kingFile: Int,
      kingRank: Int,
      rookFile: Int,
      rookRank: Int,
      color: FenData.ActiveColor
  ): Either[ValidationFailure, Unit] =
    val colorLabel = if color == FenData.ActiveColor.White then "white" else "black"
    if !isOccupiedBy(ranks, kingFile, kingRank, color, FenData.PieceSymbol.King) then
      Left(
        ValidationFailure.InvalidValue(
          "castlingRights",
          rightLabel,
          s"Castling right '$rightLabel' declared but $colorLabel king is not on the required square"
        )
      )
    else if !isOccupiedBy(ranks, rookFile, rookRank, color, FenData.PieceSymbol.Rook) then
      Left(
        ValidationFailure.InvalidValue(
          "castlingRights",
          rightLabel,
          s"Castling right '$rightLabel' declared but $colorLabel rook is not on the required square"
        )
      )
    else Right(())

  // ── En passant ───────────────────────────────────────────────────────────────

  /** Validate that the en passant target rank is consistent with the active color.
    *
    *   - When White is to move, the previous move was Black's two-square pawn advance, so the
    *     target must be on rank 6 (0-based rank 5 = '6').
    *   - When Black is to move, the previous move was White's two-square pawn advance, so the
    *     target must be on rank 3 (0-based rank 2 = '3').
    *
    * Full move-history proof is intentionally out of scope.
    */
  private def validateEnPassant(
      ep: FenData.EnPassantTarget,
      activeColor: FenData.ActiveColor
  ): Either[ValidationFailure, Unit] =
    ep match
      case FenData.EnPassantTarget.Absent => Right(())
      case FenData.EnPassantTarget.Square(_, rank) =>
        val expectedRank = if activeColor == FenData.ActiveColor.White then 5 else 2
        if rank == expectedRank then Right(())
        else
          Left(
            ValidationFailure.InvalidValue(
              "enPassant.rank",
              (rank + 1).toString,
              s"En passant target rank '${rank + 1}' is inconsistent with active color " +
                s"${if activeColor == FenData.ActiveColor.White then "White" else "Black"}; " +
                s"expected rank ${expectedRank + 1}"
            )
          )

  // ── Helpers ──────────────────────────────────────────────────────────────────

  private def countPieces(
      ranks: Vector[Vector[FenData.Square]],
      color: FenData.ActiveColor,
      symbol: FenData.PieceSymbol
  ): Int =
    ranks.iterator.flatten.count {
      case FenData.Square.Occupied(c, s) => c == color && s == symbol
      case FenData.Square.Empty          => false
    }

  private def isOccupiedBy(
      ranks: Vector[Vector[FenData.Square]],
      file: Int,
      rank: Int,
      color: FenData.ActiveColor,
      symbol: FenData.PieceSymbol
  ): Boolean =
    squareAt(ranks, file, rank) match
      case FenData.Square.Occupied(c, s) => c == color && s == symbol
      case FenData.Square.Empty          => false

  /** Access a square by 0-based board coordinates.
    *
    * FenData ranks are stored from FEN rank 8 (index 0) to rank 1 (index 7), so board rank `r` (0 =
    * rank '1') maps to `ranks(7 - r)`.
    */
  private def squareAt(
      ranks: Vector[Vector[FenData.Square]],
      file: Int,
      rank: Int
  ): FenData.Square =
    ranks(7 - rank)(file)
