package chess.adapter.gui.assets

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class VisualStateSpec extends AnyFlatSpec with Matchers:

  "VisualState" should "expose exactly five distinct states" in {
    VisualState.values should have length 5
    VisualState.values.toSet shouldBe Set(
      VisualState.Idle,
      VisualState.Move,
      VisualState.Attack,
      VisualState.Hit,
      VisualState.Dead
    )
  }

  it should "treat all five states as distinct values" in {
    val states = Seq(
      VisualState.Idle,
      VisualState.Move,
      VisualState.Attack,
      VisualState.Hit,
      VisualState.Dead
    )
    states.distinct should have length 5
  }
