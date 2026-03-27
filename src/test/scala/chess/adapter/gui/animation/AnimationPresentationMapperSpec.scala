package chess.adapter.gui.animation

import chess.domain.model.{Color, PieceType, Position}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AnimationPresentationMapperSpec extends AnyFlatSpec with Matchers:

  // Use squareSize=100 for clean arithmetic.
  private val S = 100.0

  private def mkPos(file: Int, rank: Int): Position =
    Position.from(file, rank).getOrElse(throw AssertionError(s"Bad pos: $file,$rank"))

  // from = a1 (file=0, rank=0) → squareX=0, squareY=(7-0)*100=700
  // to   = e5 (file=4, rank=4) → squareX=400, squareY=(7-4)*100=300
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

  private def normalState(p: Double) = AnimationState(normalPlan, p)
  private def captureState(p: Double) = AnimationState(capturePlan, p)

  // ── Moving piece — normal move ────────────────────────────────────────────

  "AnimationPresentationMapper.map" should "place the moving piece at the source at t=0" in {
    val model = AnimationPresentationMapper.map(normalState(0.0), S)
    model.movingPiece.x shouldBe 0.0
    model.movingPiece.y shouldBe 700.0
  }

  it should "place the moving piece at the destination at t=1" in {
    val model = AnimationPresentationMapper.map(normalState(1.0), S)
    model.movingPiece.x shouldBe 400.0
    model.movingPiece.y shouldBe 300.0
  }

  it should "interpolate the moving piece position at t=0.5" in {
    val model = AnimationPresentationMapper.map(normalState(0.5), S)
    model.movingPiece.x shouldBe 200.0
    model.movingPiece.y shouldBe 500.0
  }

  it should "report the correct moving piece type" in {
    val model = AnimationPresentationMapper.map(normalState(0.5), S)
    model.movingPiece.piece shouldBe whitePawn
  }

  it should "set the moving piece opacity to 1.0" in {
    val model = AnimationPresentationMapper.map(normalState(0.5), S)
    model.movingPiece.opacity shouldBe 1.0
  }

  // ── Suppression ──────────────────────────────────────────────────────────

  it should "suppress the destination square" in {
    AnimationPresentationMapper.map(normalState(0.0), S).suppressedSquare shouldBe Some(to)
    AnimationPresentationMapper.map(normalState(0.5), S).suppressedSquare shouldBe Some(to)
    AnimationPresentationMapper.map(normalState(1.0), S).suppressedSquare shouldBe Some(to)
  }

  // ── No captured piece for a normal move ──────────────────────────────────

  it should "produce no captured piece for a normal (non-capture) move" in {
    AnimationPresentationMapper.map(normalState(0.0), S).capturedPiece shouldBe None
    AnimationPresentationMapper.map(normalState(0.5), S).capturedPiece shouldBe None
    AnimationPresentationMapper.map(normalState(1.0), S).capturedPiece shouldBe None
  }

  // ── Captured piece — capture move ─────────────────────────────────────────

  it should "show the captured piece at the destination at t=0" in {
    val model = AnimationPresentationMapper.map(captureState(0.0), S)
    model.capturedPiece shouldBe defined
    val info = model.capturedPiece.get
    info.piece   shouldBe blackPawn
    info.x       shouldBe 400.0
    info.y       shouldBe 300.0
    info.opacity shouldBe 1.0
  }

  it should "show the captured piece with reduced opacity before the threshold" in {
    val model = AnimationPresentationMapper.map(captureState(AnimationState.CaptureThreshold / 2), S)
    model.capturedPiece shouldBe defined
    val opacity = model.capturedPiece.get.opacity
    opacity should be > 0.0
    opacity should be < 1.0
  }

  it should "decrease captured-piece opacity as progress increases before the threshold" in {
    val early = AnimationPresentationMapper.map(captureState(0.1), S).capturedPiece.get.opacity
    val late  = AnimationPresentationMapper.map(captureState(0.5), S).capturedPiece.get.opacity
    early should be > late
  }

  it should "hide the captured piece at exactly the capture threshold" in {
    val model = AnimationPresentationMapper.map(captureState(AnimationState.CaptureThreshold), S)
    model.capturedPiece shouldBe None
  }

  it should "hide the captured piece after the capture threshold" in {
    AnimationPresentationMapper.map(captureState(0.8), S).capturedPiece shouldBe None
    AnimationPresentationMapper.map(captureState(1.0), S).capturedPiece shouldBe None
  }

  // ── Boundary: out-of-range progress ──────────────────────────────────────

  it should "clamp negative progress to the source position" in {
    val model = AnimationPresentationMapper.map(normalState(-0.5), S)
    model.movingPiece.x shouldBe 0.0
    model.movingPiece.y shouldBe 700.0
  }

  it should "clamp progress > 1 to the destination position" in {
    val model = AnimationPresentationMapper.map(normalState(2.0), S)
    model.movingPiece.x shouldBe 400.0
    model.movingPiece.y shouldBe 300.0
  }

  it should "not show a captured piece when progress is negative (below threshold)" in {
    // Negative progress is clamped to 0 which is below CaptureThreshold → piece visible
    val model = AnimationPresentationMapper.map(captureState(-0.1), S)
    model.capturedPiece shouldBe defined
  }

  it should "not show a captured piece when progress is clamped to 1 (above threshold)" in {
    val model = AnimationPresentationMapper.map(captureState(1.5), S)
    model.capturedPiece shouldBe None
  }

  // ── Default square size ───────────────────────────────────────────────────

  it should "use DefaultSquareSize when no explicit size is passed" in {
    val S2    = AnimationPresentationMapper.DefaultSquareSize
    val model = AnimationPresentationMapper.map(normalState(1.0))
    model.movingPiece.x shouldBe mkPos(4, 4).file * S2
    model.movingPiece.y shouldBe (7 - mkPos(4, 4).rank) * S2
  }

  // ── Frame indices — moving piece (white pawn, Move state, frameCount=4) ──

  it should "assign frame 0 to the moving piece at t=0" in {
    // white pawn + Move → frameCount=4; floor(0.0 × 4)=0
    AnimationPresentationMapper.map(normalState(0.0), S).movingPiece.frameIndex shouldBe 0
  }

  it should "assign frame 3 (last) to the moving piece at t=1" in {
    // floor(1.0 × 4)=4 → clamped to 3
    AnimationPresentationMapper.map(normalState(1.0), S).movingPiece.frameIndex shouldBe 3
  }

  it should "assign a mid-range frame to the moving piece at t=0.5" in {
    // floor(0.5 × 4)=2
    AnimationPresentationMapper.map(normalState(0.5), S).movingPiece.frameIndex shouldBe 2
  }

  // ── Frame indices — captured piece (black pawn, Dead state, frameCount=8) ─

  it should "assign frame 0 to the captured piece at t=0" in {
    // black pawn + Dead → frameCount=8; floor(0.0 × 8)=0
    val info = AnimationPresentationMapper.map(captureState(0.0), S).capturedPiece.get
    info.frameIndex shouldBe 0
  }

  it should "assign a mid-range frame to the captured piece at t=0.5 (before threshold)" in {
    // floor(0.5 × 8)=4
    val info = AnimationPresentationMapper.map(captureState(0.5), S).capturedPiece.get
    info.frameIndex shouldBe 4
  }
