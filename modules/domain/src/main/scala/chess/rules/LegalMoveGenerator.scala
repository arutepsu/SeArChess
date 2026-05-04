package chess.domain.rules

import chess.domain.model.{Move, Piece, PieceType, Position}
import chess.domain.state.GameState
import chess.domain.rules.application.MoveApplier
import chess.domain.model.MoveResult

/** Generates all legal moves for a position.
  *
  * Promotion moves are expanded: a pawn move to the last rank yields four canonical moves (one for
  * each promotable piece type), rather than one incomplete move with no promotion piece.
  */
object LegalMoveGenerator:

  private val promotionPieces: List[PieceType] =
    List(PieceType.Queen, PieceType.Rook, PieceType.Bishop, PieceType.Knight)

  private val knightOffsets: List[(Int, Int)] =
    List((1, 2), (2, 1), (2, -1), (1, -2), (-1, -2), (-2, -1), (-2, 1), (-1, 2))

  private val kingOffsets: List[(Int, Int)] =
    (for dx <- -1 to 1; dy <- -1 to 1 if !(dx == 0 && dy == 0) yield (dx, dy)).toList

  private val diagonalDirections: List[(Int, Int)] =
    List((1, 1), (1, -1), (-1, 1), (-1, -1))

  private val orthogonalDirections: List[(Int, Int)] =
    List((1, 0), (-1, 0), (0, 1), (0, -1))

  private val queenDirections: List[(Int, Int)] =
    diagonalDirections ++ orthogonalDirections

  private def rayTargets(
      board: chess.domain.model.Board,
      from: Position,
      direction: (Int, Int)
  ): List[Position] =
    @annotation.tailrec
    def loop(file: Int, rank: Int, acc: List[Position]): List[Position] =
      Position.from(file, rank).toOption match
        case Some(position) if board.pieceAt(position).isEmpty =>
          loop(file + direction._1, rank + direction._2, position :: acc)
        case Some(position) =>
          position :: acc
        case None =>
          acc.reverse

    loop(from.file + direction._1, from.rank + direction._2, Nil)

  /** Generate candidate target squares for a piece at `from` without heavy validation. This
    * minimizes the number of times we call the expensive `MoveApplier.applyMove` by only producing
    * plausible destination squares for the given piece type.
    */
  private def candidateTargets(state: GameState, from: Position, piece: Piece): Seq[Position] =
    val board = state.board

    def posOpt(file: Int, rank: Int) = Position.from(file, rank).toOption

    piece.pieceType match
      case PieceType.Pawn =>
        val dir = if piece.color == chess.domain.model.Color.White then 1 else -1
        val oneForward = posOpt(from.file, from.rank + dir)
        val twoForward = posOpt(from.file, from.rank + 2 * dir)

        val forwards =
          oneForward match
            case Some(p1) if board.pieceAt(p1).isEmpty =>
              twoForward match
                case Some(p2)
                    if (if piece.color == chess.domain.model.Color.White then from.rank == 1
                        else from.rank == 6) && board.pieceAt(p2).isEmpty =>
                  Seq(p1, p2)
                case _ => Seq(p1)
            case _ => Seq.empty

        // captures: diagonal left/right. Include even if target empty because en-passant may apply.
        val diagOffsets = Seq(-1, 1)
        val captures =
          diagOffsets.flatMap { dx =>
            posOpt(from.file + dx, from.rank + dir) match
              case Some(p) => Some(p)
              case None    => None
          }

        forwards ++ captures

      case PieceType.Knight =>
        knightOffsets.flatMap { case (dx, dy) => posOpt(from.file + dx, from.rank + dy) }

      case PieceType.King =>
        val normal = kingOffsets.flatMap { case (dx, dy) => posOpt(from.file + dx, from.rank + dy) }
        // include potential castling target squares (two squares left/right) — validation happens in MoveApplier
        val castleTargets =
          Seq(posOpt(from.file + 2, from.rank), posOpt(from.file - 2, from.rank)).flatten
        normal ++ castleTargets

      case PieceType.Bishop | PieceType.Rook | PieceType.Queen =>
        val directions = piece.pieceType match
          case PieceType.Bishop => diagonalDirections
          case PieceType.Rook   => orthogonalDirections
          case PieceType.Queen  => queenDirections
          case _                => Nil

        directions.flatMap(rayTargets(board, from, _))

  /** All legal moves from `from` in the current game state.
    *
    * Promotion moves are expanded into four moves (Q / R / B / N). Returns an empty set if there is
    * no current-player piece at `from`.
    */
  def legalMovesFrom(state: GameState, from: Position): Set[Move] =
    state.board.pieceAt(from) match
      case Some(piece) if piece.color == state.currentPlayer =>
        // only evaluate plausible targets instead of trying all 64 squares
        candidateTargets(state, from, piece).flatMap { to =>
          MoveApplier.applyMove(
            state.board,
            Move(from, to),
            state.castlingRights,
            state.enPassantState
          ) match
            case Right(MoveResult.PromotionRequired(_, _, _)) =>
              promotionPieces.map(pt => Move(from, to, Some(pt)))
            case Right(MoveResult.Applied(_)) =>
              List(Move(from, to))
            case Left(_) =>
              List.empty
        }.toSet
      case _ => Set.empty

  /** All legal target squares from `from` (without promotion expansion). */
  def legalTargetsFrom(state: GameState, from: Position): Set[Position] =
    state.board.pieceAt(from) match
      case Some(piece) if piece.color == state.currentPlayer =>
        candidateTargets(state, from, piece).filter { to =>
          MoveApplier
            .applyMove(state.board, Move(from, to), state.castlingRights, state.enPassantState)
            .isRight
        }.toSet
      case _ => Set.empty
