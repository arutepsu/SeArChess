package chess.adapter.gui.animation

import chess.domain.model.PieceType
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MotionStyleResolverSpec extends AnyFlatSpec with Matchers:

  "MotionStyleResolver.resolve" should "assign Linear to Pawn" in {
    MotionStyleResolver.resolve(PieceType.Pawn) shouldBe MotionStyle.Linear
  }

  it should "assign Heavy to Rook" in {
    MotionStyleResolver.resolve(PieceType.Rook) shouldBe MotionStyle.Heavy
  }

  it should "assign Arc(0.5) to Knight" in {
    MotionStyleResolver.resolve(PieceType.Knight) shouldBe MotionStyle.Arc(0.5)
  }

  it should "assign Smooth to Bishop" in {
    MotionStyleResolver.resolve(PieceType.Bishop) shouldBe MotionStyle.Smooth
  }

  it should "assign Smooth to Queen" in {
    MotionStyleResolver.resolve(PieceType.Queen) shouldBe MotionStyle.Smooth
  }

  it should "assign Heavy to King" in {
    MotionStyleResolver.resolve(PieceType.King) shouldBe MotionStyle.Heavy
  }

  // ── Capture overrides ─────────────────────────────────────────────────────

  it should "assign AttackLunge for a Pawn capture" in {
    MotionStyleResolver.resolve(PieceType.Pawn, isCapture = true) shouldBe MotionStyle.AttackLunge(0.15)
  }

  it should "assign AttackLunge for a Rook capture" in {
    MotionStyleResolver.resolve(PieceType.Rook, isCapture = true) shouldBe MotionStyle.AttackLunge(0.15)
  }

  it should "assign AttackLunge for a Knight capture" in {
    MotionStyleResolver.resolve(PieceType.Knight, isCapture = true) shouldBe MotionStyle.AttackLunge(0.15)
  }

  it should "assign AttackLunge for a Bishop capture" in {
    MotionStyleResolver.resolve(PieceType.Bishop, isCapture = true) shouldBe MotionStyle.AttackLunge(0.15)
  }

  it should "assign AttackLunge for a Queen capture" in {
    MotionStyleResolver.resolve(PieceType.Queen, isCapture = true) shouldBe MotionStyle.AttackLunge(0.15)
  }

  it should "assign AttackLunge for a King capture" in {
    MotionStyleResolver.resolve(PieceType.King, isCapture = true) shouldBe MotionStyle.AttackLunge(0.15)
  }

  it should "leave non-capture styles unchanged when isCapture=false" in {
    MotionStyleResolver.resolve(PieceType.Pawn,   isCapture = false) shouldBe MotionStyle.Linear
    MotionStyleResolver.resolve(PieceType.Knight, isCapture = false) shouldBe MotionStyle.Arc(0.5)
  }
