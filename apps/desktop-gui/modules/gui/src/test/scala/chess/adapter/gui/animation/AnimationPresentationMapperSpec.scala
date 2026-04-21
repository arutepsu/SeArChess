package chess.adapter.gui.animation

import chess.adapter.gui.assets.{
  PlaybackMode,
  PlaybackSegmentRef,
  SpriteMetadata,
  SpriteMetadataRepository,
  StatePlaybackMetadata,
  StatePlaybackRepository,
  VisualState
}
import chess.domain.model.{Color, PieceType, Position}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AnimationPresentationMapperSpec extends AnyFlatSpec with Matchers:

  // Use squareSize=100 for clean arithmetic.
  private val S = 100.0

  private def mkPos(file: Int, rank: Int): Position =
    Position.from(file, rank).getOrElse(scala.sys.error(s"Bad pos: $file,$rank"))

  // from = a1 (file=0, rank=0) → toPixelX=rank*100=0,   toPixelY=(7-file)*100=700
  // to   = e5 (file=4, rank=4) → toPixelX=rank*100=400, toPixelY=(7-file)*100=300
  private val from = mkPos(0, 0)
  private val to = mkPos(4, 4)

  private val whitePawn = (Color.White, PieceType.Pawn)
  private val blackPawn = (Color.Black, PieceType.Pawn)

  private val normalPlan = AnimationPlan(
    movingPiece = whitePawn,
    from = from,
    to = to,
    capturedPiece = None
  )

  private val capturePlan = AnimationPlan(
    movingPiece = whitePawn,
    from = from,
    to = to,
    capturedPiece = Some(blackPawn)
  )

  private def normalState(p: Double) = AnimationState(normalPlan, p)
  private def captureState(p: Double) = AnimationState(capturePlan, p)

  // ── Capture phase boundaries (derived from default CapturePhaseTimings) ────
  // Default timings: approach=400ms, attack=500ms, attack1=500ms, dead=1000ms,
  //   fade=450ms, total=2850ms
  // Phase boundaries as fractions of total:
  //   Approach : [0,         approachEnd)  ≈ [0, 0.1404)
  //   Attack   : [approachEnd, attackEnd)  ≈ [0.1404, 0.3158)
  //   Attack1  : [attackEnd, attack1End)   ≈ [0.3158, 0.4912)
  //   Dead     : [attack1End, deadEnd)     ≈ [0.4912, 0.8421)
  //   Fade     : [deadEnd, 1.0]            ≈ [0.8421, 1.0]
  private val captureTimings = CapturePhaseTimings()
  private val total = captureTimings.totalMs.toDouble
  private val approachEnd = captureTimings.approachMs / total
  private val attackEnd = (captureTimings.approachMs + captureTimings.attackMs) / total
  private val attack1End =
    (captureTimings.approachMs + captureTimings.attackMs + captureTimings.attack1Ms) / total
  private val deadEnd =
    (captureTimings.approachMs + captureTimings.attackMs + captureTimings.attack1Ms + captureTimings.deadMs) / total

  // ── Test repos ─────────────────────────────────────────────────────────────
  // Frame counts are test-local values chosen for clean arithmetic:
  //   white_pawn_move → 4 frames  (moving piece assertions)
  //   black_pawn_dead → 8 frames  (captured piece assertions)

  private def makeMeta(key: String, frameCount: Int): SpriteMetadata =
    SpriteMetadata(key, "", frameCount, (64, 64), None, None)

  private val testMetaRepo = SpriteMetadataRepository(
    Map(
      "classic/white_pawn_move" -> makeMeta("classic/white_pawn_move", 4),
      "classic/black_pawn_idle" -> makeMeta("classic/black_pawn_idle", 4),
      "classic/black_pawn_dead" -> makeMeta("classic/black_pawn_dead", 8),
      "classic/white_pawn_attack" -> makeMeta("classic/white_pawn_attack", 6),
      "classic/white_pawn_attack1" -> makeMeta("classic/white_pawn_attack1", 6)
    )
  )

  private val testPlaybackRepo = StatePlaybackRepository(
    Map(
      "classic/white_pawn_move" -> StatePlaybackMetadata(
        VisualState.Move,
        Seq(PlaybackSegmentRef("classic/white_pawn_move")),
        PlaybackMode.Clamp
      ),
      "classic/black_pawn_idle" -> StatePlaybackMetadata(
        VisualState.Idle,
        Seq(PlaybackSegmentRef("classic/black_pawn_idle")),
        PlaybackMode.Clamp
      ),
      "classic/black_pawn_dead" -> StatePlaybackMetadata(
        VisualState.Dead,
        Seq(PlaybackSegmentRef("classic/black_pawn_dead")),
        PlaybackMode.Clamp
      ),
      "classic/white_pawn_attack" -> StatePlaybackMetadata(
        VisualState.Attack,
        Seq(
          PlaybackSegmentRef("classic/white_pawn_attack"),
          PlaybackSegmentRef("classic/white_pawn_attack1")
        ),
        PlaybackMode.Clamp
      )
    )
  )

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
    val info = model.capturedPiece.getOrElse(fail("expected capturedPiece"))
    info.piece shouldBe blackPawn
    info.x shouldBe 400.0
    info.y shouldBe 300.0
    info.opacity shouldBe 1.0
  }

  it should "show the captured piece with reduced opacity in the fade phase" in {
    // Any t in (deadEnd, 1.0) yields opacity in (0, 1).
    val t = (deadEnd + 1.0) / 2
    val model = mapper.map(captureState(t), S)
    model.capturedPiece shouldBe defined
    val opacity = model.capturedPiece.getOrElse(fail("expected capturedPiece")).opacity
    opacity should be > 0.0
    opacity should be < 1.0
  }

  it should "decrease captured-piece opacity as progress increases in the fade phase" in {
    val early = mapper
      .map(captureState(deadEnd + 0.05), S)
      .capturedPiece
      .getOrElse(fail("expected capturedPiece"))
      .opacity
    val late = mapper
      .map(captureState(deadEnd + 0.15), S)
      .capturedPiece
      .getOrElse(fail("expected capturedPiece"))
      .opacity
    early should be > late
  }

  it should "have opacity 0.0 for the captured piece at the end of the animation (t=1.0)" in {
    // Fade completes at t=1.0: localProgress=1.0 → opacity = 1.0 - 1.0 = 0.0
    val info =
      mapper.map(captureState(1.0), S).capturedPiece.getOrElse(fail("expected capturedPiece"))
    info.opacity shouldBe (0.0 +- 1e-10)
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

  it should "show a captured piece when progress is negative (clamped to Approach phase)" in {
    // Negative progress is clamped to 0, which falls in the Approach phase → piece visible.
    val model = mapper.map(captureState(-0.1), S)
    model.capturedPiece shouldBe defined
  }

  it should "show the captured piece with opacity 0.0 when progress is clamped to 1 (end of Fade)" in {
    val info =
      mapper.map(captureState(1.5), S).capturedPiece.getOrElse(fail("expected capturedPiece"))
    info.opacity shouldBe (0.0 +- 1e-10)
  }

  // ── Default square size ───────────────────────────────────────────────────

  it should "use DefaultSquareSize when no explicit size is passed" in {
    val S2 = AnimationPresentationMapper.DefaultSquareSize
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

  // ── Frame indices — captured piece (black pawn, Idle/Dead) ────────────────

  it should "assign frame 0 to the captured piece at t=0" in {
    // black pawn + Idle → frameCount=4; floor(0.0 × 4)=0
    val info =
      mapper.map(captureState(0.0), S).capturedPiece.getOrElse(fail("expected capturedPiece"))
    info.frameIndex shouldBe 0
  }

  it should "assign a mid-range frame to the captured piece at the dead-phase midpoint" in {
    // t = midpoint of [attack1End, deadEnd] → Dead phase, localProgress = 0.5, frameCount=8: floor(0.5 × 8)=4
    val t = (attack1End + deadEnd) / 2
    val info =
      mapper.map(captureState(t), S).capturedPiece.getOrElse(fail("expected capturedPiece"))
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

  // ── segmentAssetKey — captured piece ──────────────────────────────────────

  it should "set segmentAssetKey to the Idle segment for the captured piece in the early phase" in {
    val info =
      mapper.map(captureState(0.0), S).capturedPiece.getOrElse(fail("expected capturedPiece"))
    info.segmentAssetKey shouldBe Some("classic/black_pawn_idle")
  }

  it should "set segmentAssetKey to the Idle segment for the captured piece just before the Dead phase" in {
    val info = mapper
      .map(captureState(attack1End - 0.05), S)
      .capturedPiece
      .getOrElse(fail("expected capturedPiece"))
    info.segmentAssetKey shouldBe Some("classic/black_pawn_idle")
  }

  // ── Fallback when no playback entry is registered ─────────────────────────

  it should "use a fallback key when the piece+state is not in the playback repo" in {
    // The testPlaybackRepo has no entry for White Knight + Move;
    // the mapper falls back to a derived key with frameIndex=0.
    val knightPlan = AnimationPlan((Color.White, PieceType.Knight), from, to, None)
    val knightState = AnimationState(knightPlan, 0.5)
    val model = mapper.map(knightState, S)
    model.movingPiece.segmentAssetKey shouldBe Some("classic/white_knight_move")
    model.movingPiece.frameIndex shouldBe 0
  }

  it should "use a fallback attack key when the piece has no attack entry in the repo" in {
    // No attack entry for White Knight; mapper falls back to derived key.
    val knightCapturePlan =
      AnimationPlan((Color.White, PieceType.Knight), from, to, Some(blackPawn))
    val knightCaptureState = AnimationState(knightCapturePlan, 0.5)
    val model = mapper.map(knightCaptureState, S)
    model.movingPiece.segmentAssetKey shouldBe Some("classic/white_knight_attack")
    model.movingPiece.frameIndex shouldBe 0
  }

  // ── Attack path: moving piece transitions through Move → Attack → Attack1 ──

  it should "select the Move segment for the moving piece in the Approach phase (t=0)" in {
    mapper.map(captureState(0.0), S).movingPiece.segmentAssetKey shouldBe
      Some("classic/white_pawn_move")
  }

  it should "select the first attack segment during the Attack phase" in {
    val tAttackMid = (approachEnd + attackEnd) / 2
    mapper.map(captureState(tAttackMid), S).movingPiece.segmentAssetKey shouldBe
      Some("classic/white_pawn_attack")
  }

  it should "select the second attack segment during the Attack1 phase and beyond" in {
    // Attack1 phase and the subsequent Dead/Fade phases all use segment index 1.
    val tAttack1Mid = (attackEnd + attack1End) / 2
    mapper.map(captureState(tAttack1Mid), S).movingPiece.segmentAssetKey shouldBe
      Some("classic/white_pawn_attack1")
    mapper.map(captureState(0.5), S).movingPiece.segmentAssetKey shouldBe
      Some("classic/white_pawn_attack1")
  }

  it should "assign correct frameIndex within segment 0 at t=0.25 (Attack phase, localProgress≈0.625, frameCount=6)" in {
    // t=0.25 → Attack phase; localProgress = (0.25 − approachEnd) / (attackEnd − approachEnd) ≈ 0.625
    // floor(0.625 × 6) = 3
    mapper.map(captureState(0.25), S).movingPiece.frameIndex shouldBe 3
  }

  it should "assign correct frameIndex within segment 1 at the Attack1-phase midpoint (localProgress=0.5, frameCount=6)" in {
    // t = midpoint of Attack1 phase → localProgress=0.5; floor(0.5 × 6)=3
    val tAttack1Mid = (attackEnd + attack1End) / 2
    mapper.map(captureState(tAttack1Mid), S).movingPiece.frameIndex shouldBe 3
  }

  // ── Captured piece — phase-based presentation ─────────────────────────────

  it should "show the captured piece in Idle state during the Approach phase" in {
    // t=0.1 < approachEnd → Approach phase
    val info =
      mapper.map(captureState(0.1), S).capturedPiece.getOrElse(fail("expected capturedPiece"))
    info.segmentAssetKey shouldBe Some("classic/black_pawn_idle")
    info.opacity shouldBe 1.0
  }

  it should "show the captured piece in Idle state during the Attack and Attack1 phases" in {
    // t = midpoint of Attack1 phase (still Idle for captured piece)
    val tAttack1Mid = (attackEnd + attack1End) / 2
    val info = mapper
      .map(captureState(tAttack1Mid), S)
      .capturedPiece
      .getOrElse(fail("expected capturedPiece"))
    info.segmentAssetKey shouldBe Some("classic/black_pawn_idle")
    info.opacity shouldBe 1.0
  }

  it should "show the captured piece in Dead state with full opacity during the dead phase" in {
    // t = midpoint of [attack1End, deadEnd) → Dead phase; opacity stays at 1.0
    val t = (attack1End + deadEnd) / 2
    val info =
      mapper.map(captureState(t), S).capturedPiece.getOrElse(fail("expected capturedPiece"))
    info.segmentAssetKey shouldBe Some("classic/black_pawn_dead")
    info.opacity shouldBe 1.0
  }

  it should "show full opacity throughout Approach, Attack1, and Dead phases" in {
    mapper
      .map(captureState(0.0), S)
      .capturedPiece
      .getOrElse(fail("expected capturedPiece"))
      .opacity shouldBe 1.0
    mapper
      .map(captureState(attack1End - 0.05), S)
      .capturedPiece
      .getOrElse(fail("expected capturedPiece"))
      .opacity shouldBe 1.0
    mapper
      .map(captureState((attack1End + deadEnd) / 2), S)
      .capturedPiece
      .getOrElse(fail("expected capturedPiece"))
      .opacity shouldBe 1.0
  }

  it should "begin fading exactly at the start of the Fade phase" in {
    // At exactly deadEnd: Fade localProgress=0.0 → opacity = 1.0 - 0.0 = 1.0 (fade just begins)
    val info =
      mapper.map(captureState(deadEnd), S).capturedPiece.getOrElse(fail("expected capturedPiece"))
    info.segmentAssetKey shouldBe Some("classic/black_pawn_dead")
    info.opacity shouldBe 1.0 +- 1e-10
  }

  it should "transition captured piece from Idle to Dead at the end of the Attack1 phase" in {
    val justBefore = mapper
      .map(captureState(attack1End - 0.01), S)
      .capturedPiece
      .getOrElse(fail("expected capturedPiece"))
    val atDead = mapper
      .map(captureState(attack1End), S)
      .capturedPiece
      .getOrElse(fail("expected capturedPiece"))
    justBefore.segmentAssetKey shouldBe Some("classic/black_pawn_idle")
    atDead.segmentAssetKey shouldBe Some("classic/black_pawn_dead")
  }

  it should "show the captured piece in Dead state throughout both the Dead and Fade phases" in {
    val atDeadMid = mapper
      .map(captureState((attack1End + deadEnd) / 2), S)
      .capturedPiece
      .getOrElse(fail("expected capturedPiece"))
    val atFadeMid = mapper
      .map(captureState((deadEnd + 1.0) / 2), S)
      .capturedPiece
      .getOrElse(fail("expected capturedPiece"))
    atDeadMid.segmentAssetKey shouldBe Some("classic/black_pawn_dead")
    atFadeMid.segmentAssetKey shouldBe Some("classic/black_pawn_dead")
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
    // At t=0.5, capture is in Dead phase → attacker locked at destination (400, 300).
    // Non-capture (Linear pawn) at t=0.5 → (200, 500). Trajectories differ.
    val capture = mapper.map(captureState(0.5), S).movingPiece
    val nonCapture = mapper.map(normalState(0.5), S).movingPiece
    capture.x should not be nonCapture.x
  }

  it should "place the capture attacker at the destination during the Attack phase" in {
    // From the Attack phase onward the moving piece is locked at the destination.
    val tAttackMid = (approachEnd + attackEnd) / 2
    val info = mapper.map(captureState(tAttackMid), S).movingPiece
    info.x shouldBe (400.0 +- 1e-9)
    info.y shouldBe (300.0 +- 1e-9)
  }

  it should "place the capture attacker at the destination during the Dead and Fade phases" in {
    val tDeadMid = (attack1End + deadEnd) / 2
    val tFadeMid = (deadEnd + 1.0) / 2
    mapper.map(captureState(tDeadMid), S).movingPiece.x shouldBe (400.0 +- 1e-9)
    mapper.map(captureState(tFadeMid), S).movingPiece.x shouldBe (400.0 +- 1e-9)
  }

  // ── Scale — always 1.0 ───────────────────────────────────────────────────

  it should "keep scale at 1.0 for the moving piece on a normal move" in {
    mapper.map(normalState(0.5), S).movingPiece.scale shouldBe 1.0
  }

  it should "keep scale at 1.0 for the captured piece throughout all capture phases" in {
    // Approach phase
    mapper
      .map(captureState(0.1), S)
      .capturedPiece
      .getOrElse(fail("expected capturedPiece"))
      .scale shouldBe 1.0
    // Attack1 phase
    mapper
      .map(captureState((attackEnd + attack1End) / 2), S)
      .capturedPiece
      .getOrElse(fail("expected capturedPiece"))
      .scale shouldBe 1.0
    // Dead phase
    mapper
      .map(captureState((attack1End + deadEnd) / 2), S)
      .capturedPiece
      .getOrElse(fail("expected capturedPiece"))
      .scale shouldBe 1.0
    // Fade phase
    mapper
      .map(captureState((deadEnd + 1.0) / 2), S)
      .capturedPiece
      .getOrElse(fail("expected capturedPiece"))
      .scale shouldBe 1.0
  }

  // ── flipX orientation branch coverage ─────────────────────────────────────

  it should "set flipX to true when the moving piece travels left (dx < 0)" in {
    val fromLeft = mkPos(4, 4) // x = 400
    val toLeft = mkPos(0, 0) // x = 0
    val leftPlan = AnimationPlan(
      movingPiece = whitePawn,
      from = fromLeft,
      to = toLeft,
      capturedPiece = None
    )
    val model = mapper.map(AnimationState(leftPlan, 0.5), S)

    model.movingPiece.flipX shouldBe true
  }

  it should "set flipX to false for a vertical move by a white piece (dx == 0)" in {
    // same rank -> same screen x
    val fromVertical = mkPos(0, 3)
    val toVertical = mkPos(4, 3)

    val verticalPlan = AnimationPlan(
      movingPiece = whitePawn,
      from = fromVertical,
      to = toVertical,
      capturedPiece = None
    )
    val model = mapper.map(AnimationState(verticalPlan, 0.5), S)

    model.movingPiece.flipX shouldBe false
  }

  it should "set flipX to true for a vertical move by a black piece (dx == 0)" in {
    // same rank -> same screen x
    val fromVertical = mkPos(0, 3)
    val toVertical = mkPos(4, 3)

    val verticalPlan = AnimationPlan(
      movingPiece = blackPawn,
      from = fromVertical,
      to = toVertical,
      capturedPiece = None
    )
    val model = mapper.map(AnimationState(verticalPlan, 0.5), S)

    model.movingPiece.flipX shouldBe true
  }
