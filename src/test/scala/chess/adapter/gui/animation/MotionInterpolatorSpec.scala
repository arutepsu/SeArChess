package chess.adapter.gui.animation

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MotionInterpolatorSpec extends AnyFlatSpec with Matchers:

  // Source: (0, 0), destination: (400, 300) — same geometry as the presentation mapper tests.
  private val fromX = 0.0
  private val fromY = 0.0
  private val toX   = 400.0
  private val toY   = 300.0

  private val eps = 1e-9

  // ── Linear ───────────────────────────────────────────────────────────────

  "MotionInterpolator.interpolate (Linear)" should "place piece at source at t=0" in {
    val (x, y) = MotionInterpolator.interpolate(MotionStyle.Linear, fromX, fromY, toX, toY, 0.0)
    x shouldBe 0.0; y shouldBe 0.0
  }

  it should "place piece at destination at t=1" in {
    val (x, y) = MotionInterpolator.interpolate(MotionStyle.Linear, fromX, fromY, toX, toY, 1.0)
    x shouldBe 400.0; y shouldBe 300.0
  }

  it should "interpolate linearly at t=0.5" in {
    val (x, y) = MotionInterpolator.interpolate(MotionStyle.Linear, fromX, fromY, toX, toY, 0.5)
    x shouldBe 200.0; y shouldBe 150.0
  }

  // ── Smooth ───────────────────────────────────────────────────────────────

  "MotionInterpolator.interpolate (Smooth)" should "place piece at source at t=0" in {
    val (x, y) = MotionInterpolator.interpolate(MotionStyle.Smooth, fromX, fromY, toX, toY, 0.0)
    x shouldBe (0.0 +- eps); y shouldBe (0.0 +- eps)
  }

  it should "place piece at destination at t=1" in {
    val (x, y) = MotionInterpolator.interpolate(MotionStyle.Smooth, fromX, fromY, toX, toY, 1.0)
    x shouldBe (400.0 +- eps); y shouldBe (300.0 +- eps)
  }

  it should "place piece exactly at midpoint at t=0.5 (smoothstep is symmetric)" in {
    // smoothstep(0.5) = 3*0.25 - 2*0.125 = 0.75 - 0.25 = 0.5
    val (x, y) = MotionInterpolator.interpolate(MotionStyle.Smooth, fromX, fromY, toX, toY, 0.5)
    x shouldBe (200.0 +- eps); y shouldBe (150.0 +- eps)
  }

  it should "be slower than Linear near t=0 (ease-in)" in {
    val (xSmooth, _) = MotionInterpolator.interpolate(MotionStyle.Smooth, fromX, fromY, toX, toY, 0.1)
    val (xLinear, _) = MotionInterpolator.interpolate(MotionStyle.Linear, fromX, fromY, toX, toY, 0.1)
    xSmooth should be < xLinear
  }

  it should "be faster than Linear near t=0.5 then slow down (ease-out)" in {
    // By t=0.9 smoothstep has covered more ground than linear
    val (xSmooth, _) = MotionInterpolator.interpolate(MotionStyle.Smooth, fromX, fromY, toX, toY, 0.9)
    val (xLinear, _) = MotionInterpolator.interpolate(MotionStyle.Linear, fromX, fromY, toX, toY, 0.9)
    xSmooth should be > xLinear
  }

  // ── Heavy ─────────────────────────────────────────────────────────────────

  "MotionInterpolator.interpolate (Heavy)" should "place piece at source at t=0" in {
    val (x, y) = MotionInterpolator.interpolate(MotionStyle.Heavy, fromX, fromY, toX, toY, 0.0)
    x shouldBe (0.0 +- eps); y shouldBe (0.0 +- eps)
  }

  it should "place piece at destination at t=1" in {
    val (x, y) = MotionInterpolator.interpolate(MotionStyle.Heavy, fromX, fromY, toX, toY, 1.0)
    x shouldBe (400.0 +- eps); y shouldBe (300.0 +- eps)
  }

  it should "be slower than Smooth near t=0 (heavier ease-in)" in {
    val (xHeavy, _)  = MotionInterpolator.interpolate(MotionStyle.Heavy,  fromX, fromY, toX, toY, 0.2)
    val (xSmooth, _) = MotionInterpolator.interpolate(MotionStyle.Smooth, fromX, fromY, toX, toY, 0.2)
    xHeavy should be < xSmooth
  }

  // ── Arc ───────────────────────────────────────────────────────────────────

  "MotionInterpolator.interpolate (Arc)" should "place piece at source at t=0" in {
    val (x, y) = MotionInterpolator.interpolate(MotionStyle.Arc(0.5), fromX, fromY, toX, toY, 0.0)
    x shouldBe (0.0 +- eps); y shouldBe (0.0 +- eps)
  }

  it should "place piece at destination at t=1" in {
    val (x, y) = MotionInterpolator.interpolate(MotionStyle.Arc(0.5), fromX, fromY, toX, toY, 1.0)
    x shouldBe (400.0 +- eps); y shouldBe (300.0 +- eps)
  }

  it should "lift the piece above the straight-line path at t=0.5" in {
    // The arc lifts in -y, so y should be less than the straight-line midpoint.
    val (_, yArc)    = MotionInterpolator.interpolate(MotionStyle.Arc(0.5),    fromX, fromY, toX, toY, 0.5)
    val (_, yLinear) = MotionInterpolator.interpolate(MotionStyle.Linear, fromX, fromY, toX, toY, 0.5)
    yArc should be < yLinear
  }

  it should "produce zero lift when heightFraction is 0" in {
    val (_, yArc)    = MotionInterpolator.interpolate(MotionStyle.Arc(0.0), fromX, fromY, toX, toY, 0.5)
    val (_, ySmooth) = MotionInterpolator.interpolate(MotionStyle.Smooth, fromX, fromY, toX, toY, 0.5)
    // Arc base uses smoothstep; with heightFraction=0 there is no lift, so y = smooth base y
    yArc shouldBe ySmooth +- eps
  }

  it should "have greater lift at peak for larger heightFraction" in {
    val (_, y05) = MotionInterpolator.interpolate(MotionStyle.Arc(0.5), fromX, fromY, toX, toY, 0.5)
    val (_, y10) = MotionInterpolator.interpolate(MotionStyle.Arc(1.0), fromX, fromY, toX, toY, 0.5)
    y10 should be < y05  // larger lift → smaller y (higher on screen)
  }

  // ── AttackLunge ───────────────────────────────────────────────────────────
  // Horizontal test geometry: (0,0) → (400,0)  direction = (1,0)
  // overshootFraction=0.15 → overshootDist=60  osX=460, osY=0
  // impactT = CaptureTiming.LungePeakT = 0.65

  "MotionInterpolator.interpolate (AttackLunge)" should "place piece at source at t=0" in {
    val (x, y) = MotionInterpolator.interpolate(MotionStyle.AttackLunge(0.15), 0.0, 0.0, 400.0, 0.0, 0.0)
    x shouldBe (0.0 +- eps); y shouldBe (0.0 +- eps)
  }

  it should "place piece at destination at t=1" in {
    val (x, y) = MotionInterpolator.interpolate(MotionStyle.AttackLunge(0.15), 0.0, 0.0, 400.0, 0.0, 1.0)
    x shouldBe (400.0 +- eps); y shouldBe (0.0 +- eps)
  }

  it should "overshoot past the destination at t=impactT (horizontal)" in {
    // At impactT the lunge reaches the overshoot point: osX=460 > toX=400
    val impT   = CaptureTiming.LungePeakT
    val (x, _) = MotionInterpolator.interpolate(MotionStyle.AttackLunge(0.15), 0.0, 0.0, 400.0, 0.0, impT)
    x should be > 400.0
  }

  it should "overshoot to exactly the computed overshoot point at t=impactT" in {
    // smoothstep(1.0)=1.0 so position = overshoot = (460, 0)
    val impT   = CaptureTiming.LungePeakT
    val (x, y) = MotionInterpolator.interpolate(MotionStyle.AttackLunge(0.15), 0.0, 0.0, 400.0, 0.0, impT)
    x shouldBe (460.0 +- eps); y shouldBe (0.0 +- eps)
  }

  it should "produce no overshoot when overshootFraction is 0" in {
    // With no overshoot the phase-1 endpoint equals the destination itself.
    val impT   = CaptureTiming.LungePeakT
    val (x, y) = MotionInterpolator.interpolate(MotionStyle.AttackLunge(0.0), 0.0, 0.0, 400.0, 0.0, impT)
    x shouldBe (400.0 +- eps); y shouldBe (0.0 +- eps)
  }

  it should "overshoot vertically for a vertical capture" in {
    // (0,0) → (0,400), direction = (0,1), osY=460
    val impT   = CaptureTiming.LungePeakT
    val (x, y) = MotionInterpolator.interpolate(MotionStyle.AttackLunge(0.15), 0.0, 0.0, 0.0, 400.0, impT)
    x shouldBe (0.0 +- eps)   // no horizontal overshoot
    y should be > 400.0        // overshoots vertically
  }

  it should "overshoot diagonally for a diagonal capture" in {
    // (0,0) → (300,400), dist=500, direction=(0.6,0.8)
    // osX = 300 + 0.15*500*0.6 = 345, osY = 400 + 0.15*500*0.8 = 460
    val impT   = CaptureTiming.LungePeakT
    val (x, y) = MotionInterpolator.interpolate(MotionStyle.AttackLunge(0.15), 0.0, 0.0, 300.0, 400.0, impT)
    x should be > 300.0   // both axes overshoot
    y should be > 400.0
  }

  it should "return destination immediately when source equals destination" in {
    val (x, y) = MotionInterpolator.interpolate(MotionStyle.AttackLunge(0.15), 200.0, 150.0, 200.0, 150.0, 0.5)
    x shouldBe 200.0; y shouldBe 150.0
  }
