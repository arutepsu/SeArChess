package chess.adapter.http4s.mapper

import chess.application.ChessService
import chess.application.query.game.GameView
import chess.application.session.model.{GameSession, SessionLifecycle, SessionMode, SideController}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import java.time.Instant
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SessionMapperSpec extends AnyFlatSpec with Matchers with EitherValues:

  private val now  = Instant.parse("2026-01-01T12:00:00Z")
  private val gid  = GameId.random()
  private val sess = GameSession(
    sessionId       = SessionId.random(),
    gameId          = gid,
    mode            = SessionMode.HumanVsHuman,
    whiteController = SideController.HumanLocal,
    blackController = SideController.HumanLocal,
    lifecycle       = SessionLifecycle.Created,
    createdAt       = now,
    updatedAt       = now
  )

  "SessionMapper.parseMode" should "default to HumanVsHuman when None" in {
    SessionMapper.parseMode(None).value shouldBe SessionMode.HumanVsHuman
  }

  it should "parse 'HumanVsHuman'" in {
    SessionMapper.parseMode(Some("HumanVsHuman")).value shouldBe SessionMode.HumanVsHuman
  }

  it should "parse 'HumanVsAI'" in {
    SessionMapper.parseMode(Some("HumanVsAI")).value shouldBe SessionMode.HumanVsAI
  }

  it should "parse 'AIVsAI'" in {
    SessionMapper.parseMode(Some("AIVsAI")).value shouldBe SessionMode.AIVsAI
  }

  it should "return Left for an unknown mode string" in {
    SessionMapper.parseMode(Some("invalid")).isLeft shouldBe true
  }

  it should "include the offending value in the error message" in {
    SessionMapper.parseMode(Some("blah")).left.value should include("blah")
  }

  "SessionMapper.parseController" should "default to HumanLocal when None" in {
    SessionMapper.parseController(None).value shouldBe SideController.HumanLocal
  }

  it should "parse 'HumanLocal'" in {
    SessionMapper.parseController(Some("HumanLocal")).value shouldBe SideController.HumanLocal
  }

  it should "parse 'HumanRemote'" in {
    SessionMapper.parseController(Some("HumanRemote")).value shouldBe SideController.HumanRemote
  }

  it should "return Left for an unknown controller string" in {
    SessionMapper.parseController(Some("Robot")).isLeft shouldBe true
  }

  it should "return Left for AI because REST v1 derives AI seats from mode" in {
    SessionMapper.parseController(Some("AI")).isLeft shouldBe true
  }

  it should "include the offending value in the error message" in {
    SessionMapper.parseController(Some("Robot")).left.value should include("Robot")
  }

  "SessionMapper.resolveCreateControllers" should "default HumanVsHuman to two local human controllers" in {
    val (white, black) = SessionMapper
      .resolveCreateControllers(SessionMode.HumanVsHuman, None, None)
      .value

    white shouldBe SideController.HumanLocal
    black shouldBe SideController.HumanLocal
  }

  it should "resolve HumanVsAI as White human and Black server AI" in {
    val (white, black) = SessionMapper
      .resolveCreateControllers(SessionMode.HumanVsAI, None, None)
      .value

    white shouldBe SideController.HumanLocal
    black shouldBe SideController.AI()
  }

  it should "allow a human controller override for the HumanVsAI human side" in {
    val (white, black) = SessionMapper
      .resolveCreateControllers(SessionMode.HumanVsAI, Some("HumanRemote"), None)
      .value

    white shouldBe SideController.HumanRemote
    black shouldBe SideController.AI()
  }

  it should "reject a HumanVsAI blackController override because Black is server AI" in {
    SessionMapper
      .resolveCreateControllers(SessionMode.HumanVsAI, None, Some("HumanLocal"))
      .left.value should include("blackController")
  }

  it should "resolve AIVsAI as two server AI controllers" in {
    val (white, black) = SessionMapper
      .resolveCreateControllers(SessionMode.AIVsAI, None, None)
      .value

    white shouldBe SideController.AI()
    black shouldBe SideController.AI()
  }

  it should "reject controller overrides for AIVsAI" in {
    SessionMapper
      .resolveCreateControllers(SessionMode.AIVsAI, Some("HumanLocal"), None)
      .left.value should include("AIVsAI")
  }

  "SessionMapper.controllerToString" should "serialize HumanLocal" in {
    SessionMapper.controllerToString(SideController.HumanLocal) shouldBe "HumanLocal"
  }

  it should "serialize HumanRemote" in {
    SessionMapper.controllerToString(SideController.HumanRemote) shouldBe "HumanRemote"
  }

  it should "serialize AI regardless of engine id" in {
    SessionMapper.controllerToString(SideController.AI(None))              shouldBe "AI"
    SessionMapper.controllerToString(SideController.AI(Some("stockfish"))) shouldBe "AI"
  }

  "SessionMapper.toSessionResponse" should "populate all fields from GameSession" in {
    val resp = SessionMapper.toSessionResponse(sess)
    resp.sessionId       shouldBe sess.sessionId.value.toString
    resp.gameId          shouldBe gid.value.toString
    resp.mode            shouldBe "HumanVsHuman"
    resp.lifecycle       shouldBe "Created"
    resp.whiteController shouldBe "HumanLocal"
    resp.blackController shouldBe "HumanLocal"
    resp.createdAt       shouldBe now.toString
    resp.updatedAt       shouldBe now.toString
  }

  it should "reflect lifecycle changes" in {
    val active = sess.copy(lifecycle = SessionLifecycle.Active)
    SessionMapper.toSessionResponse(active).lifecycle shouldBe "Active"
  }

  it should "serialize AwaitingPromotion lifecycle" in {
    val promo = sess.copy(lifecycle = SessionLifecycle.AwaitingPromotion)
    SessionMapper.toSessionResponse(promo).lifecycle shouldBe "AwaitingPromotion"
  }

  it should "serialize Finished lifecycle" in {
    val fin = sess.copy(lifecycle = SessionLifecycle.Finished)
    SessionMapper.toSessionResponse(fin).lifecycle shouldBe "Finished"
  }

  "SessionMapper.toCreateSessionResponse" should "bundle session and game data" in {
    val state    = ChessService.createNewGame()
    val gameResp = GameMapper.toGameResponse(GameView.fromState(gid, state))
    val resp     = SessionMapper.toCreateSessionResponse(sess, gid, gameResp)
    resp.session.sessionId shouldBe sess.sessionId.value.toString
    resp.game.gameId       shouldBe gid.value.toString
    resp.game.board        should have size 32
  }
