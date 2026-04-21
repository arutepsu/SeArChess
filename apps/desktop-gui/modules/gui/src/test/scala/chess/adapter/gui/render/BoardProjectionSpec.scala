package chess.adapter.gui.render

import chess.domain.model.Position
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class BoardProjectionSpec extends AnyFlatSpec with Matchers:

  private def pos(file: Int, rank: Int): Position =
    Position.from(file, rank).getOrElse(throw AssertionError(s"Bad pos: $file,$rank"))

  // ── Column mapping (rank → col) ──────────────────────────────────────────

  "BoardProjection.toScreenCol" should "map rank to column directly" in {
    BoardProjection.toScreenCol(pos(0, 0)) shouldBe 0
    BoardProjection.toScreenCol(pos(0, 7)) shouldBe 7
    BoardProjection.toScreenCol(pos(4, 3)) shouldBe 3
  }

  it should "be independent of file" in {
    BoardProjection.toScreenCol(pos(0, 5)) shouldBe BoardProjection.toScreenCol(pos(7, 5))
  }

  // ── Row mapping (7 - file → row) ─────────────────────────────────────────

  "BoardProjection.toScreenRow" should "place file 7 at row 0 (top edge)" in {
    BoardProjection.toScreenRow(pos(7, 0)) shouldBe 0
  }

  it should "place file 0 at row 7 (bottom edge)" in {
    BoardProjection.toScreenRow(pos(0, 0)) shouldBe 7
  }

  it should "be independent of rank" in {
    BoardProjection.toScreenRow(pos(3, 0)) shouldBe BoardProjection.toScreenRow(pos(3, 7))
  }

  // ── Pixel X (rank * size) ────────────────────────────────────────────────

  "BoardProjection.toPixelX" should "place rank-0 squares at x=0" in {
    BoardProjection.toPixelX(pos(0, 0), 72.0) shouldBe 0.0
    BoardProjection.toPixelX(pos(7, 0), 72.0) shouldBe 0.0
  }

  it should "place rank-7 squares at x = 7 * squareSize" in {
    BoardProjection.toPixelX(pos(0, 7), 72.0) shouldBe (7 * 72.0)
  }

  it should "scale with squareSize" in {
    BoardProjection.toPixelX(pos(0, 4), 100.0) shouldBe 400.0
    BoardProjection.toPixelX(pos(0, 4), 72.0) shouldBe (4 * 72.0)
  }

  it should "be independent of file" in {
    BoardProjection.toPixelX(pos(0, 3), 100.0) shouldBe BoardProjection.toPixelX(pos(7, 3), 100.0)
  }

  // ── Pixel Y ((7 - file) * size) ──────────────────────────────────────────

  "BoardProjection.toPixelY" should "place file-7 squares at y=0 (top edge)" in {
    BoardProjection.toPixelY(pos(7, 0), 72.0) shouldBe 0.0
    BoardProjection.toPixelY(pos(7, 7), 72.0) shouldBe 0.0
  }

  it should "place file-0 squares at y = 7 * squareSize (bottom edge)" in {
    BoardProjection.toPixelY(pos(0, 0), 72.0) shouldBe (7 * 72.0)
  }

  it should "scale with squareSize" in {
    BoardProjection.toPixelY(pos(4, 0), 100.0) shouldBe 300.0 // (7-4)*100
  }

  it should "be independent of rank" in {
    BoardProjection.toPixelY(pos(3, 0), 100.0) shouldBe BoardProjection.toPixelY(pos(3, 7), 100.0)
  }

  // ── Grid covers all 64 squares without overlap ───────────────────────────

  "BoardProjection" should "assign distinct (col, row) pairs to all 64 squares" in {
    val cells = for
      file <- 0 to 7
      rank <- 0 to 7
      p = pos(file, rank)
    yield (BoardProjection.toScreenCol(p), BoardProjection.toScreenRow(p))
    cells.distinct.length shouldBe 64
  }

  it should "keep all col and row values in [0, 7]" in {
    for file <- 0 to 7; rank <- 0 to 7 do
      val p = pos(file, rank)
      BoardProjection.toScreenCol(p) should (be >= 0 and be <= 7)
      BoardProjection.toScreenRow(p) should (be >= 0 and be <= 7)
  }

  it should "be consistent: pixelX == col * size and pixelY == row * size" in {
    val size = 72.0
    for file <- 0 to 7; rank <- 0 to 7 do
      val p = pos(file, rank)
      BoardProjection.toPixelX(p, size) shouldBe BoardProjection.toScreenCol(p) * size
      BoardProjection.toPixelY(p, size) shouldBe BoardProjection.toScreenRow(p) * size
  }
