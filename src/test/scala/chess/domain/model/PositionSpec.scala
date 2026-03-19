package chess.domain.model

import munit.FunSuite
import chess.domain.error.DomainError

class PositionSpec extends FunSuite:

  // ── Position.from ──────────────────────────────────────────────────────────

  test("Position.from accepts all corners of the valid range"):
    assert(Position.from(0, 0).isRight)
    assert(Position.from(7, 7).isRight)
    assert(Position.from(0, 7).isRight)
    assert(Position.from(7, 0).isRight)

  test("Position.from preserves file and rank"):
    val pos = Position.from(4, 1).getOrElse(fail("expected Right"))
    assertEquals(pos.file, 4)
    assertEquals(pos.rank, 1)

  test("Position.from rejects negative file"):
    assert(Position.from(-1, 0).isLeft)

  test("Position.from rejects negative rank"):
    assert(Position.from(0, -1).isLeft)

  test("Position.from rejects file > 7"):
    assert(Position.from(8, 0).isLeft)

  test("Position.from rejects rank > 7"):
    assert(Position.from(0, 8).isLeft)

  test("Position.from returns OutOfBounds error"):
    Position.from(9, 9) match
      case Left(DomainError.OutOfBounds(9, 9)) => ()
      case other => fail(s"unexpected: $other")

  // ── Position.fromAlgebraic ─────────────────────────────────────────────────

  test("Position.fromAlgebraic accepts 'a1'"):
    val pos = Position.fromAlgebraic("a1").getOrElse(fail("expected Right"))
    assertEquals(pos.file, 0)
    assertEquals(pos.rank, 0)

  test("Position.fromAlgebraic accepts 'e2'"):
    val pos = Position.fromAlgebraic("e2").getOrElse(fail("expected Right"))
    assertEquals(pos.file, 4)
    assertEquals(pos.rank, 1)

  test("Position.fromAlgebraic accepts 'h8'"):
    val pos = Position.fromAlgebraic("h8").getOrElse(fail("expected Right"))
    assertEquals(pos.file, 7)
    assertEquals(pos.rank, 7)

  test("Position.fromAlgebraic rejects 'i9' (out of range)"):
    assert(Position.fromAlgebraic("i9").isLeft)

  test("Position.fromAlgebraic rejects '22' (no letter file)"):
    assert(Position.fromAlgebraic("22").isLeft)

  test("Position.fromAlgebraic rejects empty string"):
    assert(Position.fromAlgebraic("").isLeft)

  test("Position.fromAlgebraic rejects 'abc' (too long)"):
    assert(Position.fromAlgebraic("abc").isLeft)

  test("Position.fromAlgebraic rejects 'a9' (rank out of range)"):
    assert(Position.fromAlgebraic("a9").isLeft)

  test("Position.fromAlgebraic returns InvalidPositionString error"):
    Position.fromAlgebraic("!!") match
      case Left(DomainError.InvalidPositionString("!!")) => ()
      case other => fail(s"unexpected: $other")

  // ── toString ───────────────────────────────────────────────────────────────

  test("Position.toString returns algebraic label"):
    val pos = Position.from(4, 1).getOrElse(fail("expected Right"))
    assertEquals(pos.toString, "e2")
