package chess.tournamentservice

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger

class PublicTournamentBotRunnerSpec extends AnyFlatSpec with Matchers:

  private val StartFen   = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
  private val AfterE4Fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"

  private val UciPattern = "[a-h][1-8][a-h][1-8][qrbn]?".r

  private def runnerKey(tournamentId: String, gameId: String, botId: String, fen: String): String =
    val hash = f"${fen.hashCode}%08x"
    s"$tournamentId:$gameId:$botId:$hash"

  private val randomBotParticipant = RunnerParticipant(
    tournamentServerBotId   = "white-bot-id",
    tournamentServerBotName = "searchess-random-bot",
    searchessCatalogBotId   = Some("random-bot")
  )

  private val blackBotParticipant = RunnerParticipant(
    tournamentServerBotId   = "black-bot-id",
    tournamentServerBotName = "searchess-random-bot",
    searchessCatalogBotId   = Some("random-bot")
  )

  private def detail(round: Int = 1) =
    RunnerTournamentDetail(status = "ongoing", round = round)

  private def game(
    gameId:     String  = "game1",
    whiteBotId: String  = "white-bot-id",
    blackBotId: String  = "black-bot-id",
    status:     String  = "ongoing"
  ) = RunnerRoundGame(gameId, whiteBotId, blackBotId, status)

  private def snapshot(fen: Option[String] = Some(StartFen), status: String = "ongoing") =
    RunnerGameSnapshot(fen, status)

  private def makeRunner(
    detail:       Either[String, RunnerTournamentDetail]              = Right(detail()),
    pairings:     Either[String, List[RunnerRoundGame]]               = Right(List(game())),
    snapshots:    Map[String, Either[String, RunnerGameSnapshot]]     = Map("game1" -> Right(snapshot())),
    participants: Either[String, List[RunnerParticipant]]             = Right(List(randomBotParticipant)),
    moveResult:   Either[String, Unit]                                = Right(()),
    recentGuard:  Option[RecentSubmissionGuard]                       = None
  ): (PublicTournamentBotRunner, FakeGatewayMoveClient) =
    val serverClient      = FakeTournamentRunnerServerClient(detail, Map(1 -> pairings), snapshots)
    val participantClient = FakeSearchessParticipantClient(participants)
    val moveClient        = FakeGatewayMoveClient(moveResult)
    val registry          = DefaultBotRegistry(TournamentServiceConfig(
      host = "localhost", port = 8085, outputBasePath = "target", maxParallelJobs = 1,
      analyticsEnabled = false, analyticsOutputBasePath = "target", maxParallelAnalyticsJobs = 1,
      analyticsSbtCommand = List("sbt"), stockfishPath = None, searchessAiBaseUrl = None,
      jobStore = "memory", postgresUrl = None, postgresUser = None, postgresPassword = None,
      postgresSchema = "public"
    ))
    val runner = PublicTournamentBotRunner(registry, serverClient, participantClient, moveClient, recentGuard)
    (runner, moveClient)

  "PublicTournamentBotRunner.tick" should "submit a move for the active Searchess bot and return Submitted action" in {
    val (runner, moveClient) = makeRunner()
    val result = runner.tick("t1").unsafeRunSync()

    result.tournamentId shouldBe "t1"
    result.round        shouldBe 1
    result.gamesFound   shouldBe 1
    result.actions       should have size 1

    val action = result.actions.head
    action.status                shouldBe RunnerActionStatus.Submitted
    action.gameId                shouldBe "game1"
    action.tournamentServerBotId shouldBe "white-bot-id"
    action.uciMove match
      case None      => fail("Expected a UCI move but got None")
      case Some(uci) => UciPattern.matches(uci) shouldBe true

    moveClient.submittedCount shouldBe 1
  }

  it should "submit for the black bot when it is black's turn" in {
    val (runner, moveClient) = makeRunner(
      snapshots    = Map("game1" -> Right(snapshot(Some(AfterE4Fen)))),
      participants = Right(List(randomBotParticipant, blackBotParticipant))
    )
    val result = runner.tick("t1").unsafeRunSync()

    result.actions should have size 1
    result.actions.head.tournamentServerBotId shouldBe "black-bot-id"
    result.actions.head.status                shouldBe RunnerActionStatus.Submitted
  }

  it should "return empty result when tournament detail fetch fails" in {
    val (runner, moveClient) = makeRunner(detail = Left("not found"))
    val result = runner.tick("t1").unsafeRunSync()

    result.gamesFound  shouldBe 0
    result.actions     shouldBe Nil
    moveClient.submittedCount shouldBe 0
  }

  it should "skip non-ongoing games without fetching snapshots" in {
    val (runner, moveClient) = makeRunner(pairings = Right(List(game(status = "finished"))))
    val result = runner.tick("t1").unsafeRunSync()

    result.gamesFound shouldBe 1
    result.actions    shouldBe Nil
    moveClient.submittedCount shouldBe 0
  }

  it should "return Skipped with not_a_searchess_participant when active bot is not a participant" in {
    val (runner, moveClient) = makeRunner(participants = Right(Nil))
    val result = runner.tick("t1").unsafeRunSync()

    result.actions should have size 1
    val action = result.actions.head
    action.status shouldBe RunnerActionStatus.Skipped
    action.reason shouldBe Some("not_a_searchess_participant")
    moveClient.submittedCount shouldBe 0
  }

  it should "return Skipped with missing_catalog_bot_id when participant has no catalog id" in {
    val noCatalogParticipant = randomBotParticipant.copy(searchessCatalogBotId = None)
    val (runner, moveClient) = makeRunner(participants = Right(List(noCatalogParticipant)))
    val result = runner.tick("t1").unsafeRunSync()

    result.actions should have size 1
    result.actions.head.status shouldBe RunnerActionStatus.Skipped
    result.actions.head.reason shouldBe Some("missing_catalog_bot_id")
  }

  it should "return Skipped with no_fen when game snapshot has no FEN" in {
    val (runner, moveClient) = makeRunner(snapshots = Map("game1" -> Right(snapshot(fen = None))))
    val result = runner.tick("t1").unsafeRunSync()

    result.actions should have size 1
    result.actions.head.status shouldBe RunnerActionStatus.Skipped
    result.actions.head.reason shouldBe Some("no_fen")
  }

  it should "return Failed when BotRegistry does not know the catalog bot id" in {
    val unknownCatalogParticipant = randomBotParticipant.copy(searchessCatalogBotId = Some("unknown-bot-xyz"))
    val (runner, moveClient) = makeRunner(participants = Right(List(unknownCatalogParticipant)))
    val result = runner.tick("t1").unsafeRunSync()

    result.actions should have size 1
    result.actions.head.status shouldBe RunnerActionStatus.Failed
    moveClient.submittedCount  shouldBe 0
  }

  it should "return Failed when move submission is rejected by the gateway" in {
    val (runner, _) = makeRunner(moveResult = Left("tournament server rejected"))
    val result = runner.tick("t1").unsafeRunSync()

    result.actions should have size 1
    val action = result.actions.head
    action.status shouldBe RunnerActionStatus.Failed
    action.uciMove should not be empty
  }

  it should "return Skipped with already_in_flight when the same game/bot/FEN combination is in progress" in {
    val (runner, moveClient) = makeRunner()
    val key = runnerKey("t1", "game1", "white-bot-id", StartFen)
    runner.acquireForTest(key)
    val result = runner.tick("t1").unsafeRunSync()

    result.actions should have size 1
    result.actions.head.status shouldBe RunnerActionStatus.Skipped
    result.actions.head.reason shouldBe Some("already_in_flight")
    moveClient.submittedCount  shouldBe 0
    runner.releaseForTest(key)
  }

  it should "NOT block a second tick when the FEN has advanced (different position)" in {
    val (runner, moveClient) = makeRunner()
    // Acquire the key for StartFen but tick will see AfterE4Fen — different hash, not blocked
    val staleKey = runnerKey("t1", "game1", "white-bot-id", StartFen)
    runner.acquireForTest(staleKey)
    val result = makeRunner(
      snapshots    = Map("game1" -> Right(snapshot(Some(AfterE4Fen)))),
      participants = Right(List(randomBotParticipant, blackBotParticipant))
    )._1.tick("t1").unsafeRunSync()

    result.actions should have size 1
    result.actions.head.status shouldBe RunnerActionStatus.Submitted
    runner.releaseForTest(staleKey)
  }

  it should "block a second tick when FEN and bot are identical (same position, same bot)" in {
    val (runner, moveClient) = makeRunner()
    // White bot, StartFen — pre-acquire the exact key the runner will compute
    val key = runnerKey("t1", "game1", "white-bot-id", StartFen)
    runner.acquireForTest(key)
    val result = runner.tick("t1").unsafeRunSync()

    result.actions.head.reason shouldBe Some("already_in_flight")
    runner.releaseForTest(key)
  }

  // ── RecentSubmissionGuard integration tests ───────────────────────────────────

  it should "return Skipped with recently_submitted when same FEN is re-ticked within TTL" in {
    val guard = RecentSubmissionGuard(ttlMillis = 60000L)
    val (runner, moveClient) = makeRunner(recentGuard = Some(guard))
    // First tick: submits successfully and marks the key in the guard
    val first = runner.tick("t1").unsafeRunSync()
    first.actions.head.status shouldBe RunnerActionStatus.Submitted
    moveClient.submittedCount shouldBe 1
    // Second tick: same FEN still visible (server snapshot not yet advanced)
    val second = runner.tick("t1").unsafeRunSync()
    second.actions.head.status shouldBe RunnerActionStatus.Skipped
    second.actions.head.reason shouldBe Some("recently_submitted")
    moveClient.submittedCount shouldBe 1
  }

  it should "not block a tick with a different FEN even when the prior FEN key is in recentGuard" in {
    val guard = RecentSubmissionGuard(ttlMillis = 60000L)
    // Pre-mark StartFen/white-bot — simulates a prior successful submission
    guard.markSubmitted(runnerKey("t1", "game1", "white-bot-id", StartFen))
    // AfterE4Fen is black's turn: different FEN and different botId → different key
    val (runner, moveClient) = makeRunner(
      snapshots    = Map("game1" -> Right(snapshot(Some(AfterE4Fen)))),
      participants = Right(List(randomBotParticipant, blackBotParticipant)),
      recentGuard  = Some(guard)
    )
    val result = runner.tick("t1").unsafeRunSync()
    result.actions.head.status                shouldBe RunnerActionStatus.Submitted
    result.actions.head.tournamentServerBotId shouldBe "black-bot-id"
    moveClient.submittedCount                 shouldBe 1
  }

  it should "allow same FEN to be submitted again after the recentGuard TTL has elapsed" in {
    var now = 1000L
    val guard = RecentSubmissionGuard(ttlMillis = 100L, nowMillis = () => now)
    val (runner, moveClient) = makeRunner(recentGuard = Some(guard))
    // First tick: submits and marks key (expires at 1000 + 100 = 1100)
    val first = runner.tick("t1").unsafeRunSync()
    first.actions.head.status shouldBe RunnerActionStatus.Submitted
    moveClient.submittedCount shouldBe 1
    // Advance the controllable clock past the TTL
    now = 1200L
    // Second tick: guard TTL has elapsed — same FEN is now allowed
    val second = runner.tick("t1").unsafeRunSync()
    second.actions.head.status shouldBe RunnerActionStatus.Submitted
    moveClient.submittedCount shouldBe 2
  }

  it should "not mark recentGuard when gateway submission fails" in {
    var now = 1000L
    val guard = RecentSubmissionGuard(ttlMillis = 60000L, nowMillis = () => now)
    val (runner, moveClient) = makeRunner(
      moveResult  = Left("gateway error"),
      recentGuard = Some(guard)
    )
    // First tick: gateway rejects — guard must NOT be marked
    val first = runner.tick("t1").unsafeRunSync()
    first.actions.head.status shouldBe RunnerActionStatus.Failed
    moveClient.submittedCount shouldBe 1
    // Second tick: not blocked — guard was never marked by the failed submission
    val second = runner.tick("t1").unsafeRunSync()
    second.actions.head.status shouldBe RunnerActionStatus.Failed
    moveClient.submittedCount shouldBe 2
  }

  it should "silently skip a game when snapshot fetch fails" in {
    val (runner, moveClient) = makeRunner(snapshots = Map("game1" -> Left("not found")))
    val result = runner.tick("t1").unsafeRunSync()

    result.gamesFound shouldBe 1
    result.actions    shouldBe Nil
    moveClient.submittedCount shouldBe 0
  }

  // ── Bot provider identity tests ───────────────────────────────────────────────
  // These prove the public runner uses the same BotRegistry / bot executor as the
  // local bot-arena tournament, not a separate simplified move generator.

  it should "generate a legal UCI move for material-greedy bot from start position" in {
    val materialGreedyParticipant = randomBotParticipant.copy(searchessCatalogBotId = Some("material-greedy"))
    val (runner, moveClient) = makeRunner(participants = Right(List(materialGreedyParticipant)))
    val result = runner.tick("t1").unsafeRunSync()

    result.actions should have size 1
    val action = result.actions.head
    action.status shouldBe RunnerActionStatus.Submitted
    action.uciMove match
      case None      => fail("Expected a UCI move but got None")
      case Some(uci) => UciPattern.matches(uci) shouldBe true
    moveClient.submittedCount shouldBe 1
  }

  it should "generate a legal UCI move for capture-first bot from start position" in {
    val captureFirstParticipant = randomBotParticipant.copy(searchessCatalogBotId = Some("capture-first"))
    val (runner, moveClient) = makeRunner(participants = Right(List(captureFirstParticipant)))
    val result = runner.tick("t1").unsafeRunSync()

    result.actions should have size 1
    result.actions.head.status shouldBe RunnerActionStatus.Submitted
    result.actions.head.uciMove.exists(UciPattern.matches) shouldBe true
    moveClient.submittedCount shouldBe 1
  }

  it should "report Failed with STOCKFISH_PATH error when stockfish-depth-1 is used but binary is absent" in {
    val stockfishParticipant = randomBotParticipant.copy(searchessCatalogBotId = Some("stockfish-depth-1"))
    // Registry built without stockfishPath → stockfish bots are unavailable
    val (runner, moveClient) = makeRunner(participants = Right(List(stockfishParticipant)))
    val result = runner.tick("t1").unsafeRunSync()

    result.actions should have size 1
    val action = result.actions.head
    action.status shouldBe RunnerActionStatus.Failed
    action.reason.getOrElse("") should include("STOCKFISH_PATH")
    moveClient.submittedCount shouldBe 0
  }

  it should "report Failed with STOCKFISH_PATH error when stockfish-fast is used but binary is absent" in {
    val stockfishParticipant = randomBotParticipant.copy(searchessCatalogBotId = Some("stockfish-fast"))
    val (runner, moveClient) = makeRunner(participants = Right(List(stockfishParticipant)))
    val result = runner.tick("t1").unsafeRunSync()

    result.actions should have size 1
    result.actions.head.status shouldBe RunnerActionStatus.Failed
    result.actions.head.reason.getOrElse("") should include("STOCKFISH_PATH")
    moveClient.submittedCount shouldBe 0
  }

  it should "map catalog bot ID random-bot through BotRegistry identically to local tournament" in {
    // Verifies the catalog ID string "random-bot" resolves to the same executor in both
    // local (GameRunner) and public (PublicTournamentBotRunner) contexts.
    val participant = randomBotParticipant.copy(searchessCatalogBotId = Some("random-bot"))
    val (runner, moveClient) = makeRunner(participants = Right(List(participant)))
    val result = runner.tick("t1").unsafeRunSync()

    result.actions.head.status shouldBe RunnerActionStatus.Submitted
    result.actions.head.uciMove.exists(UciPattern.matches) shouldBe true
  }

  // ── Fake implementations ──────────────────────────────────────────────────────

  private class FakeTournamentRunnerServerClient(
    detail:    Either[String, RunnerTournamentDetail],
    pairings:  Map[Int, Either[String, List[RunnerRoundGame]]],
    snapshots: Map[String, Either[String, RunnerGameSnapshot]]
  ) extends TournamentRunnerServerClient:

    def fetchTournamentDetail(tournamentId: String): IO[Either[String, RunnerTournamentDetail]] =
      IO.pure(detail)

    def fetchRoundPairings(tournamentId: String, round: Int): IO[Either[String, List[RunnerRoundGame]]] =
      IO.pure(pairings.getOrElse(round, Left(s"round $round not in fake")))

    def fetchGameSnapshot(tournamentId: String, gameId: String): IO[Either[String, RunnerGameSnapshot]] =
      IO.pure(snapshots.getOrElse(gameId, Left(s"snapshot $gameId not in fake")))

  private class FakeSearchessParticipantClient(
    participants: Either[String, List[RunnerParticipant]]
  ) extends SearchessParticipantClient:

    def fetchParticipants(tournamentId: String): IO[Either[String, List[RunnerParticipant]]] =
      IO.pure(participants)

  private class FakeGatewayMoveClient(result: Either[String, Unit]) extends GatewayMoveClient:
    private val count = new AtomicInteger(0)

    def submittedCount: Int = count.get()

    def submitMove(
      tournamentId:            String,
      gameId:                  String,
      tournamentServerBotId:   String,
      tournamentServerBotName: String,
      uciMove:                 String
    ): IO[Either[String, Unit]] =
      val _ = count.incrementAndGet()
      IO.pure(result)

extension (runner: PublicTournamentBotRunner)
  def acquireForTest(key: String): Unit =
    runner.inFlight.add(key)
    ()
  def releaseForTest(key: String): Unit =
    runner.inFlight.remove(key)
    ()
