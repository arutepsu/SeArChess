package chess.tournamentservice

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.http4s.*
import org.http4s.implicits.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

class TournamentServiceSpec extends AnyFlatSpec with Matchers:

  private def config(output: Path): TournamentServiceConfig =
    TournamentServiceConfig(
      host               = "127.0.0.1",
      port               = 8085,
      outputBasePath     = output.toString,
      maxParallelJobs    = 1,
      stockfishPath      = None,
      searchessAiBaseUrl = None
    )

  private def app(service: TournamentJobService): HttpApp[IO] =
    TournamentRoutes(service).routes.orNotFound

  private def get(service: TournamentJobService, path: Uri): Response[IO] =
    app(service).run(Request[IO](Method.GET, path)).unsafeRunSync()

  private def post(service: TournamentJobService, path: Uri, body: String): Response[IO] =
    app(service)
      .run(
        Request[IO](Method.POST, path)
          .withEntity(body)
      )
      .unsafeRunSync()

  private def bodyJson(resp: Response[IO]): ujson.Value =
    ujson.read(resp.bodyText.compile.string.unsafeRunSync())

  private def freshService(): (TournamentJobService, cats.effect.FiberIO[Nothing], Path) =
    val output = Files.createTempDirectory("searchess-tournament-service-test")
    val service = TournamentJobService
      .create(DefaultBotRegistry(config(output)), config(output))
      .unsafeRunSync()
    val worker = service.startWorker().unsafeRunSync()
    (service, worker, output)

  private def waitForTerminal(service: TournamentJobService, jobId: String): TournamentJob =
    val deadline = System.nanoTime() + 10.seconds.toNanos
    def loop(): TournamentJob =
      val job = service.getJob(jobId).unsafeRunSync().getOrElse(fail(s"job not found: $jobId"))
      if TournamentJobStatus.terminal(job.status) then job
      else if System.nanoTime() > deadline then job
      else
        Thread.sleep(50)
        loop()
    loop()

  "DefaultBotRegistry" should "list heuristic bots as available" in {
    val bots = DefaultBotRegistry(config(Files.createTempDirectory("registry-test"))).listBots()
    bots.find(_.botId == "random-bot").map(_.available) shouldBe Some(true)
    bots.find(_.botId == "capture-first").map(_.available) shouldBe Some(true)
    bots.find(_.botId == "material-greedy").map(_.available) shouldBe Some(true)
  }

  it should "mark Stockfish bots unavailable when STOCKFISH_PATH is missing" in {
    val bots = DefaultBotRegistry(config(Files.createTempDirectory("registry-test"))).listBots()
    val stockfish = bots.filter(_.engineType == "stockfish")
    stockfish.map(_.available).distinct shouldBe List(false)
    stockfish.head.unavailableReason.getOrElse(fail("expected unavailable reason")) should include("STOCKFISH_PATH")
  }

  "POST /api/tournaments" should "reject fewer than 2 bots" in {
    val (service, worker, _) = freshService()
    try
      val resp = post(service, uri"/api/tournaments",
        """{"botIds":["random-bot"],"mode":"double-round-robin","repetitions":1,"maxPly":20}""")
      resp.status shouldBe Status.BadRequest
      bodyJson(resp)("message").str should include("at least 2")
    finally worker.cancel.unsafeRunSync()
  }

  it should "reject duplicate bot IDs" in {
    val (service, worker, _) = freshService()
    try
      val resp = post(service, uri"/api/tournaments",
        """{"botIds":["random-bot","random-bot"],"mode":"double-round-robin","repetitions":1,"maxPly":20}""")
      resp.status shouldBe Status.BadRequest
      bodyJson(resp)("message").str should include("unique")
    finally worker.cancel.unsafeRunSync()
  }

  it should "reject unknown bot IDs" in {
    val (service, worker, _) = freshService()
    try
      val resp = post(service, uri"/api/tournaments",
        """{"botIds":["random-bot","missing-bot"],"mode":"double-round-robin","repetitions":1,"maxPly":20}""")
      resp.status shouldBe Status.BadRequest
      bodyJson(resp)("message").str should include("Unknown botId")
    finally worker.cancel.unsafeRunSync()
  }

  it should "reject unavailable bots" in {
    val (service, worker, _) = freshService()
    try
      val resp = post(service, uri"/api/tournaments",
        """{"botIds":["random-bot","stockfish-depth-1"],"mode":"double-round-robin","repetitions":1,"maxPly":20}""")
      resp.status shouldBe Status.BadRequest
      bodyJson(resp)("message").str should include("STOCKFISH_PATH")
    finally worker.cancel.unsafeRunSync()
  }

  it should "accept a valid heuristic tournament and expose status" in {
    val (service, worker, _) = freshService()
    try
      val resp = post(service, uri"/api/tournaments",
        """{"name":"Heuristic smoke","botIds":["random-bot","capture-first"],"mode":"double-round-robin","repetitions":1,"maxPly":12,"seed":42}""")
      resp.status shouldBe Status.Accepted
      val accepted = bodyJson(resp)
      val jobId = accepted("jobId").str
      accepted("status").str shouldBe "queued"

      val done = waitForTerminal(service, jobId)
      done.status shouldBe TournamentJobStatus.Succeeded

      val statusResp = get(service, Uri.unsafeFromString(s"/api/tournaments/$jobId"))
      statusResp.status shouldBe Status.Ok
      val statusJson = bodyJson(statusResp)
      statusJson("jobId").str shouldBe jobId
      statusJson("selectedBotIds").arr.map(_.str).toList shouldBe List("random-bot", "capture-first")
      statusJson("plannedGames").num.toInt shouldBe 2
    finally worker.cancel.unsafeRunSync()
  }

  "GET /api/tournaments/:jobId" should "return 404 for an unknown job" in {
    val (service, worker, _) = freshService()
    try
      val resp = get(service, uri"/api/tournaments/550e8400-e29b-41d4-a716-446655440099")
      resp.status shouldBe Status.NotFound
    finally worker.cancel.unsafeRunSync()
  }

  it should "return 400 for an invalid jobId" in {
    val (service, worker, _) = freshService()
    try
      val resp = get(service, uri"/api/tournaments/not-a-uuid")
      resp.status shouldBe Status.BadRequest
      bodyJson(resp)("code").str shouldBe "INVALID_JOB_ID"
    finally worker.cancel.unsafeRunSync()
  }

  "Tournament worker" should "write JSONL output for a small heuristic tournament" in {
    val (service, worker, _) = freshService()
    try
      val resp = post(service, uri"/api/tournaments",
        """{"botIds":["random-bot","capture-first"],"mode":"double-round-robin","repetitions":1,"maxPly":8,"seed":7}""")
      val jobId = bodyJson(resp)("jobId").str
      val done = waitForTerminal(service, jobId)
      done.status shouldBe TournamentJobStatus.Succeeded
      done.completedGames shouldBe done.plannedGames
      val output = Path.of(done.outputPath.getOrElse(fail("expected outputPath")))
      Files.exists(output) shouldBe true
      val lines = Files.readAllLines(output)
      lines.isEmpty shouldBe false
      lines.toString should include("GameFinished")
    finally worker.cancel.unsafeRunSync()
  }
