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
