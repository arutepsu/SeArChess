package chess.domain.model

import munit.FunSuite

class BoardSpec extends FunSuite:

  private val pos   = Position.from(0, 0).getOrElse(fail("setup failed"))
  private val pos2  = Position.from(1, 0).getOrElse(fail("setup failed"))
  private val piece = Piece(Color.White, PieceType.Pawn)
  private val other = Piece(Color.Black, PieceType.Rook)

  // ── Board.empty ────────────────────────────────────────────────────────────

  test("Board.empty has no pieces"):
    assertEquals(Board.empty.pieceAt(pos), None)

  // ── Board.place ────────────────────────────────────────────────────────────

  test("Board.place puts a piece at the given position"):
    val board = Board.empty.place(pos, piece)
    assertEquals(board.pieceAt(pos), Some(piece))

  test("Board.place does not affect other squares"):
    val board = Board.empty.place(pos, piece)
    assertEquals(board.pieceAt(pos2), None)

  test("Board.place overwrites an existing piece"):
    val board = Board.empty.place(pos, piece).place(pos, other)
    assertEquals(board.pieceAt(pos), Some(other))

  test("Board.place is immutable: original board unchanged"):
    val original = Board.empty
    val _        = original.place(pos, piece)
    assertEquals(original.pieceAt(pos), None)

  // ── Board.remove ───────────────────────────────────────────────────────────

  test("Board.remove clears the piece at a position"):
    val board = Board.empty.place(pos, piece).remove(pos)
    assertEquals(board.pieceAt(pos), None)

  test("Board.remove on empty square returns same empty square"):
    val board = Board.empty.remove(pos)
    assertEquals(board.pieceAt(pos), None)

  test("Board.remove does not affect other squares"):
    val board = Board.empty.place(pos, piece).place(pos2, other).remove(pos)
    assertEquals(board.pieceAt(pos2), Some(other))

  // ── Board.pieceAt ──────────────────────────────────────────────────────────

  test("Board.pieceAt returns None on empty board"):
    assertEquals(Board.empty.pieceAt(pos), None)

  test("Board.pieceAt returns Some(piece) after placement"):
    val board = Board.empty.place(pos, piece)
    assertEquals(board.pieceAt(pos), Some(piece))
