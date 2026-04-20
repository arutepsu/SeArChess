package chess.adapter.gui.assets

import chess.domain.model.{Color, PieceType}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PieceSymbolSpec extends AnyFlatSpec with Matchers:

  "PieceSymbol.symbol" should "return white king ♔" in {
    PieceSymbol.symbol(Color.White, PieceType.King) shouldBe "♔"
  }

  it should "return white queen ♕" in {
    PieceSymbol.symbol(Color.White, PieceType.Queen) shouldBe "♕"
  }

  it should "return white rook ♖" in {
    PieceSymbol.symbol(Color.White, PieceType.Rook) shouldBe "♖"
  }

  it should "return white bishop ♗" in {
    PieceSymbol.symbol(Color.White, PieceType.Bishop) shouldBe "♗"
  }

  it should "return white knight ♘" in {
    PieceSymbol.symbol(Color.White, PieceType.Knight) shouldBe "♘"
  }

  it should "return white pawn ♙" in {
    PieceSymbol.symbol(Color.White, PieceType.Pawn) shouldBe "♙"
  }

  it should "return black king ♚" in {
    PieceSymbol.symbol(Color.Black, PieceType.King) shouldBe "♚"
  }

  it should "return black queen ♛" in {
    PieceSymbol.symbol(Color.Black, PieceType.Queen) shouldBe "♛"
  }

  it should "return black rook ♜" in {
    PieceSymbol.symbol(Color.Black, PieceType.Rook) shouldBe "♜"
  }

  it should "return black bishop ♝" in {
    PieceSymbol.symbol(Color.Black, PieceType.Bishop) shouldBe "♝"
  }

  it should "return black knight ♞" in {
    PieceSymbol.symbol(Color.Black, PieceType.Knight) shouldBe "♞"
  }

  it should "return black pawn ♟" in {
    PieceSymbol.symbol(Color.Black, PieceType.Pawn) shouldBe "♟"
  }
