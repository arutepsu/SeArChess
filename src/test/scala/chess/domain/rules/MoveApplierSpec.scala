package chess.domain.rules

import munit.FunSuite
import chess.domain.error.DomainError
import chess.domain.model.*

class MoveApplierSpec extends FunSuite:

  private val from  = Position.from(0, 0).getOrElse(fail("setup failed"))
  private val to    = Position.from(1, 0).getOrElse(fail("setup failed"))
  private val piece = Piece(Color.White, PieceType.Pawn)
  private val enemy = Piece(Color.Black, PieceType.Rook)

  // ── success ────────────────────────────────────────────────────────────────

  test("applyMove moves piece from source to target"):
    val board  = Board.empty.place(from, piece)
    val result = MoveApplier.applyMove(board, Move(from, to))
    result match
      case Right(b) =>
        assertEquals(b.pieceAt(to),   Some(piece))
        assertEquals(b.pieceAt(from), None)
      case Left(err) => fail(s"unexpected error: $err")

  test("applyMove overwrites an occupied target square"):
    val board  = Board.empty.place(from, piece).place(to, enemy)
    val result = MoveApplier.applyMove(board, Move(from, to))
    result match
      case Right(b) =>
        assertEquals(b.pieceAt(to),   Some(piece))
        assertEquals(b.pieceAt(from), None)
      case Left(err) => fail(s"unexpected error: $err")

  test("applyMove does not mutate the original board"):
    val original = Board.empty.place(from, piece)
    val _        = MoveApplier.applyMove(original, Move(from, to))
    assertEquals(original.pieceAt(from), Some(piece))

  // ── failure ────────────────────────────────────────────────────────────────

  test("applyMove fails with EmptySourceSquare when source is empty"):
    val result = MoveApplier.applyMove(Board.empty, Move(from, to))
    result match
      case Left(DomainError.EmptySourceSquare(_)) => ()
      case other => fail(s"unexpected: $other")

  test("applyMove EmptySourceSquare contains the source label"):
    val result = MoveApplier.applyMove(Board.empty, Move(from, to))
    result match
      case Left(DomainError.EmptySourceSquare(label)) =>
        assertEquals(label, from.toString)
      case other => fail(s"unexpected: $other")
