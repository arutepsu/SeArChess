package chess.domain.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.EitherValues
import chess.domain.error.DomainError

class PositionSpec extends AnyFlatSpec with Matchers with EitherValues:

  // ── Position.from ──────────────────────────────────────────────────────────

  "Position.from" should "accept all corners of the valid range" in {
    Position.from(0, 0).isRight shouldBe true
    Position.from(7, 7).isRight shouldBe true
    Position.from(0, 7).isRight shouldBe true
    Position.from(7, 0).isRight shouldBe true
  }

  it should "preserve file and rank" in {
    val pos = Position.from(4, 1).value
    pos.file shouldBe 4
    pos.rank shouldBe 1
  }

  it should "reject a negative file" in {
    Position.from(-1, 0).isLeft shouldBe true
  }

  it should "reject a negative rank" in {
    Position.from(0, -1).isLeft shouldBe true
  }

  it should "reject file > 7" in {
    Position.from(8, 0).isLeft shouldBe true
  }

  it should "reject rank > 7" in {
    Position.from(0, 8).isLeft shouldBe true
  }

  it should "return OutOfBounds for invalid coordinates" in {
    Position.from(9, 9).left.value shouldBe DomainError.OutOfBounds(9, 9)
  }

  // ── Position.fromAlgebraic ─────────────────────────────────────────────────

  "Position.fromAlgebraic" should "accept 'a1'" in {
    val pos = Position.fromAlgebraic("a1").value
    pos.file shouldBe 0
    pos.rank shouldBe 0
  }

  it should "accept 'e2'" in {
    val pos = Position.fromAlgebraic("e2").value
    pos.file shouldBe 4
    pos.rank shouldBe 1
  }

  it should "accept 'h8'" in {
    val pos = Position.fromAlgebraic("h8").value
    pos.file shouldBe 7
    pos.rank shouldBe 7
  }

  it should "reject 'i9' (file out of range)" in {
    Position.fromAlgebraic("i9").isLeft shouldBe true
  }

  it should "reject '22' (no letter file)" in {
    Position.fromAlgebraic("22").isLeft shouldBe true
  }

  it should "reject an empty string" in {
    Position.fromAlgebraic("").isLeft shouldBe true
  }

  it should "reject 'abc' (too long)" in {
    Position.fromAlgebraic("abc").isLeft shouldBe true
  }

  it should "reject 'a9' (rank out of range)" in {
    Position.fromAlgebraic("a9").isLeft shouldBe true
  }

  it should "return InvalidPositionString for a bad format" in {
    Position.fromAlgebraic("!!").left.value shouldBe DomainError.InvalidPositionString("!!")
  }

  // ── toString ───────────────────────────────────────────────────────────────

  "Position.toString" should "return the algebraic label" in {
    Position.from(4, 1).value.toString shouldBe "e2"
  }
