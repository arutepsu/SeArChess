package chess.lichessbridge

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class BlunderDetectorSpec extends AnyFlatSpec with Matchers:

  private def position(initialFen: String, moves: String) =
    GamePositionAdapter.toGameState(initialFen, moves).fold(
      err => fail(s"position replay failed: $err"),
      identity
    )

  "BlunderDetector.detect" should "return None for a clean starting position" in {
    val state = position("startpos", "")
    BlunderDetector.detect(state) shouldBe None
  }

  it should "return queen blunder message when opponent left queen hanging" in {
    // White: rook d1, king f1; Black: queen d4, king g6 — Rd1xd4 is free, king on g6 can't recapture
    val state = position("8/8/8/5k2/3q4/8/8/3RK3 w - - 0 1", "e1f1 f5g6")
    BlunderDetector.detect(state) shouldBe Some("Hoppla, deine Dame steht im Visier!")
  }

  it should "return None when the capturable piece is defended by the opponent king" in {
    // White: rook d1, king f1; Black: queen d5, king c4 — after Rd1xd5 black king c4 can recapture on d5
    val state = position("8/8/8/3q4/2k5/8/8/3R1K2 w - - 0 1", "")
    BlunderDetector.detect(state) shouldBe None
  }
