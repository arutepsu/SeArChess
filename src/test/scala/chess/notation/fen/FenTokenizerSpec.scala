package chess.notation.fen

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.EitherValues
import chess.notation.api.{ParseFailure}
import chess.notation.fen.FenTokenizer.FenTokens

class FenTokenizerSpec extends AnyFlatSpec with Matchers with EitherValues:

  private val InitialFen =
    "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

  // ── Success ─────────────────────────────────────────────────────────────────

  "FenTokenizer" should "split a valid 6-field FEN into labelled tokens" in {
    val result = FenTokenizer.tokenize(InitialFen)
    val tokens = result.value
    tokens.piecePlacement shouldBe "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"
    tokens.activeColor    shouldBe "w"
    tokens.castling       shouldBe "KQkq"
    tokens.enPassant      shouldBe "-"
    tokens.halfmoveClock  shouldBe "0"
    tokens.fullmoveNumber shouldBe "1"
  }

  it should "accept a FEN with an en passant square in field 4" in {
    val fen    = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
    val tokens = FenTokenizer.tokenize(fen).value
    tokens.enPassant shouldBe "e3"
  }

  // ── Empty input ──────────────────────────────────────────────────────────────

  it should "return UnexpectedEndOfInput for an empty string" in {
    FenTokenizer.tokenize("").left.value shouldBe a[ParseFailure.UnexpectedEndOfInput]
  }

  // ── Wrong field count ────────────────────────────────────────────────────────

  it should "return StructuralError for too few fields (5)" in {
    val result = FenTokenizer.tokenize("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0")
    result.left.value shouldBe a[ParseFailure.StructuralError]
  }

  it should "return StructuralError for too many fields (7)" in {
    val result = FenTokenizer.tokenize("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1 extra")
    result.left.value shouldBe a[ParseFailure.StructuralError]
  }

  it should "return StructuralError for a double space between fields" in {
    // double space creates an empty token → 7 fields
    val result = FenTokenizer.tokenize("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR  w KQkq - 0 1")
    result.left.value shouldBe a[ParseFailure.StructuralError]
  }

  it should "include the actual field count in the error message" in {
    val msg = FenTokenizer.tokenize("a b c").left.value.message
    msg should include("3")
  }
