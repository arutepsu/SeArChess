package chess.domain.rules.application

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import chess.domain.model.*

class CastlingApplierSpec extends AnyFlatSpec with Matchers:

  private def at(alg: String): Position =
    Position
      .fromAlgebraic(alg)
      .toOption
      .getOrElse(scala.sys.error(s"invalid algebraic position: $alg"))

  "CastlingApplier.applyCastle" should "move the white king and rook correctly for king-side castling" in {
    val board = Board.empty
      .place(at("e1"), Piece(Color.White, PieceType.King))
      .place(at("h1"), Piece(Color.White, PieceType.Rook))

    val result = CastlingApplier.applyCastle(board, Color.White, kingSide = true)

    result.pieceAt(at("g1")) shouldBe Some(Piece(Color.White, PieceType.King))
    result.pieceAt(at("f1")) shouldBe Some(Piece(Color.White, PieceType.Rook))
    result.pieceAt(at("e1")) shouldBe None
    result.pieceAt(at("h1")) shouldBe None
  }

  it should "throw AssertionError when the king is missing from its origin square" in {
    val board = Board.empty
      .place(at("h1"), Piece(Color.White, PieceType.Rook))

    val ex = the[AssertionError] thrownBy {
      CastlingApplier.applyCastle(board, Color.White, kingSide = true)
    }

    ex.getMessage should include("King missing after CastlingValidator")
  }

  it should "throw AssertionError when the rook is missing from its origin square" in {
    val board = Board.empty
      .place(at("e1"), Piece(Color.White, PieceType.King))

    val ex = the[AssertionError] thrownBy {
      CastlingApplier.applyCastle(board, Color.White, kingSide = true)
    }

    ex.getMessage should include("Rook missing after CastlingValidator")
  }

  it should "throw AssertionError for an invalid castling constant in the private helper" in {
    val method = CastlingApplier.getClass.getDeclaredMethod("p", classOf[Int], classOf[Int])
    method.setAccessible(true)

    val ex = the[java.lang.reflect.InvocationTargetException] thrownBy {
      method.invoke(CastlingApplier, Int.box(8), Int.box(0))
    }

    ex.getCause shouldBe a[AssertionError]
    ex.getCause.getMessage should include("Invalid castling constant: file=8 rank=0")
  }
