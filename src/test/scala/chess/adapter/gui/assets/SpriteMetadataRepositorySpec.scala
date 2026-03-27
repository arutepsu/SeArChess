package chess.adapter.gui.assets

import chess.domain.model.{Color, PieceType}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SpriteMetadataRepositorySpec extends AnyFlatSpec with Matchers:

  // ── Helpers ─────────────────────────────────────────────────────────────────

  private def lookupExpected(assetKey: String): SpriteMetadata =
    SpriteMetadataRepository.lookup(assetKey)
      .getOrElse(fail(s"Expected metadata for '$assetKey' but got None"))

  // ── Successful lookup — asset key structure ──────────────────────────────────

  "SpriteMetadataRepository.lookup" should "return Some for a known idle asset key" in {
    val meta = lookupExpected("classic/white_king_idle")
    meta.assetKey shouldBe "classic/white_king_idle"
  }

  it should "return Some for a known move asset key" in {
    SpriteMetadataRepository.lookup("classic/black_knight_move") shouldBe defined
  }

  it should "return Some for a known attack asset key" in {
    SpriteMetadataRepository.lookup("classic/white_pawn_attack") shouldBe defined
  }

  it should "return Some for a known hit asset key" in {
    SpriteMetadataRepository.lookup("classic/black_rook_hit") shouldBe defined
  }

  it should "return Some for a known dead asset key" in {
    SpriteMetadataRepository.lookup("classic/white_queen_dead") shouldBe defined
  }

  it should "return None for an unknown asset key" in {
    SpriteMetadataRepository.lookup("classic/white_king_flying") shouldBe None
  }

  // ── Frame counts vary by state ───────────────────────────────────────────────

  it should "assign frameCount 1 to Idle assets" in {
    lookupExpected("classic/white_king_idle").frameCount shouldBe 1
  }

  it should "assign frameCount 4 to Move assets" in {
    lookupExpected("classic/black_bishop_move").frameCount shouldBe 4
  }

  it should "assign frameCount 6 to Attack assets" in {
    lookupExpected("classic/white_rook_attack").frameCount shouldBe 6
  }

  it should "assign frameCount 3 to Hit assets" in {
    lookupExpected("classic/black_queen_hit").frameCount shouldBe 3
  }

  it should "assign frameCount 8 to Dead assets" in {
    lookupExpected("classic/white_knight_dead").frameCount shouldBe 8
  }

  // ── Frame size ───────────────────────────────────────────────────────────────

  it should "assign a non-zero frameSize to all assets" in {
    val meta = lookupExpected("classic/white_pawn_idle")
    val (w, h) = meta.frameSize
    w should be > 0
    h should be > 0
  }

  it should "assign the same placeholder frameSize across different states" in {
    val idleSize  = lookupExpected("classic/white_king_idle").frameSize
    val moveSize  = lookupExpected("classic/white_king_move").frameSize
    val attackSize = lookupExpected("classic/white_king_attack").frameSize
    idleSize  shouldBe moveSize
    moveSize  shouldBe attackSize
  }

  // ── Optional fields default to None ─────────────────────────────────────────

  it should "leave displaySize as None for placeholder assets" in {
    lookupExpected("classic/black_king_idle").displaySize shouldBe None
  }

  it should "leave anchor as None for placeholder assets" in {
    lookupExpected("classic/black_queen_move").anchor shouldBe None
  }

  // ── Completeness — all 60 combinations must be present ───────────────────────

  it should "contain metadata for every color + pieceType + state combination" in {
    for
      color <- Seq(Color.White, Color.Black)
      pt    <- PieceType.values
      state <- VisualState.values
    do
      val id  = PieceVisualId(color, pt, state)
      val key = VisualResolver.resolve(id).assetKey
      withClue(s"missing metadata for key '$key'") {
        SpriteMetadataRepository.lookup(key) shouldBe defined
      }
  }

  // ── Frame-count variety — at least two distinct counts exist ─────────────────

  it should "expose more than one distinct frame count across all states" in {
    val counts = VisualState.values.map { state =>
      SpriteMetadataRepository.lookup(s"classic/white_king_${state.toString.toLowerCase}")
        .map(_.frameCount)
        .getOrElse(fail(s"Missing metadata for state $state"))
    }.toSet
    counts.size should be > 1
  }
