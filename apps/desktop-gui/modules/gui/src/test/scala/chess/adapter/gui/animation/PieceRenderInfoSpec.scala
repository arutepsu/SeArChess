import chess.adapter.gui.assets.VisualState
import chess.adapter.gui.animation.PieceRenderInfo
import chess.domain.model.{Color, PieceType}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PieceRenderInfoSpec extends AnyFlatSpec with Matchers:

  "PieceRenderInfo" should "default frameIndex to 0 when not provided" in {
    val info = PieceRenderInfo(
      piece = (Color.White, PieceType.Pawn),
      x = 100.0,
      y = 200.0,
      visualState = VisualState.Move
    )

    info.frameIndex shouldBe 0
  }

  it should "default segmentAssetKey to None when not provided" in {
    val info = PieceRenderInfo(
      piece = (Color.White, PieceType.Pawn),
      x = 100.0,
      y = 200.0,
      visualState = VisualState.Move
    )

    info.segmentAssetKey shouldBe None
  }

  it should "keep explicitly provided frameIndex and segmentAssetKey values" in {
    val info = PieceRenderInfo(
      piece = (Color.Black, PieceType.Knight),
      x = 50.0,
      y = 75.0,
      visualState = VisualState.Attack,
      frameIndex = 3,
      segmentAssetKey = Some("classic/black_knight_attack")
    )

    info.frameIndex shouldBe 3
    info.segmentAssetKey shouldBe Some("classic/black_knight_attack")
  }
