package chess.tournamentservice

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.semigroupk.*
import chess.tournamentservice.db.{InMemoryPublicImportRepository, InMemoryTournamentJobRepository}
import org.http4s.*
import org.http4s.implicits.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

class PublicImportServiceSpec extends AnyFlatSpec with Matchers:

  // ── Fixtures ──────────────────────────────────────────────────────────────────

  private val sampleExport = PublicTournamentExport(
    tournamentId = "pub-abc",
    format       = "swiss",
    clock        = ExportClock(300, 5),
    rated        = true,
    startedAt    = Some("2025-01-01T10:00:00Z"),
    finishedAt   = Some("2025-01-01T10:30:00Z"),
    games = List(
      ExportGame(
        gameId            = "game-1",
        tournamentId      = "pub-abc",
        round             = 1,
        whiteBotId        = "alpha",
        whiteBotName      = "AlphaBot",
        whiteBotFamily    = Some("heuristic"),
        whiteStrategyType = Some("capture-first"),
        blackBotId        = "beta",
        blackBotName      = "BetaBot",
        blackBotFamily    = None,
        blackStrategyType = None,
        winner            = Some("white"),
        winnerBotId       = Some("alpha"),
        terminationReason = "checkmate",
        totalPly          = 5,
        moves             = "e2e4 f7f6 d2d4 g7g5 d1h5",
        startedAt         = Some("2025-01-01T10:01:00Z"),
        endedAt           = Some("2025-01-01T10:02:00Z"),
        durationMillis    = Some(60000L),
      )
    ),
    standings = List(
      ExportStanding("pub-abc", "alpha", "AlphaBot", Some("heuristic"), Some("capture-first"), 1, 1.0, 1, 0, 0, 1, 0.0),
      ExportStanding("pub-abc", "beta",  "BetaBot",  None,              None,                   2, 0.0, 0, 0, 1, 1, 0.0),
    ),
  )

  final class StubPublicTournamentClient(
      var response: Either[ImportError, PublicTournamentExport] = Right(sampleExport)
  ) extends PublicTournamentClient:
    var lastBaseUrl: String      = ""
    var lastTournamentId: String = ""

    def fetchExport(serverBaseUrl: String, tournamentId: String): IO[Either[ImportError, PublicTournamentExport]] =
      lastBaseUrl      = serverBaseUrl
      lastTournamentId = tournamentId
      IO.pure(response)

  private def freshService(
      output: Path,
      stub: StubPublicTournamentClient = new StubPublicTournamentClient(),
  ): (PublicImportService, InMemoryTournamentJobRepository) =
    val jobRepo    = InMemoryTournamentJobRepository.create().unsafeRunSync()
    val importRepo = InMemoryPublicImportRepository.create().unsafeRunSync()
    val service    = PublicImportService(jobRepo, importRepo, stub, output.toString)
    (service, jobRepo)

  private def freshRouteApp(
      output: Path,
      stub: StubPublicTournamentClient = new StubPublicTournamentClient(),
  ): HttpApp[IO] =
    val (service, _) = freshService(output, stub)
    PublicImportRoutes(service).routes.orNotFound

  private def post(app: HttpApp[IO], path: Uri, body: String): Response[IO] =
    app.run(Request[IO](Method.POST, path).withEntity(body)).unsafeRunSync()

  private def bodyJson(resp: Response[IO]): ujson.Value =
    ujson.read(resp.bodyText.compile.string.unsafeRunSync())

  private def readLines(path: java.nio.file.Path): List[String] =
    Files.readAllLines(path).asScala.toList

  private def importResult(result: Either[ImportError, PublicImportResult]): PublicImportResult =
    result.toOption.getOrElse(fail(s"Expected Right but got Left: $result"))

  private def jsonlLines(result: Either[ImportError, PublicImportResult]): List[String] =
    readLines(java.nio.file.Paths.get(importResult(result).jsonlPath))

  // ── PublicExportParser tests ──────────────────────────────────────────────────

  "PublicExportParser" should "parse a valid analytics export" in {
    val json = ujson.write(ujson.Obj(
      "tournamentId" -> "t1",
      "format"       -> "swiss",
      "clock"        -> ujson.Obj("limit" -> 300, "increment" -> 5),
      "rated"        -> true,
      "startedAt"    -> "2025-01-01T10:00:00Z",
      "finishedAt"   -> "2025-01-01T11:00:00Z",
      "games" -> ujson.Arr(ujson.Obj(
        "gameId"            -> "g1",
        "tournamentId"      -> "t1",
        "round"             -> 1,
        "whiteBotId"        -> "w1",
        "whiteBotName"      -> "White",
        "blackBotId"        -> "b1",
        "blackBotName"      -> "Black",
        "winner"            -> "white",
        "winnerBotId"       -> "w1",
        "terminationReason" -> "checkmate",
        "totalPly"          -> 5,
        "moves"             -> "e2e4 e7e5",
        "durationMillis"    -> 1000,
      )),
      "standings" -> ujson.Arr(),
    ))
    val parsed = PublicExportParser.parse(json).toOption.getOrElse(fail("Expected Right"))
    parsed.tournamentId shouldBe "t1"
    parsed.games.length shouldBe 1
    parsed.games.head.gameId shouldBe "g1"
    parsed.games.head.totalPly shouldBe 5
    parsed.games.head.moves shouldBe "e2e4 e7e5"
  }

  it should "return ParseError for invalid JSON" in {
    val result = PublicExportParser.parse("{not-json}")
    result.isLeft shouldBe true
    result.left.toOption.getOrElse(fail("Expected Left")) shouldBe a[ImportError.ParseError]
  }

  it should "return ParseError for missing required fields" in {
    PublicExportParser.parse("""{"format":"swiss"}""").isLeft shouldBe true
  }

  it should "handle optional startedAt and finishedAt absent" in {
    val json = ujson.write(ujson.Obj(
      "tournamentId" -> "t1",
      "format"       -> "swiss",
      "clock"        -> ujson.Obj("limit" -> 300, "increment" -> 5),
      "rated"        -> false,
      "games"        -> ujson.Arr(),
      "standings"    -> ujson.Arr(),
    ))
    val parsed = PublicExportParser.parse(json).toOption.getOrElse(fail("Expected Right"))
    parsed.startedAt shouldBe None
    parsed.finishedAt shouldBe None
  }

  // ── PublicImportService tests ─────────────────────────────────────────────────

  "PublicImportService" should "return Imported on successful import" in {
    val output = Files.createTempDirectory("import-test")
    val (service, _) = freshService(output)
    val result = service.importTournament(PublicImportRequest("http://server", "pub-abc")).unsafeRunSync()
    result.isRight shouldBe true
    val r = importResult(result)
    r.publicTournamentId shouldBe "pub-abc"
    r.serverBaseUrl shouldBe "http://server"
    r.status shouldBe "Imported"
  }

  it should "write a JSONL file at the returned jsonlPath" in {
    val output = Files.createTempDirectory("import-test")
    val (service, _) = freshService(output)
    val result = service.importTournament(PublicImportRequest("http://server", "pub-abc")).unsafeRunSync()
    Files.exists(java.nio.file.Paths.get(importResult(result).jsonlPath)) shouldBe true
  }

  it should "produce GameStarted + N MovePlayed + GameFinished per game (7 lines for 5-ply game)" in {
    val output = Files.createTempDirectory("import-test")
    val (service, _) = freshService(output)
    val result = service.importTournament(PublicImportRequest("http://server", "pub-abc")).unsafeRunSync()
    jsonlLines(result).size shouldBe 7
  }

  it should "produce a GameStarted event as first JSONL line" in {
    val output = Files.createTempDirectory("import-test")
    val (service, _) = freshService(output)
    val result = service.importTournament(PublicImportRequest("http://server", "pub-abc")).unsafeRunSync()
    val first = ujson.read(jsonlLines(result).head)
    first("eventType").str shouldBe "GameStarted"
    first("whiteBotId").str shouldBe "alpha"
    first("blackBotId").str shouldBe "beta"
    first("tournamentId").str shouldBe "pub-abc"
    first("whiteBotFamily").str shouldBe "heuristic"
  }

  it should "produce GameFinished as last JSONL line with correct result and metadata" in {
    val output = Files.createTempDirectory("import-test")
    val (service, _) = freshService(output)
    val result = service.importTournament(PublicImportRequest("http://server", "pub-abc")).unsafeRunSync()
    val last = ujson.read(jsonlLines(result).last)
    last("eventType").str shouldBe "GameFinished"
    last("result").str shouldBe "white"
    last("winnerBotId").str shouldBe "alpha"
    last("loserBotId").str shouldBe "beta"
    last("terminationReason").str shouldBe "checkmate"
    last("totalPly").num.toInt shouldBe 5
    last("durationMillis").num.toLong shouldBe 60000L
  }

  it should "assign moves to correct bots (odd ply = white, even ply = black)" in {
    val output = Files.createTempDirectory("import-test")
    val (service, _) = freshService(output)
    val result = service.importTournament(PublicImportRequest("http://server", "pub-abc")).unsafeRunSync()
    val moveLines = jsonlLines(result)
      .filter(l => ujson.read(l)("eventType").str == "MovePlayed")
      .map(ujson.read(_))
    moveLines.length shouldBe 5
    moveLines(0)("botId").str shouldBe "alpha"
    moveLines(0)("moveUci").str shouldBe "e2e4"
    moveLines(0)("plyNumber").num.toInt shouldBe 1
    moveLines(1)("botId").str shouldBe "beta"
    moveLines(1)("moveUci").str shouldBe "f7f6"
    moveLines(1)("plyNumber").num.toInt shouldBe 2
    moveLines(4)("botId").str shouldBe "alpha"
    moveLines(4)("moveUci").str shouldBe "d1h5"
    moveLines(4)("plyNumber").num.toInt shouldBe 5
  }

  it should "create a TournamentJob with status Succeeded and correct fields" in {
    val output = Files.createTempDirectory("import-test")
    val (service, jobRepo) = freshService(output)
    val result = service.importTournament(PublicImportRequest("http://server", "pub-abc")).unsafeRunSync()
    val r = importResult(result)
    val job = jobRepo.getJob(r.jobId).unsafeRunSync().getOrElse(fail(s"job not found: ${r.jobId}"))
    job.status shouldBe TournamentJobStatus.Succeeded
    job.mode shouldBe "public-import"
    job.outputPath shouldBe Some(r.jsonlPath)
    job.plannedGames shouldBe 1
    job.completedGames shouldBe 1
    job.selectedBotIds should contain allOf ("alpha", "beta")
    job.analyzeUrl.getOrElse("") should include(r.jobId)
  }

  it should "forward NotFound error from the client" in {
    val output = Files.createTempDirectory("import-test")
    val stub = new StubPublicTournamentClient(Left(ImportError.NotFound("pub-abc")))
    val (service, _) = freshService(output, stub)
    val result = service.importTournament(PublicImportRequest("http://server", "pub-abc")).unsafeRunSync()
    result shouldBe Left(ImportError.NotFound("pub-abc"))
  }

  it should "forward NotFinished error from the client" in {
    val output = Files.createTempDirectory("import-test")
    val stub = new StubPublicTournamentClient(Left(ImportError.NotFinished("pub-abc")))
    val (service, _) = freshService(output, stub)
    val result = service.importTournament(PublicImportRequest("http://server", "pub-abc")).unsafeRunSync()
    result shouldBe Left(ImportError.NotFinished("pub-abc"))
  }

  it should "map black winner correctly in GameFinished" in {
    val blackWins = sampleExport.copy(
      games = List(sampleExport.games.head.copy(winner = Some("black"), winnerBotId = Some("beta")))
    )
    val output = Files.createTempDirectory("import-test")
    val (service, _) = freshService(output, new StubPublicTournamentClient(Right(blackWins)))
    val result = service.importTournament(PublicImportRequest("http://server", "pub-abc")).unsafeRunSync()
    val last = ujson.read(jsonlLines(result).last)
    last("result").str shouldBe "black"
    last("winnerBotId").str shouldBe "beta"
    last("loserBotId").str shouldBe "alpha"
  }

  it should "map draw correctly (no winnerBotId in GameFinished)" in {
    val drawGame = sampleExport.copy(
      games = List(sampleExport.games.head.copy(winner = Some("draw"), winnerBotId = None, terminationReason = "stalemate"))
    )
    val output = Files.createTempDirectory("import-test")
    val (service, _) = freshService(output, new StubPublicTournamentClient(Right(drawGame)))
    val result = service.importTournament(PublicImportRequest("http://server", "pub-abc")).unsafeRunSync()
    val last = ujson.read(jsonlLines(result).last)
    last("result").str shouldBe "draw"
    last.obj.contains("winnerBotId") shouldBe false
  }

  it should "produce no MovePlayed events for a game with zero ply" in {
    val zeroply = sampleExport.copy(
      games = List(sampleExport.games.head.copy(moves = "", totalPly = 0))
    )
    val output = Files.createTempDirectory("import-test")
    val (service, _) = freshService(output, new StubPublicTournamentClient(Right(zeroply)))
    val result = service.importTournament(PublicImportRequest("http://server", "pub-abc")).unsafeRunSync()
    jsonlLines(result).size shouldBe 2
  }

  it should "pass serverBaseUrl and tournamentId verbatim to the client" in {
    val output = Files.createTempDirectory("import-test")
    val stub = new StubPublicTournamentClient()
    val (service, _) = freshService(output, stub)
    service.importTournament(PublicImportRequest("http://my-server:9090", "my-id")).unsafeRunSync()
    stub.lastBaseUrl shouldBe "http://my-server:9090"
    stub.lastTournamentId shouldBe "my-id"
  }

  // ── JSONL round-trip: verify via existing GameEventJson decoder ───────────────

  "Imported JSONL" should "be decodable by GameEventJson for all lines" in {
    val output = Files.createTempDirectory("import-roundtrip")
    val (service, _) = freshService(output)
    val result = service.importTournament(PublicImportRequest("http://server", "pub-abc")).unsafeRunSync()
    jsonlLines(result).foreach: line =>
      chess.arena.events.GameEventJson.decode(line).isRight shouldBe true
  }

  // ── PublicImportRoutes HTTP tests ─────────────────────────────────────────────

  "POST /api/tournaments/public/import" should "return 200 with import result on success" in {
    val output = Files.createTempDirectory("import-route-test")
    val resp = post(freshRouteApp(output), uri"/api/tournaments/public/import",
      """{"serverBaseUrl":"http://server","tournamentId":"pub-abc"}""")
    resp.status shouldBe Status.Ok
    val json = bodyJson(resp)
    json("publicTournamentId").str shouldBe "pub-abc"
    json("status").str shouldBe "Imported"
    json("serverBaseUrl").str shouldBe "http://server"
    json("analyzeUrl").str should include("/api/tournaments/")
    json("analyzeUrl").str should endWith("/analyze")
  }

  it should "return 400 for missing serverBaseUrl" in {
    val output = Files.createTempDirectory("import-route-test")
    val resp = post(freshRouteApp(output), uri"/api/tournaments/public/import",
      """{"tournamentId":"pub-abc"}""")
    resp.status shouldBe Status.BadRequest
    bodyJson(resp)("code").str shouldBe "INVALID_IMPORT_REQUEST"
  }

  it should "return 400 for missing tournamentId" in {
    val output = Files.createTempDirectory("import-route-test")
    val resp = post(freshRouteApp(output), uri"/api/tournaments/public/import",
      """{"serverBaseUrl":"http://server"}""")
    resp.status shouldBe Status.BadRequest
    bodyJson(resp)("code").str shouldBe "INVALID_IMPORT_REQUEST"
  }

  it should "return 400 for malformed JSON body" in {
    val output = Files.createTempDirectory("import-route-test")
    val resp = post(freshRouteApp(output), uri"/api/tournaments/public/import", "not-json")
    resp.status shouldBe Status.BadRequest
    bodyJson(resp)("code").str shouldBe "INVALID_IMPORT_REQUEST"
  }

  it should "return 404 when public server returns NotFound" in {
    val output = Files.createTempDirectory("import-route-test")
    val stub = new StubPublicTournamentClient(Left(ImportError.NotFound("pub-abc")))
    val resp = post(freshRouteApp(output, stub), uri"/api/tournaments/public/import",
      """{"serverBaseUrl":"http://server","tournamentId":"pub-abc"}""")
    resp.status shouldBe Status.NotFound
    bodyJson(resp)("code").str shouldBe "TOURNAMENT_NOT_FOUND"
  }

  it should "return 409 when tournament is not finished" in {
    val output = Files.createTempDirectory("import-route-test")
    val stub = new StubPublicTournamentClient(Left(ImportError.NotFinished("pub-abc")))
    val resp = post(freshRouteApp(output, stub), uri"/api/tournaments/public/import",
      """{"serverBaseUrl":"http://server","tournamentId":"pub-abc"}""")
    resp.status shouldBe Status.Conflict
    bodyJson(resp)("code").str shouldBe "TOURNAMENT_NOT_FINISHED"
  }

  it should "return 422 when export JSON is malformed" in {
    val output = Files.createTempDirectory("import-route-test")
    val stub = new StubPublicTournamentClient(Left(ImportError.ParseError("bad structure")))
    val resp = post(freshRouteApp(output, stub), uri"/api/tournaments/public/import",
      """{"serverBaseUrl":"http://server","tournamentId":"pub-abc"}""")
    resp.status shouldBe Status.UnprocessableEntity
    bodyJson(resp)("code").str shouldBe "EXPORT_PARSE_ERROR"
  }

  it should "return 502 on network error" in {
    val output = Files.createTempDirectory("import-route-test")
    val stub = new StubPublicTournamentClient(Left(ImportError.NetworkError("connection refused")))
    val resp = post(freshRouteApp(output, stub), uri"/api/tournaments/public/import",
      """{"serverBaseUrl":"http://server","tournamentId":"pub-abc"}""")
    resp.status shouldBe Status.BadGateway
    bodyJson(resp)("code").str shouldBe "NETWORK_ERROR"
  }

  it should "return 502 on HTTP error from public server" in {
    val output = Files.createTempDirectory("import-route-test")
    val stub = new StubPublicTournamentClient(Left(ImportError.HttpError(500, "internal server error")))
    val resp = post(freshRouteApp(output, stub), uri"/api/tournaments/public/import",
      """{"serverBaseUrl":"http://server","tournamentId":"pub-abc"}""")
    resp.status shouldBe Status.BadGateway
    bodyJson(resp)("code").str shouldBe "PUBLIC_SERVER_ERROR"
  }

  // ── Existing routes compatibility ──────────────────────────────────────────────

  "PublicImportRoutes" should "coexist with TournamentRoutes without shadowing" in {
    val output = Files.createTempDirectory("import-compat-test")
    val config = TournamentServiceConfig(
      host                     = "127.0.0.1",
      port                     = 8085,
      outputBasePath           = output.toString,
      maxParallelJobs          = 1,
      analyticsEnabled         = false,
      analyticsOutputBasePath  = output.toString,
      maxParallelAnalyticsJobs = 1,
      analyticsSbtCommand      = List("sbt"),
      stockfishPath            = None,
      searchessAiBaseUrl       = None,
      jobStore                 = "memory",
      postgresUrl              = None,
      postgresUser             = None,
      postgresPassword         = None,
      postgresSchema           = "public",
    )
    val runner = new TournamentAnalyticsRunner:
      def runAnalytics(req: TournamentAnalyticsRunRequest): IO[TournamentAnalyticsRunResult] =
        IO.pure(TournamentAnalyticsRunResult("run-1", req.inputPath, req.outputPath))
    val jobRepo       = InMemoryTournamentJobRepository.create().unsafeRunSync()
    val importRepo    = InMemoryPublicImportRepository.create().unsafeRunSync()
    val stub          = new StubPublicTournamentClient()
    val importService = PublicImportService(jobRepo, importRepo, stub, output.toString)
    val tourService   = TournamentJobService.create(DefaultBotRegistry(config), config, runner, jobRepo).unsafeRunSync()
    val worker        = tourService.startWorker().unsafeRunSync()
    try
      val app = (TournamentRoutes(tourService).routes <+> PublicImportRoutes(importService).routes).orNotFound
      val health = app.run(Request[IO](Method.GET, uri"/health")).unsafeRunSync()
      health.status shouldBe Status.Ok
      val listResp = app.run(Request[IO](Method.GET, uri"/api/tournaments")).unsafeRunSync()
      listResp.status shouldBe Status.Ok
    finally
      worker.cancel.unsafeRunSync()
  }
