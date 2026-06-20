package chess.application.session.policy

import chess.application.session.model.{ExternalPlatform, GameSession, SessionMode, SideController}
import chess.application.session.model.SessionIds.GameId
import chess.domain.model.Color
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ActorControlPolicySpec extends AnyFlatSpec with Matchers:

  private def sessionWith(white: SideController, black: SideController): GameSession =
    GameSession.create(
      gameId = GameId.random(),
      mode = SessionMode.HumanVsHuman,
      whiteController = white,
      blackController = black
    )

  // ── HumanLocal ────────────────────────────────────────────────────────────

  "ActorControlPolicy.canAct" should "allow HumanLocal to act when the side is HumanLocal" in {
    val session = sessionWith(SideController.HumanLocal, SideController.HumanRemote)
    ActorControlPolicy.canAct(session, SideController.HumanLocal, Color.White) shouldBe true
  }

  it should "deny HumanRemote acting for a HumanLocal side" in {
    val session = sessionWith(SideController.HumanLocal, SideController.HumanRemote)
    ActorControlPolicy.canAct(session, SideController.HumanRemote, Color.White) shouldBe false
  }

  // ── HumanRemote ───────────────────────────────────────────────────────────

  it should "allow HumanRemote to act when the side is HumanRemote" in {
    val session = sessionWith(SideController.HumanLocal, SideController.HumanRemote)
    ActorControlPolicy.canAct(session, SideController.HumanRemote, Color.Black) shouldBe true
  }

  it should "deny HumanLocal acting for a HumanRemote side" in {
    val session = sessionWith(SideController.HumanLocal, SideController.HumanRemote)
    ActorControlPolicy.canAct(session, SideController.HumanLocal, Color.Black) shouldBe false
  }

  // ── AI ────────────────────────────────────────────────────────────────────

  it should "allow any AI controller to act when the side is AI-controlled" in {
    val session = sessionWith(SideController.AI(), SideController.HumanLocal)
    ActorControlPolicy.canAct(session, SideController.AI(Some("stockfish")), Color.White) shouldBe true
  }

  it should "allow AI without engine id to act when the side is AI-controlled" in {
    val session = sessionWith(SideController.AI(Some("stockfish")), SideController.HumanLocal)
    ActorControlPolicy.canAct(session, SideController.AI(None), Color.White) shouldBe true
  }

  // ── External ─────────────────────────────────────────────────────────────

  it should "allow External(Lichess) to act when the side is External(Lichess)" in {
    val session = sessionWith(
      SideController.HumanLocal,
      SideController.External(ExternalPlatform.Lichess, "some-bot")
    )
    ActorControlPolicy.canAct(
      session,
      SideController.External(ExternalPlatform.Lichess, "some-bot"),
      Color.Black
    ) shouldBe true
  }

  it should "allow a different actorId on the same platform to act (actorId auth is ExternalGameService's concern)" in {
    val session = sessionWith(
      SideController.HumanLocal,
      SideController.External(ExternalPlatform.Lichess, "bot-a")
    )
    ActorControlPolicy.canAct(
      session,
      SideController.External(ExternalPlatform.Lichess, "bot-b"),
      Color.Black
    ) shouldBe true
  }

  it should "deny External when the side is HumanLocal" in {
    val session = sessionWith(SideController.HumanLocal, SideController.HumanLocal)
    ActorControlPolicy.canAct(
      session,
      SideController.External(ExternalPlatform.Lichess, "any-bot"),
      Color.White
    ) shouldBe false
  }

  it should "deny External when the side is AI" in {
    val session = sessionWith(SideController.AI(), SideController.HumanLocal)
    ActorControlPolicy.canAct(
      session,
      SideController.External(ExternalPlatform.Lichess, "any-bot"),
      Color.White
    ) shouldBe false
  }
