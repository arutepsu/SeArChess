package chess.adapter.http4s

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import chess.adapter.http4s.route.{BotWorkerAuth, Http4sBotInternalRoutes}
import chess.adapter.repository.{
  InMemoryGameRepository,
  InMemorySessionGameStore,
  InMemorySessionRepository
}
import chess.application.bot.{BotTurnStatus, BotTurnTask, InMemoryBotTurnTaskRepository}
import chess.application.session.model.{GameSession, SessionMode, SideController}
import chess.application.session.service.{SessionGameCommandService, SessionLifecycleService}
import chess.domain.model.{Color, Move, Position}
import chess.domain.state.GameStateFactory
import fs2.Stream
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.ci.CIString
import java.time.Instant
import java.util.UUID

class Http4sBotInternalRoutesSpec extends AnyFlatSpec with Matchers:

  private val ValidKey = "test-bot-key-xyz"
  private val WrongKey = "not-the-right-key"
  private val ActorId  = "searchess-bot"

  // ── Fixture ───────────────────────────────────────────────────────────────

  private final case class Fixture(
      routes: HttpApp[IO],
      sessionRepo: InMemorySessionRepository,
      gameRepo: InMemoryGameRepository,
      botTaskRepo: InMemoryBotTurnTaskRepository,
      commandService: SessionGameCommandService
  )

  private def makeFixture(): Fixture =
    val sessionRepo    = InMemorySessionRepository()
    val gameRepo       = InMemoryGameRepository()
    val store          = InMemorySessionGameStore(sessionRepo, gameRepo)
    val botTaskRepo    = InMemoryBotTurnTaskRepository()
    val lifecycleSvc   = SessionLifecycleService(sessionRepo, _ => ())
    // commandService without botTurnTaskRepository: tasks are created manually in each test
    val commandService = SessionGameCommandService(lifecycleSvc, store, _ => ())
    val auth           = BotWorkerAuth(ValidKey)
    val routes         = Http4sBotInternalRoutes(
      auth                  = auth,
      botTurnTaskRepository = botTaskRepo,
      sessionRepository     = sessionRepo,
      gameRepository        = gameRepo,
      commandService        = commandService,
      leaseSeconds          = 30
    ).routes.orNotFound
    Fixture(routes, sessionRepo, gameRepo, botTaskRepo, commandService)

  // Creates a HumanVsDeployedBot session (white=Human, black=DeployedBot) and saves initial state.
  private def newGame(f: Fixture): (GameStateFactory.type, GameSession) =
    val (_, session) = f.commandService
      .newGame(SessionMode.HumanVsDeployedBot, SideController.HumanLocal, SideController.DeployedBot)
      .getOrElse(throw AssertionError("newGame failed"))
    (GameStateFactory, session)

  private def playWhiteE2E4(f: Fixture, session: GameSession): Unit =
    val state = f.gameRepo.load(session.gameId).getOrElse(throw AssertionError("game not found"))
    val e2    = Position.from(4, 1).getOrElse(throw AssertionError("bad position"))
    val e4    = Position.from(4, 3).getOrElse(throw AssertionError("bad position"))
    f.commandService.submitMove(session, state, Move(e2, e4), SideController.HumanLocal)

  private def pendingTask(session: GameSession, side: Color, now: Instant): BotTurnTask =
    BotTurnTask(
      id           = UUID.randomUUID(),
      sessionId    = session.sessionId,
      gameId       = session.gameId,
      botActorId   = ActorId,
      sideToMove   = side,
      status       = BotTurnStatus.Pending,
      leaseUntil   = None,
      attemptCount = 0,
      lastError    = None,
      createdAt    = now,
      updatedAt    = now
    )

  private def leasedTask(session: GameSession, side: Color, now: Instant): BotTurnTask =
    pendingTask(session, side, now).copy(
      status     = BotTurnStatus.Leased,
      leaseUntil = Some(now.plusSeconds(30))
    )

  // ── HTTP helpers ──────────────────────────────────────────────────────────

  private def run(f: Fixture, req: Request[IO]): Response[IO] =
    f.routes.run(req).unsafeRunSync()

  private def bodyJson(resp: Response[IO]): ujson.Value =
    ujson.read(resp.bodyText.compile.string.unsafeRunSync())

  private def getRequest(key: Option[String] = Some(ValidKey), actorId: String = ActorId) =
    val base = Request[IO](
      Method.GET,
      Uri.unsafeFromString(s"/internal/bot/turns?actorId=$actorId&limit=1")
    )
    key.fold(base)(k => base.withHeaders(Header.Raw(CIString("X-Bot-Api-Key"), k)))

  private def postRequest(taskId: UUID, body: String, key: Option[String] = Some(ValidKey)) =
    val base = Request[IO](
      Method.POST,
      Uri.unsafeFromString(s"/internal/bot/turns/$taskId/move")
    ).withBodyStream(Stream.emits(body.getBytes("UTF-8")).covary[IO])
    key.fold(base)(k => base.withHeaders(Header.Raw(CIString("X-Bot-Api-Key"), k)))

  // ── GET auth ─────────────────────────────────────────────────────────────

  "GET /internal/bot/turns" should "return 401 when X-Bot-Api-Key is absent" in {
    val f    = makeFixture()
    val resp = run(f, getRequest(key = None))
    resp.status shouldBe Status.Unauthorized
    bodyJson(resp)("code").str shouldBe "UNAUTHORIZED"
  }

  it should "return 401 when X-Bot-Api-Key is wrong" in {
    val f    = makeFixture()
    val resp = run(f, getRequest(key = Some(WrongKey)))
    resp.status shouldBe Status.Unauthorized
    bodyJson(resp)("code").str shouldBe "UNAUTHORIZED"
  }

  // ── GET no tasks ──────────────────────────────────────────────────────────

  it should "return 204 when there are no pending tasks" in {
    val f    = makeFixture()
    val resp = run(f, getRequest())
    resp.status shouldBe Status.NoContent
  }

  // ── GET with a pending task ───────────────────────────────────────────────

  it should "return 200 with task details when a pending task exists" in {
    val f   = makeFixture()
    val now = Instant.now()
    val (_, session) = f.commandService
      .newGame(SessionMode.HumanVsDeployedBot, SideController.HumanLocal, SideController.DeployedBot)
      .getOrElse(throw AssertionError("newGame failed"))
    val task = pendingTask(session, Color.Black, now)
    f.botTaskRepo.createPending(task)

    val resp = run(f, getRequest())
    resp.status shouldBe Status.Ok
    val json = bodyJson(resp)
    json("turnTaskId").str shouldBe task.id.toString
    json("sessionId").str shouldBe session.sessionId.value.toString
    json("gameId").str shouldBe session.gameId.value.toString
    json("sideToMove").str shouldBe "Black"
    json("legalMoves").arr.nonEmpty shouldBe true
    json("leaseUntil").str should not be empty
  }

  it should "transition the task to Leased after a successful GET" in {
    val f   = makeFixture()
    val now = Instant.now()
    val (_, session) = f.commandService
      .newGame(SessionMode.HumanVsDeployedBot, SideController.HumanLocal, SideController.DeployedBot)
      .getOrElse(throw AssertionError("newGame failed"))
    val task = pendingTask(session, Color.Black, now)
    f.botTaskRepo.createPending(task)

    run(f, getRequest())

    val updated = f.botTaskRepo.findById(task.id).toOption.flatten
      .getOrElse(throw AssertionError("task not found"))
    updated.status shouldBe BotTurnStatus.Leased
    updated.leaseUntil shouldBe defined
    updated.attemptCount shouldBe 1
  }

  it should "only return tasks matching the actorId" in {
    val f   = makeFixture()
    val now = Instant.now()
    val (_, session) = f.commandService
      .newGame(SessionMode.HumanVsDeployedBot, SideController.HumanLocal, SideController.DeployedBot)
      .getOrElse(throw AssertionError("newGame failed"))
    val task = pendingTask(session, Color.Black, now).copy(botActorId = "other-bot")
    f.botTaskRepo.createPending(task)

    val resp = run(f, getRequest(actorId = ActorId))
    resp.status shouldBe Status.NoContent
  }

  // ── POST auth ─────────────────────────────────────────────────────────────

  "POST /internal/bot/turns/{id}/move" should "return 401 when X-Bot-Api-Key is absent" in {
    val f    = makeFixture()
    val resp = run(f, postRequest(UUID.randomUUID(), """{"from":"e7","to":"e5"}""", key = None))
    resp.status shouldBe Status.Unauthorized
    bodyJson(resp)("code").str shouldBe "UNAUTHORIZED"
  }

  it should "return 401 when X-Bot-Api-Key is wrong" in {
    val f    = makeFixture()
    val resp = run(f, postRequest(UUID.randomUUID(), """{"from":"e7","to":"e5"}""", key = Some(WrongKey)))
    resp.status shouldBe Status.Unauthorized
    bodyJson(resp)("code").str shouldBe "UNAUTHORIZED"
  }

  // ── POST validation ───────────────────────────────────────────────────────

  it should "return 404 when the task ID is unknown" in {
    val f    = makeFixture()
    val resp = run(f, postRequest(UUID.randomUUID(), """{"from":"e7","to":"e5"}"""))
    resp.status shouldBe Status.NotFound
    bodyJson(resp)("code").str shouldBe "TASK_NOT_FOUND"
  }

  it should "return 409 when the task is still Pending (not yet Leased)" in {
    val f   = makeFixture()
    val now = Instant.now()
    val (_, session) = f.commandService
      .newGame(SessionMode.HumanVsDeployedBot, SideController.HumanLocal, SideController.DeployedBot)
      .getOrElse(throw AssertionError("newGame failed"))
    val task = pendingTask(session, Color.Black, now)
    f.botTaskRepo.createPending(task)

    val resp = run(f, postRequest(task.id, """{"from":"e7","to":"e5"}"""))
    resp.status shouldBe Status.Conflict
    bodyJson(resp)("code").str shouldBe "LEASE_INVALID"
  }

  it should "return 409 when the lease has expired" in {
    val f   = makeFixture()
    val now = Instant.now()
    val (_, session) = f.commandService
      .newGame(SessionMode.HumanVsDeployedBot, SideController.HumanLocal, SideController.DeployedBot)
      .getOrElse(throw AssertionError("newGame failed"))
    val expiredLease = now.minusSeconds(60)
    val task = leasedTask(session, Color.Black, now).copy(leaseUntil = Some(expiredLease))
    f.botTaskRepo.createPending(task)

    val resp = run(f, postRequest(task.id, """{"from":"e7","to":"e5"}"""))
    resp.status shouldBe Status.Conflict
    bodyJson(resp)("code").str shouldBe "LEASE_EXPIRED"
  }

  it should "return 409 when the game does not have the bot's side to move" in {
    val f   = makeFixture()
    val now = Instant.now()
    val (_, session) = f.commandService
      .newGame(SessionMode.HumanVsDeployedBot, SideController.HumanLocal, SideController.DeployedBot)
      .getOrElse(throw AssertionError("newGame failed"))
    // Initial state has White to move; bot task says Black should move → mismatch
    val task = leasedTask(session, Color.Black, now)
    f.botTaskRepo.createPending(task)

    val resp = run(f, postRequest(task.id, """{"from":"e7","to":"e5"}"""))
    resp.status shouldBe Status.Conflict
    bodyJson(resp)("code").str shouldBe "NOT_BOT_TURN"
  }

  it should "return 422 when the submitted move is illegal" in {
    val f   = makeFixture()
    val now = Instant.now()
    val (_, session) = f.commandService
      .newGame(SessionMode.HumanVsDeployedBot, SideController.HumanLocal, SideController.DeployedBot)
      .getOrElse(throw AssertionError("newGame failed"))
    playWhiteE2E4(f, session)
    val task = leasedTask(session, Color.Black, now)
    f.botTaskRepo.createPending(task)

    val resp = run(f, postRequest(task.id, """{"from":"a7","to":"a2"}""")) // a7→a2 is illegal
    resp.status shouldBe Status.UnprocessableEntity
    bodyJson(resp)("code").str shouldBe "MOVE_REJECTED"
  }

  it should "return 200 and mark task Completed after a valid bot move" in {
    val f   = makeFixture()
    val now = Instant.now()
    val (_, session) = f.commandService
      .newGame(SessionMode.HumanVsDeployedBot, SideController.HumanLocal, SideController.DeployedBot)
      .getOrElse(throw AssertionError("newGame failed"))
    playWhiteE2E4(f, session)
    val task = leasedTask(session, Color.Black, now)
    f.botTaskRepo.createPending(task)

    val resp = run(f, postRequest(task.id, """{"from":"e7","to":"e5"}""")) // e7→e5 legal
    resp.status shouldBe Status.Ok
    bodyJson(resp)("status").str shouldBe "ok"

    val updated = f.botTaskRepo.findById(task.id).toOption.flatten
      .getOrElse(throw AssertionError("task not found after move"))
    updated.status shouldBe BotTurnStatus.Completed
  }

  it should "return 200 immediately when the task is already Completed (idempotent)" in {
    val f   = makeFixture()
    val now = Instant.now()
    val (_, session) = f.commandService
      .newGame(SessionMode.HumanVsDeployedBot, SideController.HumanLocal, SideController.DeployedBot)
      .getOrElse(throw AssertionError("newGame failed"))
    val task = leasedTask(session, Color.Black, now).copy(status = BotTurnStatus.Completed)
    f.botTaskRepo.createPending(task)

    val resp = run(f, postRequest(task.id, """{"from":"e7","to":"e5"}"""))
    resp.status shouldBe Status.Ok
    bodyJson(resp)("note").str shouldBe "already_completed"
  }
