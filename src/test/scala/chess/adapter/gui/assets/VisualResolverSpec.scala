package chess.adapter.gui.assets

import chess.domain.model.{Color, PieceType}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class VisualResolverSpec extends AnyFlatSpec with Matchers:

  // ── Helpers ─────────────────────────────────────────────────────────────────

  private def resolve(color: Color, pt: PieceType, state: VisualState): VisualDescriptor =
    VisualResolver.resolve(PieceVisualId(color, pt, state))

  private def idleWhite(pt: PieceType): VisualDescriptor = resolve(Color.White, pt, VisualState.Idle)

  // ── Asset key — all piece types (White / Idle, covers pieceType branches) ──

  "VisualResolver.resolve" should "produce the correct asset key for a white king (Idle)" in {
    idleWhite(PieceType.King).assetKey shouldBe "classic/white_king_idle"
  }

  it should "produce the correct asset key for a white queen (Idle)" in {
    idleWhite(PieceType.Queen).assetKey shouldBe "classic/white_queen_idle"
  }

  it should "produce the correct asset key for a white rook (Idle)" in {
    idleWhite(PieceType.Rook).assetKey shouldBe "classic/white_rook_idle"
  }

  it should "produce the correct asset key for a white bishop (Idle)" in {
    idleWhite(PieceType.Bishop).assetKey shouldBe "classic/white_bishop_idle"
  }

  it should "produce the correct asset key for a white knight (Idle)" in {
    idleWhite(PieceType.Knight).assetKey shouldBe "classic/white_knight_idle"
  }

  it should "produce the correct asset key for a white pawn (Idle)" in {
    idleWhite(PieceType.Pawn).assetKey shouldBe "classic/white_pawn_idle"
  }

  // ── Asset key — Black color branch ──────────────────────────────────────────

  it should "produce the correct asset key for a black king (Idle)" in {
    resolve(Color.Black, PieceType.King, VisualState.Idle).assetKey shouldBe "classic/black_king_idle"
  }

  // ── Asset key — all visual states (White / King, covers state branches) ─────

  it should "produce the correct asset key for a Move state" in {
    resolve(Color.White, PieceType.King, VisualState.Move).assetKey shouldBe "classic/white_king_move"
  }

  it should "produce the correct asset key for an Attack state" in {
    resolve(Color.White, PieceType.King, VisualState.Attack).assetKey shouldBe "classic/white_king_attack"
  }

  it should "produce the correct asset key for a Hit state" in {
    resolve(Color.White, PieceType.King, VisualState.Hit).assetKey shouldBe "classic/white_king_hit"
  }

  it should "produce the correct asset key for a Dead state" in {
    resolve(Color.White, PieceType.King, VisualState.Dead).assetKey shouldBe "classic/white_king_dead"
  }

  // ── Descriptor placeholder invariants ───────────────────────────────────────

  it should "set frameCount to 1 for all piece + state combinations (placeholder)" in {
    for
      color <- Seq(Color.White, Color.Black)
      pt    <- PieceType.values
      state <- VisualState.values
    do resolve(color, pt, state).frameCount shouldBe 1
  }

  it should "leave frameSize as None for all combinations (not yet measured)" in {
    for
      color <- Seq(Color.White, Color.Black)
      pt    <- PieceType.values
      state <- VisualState.values
    do resolve(color, pt, state).frameSize shouldBe None
  }

  it should "leave displaySize as None for all combinations (use square size)" in {
    for
      color <- Seq(Color.White, Color.Black)
      pt    <- PieceType.values
      state <- VisualState.values
    do resolve(color, pt, state).displaySize shouldBe None
  }

  it should "leave anchor as None for all combinations (top-left default)" in {
    for
      color <- Seq(Color.White, Color.Black)
      pt    <- PieceType.values
      state <- VisualState.values
    do resolve(color, pt, state).anchor shouldBe None
  }

  // ── Fallback symbol integration ──────────────────────────────────────────────

  it should "match the PieceSymbol fallback for all piece type + color combinations" in {
    for
      color <- Seq(Color.White, Color.Black)
      pt    <- PieceType.values
    do
      val descriptor = resolve(color, pt, VisualState.Idle)
      descriptor.fallbackSymbol shouldBe PieceSymbol.symbol(color, pt)
  }

  it should "return the same fallback symbol regardless of visual state" in {
    // The fallback glyph is state-independent — it represents the piece identity only.
    val states = VisualState.values.toSeq
    val glyphs = states.map(s => resolve(Color.White, PieceType.Queen, s).fallbackSymbol)
    glyphs.toSet should have size 1
  }

  // ── Asset key non-empty invariant ────────────────────────────────────────────

  it should "never produce an empty asset key" in {
    for
      color <- Seq(Color.White, Color.Black)
      pt    <- PieceType.values
      state <- VisualState.values
    do resolve(color, pt, state).assetKey should not be empty
  }

  // ── PieceVisualId equality ───────────────────────────────────────────────────

  it should "produce distinct descriptors for distinct PieceVisualIds" in {
    val whiteKingIdle = resolve(Color.White, PieceType.King, VisualState.Idle)
    val blackKingIdle = resolve(Color.Black, PieceType.King, VisualState.Idle)
    val whiteKingMove = resolve(Color.White, PieceType.King, VisualState.Move)
    whiteKingIdle.assetKey should not equal blackKingIdle.assetKey
    whiteKingIdle.assetKey should not equal whiteKingMove.assetKey
  }
