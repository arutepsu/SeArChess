package chess.adapter.gui.animation

import chess.domain.model.{Color, PieceType, Position}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AnimationStateSpec extends AnyFlatSpec with Matchers:

  private def mkPos(f: Int, r: Int): Position =
    Position.from(f, r).getOrElse(scala.sys.error(s"Bad pos: $f,$r"))

  private val from = mkPos(0, 1)
  private val to = mkPos(0, 3)
  private val plan = AnimationPlan(
    movingPiece = (Color.White, PieceType.Pawn),
    from = from,
    to = to,
    capturedPiece = None
  )

  "AnimationState.isActive" should "be true when progress < 1.0" in {
    AnimationState(plan, 0.5).isActive shouldBe true
  }

  it should "be false when progress is exactly 1.0" in {
    AnimationState(plan, 1.0).isActive shouldBe false
  }

  "AnimationState.clampedProgress" should "return the progress unchanged when in [0, 1]" in {
    AnimationState(plan, 0.7).clampedProgress shouldBe 0.7
  }

  it should "clamp to 0.0 when progress is negative" in {
    AnimationState(plan, -0.1).clampedProgress shouldBe 0.0
  }

  it should "clamp to 1.0 when progress exceeds 1.0" in {
    AnimationState(plan, 1.2).clampedProgress shouldBe 1.0
  }

  "AnimationState.CaptureThreshold" should "be defined as a positive fraction below 1" in {
    AnimationState.CaptureThreshold should (be > 0.0 and be < 1.0)
  }
