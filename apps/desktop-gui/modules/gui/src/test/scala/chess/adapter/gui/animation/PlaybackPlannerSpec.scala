package chess.adapter.gui.animation

import chess.adapter.gui.assets.{PlaybackMode, PlaybackSegmentRef, StatePlaybackMetadata, VisualState}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PlaybackPlannerSpec extends AnyFlatSpec with Matchers:

  // ── Fixture helpers ───────────────────────────────────────────────────────

  private def seg(key: String): PlaybackSegmentRef = PlaybackSegmentRef(key)

  private def meta(mode: PlaybackMode, keys: String*): StatePlaybackMetadata =
    StatePlaybackMetadata(VisualState.Attack, keys.map(seg).toSeq, mode)

  private val seg0 = "seg0"
  private val seg1 = "seg1"

  // ── Single-segment, Clamp ─────────────────────────────────────────────────

  "PlaybackPlanner.plan" should "select the only segment at t=0 (single segment)" in {
    PlaybackPlanner.plan(meta(PlaybackMode.Clamp, seg0), 0.0).segmentAssetKey shouldBe seg0
  }

  it should "select the only segment at t=1 (single segment)" in {
    PlaybackPlanner.plan(meta(PlaybackMode.Clamp, seg0), 1.0).segmentAssetKey shouldBe seg0
  }

  it should "return localProgress=0.0 at t=0 (single segment)" in {
    PlaybackPlanner.plan(meta(PlaybackMode.Clamp, seg0), 0.0).localProgress shouldBe 0.0
  }

  it should "return localProgress=0.5 at t=0.5 (single segment)" in {
    PlaybackPlanner.plan(meta(PlaybackMode.Clamp, seg0), 0.5).localProgress shouldBe 0.5
  }

  it should "return localProgress=1.0 at t=1 (single segment)" in {
    PlaybackPlanner.plan(meta(PlaybackMode.Clamp, seg0), 1.0).localProgress shouldBe 1.0
  }

  // ── Two segments, Clamp ──────────────────────────────────────────────────

  it should "select segment 0 at t=0 (two segments)" in {
    PlaybackPlanner.plan(meta(PlaybackMode.Clamp, seg0, seg1), 0.0).segmentAssetKey shouldBe seg0
  }

  it should "return localProgress=0.0 at t=0 in segment 0" in {
    PlaybackPlanner.plan(meta(PlaybackMode.Clamp, seg0, seg1), 0.0).localProgress shouldBe 0.0
  }

  it should "return localProgress=0.5 at t=0.25 in segment 0" in {
    PlaybackPlanner.plan(meta(PlaybackMode.Clamp, seg0, seg1), 0.25).localProgress shouldBe 0.5 +- 1e-10
  }

  it should "select segment 1 at t=0.5 (two segments)" in {
    PlaybackPlanner.plan(meta(PlaybackMode.Clamp, seg0, seg1), 0.5).segmentAssetKey shouldBe seg1
  }

  it should "return localProgress=0.0 at t=0.5 in segment 1" in {
    PlaybackPlanner.plan(meta(PlaybackMode.Clamp, seg0, seg1), 0.5).localProgress shouldBe 0.0 +- 1e-10
  }

  it should "return localProgress=0.5 at t=0.75 in segment 1" in {
    PlaybackPlanner.plan(meta(PlaybackMode.Clamp, seg0, seg1), 0.75).localProgress shouldBe 0.5 +- 1e-10
  }

  it should "select segment 1 at t=1.0 and return localProgress=1.0 (two segments)" in {
    val plan = PlaybackPlanner.plan(meta(PlaybackMode.Clamp, seg0, seg1), 1.0)
    plan.segmentAssetKey shouldBe seg1
    plan.localProgress   shouldBe 1.0 +- 1e-10
  }

  // ── Clamp: out-of-range progress ─────────────────────────────────────────

  it should "clamp negative progress to segment 0 with localProgress=0.0" in {
    val plan = PlaybackPlanner.plan(meta(PlaybackMode.Clamp, seg0, seg1), -0.5)
    plan.segmentAssetKey shouldBe seg0
    plan.localProgress   shouldBe 0.0
  }

  it should "clamp progress > 1 to the last segment with localProgress=1.0" in {
    val plan = PlaybackPlanner.plan(meta(PlaybackMode.Clamp, seg0, seg1), 1.5)
    plan.segmentAssetKey shouldBe seg1
    plan.localProgress   shouldBe 1.0 +- 1e-10
  }

  // ── Loop mode ────────────────────────────────────────────────────────────

  it should "wrap progress at t=1.5 the same as t=0.5 in Loop mode" in {
    val loopMeta = meta(PlaybackMode.Loop, seg0, seg1)
    val loopPlan = PlaybackPlanner.plan(loopMeta, 1.5)
    val refPlan  = PlaybackPlanner.plan(loopMeta, 0.5)
    loopPlan.segmentAssetKey shouldBe refPlan.segmentAssetKey
    loopPlan.localProgress   shouldBe refPlan.localProgress +- 1e-10
  }

  it should "wrap progress at t=2.25 the same as t=0.25 in Loop mode" in {
    val loopMeta = meta(PlaybackMode.Loop, seg0, seg1)
    val loopPlan = PlaybackPlanner.plan(loopMeta, 2.25)
    val refPlan  = PlaybackPlanner.plan(loopMeta, 0.25)
    loopPlan.segmentAssetKey shouldBe refPlan.segmentAssetKey
    loopPlan.localProgress   shouldBe refPlan.localProgress +- 1e-10
  }
