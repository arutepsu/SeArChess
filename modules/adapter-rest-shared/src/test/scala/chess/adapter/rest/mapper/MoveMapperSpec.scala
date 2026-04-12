package chess.adapter.rest.mapper

import chess.adapter.rest.dto.SubmitMoveRequest
import chess.domain.model.{Move, PieceType, Position}
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MoveMapperSpec extends AnyFlatSpec with Matchers with EitherValues:

  private def req(from: String, to: String,
                  promo: Option[String] = None,
                  ctrl: Option[String]  = None) =
    SubmitMoveRequest(from, to, promo, ctrl)

  // ── valid moves ────────────────────────────────────────────────────────────

  "MoveMapper.toDomain" should "parse a valid move without promotion" in {
    val move = MoveMapper.toDomain(req("e2", "e4")).value
    move.from.toString shouldBe "e2"
    move.to.toString   shouldBe "e4"
    move.promotion     shouldBe None
  }

  it should "parse corner squares" in {
    val move = MoveMapper.toDomain(req("a1", "h8")).value
    move.from.toString shouldBe "a1"
    move.to.toString   shouldBe "h8"
  }

  it should "parse a move with a Queen promotion" in {
    val move = MoveMapper.toDomain(req("e7", "e8", Some("Queen"))).value
    move.promotion shouldBe Some(PieceType.Queen)
  }

  it should "parse a move with a Rook promotion" in {
    MoveMapper.toDomain(req("a7", "a8", Some("Rook"))).value.promotion shouldBe Some(PieceType.Rook)
  }

  it should "parse a move with a Bishop promotion" in {
    MoveMapper.toDomain(req("b7", "b8", Some("Bishop"))).value.promotion shouldBe Some(PieceType.Bishop)
  }

  it should "parse a move with a Knight promotion" in {
    MoveMapper.toDomain(req("c7", "c8", Some("Knight"))).value.promotion shouldBe Some(PieceType.Knight)
  }

  // ── invalid from / to ─────────────────────────────────────────────────────

  it should "return Left for an invalid 'from' square" in {
    MoveMapper.toDomain(req("z9", "e4")).isLeft shouldBe true
  }

  it should "return Left for an invalid 'to' square" in {
    MoveMapper.toDomain(req("e2", "z9")).isLeft shouldBe true
  }

  it should "return Left for a three-character square" in {
    MoveMapper.toDomain(req("e22", "e4")).isLeft shouldBe true
  }

  it should "include the offending square in the error message" in {
    val msg = MoveMapper.toDomain(req("zz", "e4")).left.value
    msg should include("zz")
  }

  // ── invalid promotion ─────────────────────────────────────────────────────

  it should "return Left for 'King' as promotion piece" in {
    MoveMapper.toDomain(req("e7", "e8", Some("King"))).isLeft shouldBe true
  }

  it should "return Left for 'Pawn' as promotion piece" in {
    MoveMapper.toDomain(req("e7", "e8", Some("Pawn"))).isLeft shouldBe true
  }

  it should "return Left for an unknown promotion token" in {
    MoveMapper.toDomain(req("e7", "e8", Some("Dragon"))).isLeft shouldBe true
  }

  it should "include the offending token in the promotion error message" in {
    val msg = MoveMapper.toDomain(req("e7", "e8", Some("Dragon"))).left.value
    msg should include("Dragon")
  }

  // ── parsePieceType (package-private) ──────────────────────────────────────

  "MoveMapper.parsePieceType" should "accept all four legal promotion types" in {
    MoveMapper.parsePieceType("Queen").value  shouldBe PieceType.Queen
    MoveMapper.parsePieceType("Rook").value   shouldBe PieceType.Rook
    MoveMapper.parsePieceType("Bishop").value shouldBe PieceType.Bishop
    MoveMapper.parsePieceType("Knight").value shouldBe PieceType.Knight
  }

  it should "reject King" in {
    MoveMapper.parsePieceType("King").isLeft shouldBe true
  }

  it should "reject Pawn" in {
    MoveMapper.parsePieceType("Pawn").isLeft shouldBe true
  }

  it should "reject empty string" in {
    MoveMapper.parsePieceType("").isLeft shouldBe true
  }
