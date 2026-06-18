package chess.arena.bots.ai

import chess.adapter.ai.remote.RemoteAiMoveDto
import chess.domain.model.{Move, PieceType, Position}
import chess.domain.state.GameStateFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AiSquareNotationSpec extends AnyFlatSpec with Matchers:

  private val initialState = GameStateFactory.initial()

  "AiSquareNotation.moveToDto" should "encode from/to as separate algebraic squares" in {
    val e2 = Position.fromAlgebraic("e2").getOrElse(fail("bad position"))
    val e4 = Position.fromAlgebraic("e4").getOrElse(fail("bad position"))
    val dto = AiSquareNotation.moveToDto(Move(e2, e4))
    dto.from      shouldBe "e2"
    dto.to        shouldBe "e4"
    dto.promotion shouldBe None
  }

  it should "encode promotion piece as lowercase letter" in {
    val d7 = Position.fromAlgebraic("d7").getOrElse(fail())
    val d8 = Position.fromAlgebraic("d8").getOrElse(fail())
    val dto = AiSquareNotation.moveToDto(Move(d7, d8, Some(PieceType.Queen)))
    dto.promotion shouldBe Some("q")
  }

  it should "produce None promotion for non-promotion moves" in {
    val g1 = Position.fromAlgebraic("g1").getOrElse(fail())
    val f3 = Position.fromAlgebraic("f3").getOrElse(fail())
    val dto = AiSquareNotation.moveToDto(Move(g1, f3))
    dto.promotion shouldBe None
  }

  "AiSquareNotation.dtoToLegalMove" should "find a matching legal move in the initial position" in {
    val dto    = RemoteAiMoveDto("e2", "e4")
    val result = AiSquareNotation.dtoToLegalMove(dto, initialState)
    result.isRight          shouldBe true
    result.map(_.from.toString) shouldBe Right("e2")
    result.map(_.to.toString)   shouldBe Right("e4")
  }

  it should "return Left for a move not in the legal set" in {
    val dto    = RemoteAiMoveDto("e2", "e6")
    val result = AiSquareNotation.dtoToLegalMove(dto, initialState)
    result.isLeft shouldBe true
  }

  it should "return Left for a move to a nonsense square" in {
    val dto    = RemoteAiMoveDto("z9", "z9")
    val result = AiSquareNotation.dtoToLegalMove(dto, initialState)
    result.isLeft shouldBe true
  }
