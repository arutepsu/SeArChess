package chess.tournamentservice

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import chess.tournamentservice.db.InMemoryTournamentJobRepository
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
      analyticsEnabled   = true,
      analyticsOutputBasePath = output.resolve("analytics").toString,
      maxParallelAnalyticsJobs = 1,
      analyticsSbtCommand = List("sbt"),
      stockfishPath      = None,
      searchessAiBaseUrl = None,
      jobStore           = "memory",
      postgresUrl        = None,
      postgresUser       = None,
      postgresPassword   = None,
      postgresSchema     = "public"
    )

  private final class FakeAnalyticsRunner(var nextFailure: Option[String] = None) extends TournamentAnalyticsRunner:
    var requests: List[TournamentAnalyticsRunRequest] = Nil

    override def runAnalytics(request: TournamentAnalyticsRunRequest): IO[TournamentAnalyticsRunResult] =
      IO {
        requests = requests :+ request
        nextFailure match
          case Some(message) =>
            nextFailure = None
            throw RuntimeException(message)
          case None =>
            TournamentAnalyticsRunResult(
              analyticsRunId = s"fake-run-${requests.length}",
              inputPath      = request.inputPath,
              outputPath     = request.outputPath
            )
      }

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

  private def freshService(): (TournamentJobService, List[cats.effect.FiberIO[Nothing]], Path, FakeAnalyticsRunner) =
    val output     = Files.createTempDirectory("searchess-tournament-service-test")
    val runner     = FakeAnalyticsRunner()
    val repository = InMemoryTournamentJobRepository.create().unsafeRunSync()
    val service    = TournamentJobService
      .create(DefaultBotRegistry(config(output)), config(output), runner, repository)
      .unsafeRunSync()
    val worker           = service.startWorker().unsafeRunSync()
    val analyticsWorkers = service.startAnalyticsWorkers().unsafeRunSync()
    (service, worker :: analyticsWorkers, output, runner)

  private def cancelWorkers(workers: List[cats.effect.FiberIO[Nothing]]): Unit =
    workers.foreach(_.cancel.unsafeRunSync())

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

  private def waitForAnalysisStatus(
      service: TournamentJobService,
      jobId: String,
      status: TournamentAnalysisStatus
  ): TournamentJob =
    val deadline = System.nanoTime() + 10.seconds.toNanos
    def loop(): TournamentJob =
      val job = service.getJob(jobId).unsafeRunSync().getOrElse(fail(s"job not found: $jobId"))
      if job.analysisStatus == status then job
      else if System.nanoTime() > deadline then job
      else
        Thread.sleep(50)
        loop()
    loop()

  private def createSucceededTournament(service: TournamentJobService): String =
    val resp = post(service, uri"/api/tournaments",
      """{"botIds":["random-bot","capture-first"],"mode":"double-round-robin","repetitions":1,"maxPly":8,"seed":11}""")
    resp.status shouldBe Status.Accepted
    val jobId = bodyJson(resp)("jobId").str
    waitForTerminal(service, jobId).status shouldBe TournamentJobStatus.Succeeded
    jobId

  "TournamentServiceConfig" should "default analytics sbt command to cmd.exe /c sbt --client=false on Windows" in {
    val loaded = TournamentServiceConfig.load(_ => None, osName = "Windows 11").getOrElse(fail("expected config"))
    loaded.analyticsSbtCommand shouldBe List("cmd.exe", "/c", "sbt", "--client=false")
  }

  it should "default analytics sbt command to sbt --client=false on non-Windows" in {
    val loaded = TournamentServiceConfig.load(_ => None, osName = "Linux").getOrElse(fail("expected config"))
    loaded.analyticsSbtCommand shouldBe List("sbt", "--client=false")
  }

  it should "parse a configured analytics sbt command prefix" in {
    val loaded = TournamentServiceConfig
      .load(key => if key == "TOURNAMENT_ANALYTICS_SBT_COMMAND" then Some("\"C:\\Program Files\\sbt\\bin\\sbt.bat\"") else None)
      .getOrElse(fail("expected config"))
    loaded.analyticsSbtCommand shouldBe List("C:\\Program Files\\sbt\\bin\\sbt.bat")
  }

  it should "default to memory job store" in {
    val loaded = TournamentServiceConfig.load(_ => None).getOrElse(fail("expected config"))
    loaded.jobStore shouldBe "memory"
  }

  it should "accept postgres job store with required fields" in {
    val env: String => Option[String] = {
      case "TOURNAMENT_JOB_STORE"       => Some("postgres")
      case "TOURNAMENT_POSTGRES_URL"    => Some("jdbc:postgresql://localhost:5432/searchess")
      case "TOURNAMENT_POSTGRES_USER"   => Some("searchess")
      case "TOURNAMENT_POSTGRES_PASSWORD" => Some("searchess")
      case _                            => None
    }
    val loaded = TournamentServiceConfig.load(env).getOrElse(fail("expected config"))
    loaded.jobStore shouldBe "postgres"
    loaded.postgresUrl shouldBe Some("jdbc:postgresql://localhost:5432/searchess")
    loaded.postgresSchema shouldBe "public"
  }

  it should "reject postgres job store without URL" in {
    val env: String => Option[String] = {
      case "TOURNAMENT_JOB_STORE" => Some("postgres")
      case _                      => None
    }
    TournamentServiceConfig.load(env).isLeft shouldBe true
  }

  it should "reject invalid job store value" in {
    val env: String => Option[String] = {
      case "TOURNAMENT_JOB_STORE" => Some("redis")
      case _                      => None
    }
    TournamentServiceConfig.load(env).isLeft shouldBe true
  }

  it should "reject invalid schema name" in {
    val env: String => Option[String] = {
      case "TOURNAMENT_POSTGRES_SCHEMA" => Some("bad schema!")
      case _                            => None
    }
    TournamentServiceConfig.load(env).isLeft shouldBe true
  }

  it should "default outbox publisher to disabled with logging type and sensible poll settings" in {
    val loaded = TournamentServiceConfig.load(_ => None).getOrElse(fail("expected config"))
    loaded.outboxPublisherEnabled    shouldBe false
    loaded.outboxPollIntervalSeconds shouldBe 5
    loaded.outboxBatchSize           shouldBe 50
    loaded.outboxMaxAttempts         shouldBe 10
    loaded.outboxPublisherType       shouldBe "logging"
  }

  it should "parse explicit outbox config when vars are set" in {
    val env: String => Option[String] = {
      case "TOURNAMENT_OUTBOX_PUBLISHER_ENABLED"     => Some("true")
      case "TOURNAMENT_OUTBOX_POLL_INTERVAL_SECONDS" => Some("10")
      case "TOURNAMENT_OUTBOX_BATCH_SIZE"            => Some("25")
      case "TOURNAMENT_OUTBOX_MAX_ATTEMPTS"          => Some("5")
      case "TOURNAMENT_OUTBOX_PUBLISHER_TYPE"        => Some("noop")
      case _                                         => None
    }
    val loaded = TournamentServiceConfig.load(env).getOrElse(fail("expected config"))
    loaded.outboxPublisherEnabled    shouldBe true
    loaded.outboxPollIntervalSeconds shouldBe 10
    loaded.outboxBatchSize           shouldBe 25
    loaded.outboxMaxAttempts         shouldBe 5
    loaded.outboxPublisherType       shouldBe "noop"
  }

  it should "reject invalid outbox poll interval" in {
    val env: String => Option[String] = {
      case "TOURNAMENT_OUTBOX_POLL_INTERVAL_SECONDS" => Some("0")
      case _                                         => None
    }
    TournamentServiceConfig.load(env).isLeft shouldBe true
  }

  it should "reject invalid outbox batch size" in {
    val env: String => Option[String] = {
      case "TOURNAMENT_OUTBOX_BATCH_SIZE" => Some("-1")
      case _                              => None
    }
    TournamentServiceConfig.load(env).isLeft shouldBe true
  }

  it should "reject invalid outbox publisher type" in {
    val env: String => Option[String] = {
      case "TOURNAMENT_OUTBOX_PUBLISHER_TYPE" => Some("rabbitmq")
      case _                                  => None
    }
    TournamentServiceConfig.load(env).isLeft shouldBe true
  }

  it should "reject kafka publisher type when TOURNAMENT_KAFKA_BOOTSTRAP_SERVERS is missing" in {
    val env: String => Option[String] = {
      case "TOURNAMENT_OUTBOX_PUBLISHER_TYPE" => Some("kafka")
      case _                                  => None
    }
    TournamentServiceConfig.load(env).isLeft shouldBe true
  }

  it should "accept kafka publisher type when TOURNAMENT_KAFKA_BOOTSTRAP_SERVERS is provided" in {
    val env: String => Option[String] = {
      case "TOURNAMENT_OUTBOX_PUBLISHER_TYPE"        => Some("kafka")
      case "TOURNAMENT_KAFKA_BOOTSTRAP_SERVERS"      => Some("localhost:9092")
      case _                                         => None
    }
    val loaded = TournamentServiceConfig.load(env).getOrElse(fail("expected config"))
    loaded.outboxPublisherType    shouldBe "kafka"
    loaded.kafkaBootstrapServers  shouldBe Some("localhost:9092")
    loaded.kafkaTopic             shouldBe "searchess.tournament.lifecycle.v1"
    loaded.kafkaClientId          shouldBe "searchess-tournament-service"
    loaded.kafkaAcks              shouldBe "all"
  }

  it should "parse explicit kafka topic and client-id overrides" in {
    val env: String => Option[String] = {
      case "TOURNAMENT_OUTBOX_PUBLISHER_TYPE"   => Some("kafka")
      case "TOURNAMENT_KAFKA_BOOTSTRAP_SERVERS" => Some("broker1:9092,broker2:9092")
      case "TOURNAMENT_KAFKA_TOPIC"             => Some("my.custom.topic.v1")
      case "TOURNAMENT_KAFKA_CLIENT_ID"         => Some("my-client")
      case "TOURNAMENT_KAFKA_ACKS"              => Some("1")
      case _                                    => None
    }
    val loaded = TournamentServiceConfig.load(env).getOrElse(fail("expected config"))
    loaded.kafkaTopic    shouldBe "my.custom.topic.v1"
    loaded.kafkaClientId shouldBe "my-client"
    loaded.kafkaAcks     shouldBe "1"
  }

  "SparkTournamentAnalyticsProcessRunner" should "build commands from the configured prefix" in {
    val request = TournamentAnalyticsRunRequest(
      tournamentJobId = "job-1",
      inputPath       = "C:\\searchess data\\arena\\job-1\\game-events.jsonl",
      outputPath      = "C:\\searchess data\\spark-analytics\\job-1"
    )

    val command = SparkTournamentAnalyticsProcessRunner.command(List("cmd.exe", "/c", "sbt"), request)

    command.take(3) shouldBe List("cmd.exe", "/c", "sbt")
    command should have size 4

    val sbtRunCommand = command.last
    sbtRunCommand should include("sparkAnalytics/runMain")
    sbtRunCommand should include("chess.analytics.app.GameAnalyticsJob")
    sbtRunCommand should include("\"C:\\searchess data\\arena\\job-1\\game-events.jsonl\"")
    sbtRunCommand should include("\"C:\\searchess data\\spark-analytics\\job-1\"")
  }

  it should "reject analytics paths containing double quotes" in {
    val request = TournamentAnalyticsRunRequest(
      tournamentJobId = "job-1",
      inputPath       = "target/arena/job-\"1\"/game-events.jsonl",
      outputPath      = "target/spark-analytics/job-1"
    )

    val error = intercept[IllegalArgumentException] {
      SparkTournamentAnalyticsProcessRunner.command(List("sbt"), request)
    }
    error.getMessage should include("must not contain double quotes")
  }

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
    val (service, workers, _, _) = freshService()
    try
      val resp = post(service, uri"/api/tournaments",
        """{"botIds":["random-bot"],"mode":"double-round-robin","repetitions":1,"maxPly":20}""")
      resp.status shouldBe Status.BadRequest
      bodyJson(resp)("message").str should include("at least 2")
    finally cancelWorkers(workers)
  }

  it should "reject duplicate bot IDs" in {
    val (service, workers, _, _) = freshService()
    try
      val resp = post(service, uri"/api/tournaments",
        """{"botIds":["random-bot","random-bot"],"mode":"double-round-robin","repetitions":1,"maxPly":20}""")
      resp.status shouldBe Status.BadRequest
      bodyJson(resp)("message").str should include("unique")
    finally cancelWorkers(workers)
  }

  it should "reject unknown bot IDs" in {
    val (service, workers, _, _) = freshService()
    try
      val resp = post(service, uri"/api/tournaments",
        """{"botIds":["random-bot","missing-bot"],"mode":"double-round-robin","repetitions":1,"maxPly":20}""")
      resp.status shouldBe Status.BadRequest
      bodyJson(resp)("message").str should include("Unknown botId")
    finally cancelWorkers(workers)
  }

  it should "reject unavailable bots" in {
    val (service, workers, _, _) = freshService()
    try
      val resp = post(service, uri"/api/tournaments",
        """{"botIds":["random-bot","stockfish-depth-1"],"mode":"double-round-robin","repetitions":1,"maxPly":20}""")
      resp.status shouldBe Status.BadRequest
      bodyJson(resp)("message").str should include("STOCKFISH_PATH")
    finally cancelWorkers(workers)
  }

  it should "accept a valid heuristic tournament and expose status" in {
    val (service, workers, _, _) = freshService()
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
    finally cancelWorkers(workers)
  }

  "GET /api/tournaments/:jobId" should "return 404 for an unknown job" in {
    val (service, workers, _, _) = freshService()
    try
      val resp = get(service, uri"/api/tournaments/550e8400-e29b-41d4-a716-446655440099")
      resp.status shouldBe Status.NotFound
    finally cancelWorkers(workers)
  }

  it should "return 400 for an invalid jobId" in {
    val (service, workers, _, _) = freshService()
    try
      val resp = get(service, uri"/api/tournaments/not-a-uuid")
      resp.status shouldBe Status.BadRequest
      bodyJson(resp)("code").str shouldBe "INVALID_JOB_ID"
    finally cancelWorkers(workers)
  }

  "Tournament worker" should "write JSONL output for a small heuristic tournament" in {
    val (service, workers, _, _) = freshService()
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
    finally cancelWorkers(workers)
  }

  "POST /api/tournaments/:jobId/analyze" should "return 404 for an unknown job" in {
    val (service, workers, _, _) = freshService()
    try
      val resp = post(service, uri"/api/tournaments/550e8400-e29b-41d4-a716-446655440099/analyze", "{}")
      resp.status shouldBe Status.NotFound
    finally cancelWorkers(workers)
  }

  it should "reject a non-succeeded job" in {
    val (service, workers, _, _) = freshService()
    try
      val resp = post(service, uri"/api/tournaments",
        """{"botIds":["random-bot","capture-first"],"mode":"double-round-robin","repetitions":1,"maxPly":80}""")
      val jobId = bodyJson(resp)("jobId").str
      val analyzeResp = post(service, Uri.unsafeFromString(s"/api/tournaments/$jobId/analyze"), "{}")
      analyzeResp.status shouldBe Status.Conflict
      bodyJson(analyzeResp)("code").str shouldBe "JOB_NOT_SUCCEEDED"
    finally cancelWorkers(workers)
  }

  it should "queue analytics for a succeeded job" in {
    val (service, workers, _, _) = freshService()
    try
      val jobId = createSucceededTournament(service)
      val resp = post(service, Uri.unsafeFromString(s"/api/tournaments/$jobId/analyze"), "{}")
      resp.status shouldBe Status.Accepted
      val json = bodyJson(resp)
      json("jobId").str shouldBe jobId
      json("analysisStatus").str shouldBe "queued"
      json("analyticsOutputPath").str should include(jobId)
    finally cancelWorkers(workers)
  }

  it should "update analysis status and run ID on success" in {
    val (service, workers, _, _) = freshService()
    try
      val jobId = createSucceededTournament(service)
      post(service, Uri.unsafeFromString(s"/api/tournaments/$jobId/analyze"), "{}").status shouldBe Status.Accepted
      val done = waitForAnalysisStatus(service, jobId, TournamentAnalysisStatus.Succeeded)
      done.analyticsRunId shouldBe Some("fake-run-1")
      done.analyticsOutputPath.getOrElse(fail("expected analyticsOutputPath")) should include(jobId)
    finally cancelWorkers(workers)
  }

  it should "update analysis status and error message on failure" in {
    val (service, workers, _, runner) = freshService()
    try
      val jobId = createSucceededTournament(service)
      runner.nextFailure = Some("spark failed")
      post(service, Uri.unsafeFromString(s"/api/tournaments/$jobId/analyze"), "{}").status shouldBe Status.Accepted
      val failed = waitForAnalysisStatus(service, jobId, TournamentAnalysisStatus.Failed)
      failed.analyticsErrorMessage shouldBe Some("spark failed")
    finally cancelWorkers(workers)
  }

  it should "allow retrying failed analysis" in {
    val (service, workers, _, runner) = freshService()
    try
      val jobId = createSucceededTournament(service)
      runner.nextFailure = Some("first attempt failed")
      post(service, Uri.unsafeFromString(s"/api/tournaments/$jobId/analyze"), "{}").status shouldBe Status.Accepted
      waitForAnalysisStatus(service, jobId, TournamentAnalysisStatus.Failed).analysisStatus shouldBe TournamentAnalysisStatus.Failed

      post(service, Uri.unsafeFromString(s"/api/tournaments/$jobId/analyze"), "{}").status shouldBe Status.Accepted
      val succeeded = waitForAnalysisStatus(service, jobId, TournamentAnalysisStatus.Succeeded)
      succeeded.analyticsRunId shouldBe Some("fake-run-2")
      succeeded.analyticsErrorMessage shouldBe None
    finally cancelWorkers(workers)
  }
