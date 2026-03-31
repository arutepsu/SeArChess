package chess.domain.rules.validation

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.EitherValues
import chess.domain.error.DomainError
import chess.domain.model.*
import chess.domain.state.EnPassantState

class EnPassantValidatorSpec extends AnyFlatSpec with Matchers with EitherValues:

  private def at(alg: String): Position = Position.fromAlgebraic(alg).value

  // ── Shared fixtures ────────────────────────────────────────────────────────

  // White pawn e2→e4; Black pawn on d4 or f4 may capture en passant to e3.
  private val epWhite = EnPassantState(at("e3"), at("e4"), Color.White)

  // Black pawn d7→d5; White pawn on c5 or e5 may capture en passant to d6.
  private val epBlack = EnPassantState(at("d6"), at("d5"), Color.Black)

  // Board for Black capturing White's pawn: Black pawn on d4, White pawn on e4.
  private val boardBlackCaptures: Board =
    Board.empty
      .place(at("d4"), Piece(Color.Black, PieceType.Pawn))
      .place(at("e4"), Piece(Color.White, PieceType.Pawn))

  // Board for White capturing Black's pawn: White pawn on e5, Black pawn on d5.
  private val boardWhiteCaptures: Board =
    Board.empty
      .place(at("e5"), Piece(Color.White, PieceType.Pawn))
      .place(at("d5"), Piece(Color.Black, PieceType.Pawn))

  // ── isEnPassantMove ────────────────────────────────────────────────────────

  "EnPassantValidator.isEnPassantMove" should "return true for a valid black en passant routing" in {
    // Black pawn d4 moving to e3 with White's pawn on e4 captured
    EnPassantValidator.isEnPassantMove(boardBlackCaptures, Move(at("d4"), at("e3")), Some(epWhite)) shouldBe true
  }

  it should "return true for a valid white en passant routing" in {
    // White pawn e5 moving to d6 with Black's pawn on d5 captured
    EnPassantValidator.isEnPassantMove(boardWhiteCaptures, Move(at("e5"), at("d6")), Some(epBlack)) shouldBe true
  }

  it should "return false when en passant state is None" in {
    EnPassantValidator.isEnPassantMove(boardBlackCaptures, Move(at("d4"), at("e3")), None) shouldBe false
  }

  it should "return false when the target square does not match the en passant state" in {
    // d4→f3 is not the en passant target e3
    EnPassantValidator.isEnPassantMove(boardBlackCaptures, Move(at("d4"), at("f3")), Some(epWhite)) shouldBe false
  }

  it should "return false when the moving piece is not a pawn" in {
    val board = Board.empty
      .place(at("d4"), Piece(Color.Black, PieceType.Queen))
      .place(at("e4"), Piece(Color.White, PieceType.Pawn))
    EnPassantValidator.isEnPassantMove(board, Move(at("d4"), at("e3")), Some(epWhite)) shouldBe false
  }

  it should "return false when the moving pawn is the same color as the pawn being captured" in {
    // A White pawn at d4 should not trigger Black's en passant state (pawnColor=White)
    val board = Board.empty
      .place(at("d4"), Piece(Color.White, PieceType.Pawn))
      .place(at("e4"), Piece(Color.White, PieceType.Pawn))
    EnPassantValidator.isEnPassantMove(board, Move(at("d4"), at("e3")), Some(epWhite)) shouldBe false
  }

  it should "return false when the en passant target square is occupied" in {
    val boardWithOccupiedTarget = boardBlackCaptures.place(at("e3"), Piece(Color.White, PieceType.Pawn))
    EnPassantValidator.isEnPassantMove(boardWithOccupiedTarget, Move(at("d4"), at("e3")), Some(epWhite)) shouldBe false
  }

  // ── validate: success ──────────────────────────────────────────────────────

  "EnPassantValidator.validate" should "succeed for a valid black capture (d4→e3)" in {
    EnPassantValidator.validate(boardBlackCaptures, Move(at("d4"), at("e3")), epWhite).value shouldBe ()
  }

  it should "succeed for a valid white capture (e5→d6)" in {
    EnPassantValidator.validate(boardWhiteCaptures, Move(at("e5"), at("d6")), epBlack).value shouldBe ()
  }

  // ── validate: diagonal direction ──────────────────────────────────────────

  it should "fail with InvalidEnPassant for a pawn that jumps two files to the target" in {
    // A pawn on b4 cannot jump two files to reach e3
    val board = boardBlackCaptures.place(at("b4"), Piece(Color.Black, PieceType.Pawn))
    EnPassantValidator.validate(board, Move(at("b4"), at("e3")), epWhite).left.value shouldBe DomainError.InvalidEnPassant
  }

  it should "fail with InvalidEnPassant when the capturing pawn moves in the wrong direction" in {
    // Black pawn on f2 moving UP to e3 — Black pawns move down (rank decreasing)
    val board = Board.empty
      .place(at("f2"), Piece(Color.Black, PieceType.Pawn))
      .place(at("e4"), Piece(Color.White, PieceType.Pawn))
    EnPassantValidator.validate(board, Move(at("f2"), at("e3")), epWhite).left.value shouldBe DomainError.InvalidEnPassant
  }

  // ── validate: target square occupied ─────────────────────────────────────

  it should "fail with InvalidEnPassant when the target square is occupied" in {
    val board = boardBlackCaptures.place(at("e3"), Piece(Color.White, PieceType.Knight))
    EnPassantValidator.validate(board, Move(at("d4"), at("e3")), epWhite).left.value shouldBe DomainError.InvalidEnPassant
  }

  // ── validate: capturable pawn missing ────────────────────────────────────

  it should "fail with InvalidEnPassant when the capturable pawn is absent" in {
    val boardNoPawn = Board.empty.place(at("d4"), Piece(Color.Black, PieceType.Pawn))
    EnPassantValidator.validate(boardNoPawn, Move(at("d4"), at("e3")), epWhite).left.value shouldBe DomainError.InvalidEnPassant
  }

  it should "fail with InvalidEnPassant when a wrong-color piece occupies the capturable square" in {
    val board = Board.empty
      .place(at("d4"), Piece(Color.Black, PieceType.Pawn))
      .place(at("e4"), Piece(Color.Black, PieceType.Pawn))  // Black pawn instead of White
    EnPassantValidator.validate(board, Move(at("d4"), at("e3")), epWhite).left.value shouldBe DomainError.InvalidEnPassant
  }

  it should "fail with InvalidEnPassant when a non-pawn occupies the capturable square" in {
    val board = Board.empty
      .place(at("d4"), Piece(Color.Black, PieceType.Pawn))
      .place(at("e4"), Piece(Color.White, PieceType.Rook))  // Rook instead of Pawn
    EnPassantValidator.validate(board, Move(at("d4"), at("e3")), epWhite).left.value shouldBe DomainError.InvalidEnPassant
  }
