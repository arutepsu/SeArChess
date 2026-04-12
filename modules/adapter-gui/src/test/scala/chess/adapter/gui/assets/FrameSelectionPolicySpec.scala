package chess.adapter.gui.assets

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class FrameSelectionPolicySpec extends AnyFlatSpec with Matchers:

  // ── Idle — always frame 0 ───────────────────────────────────────────────────

  "FrameSelectionPolicy.select (Idle)" should "return frame 0 with a single-frame asset" in {
    FrameSelectionPolicy.select(VisualState.Idle, frameCount = 1, progress = 0.0) shouldBe 0
  }

  it should "return frame 0 even when frameCount > 1" in {
    FrameSelectionPolicy.select(VisualState.Idle, frameCount = 8, progress = 0.5) shouldBe 0
  }

  it should "return frame 0 regardless of progress" in {
    FrameSelectionPolicy.select(VisualState.Idle, frameCount = 4, progress = 1.0) shouldBe 0
  }

  // ── Move — progress-based ───────────────────────────────────────────────────

  "FrameSelectionPolicy.select (Move)" should "return frame 0 at progress 0.0" in {
    FrameSelectionPolicy.select(VisualState.Move, frameCount = 4, progress = 0.0) shouldBe 0
  }

  it should "return the last frame at progress 1.0" in {
    FrameSelectionPolicy.select(VisualState.Move, frameCount = 4, progress = 1.0) shouldBe 3
  }

  it should "return the middle frame at progress 0.5 with 4 frames" in {
    // floor(0.5 × 4) = floor(2.0) = 2
    FrameSelectionPolicy.select(VisualState.Move, frameCount = 4, progress = 0.5) shouldBe 2
  }

  it should "return frame 0 for a single-frame asset at any progress" in {
    FrameSelectionPolicy.select(VisualState.Move, frameCount = 1, progress = 0.9) shouldBe 0
  }

  it should "return frame 1 at progress 0.25 with 4 frames" in {
    // floor(0.25 × 4) = floor(1.0) = 1
    FrameSelectionPolicy.select(VisualState.Move, frameCount = 4, progress = 0.25) shouldBe 1
  }

  it should "clamp negative progress to frame 0" in {
    FrameSelectionPolicy.select(VisualState.Move, frameCount = 4, progress = -0.5) shouldBe 0
  }

  it should "clamp progress > 1 to the last frame" in {
    FrameSelectionPolicy.select(VisualState.Move, frameCount = 4, progress = 1.5) shouldBe 3
  }

  it should "handle an 8-frame asset correctly at progress 0.5" in {
    // floor(0.5 × 8) = 4
    FrameSelectionPolicy.select(VisualState.Move, frameCount = 8, progress = 0.5) shouldBe 4
  }

  // ── Dead — same progress-based rule ────────────────────────────────────────

  "FrameSelectionPolicy.select (Dead)" should "return frame 0 at progress 0.0" in {
    FrameSelectionPolicy.select(VisualState.Dead, frameCount = 8, progress = 0.0) shouldBe 0
  }

  it should "return the last frame at progress 1.0" in {
    FrameSelectionPolicy.select(VisualState.Dead, frameCount = 8, progress = 1.0) shouldBe 7
  }

  it should "return the middle frame at progress 0.5 with 8 frames" in {
    // floor(0.5 × 8) = 4
    FrameSelectionPolicy.select(VisualState.Dead, frameCount = 8, progress = 0.5) shouldBe 4
  }

  // ── Attack — progress-based (reserved for future use) ──────────────────────

  "FrameSelectionPolicy.select (Attack)" should "return frame 0 at progress 0.0" in {
    FrameSelectionPolicy.select(VisualState.Attack, frameCount = 6, progress = 0.0) shouldBe 0
  }

  it should "return the last frame at progress 1.0" in {
    FrameSelectionPolicy.select(VisualState.Attack, frameCount = 6, progress = 1.0) shouldBe 5
  }

  // ── Hit — progress-based (reserved for future use) ──────────────────────────

  "FrameSelectionPolicy.select (Hit)" should "return frame 0 at progress 0.0" in {
    FrameSelectionPolicy.select(VisualState.Hit, frameCount = 3, progress = 0.0) shouldBe 0
  }

  it should "return the last frame at progress 1.0" in {
    FrameSelectionPolicy.select(VisualState.Hit, frameCount = 3, progress = 1.0) shouldBe 2
  }

  // ── Frame count of 1 never overflows ────────────────────────────────────────

  "FrameSelectionPolicy.select" should "never return a negative index" in {
    for state <- VisualState.values do
      FrameSelectionPolicy.select(state, frameCount = 1, progress = 0.0) should be >= 0
  }

  it should "never return an index >= frameCount" in {
    for state <- VisualState.values do
      val result = FrameSelectionPolicy.select(state, frameCount = 4, progress = 0.99)
      result should be < 4
  }
