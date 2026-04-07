package chess.notation.fen

import chess.domain.model.{Color, PieceType}
import chess.domain.state.{CastlingRights, EnPassantState, GameState}
import chess.notation.api.{ExportFailure, ExportResult, NotationExporter, NotationFailure, NotationFormat, NotationWarning}

/** Pure FEN serializer.
 *
 *  Converts a [[GameState]] snapshot to a six-field FEN string.
 *
 *  Implements [[NotationExporter]] for [[GameState]] and rejects
 *  all [[NotationFormat]] values other than [[NotationFormat.FEN]].
 *
 *  Field order:
 *  {{{
 *  <piece-placement> <active-color> <castling> <en-passant> <halfmove> <fullmove>
 *  }}}
 *
 *  Clock fields: `halfmoveClock` and `fullmoveNumber` are read directly
 *  from [[GameState]].  The FEN import path writes them, so round-tripping
 *  a position through FEN will preserve the correct values.
 */
object FenSerializer extends NotationExporter[GameState]:

  def exportNotation(state: GameState, format: NotationFormat): Either[NotationFailure, ExportResult] =
    format match
      case NotationFormat.FEN =>
        val fen = serialize(state)
        Right(ExportResult(text = fen, format = NotationFormat.FEN))
      case other =>
        Left(ExportFailure.UnsupportedExportFormat(other, s"FenSerializer only supports FEN; got $other"))

  // ── Top-level serialization ─────────────────────────────────────────────────

  private[fen] def serialize(state: GameState): String =
    val fields = Seq(
      serializePiecePlacement(state),
      serializeActiveColor(state.currentPlayer),
      serializeCastlingRights(state.castlingRights),
      serializeEnPassant(state.enPassantState),
      state.halfmoveClock.toString,
      state.fullmoveNumber.toString
    )
    fields.mkString(" ")

  // ── Field serializers ───────────────────────────────────────────────────────

  /** Serialize piece placement, rank 8 → rank 1, file a → h.
   *
   *  Consecutive empty squares are compressed to a digit (1–8).
   *  Ranks are separated by `/`.
   */
  private[fen] def serializePiecePlacement(state: GameState): String =
    (7 to 0 by -1).map { rank =>
      val sb     = new StringBuilder
      var empty  = 0
      for file <- 0 to 7 do
        state.board.pieceAt(chess.domain.model.Position.from(file, rank).toOption.get) match
          case Some(piece) =>
            if empty > 0 then sb.append(empty.toString); empty = 0
            sb.append(pieceChar(piece.color, piece.pieceType))
          case None =>
            empty += 1
      if empty > 0 then sb.append(empty.toString)
      sb.toString
    }.mkString("/")

  private[fen] def serializeActiveColor(color: chess.domain.model.Color): String =
    color match
      case Color.White => "w"
      case Color.Black => "b"

  /** Serialize castling rights in standard FEN order: KQkq.
   *
   *  Returns `-` when no rights are available.
   */
  private[fen] def serializeCastlingRights(rights: CastlingRights): String =
    val sb = new StringBuilder
    if rights.whiteKingSide  then sb.append('K')
    if rights.whiteQueenSide then sb.append('Q')
    if rights.blackKingSide  then sb.append('k')
    if rights.blackQueenSide then sb.append('q')
    if sb.isEmpty then "-" else sb.toString

  /** Serialize en passant target.
   *
   *  Uses [[EnPassantState.targetSquare]], the square a capturing pawn moves
   *  into (not the square of the captured pawn).  Returns `-` if absent.
   */
  private[fen] def serializeEnPassant(ep: Option[EnPassantState]): String =
    ep match
      case Some(state) => state.targetSquare.toString
      case None        => "-"

  // ── Piece character ─────────────────────────────────────────────────────────

  /** FEN piece letter: uppercase for White, lowercase for Black. */
  private def pieceChar(color: Color, pieceType: PieceType): Char =
    val letter = pieceType match
      case PieceType.King   => 'K'
      case PieceType.Queen  => 'Q'
      case PieceType.Rook   => 'R'
      case PieceType.Bishop => 'B'
      case PieceType.Knight => 'N'
      case PieceType.Pawn   => 'P'
    if color == Color.White then letter else letter.toLower
