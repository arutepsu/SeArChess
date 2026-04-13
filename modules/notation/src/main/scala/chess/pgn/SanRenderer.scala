package chess.notation.pgn

import chess.domain.model.{GameStatus, Move, Piece, PieceType}
import chess.domain.rules.GameStateRules
import chess.domain.state.GameState
import chess.notation.api.{ExportFailure, NotationFailure}

/** Renders a single [[Move]] to its SAN string given the board state immediately
 *  before the move is played.
 *
 *  The pre-move state is required to:
 *  - identify the moving piece type and color
 *  - detect captures (normal and en passant)
 *  - compute disambiguation when multiple same-type pieces can reach the destination
 *  - produce the check/checkmate suffix (needs post-move state)
 *
 *  Handles:
 *  - pawn pushes and captures (with optional promotion suffix `=Q` etc.)
 *  - piece moves with file/rank/full disambiguation
 *  - castling (`O-O`, `O-O-O`)
 *  - check (`+`) and checkmate (`#`) suffixes
 *
 *  Visible only within the `chess.notation.pgn` package.
 */
private[pgn] object SanRenderer:

  /** Render `move` to its SAN token in the context of `stateBefore`.
   *
   *  @param stateBefore the [[GameState]] immediately before `move` is applied
   *  @param move        the move to render
   *  @return the SAN string on success, or a [[NotationFailure]] when the
   *          pre-move state is inconsistent with the move (e.g. no piece at
   *          `move.from`, or `applyMove` fails unexpectedly)
   */
  def render(stateBefore: GameState, move: Move): Either[NotationFailure, String] =
    stateBefore.board.pieceAt(move.from) match
      case None =>
        Left(ExportFailure.SerializationError(
          "move.from",
          s"No piece at ${move.from} in pre-move state; move history may be inconsistent"
        ))
      case Some(piece) =>
        val base = buildBase(stateBefore, move, piece)
        GameStateRules.applyMove(stateBefore, move).left.map { err =>
          ExportFailure.SerializationError(
            "move",
            s"Cannot apply $move during SAN rendering: $err"
          )
        }.map { postState =>
          base + checkSuffix(postState)
        }

  // ── Base SAN (without check suffix) ────────────────────────────────────────

  private def buildBase(state: GameState, move: Move, piece: Piece): String =
    if isCastling(piece, move) then
      if move.to.file > move.from.file then "O-O" else "O-O-O"
    else
      val isCapture = SanResolver.isCapturingMove(state, move)
      val dest      = move.to.toString  // algebraic, e.g. "e4"
      val promo     = move.promotion.map(pt => s"=${pieceChar(pt)}").getOrElse("")
      piece.pieceType match
        case PieceType.Pawn =>
          val capturePrefix = if isCapture then s"${fileChar(move.from.file)}x" else ""
          s"$capturePrefix$dest$promo"
        case pt =>
          val letter    = pieceChar(pt)
          val captureX  = if isCapture then "x" else ""
          val disambig  = disambiguate(state, move, pt)
          s"$letter$disambig$captureX$dest"

  // ── Helpers ─────────────────────────────────────────────────────────────────

  private def isCastling(piece: Piece, move: Move): Boolean =
    piece.pieceType == PieceType.King && math.abs(move.to.file - move.from.file) == 2

  /** Disambiguation string for piece moves (empty when unambiguous). */
  private def disambiguate(state: GameState, move: Move, pieceType: PieceType): String =
    // Other same-type pieces (current player's color, since legalMoves filters by currentPlayer)
    // that can also reach the same destination.
    val rivals = GameStateRules.legalMoves(state).filter { m =>
      m.to == move.to &&
      m.from != move.from &&
      state.board.pieceAt(m.from).exists(_.pieceType == pieceType)
    }
    if rivals.isEmpty then ""
    else if rivals.forall(_.from.file != move.from.file) then
      // Our file is unique among all candidates → file letter suffices
      s"${fileChar(move.from.file)}"
    else if rivals.forall(_.from.rank != move.from.rank) then
      // Our rank is unique → rank digit suffices
      s"${move.from.rank + 1}"
    else
      // Full square needed
      move.from.toString

  private def checkSuffix(postState: GameState): String =
    postState.status match
      case GameStatus.Checkmate(_)   => "#"
      case GameStatus.Ongoing(true)  => "+"
      case _                         => ""

  private def pieceChar(pt: PieceType): String = pt match
    case PieceType.King   => "K"
    case PieceType.Queen  => "Q"
    case PieceType.Rook   => "R"
    case PieceType.Bishop => "B"
    case PieceType.Knight => "N"
    case PieceType.Pawn   => ""

  private def fileChar(file: Int): Char = ('a' + file).toChar
