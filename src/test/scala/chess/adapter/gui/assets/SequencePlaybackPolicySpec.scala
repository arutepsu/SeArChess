package chess.adapter.gui.assets

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SequencePlaybackPolicySpec extends AnyFlatSpec with Matchers:

  // ── Helpers ───────────────────────────────────────────────────────────────

  private def makeMeta(assetKey: String, frameCount: Int): SpriteMetadata =
    SpriteMetadata(assetKey, "", frameCount, (64, 64), None, None)

  /** Metadata repository for all asset keys referenced by the test metadata. */
  private val testMetaRepo = SpriteMetadataRepository(Map(
    "classic/white_pawn_move"    -> makeMeta("classic/white_pawn_move",    4),
    "classic/white_pawn_attack"  -> makeMeta("classic/white_pawn_attack",  6),
    "classic/white_pawn_attack1" -> makeMeta("classic/white_pawn_attack1", 6)
  ))

  private def resolve(meta: StatePlaybackMetadata, p: Double) =
    SequencePlaybackPolicy.resolve(meta, p, testMetaRepo)

  /** Single-segment Clamp metadata with an asset that has 4 frames. */
  private val singleClamp = StatePlaybackMetadata(
    state    = VisualState.Move,
    segments = Seq(PlaybackSegmentRef("classic/white_pawn_move")),
    mode     = PlaybackMode.Clamp
  )

  /** Two-segment Loop metadata; each segment has 6 frames (attack / attack1). */
  private val twoSegLoop = StatePlaybackMetadata(
    state    = VisualState.Attack,
    segments = Seq(
      PlaybackSegmentRef("classic/white_pawn_attack"),
      PlaybackSegmentRef("classic/white_pawn_attack1")
    ),
    mode     = PlaybackMode.Loop
  )

  /** Two-segment Clamp metadata reusing the attack segments for structural tests. */
  private val twoSegClamp = StatePlaybackMetadata(
    state    = VisualState.Dead,
    segments = Seq(
      PlaybackSegmentRef("classic/white_pawn_attack"),
      PlaybackSegmentRef("classic/white_pawn_attack1")
    ),
    mode     = PlaybackMode.Clamp
  )

  // ── Single-segment Clamp ──────────────────────────────────────────────────

  "SequencePlaybackPolicy.resolve (single-segment Clamp)" should
      "resolve to the only segment at p=0" in {
    val r = resolve(singleClamp, 0.0)
    r.segmentAssetKey shouldBe "classic/white_pawn_move"
    r.frameIndex      shouldBe 0
  }

  it should "resolve to the only segment at p=1 with the last frame" in {
    // 4 frames → last frame = 3
    val r = resolve(singleClamp, 1.0)
    r.segmentAssetKey shouldBe "classic/white_pawn_move"
    r.frameIndex      shouldBe 3
  }

  it should "resolve to the correct mid-range frame at p=0.5" in {
    // floor(0.5 × 4) = 2
    val r = resolve(singleClamp, 0.5)
    r.segmentAssetKey shouldBe "classic/white_pawn_move"
    r.frameIndex      shouldBe 2
  }

  it should "clamp negative progress to frame 0" in {
    val r = resolve(singleClamp, -0.5)
    r.segmentAssetKey shouldBe "classic/white_pawn_move"
    r.frameIndex      shouldBe 0
  }

  it should "clamp progress > 1 to the last frame" in {
    val r = resolve(singleClamp, 1.5)
    r.segmentAssetKey shouldBe "classic/white_pawn_move"
    r.frameIndex      shouldBe 3
  }

  it should "fall back to frame 0 when the segment is not in the repo" in {
    val unknownMeta = singleClamp.copy(segments = Seq(PlaybackSegmentRef("classic/unknown")))
    val r = resolve(unknownMeta, 0.9)
    r.frameIndex shouldBe 0
  }

  // ── Two-segment Loop ──────────────────────────────────────────────────────

  "SequencePlaybackPolicy.resolve (two-segment Loop)" should
      "resolve to segment 0 at p=0" in {
    val r = resolve(twoSegLoop, 0.0)
    r.segmentAssetKey shouldBe "classic/white_pawn_attack"
    r.frameIndex      shouldBe 0
  }

  it should "resolve to segment 0 at p=0.25 (first half of segment 0)" in {
    // p=0.25 → segment 0 window [0, 0.5); local=0.25/0.5=0.5 → floor(0.5×6)=3
    val r = resolve(twoSegLoop, 0.25)
    r.segmentAssetKey shouldBe "classic/white_pawn_attack"
    r.frameIndex      shouldBe 3
  }

  it should "resolve to segment 1 at p=0.5 (first frame of segment 1)" in {
    // p=0.5 → segment 1 window [0.5, 1.0); local=0.0/0.5=0 → frame 0
    val r = resolve(twoSegLoop, 0.5)
    r.segmentAssetKey shouldBe "classic/white_pawn_attack1"
    r.frameIndex      shouldBe 0
  }

  it should "resolve to segment 1 at p=0.75 (mid segment 1)" in {
    // p=0.75 → local=(0.75-0.5)/0.5=0.5 → floor(0.5×6)=3
    val r = resolve(twoSegLoop, 0.75)
    r.segmentAssetKey shouldBe "classic/white_pawn_attack1"
    r.frameIndex      shouldBe 3
  }

  it should "resolve to segment 0, frame 0 at p=1.0 (Loop wraps)" in {
    // Loop: 1.0 - floor(1.0) = 0.0 → segment 0, frame 0
    val r = resolve(twoSegLoop, 1.0)
    r.segmentAssetKey shouldBe "classic/white_pawn_attack"
    r.frameIndex      shouldBe 0
  }

  it should "wrap back to segment 1 at p=1.5 (Loop)" in {
    // Loop: 1.5 - floor(1.5) = 0.5 → segment 1
    val r = resolve(twoSegLoop, 1.5)
    r.segmentAssetKey shouldBe "classic/white_pawn_attack1"
    r.frameIndex      shouldBe 0
  }

  it should "use fractional part for negative progress (Loop)" in {
    // Loop: -0.5 - floor(-0.5) = -0.5 - (-1) = 0.5 → segment 1
    val r = resolve(twoSegLoop, -0.5)
    r.segmentAssetKey shouldBe "classic/white_pawn_attack1"
    r.frameIndex      shouldBe 0
  }

  // ── Two-segment Clamp ─────────────────────────────────────────────────────

  "SequencePlaybackPolicy.resolve (two-segment Clamp)" should
      "resolve to segment 0 at p=0" in {
    val r = resolve(twoSegClamp, 0.0)
    r.segmentAssetKey shouldBe "classic/white_pawn_attack"
    r.frameIndex      shouldBe 0
  }

  it should "resolve to segment 1 at p=1.0 with the last frame (Clamp)" in {
    // Clamp: p clamped to 1.0; segment 1 window [0.5,1); local=1.0 → last frame = 5
    val r = resolve(twoSegClamp, 1.0)
    r.segmentAssetKey shouldBe "classic/white_pawn_attack1"
    r.frameIndex      shouldBe 5
  }

  it should "clamp p > 1 to segment 1 last frame" in {
    val r = resolve(twoSegClamp, 2.0)
    r.segmentAssetKey shouldBe "classic/white_pawn_attack1"
    r.frameIndex      shouldBe 5
  }

  // ── Frame index bounds ────────────────────────────────────────────────────

  "SequencePlaybackPolicy.resolve" should "never return a negative frame index" in {
    for p <- Seq(-1.0, -0.5, 0.0, 0.25, 0.5, 0.75, 1.0, 1.5, 2.0) do
      resolve(singleClamp, p).frameIndex should be >= 0
      resolve(twoSegLoop,  p).frameIndex should be >= 0
      resolve(twoSegClamp, p).frameIndex should be >= 0
  }

  it should "never return a frame index >= the segment's frame count" in {
    // single-segment: frameCount=4
    for p <- Seq(0.0, 0.5, 1.0, 1.5) do
      resolve(singleClamp, p).frameIndex should be < 4
    // two-segment: each segment has frameCount=6
    for p <- Seq(0.0, 0.25, 0.5, 0.75, 1.0) do
      resolve(twoSegLoop, p).frameIndex should be < 6
  }
  
  it should "fall back to the first segment when progress is NaN" in {
    val r = resolve(twoSegClamp, Double.NaN)

    r.segmentAssetKey shouldBe "classic/white_pawn_attack"
    r.frameIndex      shouldBe 0
  }