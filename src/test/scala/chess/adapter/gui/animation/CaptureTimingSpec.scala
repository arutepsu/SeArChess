package chess.adapter.gui.animation

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CaptureTimingSpec extends AnyFlatSpec with Matchers:

  "CaptureTiming" should "satisfy the phase ordering invariant HitStart < DeadStart < FadeEnd < 1.0" in {
    CaptureTiming.HitStart  should be < CaptureTiming.DeadStart
    CaptureTiming.DeadStart should be < CaptureTiming.FadeEnd
    CaptureTiming.FadeEnd   should be < 1.0
  }

  it should "place LungePeakT strictly before FadeEnd" in {
    CaptureTiming.LungePeakT should be < CaptureTiming.FadeEnd
  }

  it should "keep all thresholds strictly within (0, 1)" in {
    CaptureTiming.HitStart   should (be > 0.0 and be < 1.0)
    CaptureTiming.DeadStart  should (be > 0.0 and be < 1.0)
    CaptureTiming.FadeEnd    should (be > 0.0 and be < 1.0)
    CaptureTiming.LungePeakT should (be > 0.0 and be < 1.0)
  }

  // ── Impact pause zone invariants ─────────────────────────────────────────

  it should "keep the pause zone entirely within (0, 1)" in {
    (CaptureTiming.ImpactPauseCenter - CaptureTiming.ImpactPauseHalf) should be > 0.0
    (CaptureTiming.ImpactPauseCenter + CaptureTiming.ImpactPauseHalf) should be < 1.0
  }

  it should "have ImpactPauseFactor in (0, 1]" in {
    CaptureTiming.ImpactPauseFactor should (be > 0.0 and be <= 1.0)
  }

  // ── remapCapture ─────────────────────────────────────────────────────────

  "CaptureTiming.remapCapture" should "map 0.0 to 0.0" in {
    CaptureTiming.remapCapture(0.0) shouldBe 0.0
  }

  it should "map 1.0 to 1.0" in {
    CaptureTiming.remapCapture(1.0) shouldBe (1.0 +- 1e-10)
  }

  it should "advance slower inside the pause zone than outside for equal raw dt" in {
    val dt         = 0.04
    val center     = CaptureTiming.ImpactPauseCenter
    val inZoneDt   = CaptureTiming.remapCapture(center + dt / 2) - CaptureTiming.remapCapture(center - dt / 2)
    val outZoneDt  = CaptureTiming.remapCapture(0.20 + dt / 2)   - CaptureTiming.remapCapture(0.20 - dt / 2)
    inZoneDt should be < outZoneDt
  }

  it should "be monotonically non-decreasing" in {
    val ts = Seq(0.0, 0.1, 0.2, 0.3, 0.40, 0.46, 0.52, 0.58, 0.60, 0.7, 0.8, 0.9, 1.0)
    val mapped = ts.map(CaptureTiming.remapCapture)
    mapped.zip(mapped.tail).foreach { (a, b) => a should be <= b }
  }
