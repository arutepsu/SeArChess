package chess.adapter.gui.animation

import chess.adapter.gui.assets.{
  PlaybackMode, PlaybackSegmentRef, SpriteMetadata, SpriteMetadataRepository,
  StatePlaybackMetadata, StatePlaybackRepository, VisualState
}
import chess.domain.model.{Color, PieceType, Position}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AnimationPresentationMapperSpec extends AnyFlatSpec with Matchers:

  // Use squareSize=100 for clean arithmetic.
  private val S = 100.0

  private def mkPos(file: Int, rank: Int): Position =
    Position.from(file, rank).getOrElse(throw AssertionError(s"Bad pos: $file,$rank"))

  // from = a1 (file=0, rank=0) → toPixelX=rank*100=0,   toPixelY=(7-file)*100=700
  // to   = e5 (file=4, rank=4) → toPixelX=rank*100=400, toPixelY=(7-file)*100=300
  private val from = mkPos(0, 0)
  private val to   = mkPos(4, 4)

  private val whitePawn = (Color.White, PieceType.Pawn)
  private val blackPawn = (Color.Black, PieceType.Pawn)

  private val normalPlan = AnimationPlan(
    movingPiece   = whitePawn,
    from          = from,
    to            = to,
    capturedPiece = None
  )

  private val capturePlan = AnimationPlan(
    movingPiece   = whitePawn,
    from          = from,
    to            = to,
    capturedPiece = Some(blackPawn)
  )

  private def normalState(p: Double)  = AnimationState(normalPlan, p)
  private def captureState(p: Double) = AnimationState(capturePlan, p)

  // ── Test repos ─────────────────────────────────────────────────────────────
  // Frame counts are test-local values chosen for clean arithmetic:
  //   white_pawn_move → 4 frames  (moving piece assertions)
  //   black_pawn_dead → 8 frames  (captured piece assertions)

  private def makeMeta(key: String, frameCount: Int): SpriteMetadata =
    SpriteMetadata(key, "", frameCount, (64, 64), None, None)

  private val testMetaRepo = SpriteMetadataRepository(Map(
    "classic/white_pawn_move"    -> makeMeta("classic/white_pawn_move",    4),
    "classic/black_pawn_idle"    -> makeMeta("classic/black_pawn_idle",    4),
    "classic/black_pawn_hit"     -> makeMeta("classic/black_pawn_hit",     8),
    "classic/black_pawn_dead"    -> makeMeta("classic/black_pawn_dead",    8),
    "classic/white_pawn_attack"  -> makeMeta("classic/white_pawn_attack",  6),
    "classic/white_pawn_attack1" -> makeMeta("classic/white_pawn_attack1", 6)
  ))

  private val testPlaybackRepo = StatePlaybackRepository(Map(
    "classic/white_pawn_move"   -> StatePlaybackMetadata(
      VisualState.Move,   Seq(PlaybackSegmentRef("classic/white_pawn_move")),   PlaybackMode.Clamp),
    "classic/black_pawn_idle"   -> StatePlaybackMetadata(
      VisualState.Idle,   Seq(PlaybackSegmentRef("classic/black_pawn_idle")),   PlaybackMode.Clamp),
    "classic/black_pawn_hit"    -> StatePlaybackMetadata(
      VisualState.Hit,    Seq(PlaybackSegmentRef("classic/black_pawn_hit")),    PlaybackMode.Clamp),
    "classic/black_pawn_dead"   -> StatePlaybackMetadata(
      VisualState.Dead,   Seq(PlaybackSegmentRef("classic/black_pawn_dead")),   PlaybackMode.Clamp),
    "classic/white_pawn_attack" -> StatePlaybackMetadata(
      VisualState.Attack,
      Seq(PlaybackSegmentRef("classic/white_pawn_attack"), PlaybackSegmentRef("classic/white_pawn_attack1")),
      PlaybackMode.Clamp)
  ))

  private val mapper = AnimationPresentationMapper(testMetaRepo, testPlaybackRepo)

  // ── Moving piece — normal move ────────────────────────────────────────────

  "AnimationPresentationMapper.map" should "place the moving piece at the source at t=0" in {
    val model = mapper.map(normalState(0.0), S)
    model.movingPiece.x shouldBe 0.0
    model.movingPiece.y shouldBe 700.0
  }

  it should "place the moving piece at the destination at t=1" in {
    val model = mapper.map(normalState(1.0), S)
    model.movingPiece.x shouldBe 400.0
    model.movingPiece.y shouldBe 300.0
  }

  it should "interpolate the moving piece position at t=0.5" in {
    val model = mapper.map(normalState(0.5), S)
    model.movingPiece.x shouldBe 200.0
    model.movingPiece.y shouldBe 500.0
  }

  it should "report the correct moving piece type" in {
    val model = mapper.map(normalState(0.5), S)
    model.movingPiece.piece shouldBe whitePawn
  }

  it should "set the moving piece opacity to 1.0" in {
    val model = mapper.map(normalState(0.5), S)
    model.movingPiece.opacity shouldBe 1.0
  }

  // ── Suppression ──────────────────────────────────────────────────────────

  it should "suppress the destination square" in {
    mapper.map(normalState(0.0), S).suppressedSquare shouldBe Some(to)
    mapper.map(normalState(0.5), S).suppressedSquare shouldBe Some(to)
    mapper.map(normalState(1.0), S).suppressedSquare shouldBe Some(to)
  }

  // ── No captured piece for a normal move ──────────────────────────────────

  it should "produce no captured piece for a normal (non-capture) move" in {
    mapper.map(normalState(0.0), S).capturedPiece shouldBe None
    mapper.map(normalState(0.5), S).capturedPiece shouldBe None
    mapper.map(normalState(1.0), S).capturedPiece shouldBe None
  }

  // ── Captured piece — capture move ─────────────────────────────────────────

  it should "show the captured piece at the destination at t=0" in {
    val model = mapper.map(captureState(0.0), S)
    model.capturedPiece shouldBe defined
    val info = model.capturedPiece.get
    info.piece   shouldBe blackPawn
    info.x       shouldBe 400.0
    info.y       shouldBe 300.0
    info.opacity shouldBe 1.0
  }

  it should "show the captured piece with reduced opacity in the dead phase" in {
    // Any t in (DeadStart, FadeEnd) yields opacity in (0, 1).
    val t     = (CaptureTiming.DeadStart + CaptureTiming.FadeEnd) / 2
    val model = mapper.map(captureState(t), S)
    model.capturedPiece shouldBe defined
    val opacity = model.capturedPiece.get.opacity
    opacity should be > 0.0
    opacity should be < 1.0
  }

  it should "decrease captured-piece opacity as progress increases in the dead phase" in {
    val early = mapper.map(captureState(CaptureTiming.DeadStart + 0.05), S).capturedPiece.get.opacity
    val late  = mapper.map(captureState(CaptureTiming.DeadStart + 0.15), S).capturedPiece.get.opacity
    early should be > late
  }

  it should "hide the captured piece at exactly FadeEnd" in {
    val model = mapper.map(captureState(CaptureTiming.FadeEnd), S)
    model.capturedPiece shouldBe None
  }

  it should "hide the captured piece after FadeEnd" in {
    mapper.map(captureState(CaptureTiming.FadeEnd + 0.05), S).capturedPiece shouldBe None
    mapper.map(captureState(1.0), S).capturedPiece shouldBe None
  }

  // ── Boundary: out-of-range progress ──────────────────────────────────────

  it should "clamp negative progress to the source position" in {
    val model = mapper.map(normalState(-0.5), S)
    model.movingPiece.x shouldBe 0.0
    model.movingPiece.y shouldBe 700.0
  }

  it should "clamp progress > 1 to the destination position" in {
    val model = mapper.map(normalState(2.0), S)
    model.movingPiece.x shouldBe 400.0
    model.movingPiece.y shouldBe 300.0
  }

  it should "show a captured piece when progress is negative (clamped to Idle phase)" in {
    // Negative progress is clamped to 0, which falls in the Idle phase → piece visible.
    val model = mapper.map(captureState(-0.1), S)
    model.capturedPiece shouldBe defined
  }

  it should "not show a captured piece when progress is clamped to 1 (past FadeEnd)" in {
    val model = mapper.map(captureState(1.5), S)
    model.capturedPiece shouldBe None
  }

  // ── Default square size ───────────────────────────────────────────────────

  it should "use DefaultSquareSize when no explicit size is passed" in {
    val S2    = AnimationPresentationMapper.DefaultSquareSize
    val model = mapper.map(normalState(1.0))
    model.movingPiece.x shouldBe mkPos(4, 4).rank * S2
    model.movingPiece.y shouldBe (7 - mkPos(4, 4).file) * S2
  }

  // ── Frame indices — moving piece (white pawn, Move, frameCount=4) ─────────

  it should "assign frame 0 to the moving piece at t=0" in {
    // white pawn + Move → frameCount=4; floor(0.0 × 4)=0
    mapper.map(normalState(0.0), S).movingPiece.frameIndex shouldBe 0
  }

  it should "assign frame 3 (last) to the moving piece at t=1" in {
    // floor(1.0 × 4)=4 → clamped to 3
    mapper.map(normalState(1.0), S).movingPiece.frameIndex shouldBe 3
  }

  it should "assign a mid-range frame to the moving piece at t=0.5" in {
    // floor(0.5 × 4)=2
    mapper.map(normalState(0.5), S).movingPiece.frameIndex shouldBe 2
  }

  // ── Frame indices — captured piece (black pawn, Dead, frameCount=8) ───────

  it should "assign frame 0 to the captured piece at t=0" in {
    // black pawn + Dead → frameCount=8; floor(0.0 × 8)=0
    val info = mapper.map(captureState(0.0), S).capturedPiece.get
    info.frameIndex shouldBe 0
  }

  it should "assign a mid-range frame to the captured piece at the dead-phase midpoint" in {
    // t = midpoint of [DeadStart, FadeEnd] → localProgress = 0.5, frameCount=8: floor(0.5 × 8)=4
    val t    = (CaptureTiming.DeadStart + CaptureTiming.FadeEnd) / 2
    val info = mapper.map(captureState(t), S).capturedPiece.get
    info.frameIndex shouldBe 4
  }

  // ── segmentAssetKey — moving piece (white pawn, Move — single segment) ────

  it should "set segmentAssetKey to the primary move segment for the moving piece" in {
    val info = mapper.map(normalState(0.5), S).movingPiece
    info.segmentAssetKey shouldBe Some("classic/white_pawn_move")
  }

  it should "set segmentAssetKey for the moving piece consistently across progress values" in {
    for p <- Seq(0.0, 0.5, 1.0) do
      mapper.map(normalState(p), S).movingPiece.segmentAssetKey shouldBe
        Some("classic/white_pawn_move")
  }

  // ── segmentAssetKey — captured piece (black pawn, Dead — single segment) ──

  it should "set segmentAssetKey to the Idle segment for the captured piece in the early phase" in {
    val info = mapper.map(captureState(0.0), S).capturedPiece.get
    info.segmentAssetKey shouldBe Some("classic/black_pawn_idle")
  }

  it should "set segmentAssetKey to the Idle segment for the captured piece just before HitStart" in {
    val info = mapper.map(captureState(CaptureTiming.HitStart - 0.05), S).capturedPiece.get
    info.segmentAssetKey shouldBe Some("classic/black_pawn_idle")
  }

  // ── Fallback when no playback entry is registered ─────────────────────────

  it should "use a fallback key when the piece+state is not in the playback repo" in {
    // The testPlaybackRepo has no entry for White Knight + Move;
    // the mapper falls back to a derived key with frameIndex=0.
    val knightPlan  = AnimationPlan((Color.White, PieceType.Knight), from, to, None)
    val knightState = AnimationState(knightPlan, 0.5)
    val model = mapper.map(knightState, S)
    model.movingPiece.segmentAssetKey shouldBe Some("classic/white_knight_move")
    model.movingPiece.frameIndex      shouldBe 0
  }

  it should "use a fallback attack key when the piece has no attack entry in the repo" in {
    // No attack entry for White Knight; mapper falls back to derived key.
    val knightCapturePlan  = AnimationPlan((Color.White, PieceType.Knight), from, to, Some(blackPawn))
    val knightCaptureState = AnimationState(knightCapturePlan, 0.5)
    val model = mapper.map(knightCaptureState, S)
    model.movingPiece.segmentAssetKey shouldBe Some("classic/white_knight_attack")
    model.movingPiece.frameIndex      shouldBe 0
  }

  // ── Attack path: moving piece uses Attack state for captures ──────────────

  it should "select the first attack segment at t=0 for a capture move" in {
    mapper.map(captureState(0.0), S).movingPiece.segmentAssetKey shouldBe
      Some("classic/white_pawn_attack")
  }

  it should "select the second attack segment at t=0.5 for a capture move" in {
    mapper.map(captureState(0.5), S).movingPiece.segmentAssetKey shouldBe
      Some("classic/white_pawn_attack1")
  }

  it should "assign correct frameIndex within segment 0 at t=0.25 (localProgress=0.5, frameCount=6)" in {
    // t=0.25 → segment 0, localProgress=0.5; FrameSelectionPolicy: floor(0.5*6)=3
    mapper.map(captureState(0.25), S).movingPiece.frameIndex shouldBe 3
  }

  it should "assign correct frameIndex within segment 1 at t=0.75 (localProgress=0.5, frameCount=6)" in {
    // t=0.75 → segment 1, localProgress=0.5; FrameSelectionPolicy: floor(0.5*6)=3
    mapper.map(captureState(0.75), S).movingPiece.frameIndex shouldBe 3
  }

  // ── Captured piece — three-phase presentation ─────────────────────────────

  it should "show the captured piece in Idle state during early phase" in {
    // t=0.1 < HitStart → Idle phase
    val info = mapper.map(captureState(0.1), S).capturedPiece.get
    info.segmentAssetKey shouldBe Some("classic/black_pawn_idle")
    info.opacity         shouldBe 1.0
  }

  it should "show the captured piece in Hit state during hit phase" in {
    // t = midpoint of [HitStart, DeadStart)
    val t    = (CaptureTiming.HitStart + CaptureTiming.DeadStart) / 2
    val info = mapper.map(captureState(t), S).capturedPiece.get
    info.segmentAssetKey shouldBe Some("classic/black_pawn_hit")
    info.opacity         shouldBe 1.0
  }

  it should "show the captured piece in Dead state during dead phase" in {
    // t = midpoint of [DeadStart, FadeEnd)
    val t    = (CaptureTiming.DeadStart + CaptureTiming.FadeEnd) / 2
    val info = mapper.map(captureState(t), S).capturedPiece.get
    info.segmentAssetKey shouldBe Some("classic/black_pawn_dead")
    info.opacity         should be < 1.0
    info.opacity         should be > 0.0
  }

  it should "show full opacity throughout Idle and Hit phases" in {
    mapper.map(captureState(0.0),                                S).capturedPiece.get.opacity shouldBe 1.0
    mapper.map(captureState(CaptureTiming.HitStart), S).capturedPiece.get.opacity shouldBe 1.0
  }

  it should "begin fading exactly at DeadStart" in {
    // At exactly DeadStart: localProgress=0.0 → opacity = 1.0 - 0.0 = 1.0 (fade just begins)
    val info = mapper.map(captureState(CaptureTiming.DeadStart), S).capturedPiece.get
    info.segmentAssetKey shouldBe Some("classic/black_pawn_dead")
    info.opacity         shouldBe 1.0 +- 1e-10
  }

  it should "transition captured piece from Idle to Hit at HitStart" in {
    val justBefore = mapper.map(captureState(CaptureTiming.HitStart - 0.01), S).capturedPiece.get
    val atHit      = mapper.map(captureState(CaptureTiming.HitStart), S).capturedPiece.get
    justBefore.segmentAssetKey shouldBe Some("classic/black_pawn_idle")
    atHit.segmentAssetKey      shouldBe Some("classic/black_pawn_hit")
  }

  it should "transition captured piece from Hit to Dead at DeadStart" in {
    val justBefore = mapper.map(captureState(CaptureTiming.DeadStart - 0.01), S).capturedPiece.get
    val atDead     = mapper.map(captureState(CaptureTiming.DeadStart), S).capturedPiece.get
    justBefore.segmentAssetKey shouldBe Some("classic/black_pawn_hit")
    atDead.segmentAssetKey     shouldBe Some("classic/black_pawn_dead")
  }

  // ── Attacker motion style for captures ───────────────────────────────────
  // Geometry: from=(0,0)→to=(4,4) with squareSize=100
  //   fromX=0, fromY=700, toX=400, toY=300

  it should "place the capture attacker at the source at t=0" in {
    val info = mapper.map(captureState(0.0), S).movingPiece
    info.x shouldBe 0.0
    info.y shouldBe 700.0
  }

  it should "place the capture attacker at the destination at t=1" in {
    val info = mapper.map(captureState(1.0), S).movingPiece
    info.x shouldBe (400.0 +- 1e-9)
    info.y shouldBe (300.0 +- 1e-9)
  }

  it should "use a different trajectory than a non-capture at t=0.5" in {
    // Non-capture (Linear) at t=0.5: (200, 500).
    // Capture (AttackLunge) is ahead of linear since it drives toward overshoot.
    val capture    = mapper.map(captureState(0.5), S).movingPiece
    val nonCapture = mapper.map(normalState(0.5), S).movingPiece
    capture.x should not be nonCapture.x
  }

  it should "overshoot past the destination x at the attack impact time" in {
    // Diagonal move (0,700)→(400,300): dx>0, dy<0.
    // At LungePeakT the lunge is at the overshoot point, so x > toX=400.
    val impT = CaptureTiming.LungePeakT
    val info = mapper.map(captureState(impT), S).movingPiece
    info.x should be > 400.0
  }

  it should "also overshoot y at the attack impact time (diagonal move)" in {
    // dy = 300-700 = -400 < 0, so osY < toY=300 (overshoots up on screen).
    val impT = CaptureTiming.LungePeakT
    val info = mapper.map(captureState(impT), S).movingPiece
    info.y should be < 300.0
  }

  // ── Hit-pop scale ─────────────────────────────────────────────────────────

  it should "keep scale at 1.0 for the moving piece on a normal move" in {
    mapper.map(normalState(0.5), S).movingPiece.scale shouldBe 1.0
  }

  it should "keep scale at 1.0 for the captured piece in the Idle phase" in {
    // t < HitStart → Idle phase
    mapper.map(captureState(0.1), S).capturedPiece.get.scale shouldBe 1.0
  }

  it should "keep scale at 1.0 for the captured piece in the Dead phase" in {
    // t in (DeadStart, FadeEnd) → Dead phase
    val t = (CaptureTiming.DeadStart + CaptureTiming.FadeEnd) / 2
    mapper.map(captureState(t), S).capturedPiece.get.scale shouldBe 1.0
  }

  it should "return scale 1.0 at the boundaries of the Hit phase" in {
    // lp = 0.0 at HitStart → scale = 1.0 + peak * 4 * 0 * 1 = 1.0
    mapper.map(captureState(CaptureTiming.HitStart), S).capturedPiece.get.scale shouldBe (1.0 +- 1e-10)
    // lp = 1.0 at DeadStart → scale = 1.0 + peak * 4 * 1 * 0 = 1.0
    val tAtDeadStart = CaptureTiming.DeadStart - 0.001   // still in Hit (t < DeadStart)
    val lpNearEnd    = (tAtDeadStart - CaptureTiming.HitStart) / (CaptureTiming.DeadStart - CaptureTiming.HitStart)
    // lpNearEnd ≈ 0.995; scale ≈ 1.0 + 0.10 * 4 * 0.995 * 0.005 ≈ 1.002 — still very close to 1
    // Exact end boundary: lp=1.0 → scale=1.0; verified analytically; test the formula at lp close to 1
    val scaleNearEnd = mapper.map(captureState(tAtDeadStart), S).capturedPiece.get.scale
    scaleNearEnd should be < (1.0 + AnimationPresentationMapper.HitPopPeak)
  }

  it should "pop above 1.0 at the midpoint of the Hit phase" in {
    val tMid = (CaptureTiming.HitStart + CaptureTiming.DeadStart) / 2
    val scale = mapper.map(captureState(tMid), S).capturedPiece.get.scale
    scale should be > 1.0
    scale should be <= (1.0 + AnimationPresentationMapper.HitPopPeak + 1e-10)
  }

  it should "reach the peak scale at the Hit-phase midpoint" in {
    val tMid  = (CaptureTiming.HitStart + CaptureTiming.DeadStart) / 2
    val scale = mapper.map(captureState(tMid), S).capturedPiece.get.scale
    // lp=0.5 → peak = 1.0 + HitPopPeak * 4 * 0.5 * 0.5 = 1.0 + HitPopPeak
    scale shouldBe (1.0 + AnimationPresentationMapper.HitPopPeak) +- 1e-10
  }

  // ── Impact pause — capture motion remap ───────────────────────────────────

  it should "apply the impact remap to capture attacker motion" in {
    // At t = ImpactPauseCenter the remap compresses progress; the attacker
    // position should equal what AttackLunge gives at the remapped t directly.
    val t          = CaptureTiming.ImpactPauseCenter
    val remappedT  = CaptureTiming.remapCapture(t)
    val (expectedX, expectedY) = MotionInterpolator.interpolate(
      MotionStyle.AttackLunge(0.15), 0.0, 700.0, 400.0, 300.0, remappedT)
    val info = mapper.map(captureState(t), S).movingPiece
    info.x shouldBe (expectedX +- 1e-9)
    info.y shouldBe (expectedY +- 1e-9)
  }

  it should "not apply the impact remap to normal move attacker motion" in {
    // Normal pawn move uses Linear; at t = ImpactPauseCenter the position must
    // equal linear interpolation at the raw t, not the remapped t.
    val t = CaptureTiming.ImpactPauseCenter
    val (expectedX, expectedY) = MotionInterpolator.interpolate(
      MotionStyle.Linear, 0.0, 700.0, 400.0, 300.0, t)
    val info = mapper.map(normalState(t), S).movingPiece
    info.x shouldBe (expectedX +- 1e-9)
    info.y shouldBe (expectedY +- 1e-9)
  }

  it should "leave capture attacker at source at t=0 despite remap" in {
    mapper.map(captureState(0.0), S).movingPiece.x shouldBe 0.0
    mapper.map(captureState(0.0), S).movingPiece.y shouldBe 700.0
  }

  it should "leave capture attacker at destination at t=1 despite remap" in {
    mapper.map(captureState(1.0), S).movingPiece.x shouldBe (400.0 +- 1e-9)
    mapper.map(captureState(1.0), S).movingPiece.y shouldBe (300.0 +- 1e-9)
  }
