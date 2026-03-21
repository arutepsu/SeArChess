package chess.domain.rules

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.EitherValues
import chess.domain.error.DomainError
import chess.domain.model.*

class CastlingValidatorSpec extends AnyFlatSpec with Matchers with EitherValues:

  private def at(alg: String): Position = Position.fromAlgebraic(alg).value

  // ── Helper boards ──────────────────────────────────────────────────────────

  /** Minimal board for valid white king-side castling: king e1, rook h1. */
  private val whiteKingSideBoard: Board =
    Board.empty
      .place(at("e1"), Piece(Color.White, PieceType.King))
      .place(at("h1"), Piece(Color.White, PieceType.Rook))

  /** Minimal board for valid white queen-side castling: king e1, rook a1. */
  private val whiteQueenSideBoard: Board =
    Board.empty
      .place(at("e1"), Piece(Color.White, PieceType.King))
      .place(at("a1"), Piece(Color.White, PieceType.Rook))

  /** Minimal board for valid black king-side castling: king e8, rook h8. */
  private val blackKingSideBoard: Board =
    Board.empty
      .place(at("e8"), Piece(Color.Black, PieceType.King))
      .place(at("h8"), Piece(Color.Black, PieceType.Rook))

  /** Minimal board for valid black queen-side castling: king e8, rook a8. */
  private val blackQueenSideBoard: Board =
    Board.empty
      .place(at("e8"), Piece(Color.Black, PieceType.King))
      .place(at("a8"), Piece(Color.Black, PieceType.Rook))

  // ── isCastlingMove ─────────────────────────────────────────────────────────

  "CastlingValidator.isCastlingMove" should "recognise the white king-side castle move e1→g1" in {
    CastlingValidator.isCastlingMove(Move(at("e1"), at("g1"))) shouldBe true
  }

  it should "recognise the white queen-side castle move e1→c1" in {
    CastlingValidator.isCastlingMove(Move(at("e1"), at("c1"))) shouldBe true
  }

  it should "recognise the black king-side castle move e8→g8" in {
    CastlingValidator.isCastlingMove(Move(at("e8"), at("g8"))) shouldBe true
  }

  it should "recognise the black queen-side castle move e8→c8" in {
    CastlingValidator.isCastlingMove(Move(at("e8"), at("c8"))) shouldBe true
  }

  it should "return false for a normal king move" in {
    CastlingValidator.isCastlingMove(Move(at("e1"), at("e2"))) shouldBe false
  }

  // ── validate: success ──────────────────────────────────────────────────────

  "CastlingValidator.validate" should "succeed for white king-side castling" in {
    val result = CastlingValidator.validate(
      whiteKingSideBoard, Color.White, Move(at("e1"), at("g1")), CastlingRights.full
    )
    result.value shouldBe true
  }

  it should "succeed for white queen-side castling" in {
    val result = CastlingValidator.validate(
      whiteQueenSideBoard, Color.White, Move(at("e1"), at("c1")), CastlingRights.full
    )
    result.value shouldBe false
  }

  it should "succeed for black king-side castling" in {
    val result = CastlingValidator.validate(
      blackKingSideBoard, Color.Black, Move(at("e8"), at("g8")), CastlingRights.full
    )
    result.value shouldBe true
  }

  it should "succeed for black queen-side castling" in {
    val result = CastlingValidator.validate(
      blackQueenSideBoard, Color.Black, Move(at("e8"), at("c8")), CastlingRights.full
    )
    result.value shouldBe false
  }

  // ── validate: CastleNotAllowed ─────────────────────────────────────────────

  it should "fail with CastleNotAllowed when the white king-side right is false" in {
    val rights = CastlingRights.full.copy(whiteKingSide = false)
    val result = CastlingValidator.validate(
      whiteKingSideBoard, Color.White, Move(at("e1"), at("g1")), rights
    )
    result.left.value shouldBe DomainError.CastleNotAllowed
  }

  it should "fail with CastleNotAllowed when the white queen-side right is false" in {
    val rights = CastlingRights.full.copy(whiteQueenSide = false)
    val result = CastlingValidator.validate(
      whiteQueenSideBoard, Color.White, Move(at("e1"), at("c1")), rights
    )
    result.left.value shouldBe DomainError.CastleNotAllowed
  }

  it should "fail with CastleNotAllowed when the black king-side right is false" in {
    val rights = CastlingRights.full.copy(blackKingSide = false)
    val result = CastlingValidator.validate(
      blackKingSideBoard, Color.Black, Move(at("e8"), at("g8")), rights
    )
    result.left.value shouldBe DomainError.CastleNotAllowed
  }

  it should "fail with CastleNotAllowed when the black queen-side right is false" in {
    val rights = CastlingRights.full.copy(blackQueenSide = false)
    val result = CastlingValidator.validate(
      blackQueenSideBoard, Color.Black, Move(at("e8"), at("c8")), rights
    )
    result.left.value shouldBe DomainError.CastleNotAllowed
  }

  // ── validate: MissingCastlingRook ──────────────────────────────────────────

  it should "fail with MissingCastlingRook when the rook is absent from h1" in {
    val board  = Board.empty.place(at("e1"), Piece(Color.White, PieceType.King))
    val result = CastlingValidator.validate(board, Color.White, Move(at("e1"), at("g1")), CastlingRights.full)
    result.left.value shouldBe DomainError.MissingCastlingRook
  }

  it should "fail with MissingCastlingRook when the h1 piece is not a rook" in {
    val board  = Board.empty
      .place(at("e1"), Piece(Color.White, PieceType.King))
      .place(at("h1"), Piece(Color.White, PieceType.Bishop))
    val result = CastlingValidator.validate(board, Color.White, Move(at("e1"), at("g1")), CastlingRights.full)
    result.left.value shouldBe DomainError.MissingCastlingRook
  }

  it should "fail with MissingCastlingRook when the rook colour does not match" in {
    val board  = Board.empty
      .place(at("e1"), Piece(Color.White, PieceType.King))
      .place(at("h1"), Piece(Color.Black, PieceType.Rook))
    val result = CastlingValidator.validate(board, Color.White, Move(at("e1"), at("g1")), CastlingRights.full)
    result.left.value shouldBe DomainError.MissingCastlingRook
  }

  // ── validate: CastlePathBlocked ────────────────────────────────────────────

  it should "fail with CastlePathBlocked when f1 is occupied (white king-side)" in {
    val board  = whiteKingSideBoard.place(at("f1"), Piece(Color.White, PieceType.Bishop))
    val result = CastlingValidator.validate(board, Color.White, Move(at("e1"), at("g1")), CastlingRights.full)
    result.left.value shouldBe DomainError.CastlePathBlocked
  }

  it should "fail with CastlePathBlocked when d1 is occupied (white queen-side)" in {
    val board  = whiteQueenSideBoard.place(at("d1"), Piece(Color.White, PieceType.Queen))
    val result = CastlingValidator.validate(board, Color.White, Move(at("e1"), at("c1")), CastlingRights.full)
    result.left.value shouldBe DomainError.CastlePathBlocked
  }

  // ── validate: CastleThroughCheck (king starts in check) ────────────────────

  it should "fail with CastleThroughCheck when the king is currently in check" in {
    // Black rook on e8 checks the white king on e1
    val board  = whiteKingSideBoard.place(at("e8"), Piece(Color.Black, PieceType.Rook))
    val result = CastlingValidator.validate(board, Color.White, Move(at("e1"), at("g1")), CastlingRights.full)
    result.left.value shouldBe DomainError.CastleThroughCheck
  }

  // ── validate: CastleThroughCheck (king passes through attacked square) ──────

  it should "fail with CastleThroughCheck when the f1 transit square is attacked (white king-side)" in {
    // Black rook on f8 attacks f1; king is not currently in check
    val board  = whiteKingSideBoard.place(at("f8"), Piece(Color.Black, PieceType.Rook))
    val result = CastlingValidator.validate(board, Color.White, Move(at("e1"), at("g1")), CastlingRights.full)
    result.left.value shouldBe DomainError.CastleThroughCheck
  }

  it should "fail with CastleThroughCheck when the g1 destination square is attacked (white king-side)" in {
    // Black rook on g8 attacks g1; king is not currently in check
    val board  = whiteKingSideBoard.place(at("g8"), Piece(Color.Black, PieceType.Rook))
    val result = CastlingValidator.validate(board, Color.White, Move(at("e1"), at("g1")), CastlingRights.full)
    result.left.value shouldBe DomainError.CastleThroughCheck
  }

  it should "fail with CastleThroughCheck when the d1 transit square is attacked (white queen-side)" in {
    // Black rook on d8 attacks d1; king is not in check on e1
    val board  = whiteQueenSideBoard.place(at("d8"), Piece(Color.Black, PieceType.Rook))
    val result = CastlingValidator.validate(board, Color.White, Move(at("e1"), at("c1")), CastlingRights.full)
    result.left.value shouldBe DomainError.CastleThroughCheck
  }
