package chess.notation.fen

import chess.notation.api.FenData
import chess.domain.model.{Board, Color, Piece, PieceType, Position}
import chess.domain.state.{CastlingRights, EnPassantState, GameState}
import chess.domain.rules.evaluation.GameStatusEvaluator

/** Maps a semantically validated [[FenData]] into a domain [[GameState]].
 *
 *  All methods are pure: no side effects, no board rule logic, no domain
 *  validation.  Semantic validation must happen in [[FenSemanticValidator]]
 *  before this mapper is called.
 *
 *  Mapping policy:
 *  - `board`, `currentPlayer`, `castlingRights`, `enPassantState` — derived from FenData
 *  - `status`          — computed by [[GameStatusEvaluator]] to reflect the real
 *                        game state (check / checkmate / stalemate / ongoing)
 *  - `halfmoveClock`   — threaded directly from FenData
 *  - `fullmoveNumber`  — threaded directly from FenData
 *  - `moveHistory`     — always `Nil`; FEN does not encode game history
 */
object FenToGameStateMapper:

  def map(data: FenData): GameState =
    val board          = buildBoard(data.ranks)
    val currentPlayer  = mapColor(data.activeColor)
    val castlingRights = mapCastlingRights(data.castling)
    val enPassantState = mapEnPassant(data.enPassant, data.activeColor)
    val status         = GameStatusEvaluator.evaluate(board, currentPlayer, castlingRights, enPassantState)
    GameState(
      board          = board,
      currentPlayer  = currentPlayer,
      moveHistory    = Nil,
      status         = status,
      castlingRights = castlingRights,
      enPassantState = enPassantState,
      halfmoveClock  = data.halfmoveClock,
      fullmoveNumber = data.fullmoveNumber
    )

  // ── Board construction ───────────────────────────────────────────────────────

  /** Build a domain [[Board]] from the FEN rank vectors.
   *
   *  FEN rank ordering: `ranks(0)` = rank 8 (0-based rank 7), `ranks(7)` = rank 1
   *  (0-based rank 0).  Within each rank, `squares(fileIdx)` = file `fileIdx`.
   */
  private def buildBoard(ranks: Vector[Vector[FenData.Square]]): Board =
    ranks.zipWithIndex.foldLeft(Board.empty) { case (board, (rankSquares, fenIdx)) =>
      val boardRank = 7 - fenIdx
      rankSquares.zipWithIndex.foldLeft(board) { case (b, (square, fileIdx)) =>
        square match
          case FenData.Square.Empty => b
          case FenData.Square.Occupied(color, symbol) =>
            Position.from(fileIdx, boardRank).toOption.fold(b)(pos =>
              b.place(pos, Piece(mapColor(color), mapPieceSymbol(symbol)))
            )
      }
    }

  // ── Field mappers ────────────────────────────────────────────────────────────

  private def mapColor(c: FenData.ActiveColor): Color = c match
    case FenData.ActiveColor.White => Color.White
    case FenData.ActiveColor.Black => Color.Black

  private def mapPieceSymbol(s: FenData.PieceSymbol): PieceType = s match
    case FenData.PieceSymbol.King   => PieceType.King
    case FenData.PieceSymbol.Queen  => PieceType.Queen
    case FenData.PieceSymbol.Rook   => PieceType.Rook
    case FenData.PieceSymbol.Bishop => PieceType.Bishop
    case FenData.PieceSymbol.Knight => PieceType.Knight
    case FenData.PieceSymbol.Pawn   => PieceType.Pawn

  private def mapCastlingRights(c: FenData.CastlingAvailability): CastlingRights =
    CastlingRights(
      whiteKingSide  = c.whiteKingSide,
      whiteQueenSide = c.whiteQueenSide,
      blackKingSide  = c.blackKingSide,
      blackQueenSide = c.blackQueenSide
    )

  /** Map the FEN en passant field to a domain [[EnPassantState]].
   *
   *  The FEN target square is the empty square the capturing pawn moves INTO.
   *  The capturable pawn occupies the adjacent square in the opposite direction:
   *  - When White is active (White captures): Black's pawn is one rank below the
   *    target (rank - 1 in 0-based).
   *  - When Black is active (Black captures): White's pawn is one rank above the
   *    target (rank + 1 in 0-based).
   */
  private def mapEnPassant(ep: FenData.EnPassantTarget, activeColor: FenData.ActiveColor): Option[EnPassantState] =
    ep match
      case FenData.EnPassantTarget.Absent => None
      case FenData.EnPassantTarget.Square(file, rank) =>
        val capturablePawnRank = if activeColor == FenData.ActiveColor.White then rank - 1 else rank + 1
        val pawnColor          = if activeColor == FenData.ActiveColor.White then Color.Black else Color.White
        for
          targetPos <- Position.from(file, rank).toOption
          capPos    <- Position.from(file, capturablePawnRank).toOption
        yield EnPassantState(targetPos, capPos, pawnColor)
