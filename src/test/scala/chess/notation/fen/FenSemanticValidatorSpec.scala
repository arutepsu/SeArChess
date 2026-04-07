package chess.notation.fen

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.EitherValues
import chess.notation.api.{FenData, ParsedNotation, ValidationFailure}

/** Unit tests for [[FenSemanticValidator]].
 *
 *  Uses [[FenParser.parse]] to obtain valid [[FenData]] values without
 *  hand-constructing them.
 */
class FenSemanticValidatorSpec extends AnyFlatSpec with Matchers with EitherValues:

  private val InitialFen   = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
  private val SimpleFen    = "8/7k/8/8/8/8/8/4K3 w - - 0 1"  // both kings, no castling

  private def data(fen: String) =
    FenParser.parse(fen).value.asInstanceOf[ParsedNotation.ParsedFen].data

  // ── Valid positions ──────────────────────────────────────────────────────────

  "FenSemanticValidator" should "accept the standard initial position" in {
    FenSemanticValidator.validate(data(InitialFen)) shouldBe Right(())
  }

  it should "accept a minimal two-king position" in {
    FenSemanticValidator.validate(data(SimpleFen)) shouldBe Right(())
  }

  // ── King count ───────────────────────────────────────────────────────────────

  it should "reject a position with no white king" in {
    // Only a black king on a1; no white king
    val d = data("8/8/8/8/8/8/8/7k b - - 0 1")
    val err = FenSemanticValidator.validate(d).left.value
    err shouldBe a[ValidationFailure.MissingRequired]
    err.message should include("white king")
  }

  it should "reject a position with no black king" in {
    val d = data("8/8/8/8/8/8/8/4K3 w - - 0 1")
    val err = FenSemanticValidator.validate(d).left.value
    err shouldBe a[ValidationFailure.MissingRequired]
    err.message should include("black king")
  }

  it should "reject a position with two white kings" in {
    // White king at a8 and e1, black king at h8
    val d = data("K6k/8/8/8/8/8/8/4K3 w - - 0 1")
    val err = FenSemanticValidator.validate(d).left.value
    err shouldBe a[ValidationFailure.InvalidValue]
    err.message should include("white king")
    err.message should include("2")
  }

  // ── Castling rights ──────────────────────────────────────────────────────────

  it should "accept full castling rights when all home pieces are present" in {
    FenSemanticValidator.validate(data(InitialFen)) shouldBe Right(())
  }

  it should "reject K right when white king is not on e1" in {
    // White king on b1 (file 1), not e1 (file 4); white rook on h1; black king on h7
    val d = data("8/7k/8/8/8/8/8/1K5R w K - 0 1")
    val err = FenSemanticValidator.validate(d).left.value
    err shouldBe a[ValidationFailure.InvalidValue]
    err.message should include("K")
    err.message should include("king")
  }

  it should "reject K right when white king-side rook is not on h1" in {
    // White king on e1, no rook on h1; black king on h7
    val d = data("8/7k/8/8/8/8/8/4K3 w K - 0 1")
    val err = FenSemanticValidator.validate(d).left.value
    err shouldBe a[ValidationFailure.InvalidValue]
    err.message should include("K")
    err.message should include("rook")
  }

  it should "reject Q right when white queen-side rook is not on a1" in {
    // White king on e1, no rook on a1; black king on h7
    val d = data("8/7k/8/8/8/8/8/4K3 w Q - 0 1")
    val err = FenSemanticValidator.validate(d).left.value
    err shouldBe a[ValidationFailure.InvalidValue]
    err.message should include("Q")
    err.message should include("rook")
  }

  it should "reject k right when black king is not on e8" in {
    // White king on e1; black king on h8 (not e8); black rook on h8 — wait,
    // need black king somewhere other than e8 + black rook on h8.
    // "7k" puts black king on h8 (file 7). 'k' castling requires king on e8.
    val d = data("7k/8/8/8/8/8/8/4K2R w k - 0 1")
    val err = FenSemanticValidator.validate(d).left.value
    err shouldBe a[ValidationFailure.InvalidValue]
    err.message should include("k")
    err.message should include("king")
  }

  it should "reject q right when black queen-side rook is not on a8" in {
    // Black king on e8, no rook on a8; white king on e1
    val d = data("4k3/8/8/8/8/8/8/4K3 w q - 0 1")
    val err = FenSemanticValidator.validate(d).left.value
    err shouldBe a[ValidationFailure.InvalidValue]
    err.message should include("q")
    err.message should include("rook")
  }

  // ── En passant rank ──────────────────────────────────────────────────────────

  it should "accept only K right when white king is on e1 and king-side rook is on h1" in {
    // whiteKingSide = true → validateCastlingRight runs and passes;
    // other three rights are absent → else Right(()) path; yield () reached
    val d = data("8/7k/8/8/8/8/8/4K2R w K - 0 1")
    FenSemanticValidator.validate(d) shouldBe Right(())
  }

  it should "accept only Q right when white king is on e1 and queen-side rook is on a1" in {
    val d = data("8/7k/8/8/8/8/8/R3K3 w Q - 0 1")
    FenSemanticValidator.validate(d) shouldBe Right(())
  }

  it should "accept only k right when black king is on e8 and king-side rook is on h8" in {
    val d = data("4k2r/8/8/8/8/8/8/4K3 b k - 0 1")
    FenSemanticValidator.validate(d) shouldBe Right(())
  }

  it should "accept only q right when black king is on e8 and queen-side rook is on a8" in {
    val d = data("r3k3/8/8/8/8/8/8/4K3 b q - 0 1")
    FenSemanticValidator.validate(d) shouldBe Right(())
  }

  it should "accept Kk rights (white and black king-side) when all required pieces are present" in {
    // white king e1, white rook h1, black king e8, black rook h8
    val d = data("4k2r/8/8/8/8/8/8/4K2R w Kk - 0 1")
    FenSemanticValidator.validate(d) shouldBe Right(())
  }

  // ── En passant rank ──────────────────────────────────────────────────────────

  it should "accept en passant on rank 6 when White is active" in {
    // After Black's two-square pawn advance; target on rank '6' (0-based rank 5)
    val d = data("4k3/8/8/8/8/8/8/4K3 w - d6 0 1")
    FenSemanticValidator.validate(d) shouldBe Right(())
  }

  it should "accept en passant on rank 3 when Black is active" in {
    // After White's two-square pawn advance; target on rank '3' (0-based rank 2)
    val d = data("4k3/8/8/8/8/8/8/4K3 b - e3 0 1")
    FenSemanticValidator.validate(d) shouldBe Right(())
  }

  it should "reject en passant on rank 4 when White is active" in {
    val d = data("8/7k/8/8/8/8/8/4K3 w - e4 0 1")
    val err = FenSemanticValidator.validate(d).left.value
    err shouldBe a[ValidationFailure.InvalidValue]
    err.message should include("4")
  }

  it should "reject en passant on rank 5 when Black is active" in {
    val d = data("8/7k/8/8/8/8/8/4K3 b - e5 0 1")
    val err = FenSemanticValidator.validate(d).left.value
    err shouldBe a[ValidationFailure.InvalidValue]
    err.message should include("5")
  }
