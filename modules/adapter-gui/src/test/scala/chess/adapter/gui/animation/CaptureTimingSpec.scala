package chess.adapter.gui.animation

import chess.domain.model.{Color, PieceType, Position}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CaptureTimingSpec extends AnyFlatSpec with Matchers:

  // Minimal plan used only for its captureTimings (default CapturePhaseTimings).
  private val dummyPlan = AnimationPlan(
    movingPiece   = (Color.White, PieceType.Pawn),
    from          = Position.from(0, 0).getOrElse(throw AssertionError("bad pos")),
    to            = Position.from(1, 1).getOrElse(throw AssertionError("bad pos")),
    capturedPiece = Some((Color.Black, PieceType.Pawn))
  )

  // Phase boundaries as fractions of total animation duration.
  // Default timings: approach=400ms, attack=500ms, attack1=500ms, dead=1000ms,
  //   fade=450ms, total=2850ms.
  private val timings     = CapturePhaseTimings()
  private val total       = timings.totalMs.toDouble
  private val approachEnd = timings.approachMs / total
  private val attackEnd   = (timings.approachMs + timings.attackMs) / total
  private val attack1End  = (timings.approachMs + timings.attackMs + timings.attack1Ms) / total
  private val deadEnd     = (timings.approachMs + timings.attackMs + timings.attack1Ms + timings.deadMs) / total

  // ── Phase ordering ────────────────────────────────────────────────────────

  "CaptureTiming.resolve" should "assign the Approach phase at t=0.0" in {
    CaptureTiming.resolve(dummyPlan, 0.0).phase shouldBe CaptureTiming.Phase.Approach
  }

  it should "assign the Attack phase at the Attack-phase midpoint" in {
    val t = (approachEnd + attackEnd) / 2
    CaptureTiming.resolve(dummyPlan, t).phase shouldBe CaptureTiming.Phase.Attack
  }

  it should "assign the Attack1 phase at the Attack1-phase midpoint" in {
    val t = (attackEnd + attack1End) / 2
    CaptureTiming.resolve(dummyPlan, t).phase shouldBe CaptureTiming.Phase.Attack1
  }

  it should "assign the Dead phase at the Dead-phase midpoint" in {
    val t = (attack1End + deadEnd) / 2
    CaptureTiming.resolve(dummyPlan, t).phase shouldBe CaptureTiming.Phase.Dead
  }

  it should "assign the Fade phase at t=1.0" in {
    CaptureTiming.resolve(dummyPlan, 1.0).phase shouldBe CaptureTiming.Phase.Fade
  }

  // ── Phase transitions at boundaries ──────────────────────────────────────

  it should "transition from Approach to Attack at approachEnd" in {
    CaptureTiming.resolve(dummyPlan, approachEnd - 0.001).phase shouldBe CaptureTiming.Phase.Approach
    CaptureTiming.resolve(dummyPlan, approachEnd).phase          shouldBe CaptureTiming.Phase.Attack
  }

  it should "transition from Attack to Attack1 at attackEnd" in {
    CaptureTiming.resolve(dummyPlan, attackEnd - 0.001).phase shouldBe CaptureTiming.Phase.Attack
    CaptureTiming.resolve(dummyPlan, attackEnd).phase          shouldBe CaptureTiming.Phase.Attack1
  }

  it should "transition from Attack1 to Dead at attack1End" in {
    CaptureTiming.resolve(dummyPlan, attack1End - 0.001).phase shouldBe CaptureTiming.Phase.Attack1
    CaptureTiming.resolve(dummyPlan, attack1End).phase          shouldBe CaptureTiming.Phase.Dead
  }

  it should "transition from Dead to Fade at deadEnd" in {
    CaptureTiming.resolve(dummyPlan, deadEnd - 0.001).phase shouldBe CaptureTiming.Phase.Dead
    CaptureTiming.resolve(dummyPlan, deadEnd).phase          shouldBe CaptureTiming.Phase.Fade
  }

  // ── Phase-boundary ordering invariants ───────────────────────────────────

  it should "satisfy the phase-boundary ordering: approachEnd < attackEnd < attack1End < deadEnd < 1.0" in {
    approachEnd should be < attackEnd
    attackEnd   should be < attack1End
    attack1End  should be < deadEnd
    deadEnd     should be < 1.0
  }

  it should "keep all phase boundaries strictly within (0, 1)" in {
    approachEnd should (be > 0.0 and be < 1.0)
    attackEnd   should (be > 0.0 and be < 1.0)
    attack1End  should (be > 0.0 and be < 1.0)
    deadEnd     should (be > 0.0 and be < 1.0)
  }

  // ── localProgress computation ─────────────────────────────────────────────

  it should "return localProgress=0.0 at the start of each phase" in {
    CaptureTiming.resolve(dummyPlan, 0.0).localProgress        shouldBe 0.0 +- 1e-10
    CaptureTiming.resolve(dummyPlan, approachEnd).localProgress shouldBe 0.0 +- 1e-10
    CaptureTiming.resolve(dummyPlan, attackEnd).localProgress   shouldBe 0.0 +- 1e-10
    CaptureTiming.resolve(dummyPlan, attack1End).localProgress  shouldBe 0.0 +- 1e-10
    CaptureTiming.resolve(dummyPlan, deadEnd).localProgress     shouldBe 0.0 +- 1e-10
  }

  it should "return localProgress=0.5 at the midpoint of each phase" in {
    CaptureTiming.resolve(dummyPlan, approachEnd / 2).localProgress              shouldBe 0.5 +- 1e-10
    CaptureTiming.resolve(dummyPlan, (approachEnd + attackEnd) / 2).localProgress shouldBe 0.5 +- 1e-10
    CaptureTiming.resolve(dummyPlan, (attackEnd + attack1End) / 2).localProgress  shouldBe 0.5 +- 1e-10
    CaptureTiming.resolve(dummyPlan, (attack1End + deadEnd) / 2).localProgress    shouldBe 0.5 +- 1e-10
    CaptureTiming.resolve(dummyPlan, (deadEnd + 1.0) / 2).localProgress           shouldBe 0.5 +- 1e-10
  }

  it should "keep localProgress in [0, 1] for all progress values" in {
    val testValues = Seq(0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0)
    for t <- testValues do
      val lp = CaptureTiming.resolve(dummyPlan, t).localProgress
      lp should (be >= 0.0 and be <= 1.0)
  }

  it should "produce monotonically non-decreasing localProgress within each phase" in {
    // Sample several t values inside the Dead phase and verify localProgress increases.
    val deadTs = Seq(attack1End, (attack1End + deadEnd) / 2, deadEnd - 0.001)
    val deadLps = deadTs.map(t => CaptureTiming.resolve(dummyPlan, t).localProgress)
    deadLps.zip(deadLps.tail).foreach { (a, b) => a should be <= b }
  }

  // ── Clamping behavior ─────────────────────────────────────────────────────

  it should "clamp negative progress to the Approach phase at localProgress=0.0" in {
    val pp = CaptureTiming.resolve(dummyPlan, -0.5)
    pp.phase         shouldBe CaptureTiming.Phase.Approach
    pp.localProgress shouldBe 0.0 +- 1e-10
  }

  it should "clamp progress > 1.0 to the Fade phase at localProgress=1.0" in {
    val pp = CaptureTiming.resolve(dummyPlan, 1.5)
    pp.phase         shouldBe CaptureTiming.Phase.Fade
    pp.localProgress shouldBe 1.0 +- 1e-10
  }

  // ── Zero-duration phase (local: end <= start → 1.0) ──────────────────────

  it should "return localProgress=1.0 for a zero-duration Fade phase (end <= start guard)" in {
    // CapturePhaseTimings(fadeMs=0) makes endFade == endDead, so local(endDead, endFade)
    // hits the `if end <= start then 1.0` branch.
    val zeroFadePlan = AnimationPlan(
      movingPiece   = (Color.White, PieceType.Pawn),
      from          = Position.from(0, 0).getOrElse(throw AssertionError("bad pos")),
      to            = Position.from(1, 1).getOrElse(throw AssertionError("bad pos")),
      capturedPiece = Some((Color.Black, PieceType.Pawn)),
      captureTimings = CapturePhaseTimings(fadeMs = 0)
    )
    val pp = CaptureTiming.resolve(zeroFadePlan, 1.0)
    pp.phase         shouldBe CaptureTiming.Phase.Fade
    pp.localProgress shouldBe 1.0 +- 1e-10
  }
