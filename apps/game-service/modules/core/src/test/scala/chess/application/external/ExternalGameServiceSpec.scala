package chess.application.external

import chess.application.port.ai.{AIError, AIRequestContext, AIResponse, AiMoveSuggestionClient}
import chess.application.session.model.{ExternalPlatform, SessionMode, SideController}
import chess.application.session.service.SessionLifecycleService
import chess.domain.model.{Color, GameStatus}
import chess.domain.rules.GameStateRules
import org.scalatest.EitherValues
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.time.Instant

class ExternalGameServiceSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues:

  private val platform        = ExternalPlatform.Lichess
  private val externalGameId  = "game-abc123"
  private val ourActorId      = "searchess-bot"
  private val opponentActorId = "human-opponent"
  private val now             = Instant.parse("2026-01-01T12:00:00Z")

  // ── fixture ───────────────────────────────────────────────────────────────

  private case class Fixture(
      service: ExternalGameService,
      bindingRepo: InMemoryExternalGameBindingRepository,
      sessionRepo: StubSessionRepository,
      gameRepo: StubGameRepository
  )

  private def deterministicClient: AiMoveSuggestionClient = ctx =>
    GameStateRules.legalMoves(ctx.state).toSeq
      .sortBy(m => (m.from.file, m.from.rank, m.to.file, m.to.rank))
      .headOption match
        case Some(m) => Right(AIResponse(m))
        case None    => Left(AIError.NoLegalMove)

  private def makeFixture(ai: Option[AiMoveSuggestionClient] = None): Fixture =
    val sessionRepo  = StubSessionRepository()
    val gameRepo     = StubGameRepository()
    val bindingRepo  = InMemoryExternalGameBindingRepository()
    val commandStore = InMemoryExternalGameCommandStore(sessionRepo, gameRepo, bindingRepo)
    val lifecycle    = SessionLifecycleService(sessionRepo, _ => ())
    val service      = ExternalGameService(bindingRepo, commandStore, lifecycle, gameRepo, ai, _ => ())
    Fixture(service, bindingRepo, sessionRepo, gameRepo)

  private def aiVsExternalRequest(ourColor: Color = Color.White) =
    StartExternalGameRequest(
      platform        = platform,
      externalGameId  = externalGameId,
      mode            = SessionMode.AiVsExternal,
      ourActorId      = ourActorId,
      ourColor        = ourColor,
      opponentActorId = opponentActorId,
      now             = now
    )

  private def caller = VerifiedExternalCaller(platform, ourActorId)

  // ── startExternalGame ─────────────────────────────────────────────────────

  "ExternalGameService.startExternalGame" should
    "create an AiVsExternal binding with white=AI when ourColor=White" in {
      val f = makeFixture()
      val binding = f.service.startExternalGame(aiVsExternalRequest(Color.White)).value

      binding.platform        shouldBe platform
      binding.externalGameId  shouldBe externalGameId
      binding.ourActorId      shouldBe ourActorId
      binding.status          shouldBe BindingStatus.Active
      binding.lastProcessedPly shouldBe 0

      val session = f.sessionRepo.load(binding.sessionId).value
      session.mode             shouldBe SessionMode.AiVsExternal
      session.whiteController  shouldBe SideController.AI()
      session.blackController  shouldBe SideController.External(platform, opponentActorId)
    }

  it should "create an AiVsExternal binding with black=AI when ourColor=Black" in {
    val f = makeFixture()
    val binding = f.service.startExternalGame(aiVsExternalRequest(Color.Black)).value

    val session = f.sessionRepo.load(binding.sessionId).value
    session.whiteController shouldBe SideController.External(platform, opponentActorId)
    session.blackController shouldBe SideController.AI()
  }

  it should "create an ExternalVsExternal binding with both External controllers" in {
    val f = makeFixture()
    val req = StartExternalGameRequest(
      platform        = platform,
      externalGameId  = externalGameId,
      mode            = SessionMode.ExternalVsExternal,
      ourActorId      = ourActorId,
      ourColor        = Color.White,
      opponentActorId = opponentActorId,
      now             = now
    )
    val binding = f.service.startExternalGame(req).value

    val session = f.sessionRepo.load(binding.sessionId).value
    session.mode            shouldBe SessionMode.ExternalVsExternal
    session.whiteController shouldBe SideController.External(platform, ourActorId)
    session.blackController shouldBe SideController.External(platform, opponentActorId)
  }

  it should "be idempotent: return the existing binding on a second call" in {
    val f = makeFixture()
    val first  = f.service.startExternalGame(aiVsExternalRequest()).value
    val second = f.service.startExternalGame(aiVsExternalRequest()).value

    second.bindingId shouldBe first.bindingId
    f.bindingRepo.all should have size 1
  }

  it should "reject an invalid mode (HumanVsHuman)" in {
    val f = makeFixture()
    val req = aiVsExternalRequest().copy(mode = SessionMode.HumanVsHuman)
    f.service.startExternalGame(req).left.value shouldBe a[ExternalGameError.InvalidRequest]
  }

  // ── ingestExternalMoves (no-op / idempotency) ─────────────────────────────

  "ExternalGameService.ingestExternalMoves" should
    "return a no-op result when all moves are already processed" in {
      val f = makeFixture()
      f.service.startExternalGame(aiVsExternalRequest()).value

      val result = f.service.ingestExternalMoves(caller, platform, externalGameId, "", now).value
      result.lastProcessedPly shouldBe 0
      result.nextAction       shouldBe NextAction.TriggerAI  // AI on White, White to move
    }

  it should "ignore moves already covered by lastProcessedPly" in {
    val f = makeFixture(ai = Some(deterministicClient))
    f.service.startExternalGame(aiVsExternalRequest(Color.Black)).value
    // opponent (White) plays e2e4
    val after1 = f.service.ingestExternalMoves(caller, platform, externalGameId, "e2e4", now).value
    after1.lastProcessedPly shouldBe 1
    after1.nextAction       shouldBe NextAction.TriggerAI

    // same event arrives again from Lichess stream
    val after2 = f.service.ingestExternalMoves(caller, platform, externalGameId, "e2e4", now).value
    after2.lastProcessedPly shouldBe 1  // unchanged
    after2.nextAction       shouldBe NextAction.TriggerAI
  }

  it should "apply one new external move and advance lastProcessedPly" in {
    val f = makeFixture()
    f.service.startExternalGame(aiVsExternalRequest(Color.Black)).value
    // AI on Black: White (External) moves first
    val result = f.service.ingestExternalMoves(caller, platform, externalGameId, "e2e4", now).value

    result.lastProcessedPly shouldBe 1
    result.currentPlayer    shouldBe Color.Black   // Black's turn after e2e4
    result.nextAction       shouldBe NextAction.TriggerAI

    val binding = f.bindingRepo.findByExternalId(platform, externalGameId).value.value
    binding.lastProcessedPly shouldBe 1
  }

  it should "return AwaitExternalMove when it is the external side's turn" in {
    val f = makeFixture(ai = Some(deterministicClient))
    f.service.startExternalGame(aiVsExternalRequest(Color.White)).value
    // AI on White plays first; capture AI move
    val aiResult = f.service.requestAiMove(caller, platform, externalGameId, now).value
    val aiUci    = aiResult.uciMove

    // Opponent plays e7e5; stream sends AI move + opponent move
    val result = f.service.ingestExternalMoves(
      caller, platform, externalGameId, s"$aiUci e7e5", now
    ).value
    result.lastProcessedPly shouldBe 2
    result.currentPlayer    shouldBe Color.White
    result.nextAction       shouldBe NextAction.TriggerAI  // still AI's turn (White)
  }

  it should "reject an unauthorized caller" in {
    val f = makeFixture()
    f.service.startExternalGame(aiVsExternalRequest()).value
    val wrongCaller = VerifiedExternalCaller(platform, "someone-else")

    f.service.ingestExternalMoves(wrongCaller, platform, externalGameId, "", now).left.value shouldBe
      ExternalGameError.Unauthorized("someone-else", ourActorId)
  }

  it should "reject move ingestion when binding is not Active" in {
    val f = makeFixture()
    f.service.startExternalGame(aiVsExternalRequest()).value
    f.service.finishExternalGame(caller, platform, externalGameId, now).value

    f.service.ingestExternalMoves(caller, platform, externalGameId, "e2e4", now).left.value shouldBe
      ExternalGameError.BindingNotActive(BindingStatus.Finished)
  }

  // ── requestAiMove ─────────────────────────────────────────────────────────

  "ExternalGameService.requestAiMove" should
    "return a UCI move and advance lastProcessedPly" in {
      val f = makeFixture(ai = Some(deterministicClient))
      f.service.startExternalGame(aiVsExternalRequest(Color.White)).value  // AI on White

      val result = f.service.requestAiMove(caller, platform, externalGameId, now).value
      result.uciMove          should not be empty
      result.lastProcessedPly shouldBe 1
      result.nextAction       shouldBe NextAction.AwaitExternalMove  // Black's turn after AI moves

      val binding = f.bindingRepo.findByExternalId(platform, externalGameId).value.value
      binding.lastProcessedPly shouldBe 1
    }

  it should "reject an unauthorized caller" in {
    val f = makeFixture(ai = Some(deterministicClient))
    f.service.startExternalGame(aiVsExternalRequest(Color.White)).value
    val wrongCaller = VerifiedExternalCaller(platform, "intruder")

    f.service.requestAiMove(wrongCaller, platform, externalGameId, now).left.value shouldBe
      ExternalGameError.Unauthorized("intruder", ourActorId)
  }

  it should "return InvalidRequest when it is not the AI's turn" in {
    // AiVsExternal, AI on Black, White (External) to move first
    val f = makeFixture(ai = Some(deterministicClient))
    f.service.startExternalGame(aiVsExternalRequest(Color.Black)).value

    f.service.requestAiMove(caller, platform, externalGameId, now).left.value shouldBe
      ExternalGameError.InvalidRequest("It is not the AI's turn")
  }

  it should "return AiFailure when no AI client is configured" in {
    val f = makeFixture(ai = None)
    f.service.startExternalGame(aiVsExternalRequest(Color.White)).value

    f.service.requestAiMove(caller, platform, externalGameId, now).left.value shouldBe
      ExternalGameError.AiFailure(chess.application.ai.service.AITurnError.NotConfigured)
  }

  // ── finishExternalGame ────────────────────────────────────────────────────

  "ExternalGameService.finishExternalGame" should
    "mark binding Finished and advance session lifecycle" in {
      val f = makeFixture()
      val binding = f.service.startExternalGame(aiVsExternalRequest()).value

      val result = f.service.finishExternalGame(caller, platform, externalGameId, now).value
      result.status shouldBe BindingStatus.Finished

      val session = f.sessionRepo.load(binding.sessionId).value
      session.lifecycle shouldBe chess.application.session.model.SessionLifecycle.Finished
    }

  it should "reject an unauthorized caller" in {
    val f = makeFixture()
    f.service.startExternalGame(aiVsExternalRequest()).value

    f.service.finishExternalGame(
      VerifiedExternalCaller(platform, "rogue"), platform, externalGameId, now
    ).left.value shouldBe ExternalGameError.Unauthorized("rogue", ourActorId)
  }

  // ── getExternalGame ───────────────────────────────────────────────────────

  "ExternalGameService.getExternalGame" should
    "return the binding after startExternalGame" in {
      val f = makeFixture()
      val created = f.service.startExternalGame(aiVsExternalRequest()).value

      val retrieved = f.service.getExternalGame(platform, externalGameId).value
      retrieved.bindingId shouldBe created.bindingId
    }

  it should "return BindingNotFound for an unknown game" in {
    val f = makeFixture()
    f.service.getExternalGame(platform, "unknown-id").left.value shouldBe
      ExternalGameError.BindingNotFound(platform, "unknown-id")
  }
