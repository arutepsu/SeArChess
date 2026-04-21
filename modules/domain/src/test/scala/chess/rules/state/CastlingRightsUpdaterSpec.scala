package chess.domain.rules.state

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.EitherValues
import chess.domain.model.*
import chess.domain.state.CastlingRights

class CastlingRightsUpdaterSpec extends AnyFlatSpec with Matchers with EitherValues:

  private def at(alg: String): Position = Position.fromAlgebraic(alg).value

  private val wK = Piece(Color.White, PieceType.King)
  private val wR = Piece(Color.White, PieceType.Rook)
  private val bK = Piece(Color.Black, PieceType.King)
  private val bR = Piece(Color.Black, PieceType.Rook)
  private val wP = Piece(Color.White, PieceType.Pawn)

  // ── King moves ─────────────────────────────────────────────────────────────

  "CastlingRightsUpdater.update" should "clear both white rights when the white king moves from e1" in {
    val board = Board.empty.place(at("e1"), wK)
    val rights = CastlingRights.full
    val result = CastlingRightsUpdater.update(rights, board, Move(at("e1"), at("f1")))
    result.whiteKingSide shouldBe false
    result.whiteQueenSide shouldBe false
    result.blackKingSide shouldBe true
    result.blackQueenSide shouldBe true
  }

  it should "clear both black rights when the black king moves from e8" in {
    val board = Board.empty.place(at("e8"), bK)
    val rights = CastlingRights.full
    val result = CastlingRightsUpdater.update(rights, board, Move(at("e8"), at("d8")))
    result.blackKingSide shouldBe false
    result.blackQueenSide shouldBe false
    result.whiteKingSide shouldBe true
    result.whiteQueenSide shouldBe true
  }

  it should "not change rights when a king moves from a non-starting square" in {
    // King has already moved and is now on f1
    val board = Board.empty.place(at("f1"), wK)
    val rights = CastlingRights(
      whiteKingSide = false,
      whiteQueenSide = false,
      blackKingSide = true,
      blackQueenSide = true
    )
    val result = CastlingRightsUpdater.update(rights, board, Move(at("f1"), at("g1")))
    result shouldBe rights
  }

  // ── Rook moves ─────────────────────────────────────────────────────────────

  it should "clear whiteKingSide when the white king-side rook moves from h1" in {
    val board = Board.empty.place(at("h1"), wR)
    val rights = CastlingRights.full
    val result = CastlingRightsUpdater.update(rights, board, Move(at("h1"), at("h4")))
    result.whiteKingSide shouldBe false
    result.whiteQueenSide shouldBe true
  }

  it should "clear whiteQueenSide when the white queen-side rook moves from a1" in {
    val board = Board.empty.place(at("a1"), wR)
    val rights = CastlingRights.full
    val result = CastlingRightsUpdater.update(rights, board, Move(at("a1"), at("a4")))
    result.whiteQueenSide shouldBe false
    result.whiteKingSide shouldBe true
  }

  it should "clear blackKingSide when the black king-side rook moves from h8" in {
    val board = Board.empty.place(at("h8"), bR)
    val rights = CastlingRights.full
    val result = CastlingRightsUpdater.update(rights, board, Move(at("h8"), at("h5")))
    result.blackKingSide shouldBe false
    result.blackQueenSide shouldBe true
  }

  it should "clear blackQueenSide when the black queen-side rook moves from a8" in {
    val board = Board.empty.place(at("a8"), bR)
    val rights = CastlingRights.full
    val result = CastlingRightsUpdater.update(rights, board, Move(at("a8"), at("a5")))
    result.blackQueenSide shouldBe false
    result.blackKingSide shouldBe true
  }

  it should "not change rights when a rook moves from a non-origin square" in {
    val board = Board.empty.place(at("h4"), wR)
    val rights = CastlingRights.full
    val result = CastlingRightsUpdater.update(rights, board, Move(at("h4"), at("h8")))
    result shouldBe rights
  }

  // ── Rook captures on origin squares ───────────────────────────────────────

  it should "clear whiteKingSide when a piece captures the white rook on h1" in {
    val board = Board.empty
      .place(at("h1"), wR)
      .place(at("h8"), bR)
    val rights = CastlingRights.full
    val result = CastlingRightsUpdater.update(rights, board, Move(at("h8"), at("h1")))
    result.whiteKingSide shouldBe false
    result.whiteQueenSide shouldBe true
  }

  it should "clear whiteQueenSide when a piece captures the white rook on a1" in {
    val board = Board.empty
      .place(at("a1"), wR)
      .place(at("a8"), bR)
    val rights = CastlingRights.full
    val result = CastlingRightsUpdater.update(rights, board, Move(at("a8"), at("a1")))
    result.whiteQueenSide shouldBe false
    result.whiteKingSide shouldBe true
  }

  it should "clear blackKingSide when a piece captures the black rook on h8" in {
    val board = Board.empty
      .place(at("h8"), bR)
      .place(at("h1"), wR)
    val rights = CastlingRights.full
    val result = CastlingRightsUpdater.update(rights, board, Move(at("h1"), at("h8")))
    result.blackKingSide shouldBe false
    result.blackQueenSide shouldBe true
  }

  it should "clear blackQueenSide when a piece captures the black rook on a8" in {
    val board = Board.empty
      .place(at("a8"), bR)
      .place(at("a1"), wR)
    val rights = CastlingRights.full
    val result = CastlingRightsUpdater.update(rights, board, Move(at("a1"), at("a8")))
    result.blackQueenSide shouldBe false
    result.blackKingSide shouldBe true
  }

  // ── Non-special moves ──────────────────────────────────────────────────────

  it should "not change any rights for a pawn move" in {
    val board = Board.empty.place(at("e2"), wP)
    val rights = CastlingRights.full
    val result = CastlingRightsUpdater.update(rights, board, Move(at("e2"), at("e4")))
    result shouldBe rights
  }

  it should "not change any rights when the target square is empty (no capture)" in {
    val board = Board.empty.place(at("h4"), wR)
    val rights = CastlingRights.full
    val result = CastlingRightsUpdater.update(rights, board, Move(at("h4"), at("h5")))
    result shouldBe rights
  }

  // ── CastlingRightsUpdater.constPos ─────────────────────────────────────────

  "CastlingRightsUpdater.constPos" should "throw AssertionError with a descriptive message for out-of-bounds coordinates" in {
    val ex = intercept[AssertionError] {
      CastlingRightsUpdater.constPos(8, 0)
    }
    ex.getMessage should include("Invalid castling constant: file=8 rank=0")
  }
