package chess.application.ai.policy

import chess.application.session.model.{GameSession, SessionMode, SideController}
import chess.application.session.model.SessionIds.GameId
import chess.domain.model.Color
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AITurnPolicySpec extends AnyFlatSpec with Matchers:

  private def sessionWith(white: SideController, black: SideController): GameSession =
    GameSession.create(
      gameId = GameId.random(),
      mode = SessionMode.HumanVsAI,
      whiteController = white,
      blackController = black
    )

  // ── isAITurn: White side ───────────────────────────────────────────────────

  "AITurnPolicy.isAITurn" should "return true when White is AI and it is White's turn" in {
    val session = sessionWith(white = SideController.AI(), black = SideController.HumanLocal)
    AITurnPolicy.isAITurn(session, Color.White) shouldBe true
  }

  it should "return false when White is HumanLocal and it is White's turn" in {
    val session = sessionWith(white = SideController.HumanLocal, black = SideController.AI())
    AITurnPolicy.isAITurn(session, Color.White) shouldBe false
  }

  it should "return false when White is HumanRemote and it is White's turn" in {
    val session = sessionWith(white = SideController.HumanRemote, black = SideController.AI())
    AITurnPolicy.isAITurn(session, Color.White) shouldBe false
  }

  // ── isAITurn: Black side ───────────────────────────────────────────────────

  it should "return true when Black is AI and it is Black's turn" in {
    val session = sessionWith(white = SideController.HumanLocal, black = SideController.AI())
    AITurnPolicy.isAITurn(session, Color.Black) shouldBe true
  }

  it should "return false when Black is HumanLocal and it is Black's turn" in {
    val session = sessionWith(white = SideController.AI(), black = SideController.HumanLocal)
    AITurnPolicy.isAITurn(session, Color.Black) shouldBe false
  }

  // ── isAITurn: cross-side (AI on the other side) ───────────────────────────

  it should "return false when only Black is AI but it is White's turn" in {
    val session = sessionWith(white = SideController.HumanLocal, black = SideController.AI())
    AITurnPolicy.isAITurn(session, Color.White) shouldBe false
  }

  it should "return false when only White is AI but it is Black's turn" in {
    val session = sessionWith(white = SideController.AI(), black = SideController.HumanLocal)
    AITurnPolicy.isAITurn(session, Color.Black) shouldBe false
  }
