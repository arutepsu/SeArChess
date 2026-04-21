package chess.domain.rules.application

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import chess.domain.model.*
import chess.domain.state.EnPassantState

class EnPassantApplierSpec extends AnyFlatSpec with Matchers:

  private def at(alg: String): Position = Position
    .fromAlgebraic(alg)
    .toOption
    .getOrElse(scala.sys.error(s"invalid algebraic position: $alg"))

  "EnPassantApplier.applyEnPassant" should "move the capturing pawn to the en passant target square" in {
    val board = Board.empty
      .place(at("e5"), Piece(Color.White, PieceType.Pawn))
      .place(at("d5"), Piece(Color.Black, PieceType.Pawn))

    val ep = EnPassantState(
      targetSquare = at("d6"),
      capturablePawnSquare = at("d5"),
      pawnColor = Color.Black
    )

    val result = EnPassantApplier.applyEnPassant(
      board,
      Move(at("e5"), at("d6")),
      ep
    )

    result.pieceAt(at("d6")) shouldBe Some(Piece(Color.White, PieceType.Pawn))
    result.pieceAt(at("e5")) shouldBe None
  }

  it should "remove the captured pawn from the capturable pawn square" in {
    val board = Board.empty
      .place(at("e5"), Piece(Color.White, PieceType.Pawn))
      .place(at("d5"), Piece(Color.Black, PieceType.Pawn))

    val ep = EnPassantState(
      targetSquare = at("d6"),
      capturablePawnSquare = at("d5"),
      pawnColor = Color.Black
    )

    val result = EnPassantApplier.applyEnPassant(
      board,
      Move(at("e5"), at("d6")),
      ep
    )

    result.pieceAt(at("d5")) shouldBe None
  }

  it should "throw AssertionError when the capturing pawn is missing from the source square" in {
    val board = Board.empty
      .place(at("d5"), Piece(Color.Black, PieceType.Pawn))

    val ep = EnPassantState(
      targetSquare = at("d6"),
      capturablePawnSquare = at("d5"),
      pawnColor = Color.Black
    )

    val ex = the[AssertionError] thrownBy {
      EnPassantApplier.applyEnPassant(
        board,
        Move(at("e5"), at("d6")),
        ep
      )
    }

    ex.getMessage should include("Capturing pawn missing after EnPassantValidator")
  }
