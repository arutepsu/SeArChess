package chess.notation.fen

import chess.notation.api.ParseFailure

/** Splits a raw FEN string into its six space-separated fields.
 *
 *  Performs only structural splitting — no per-field syntax checking.
 *  Fails immediately if the input is empty or the field count is not exactly 6.
 *
 *  Multiple adjacent spaces produce extra empty tokens, so they are correctly
 *  detected as a wrong field count rather than silently skipped.
 */
object FenTokenizer:

  /** The six raw string fields of a FEN record, in their FEN-defined order. */
  final case class FenTokens(
    piecePlacement: String,
    activeColor:    String,
    castling:       String,
    enPassant:      String,
    halfmoveClock:  String,
    fullmoveNumber: String
  )

  def tokenize(input: String): Either[ParseFailure, FenTokens] =
    if input.isEmpty then
      Left(ParseFailure.UnexpectedEndOfInput("FEN string is empty"))
    else
      val fields = input.split(" ", -1)
      if fields.length == 6 then
        Right(FenTokens(fields(0), fields(1), fields(2), fields(3), fields(4), fields(5)))
      else
        Left(ParseFailure.StructuralError(
          s"FEN requires exactly 6 space-separated fields; got ${fields.length}"
        ))
