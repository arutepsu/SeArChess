package chess.lichessbridge

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.unsafe.implicits.global
import chess.adapter.ai.remote.RemoteAiMoveDto
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class GameLoopHandlerSpec extends AnyFlatSpec with Matchers:

  private val botUsername = Some("testbot")
  private val gameRef     = LichessGameRef("g1", "https://lichess.org/g1", "opponent", Some("white"))

  private def makeStateRef(): Ref[IO, WorkerState] =
    IO.ref(WorkerState.empty.addGame(ActiveGame("g1", "opponent", Some("white"), Instant.now())))
      .unsafeRunSync()

  private def makeHub(): BotGameSnapshotHub =
    BotGameSnapshotHub.create.unsafeRunSync()

  private def runHandler(
      events: List[Either[ParseError, LichessGameEvent]],
      aiResult: Either[AiError, UciMove] = Right(UciMove("e2e4")),
      submitResult: Either[LichessError, Unit] = Right(()),
      stateRef: Ref[IO, WorkerState] = makeStateRef()
  ): WorkerState =
    val handler = GameLoopHandler(
      token          = "test-token",
      game           = gameRef,
      botUsername    = botUsername,
      gameStream     = StubLichessGameStream(events),
      aiClient       = StubAiServiceClient(aiResult),
      lichessClient  = ControllableLichessClient(submitMoveResult = submitResult),
      stateRef       = stateRef,
      snapshotHub    = makeHub()
    )
    handler.run.unsafeRunSync()
    stateRef.get.unsafeRunSync()

  // ── GameFull handling ─────────────────────────────────────────────────────────

  "GameLoopHandler" should "call AI and submit move when bot (white) has first move in GameFull" in {
    var submitted = List.empty[String]
    val client = new ControllableLichessClient():
      override def submitMove(token: String, gameId: String, move: String): IO[Either[LichessError, Unit]] =
        IO { submitted = move :: submitted } >> IO.pure(Right(()))

    val gameFull = LichessGameEvent.GameFull(
      id         = "g1",
      white      = "testbot",
      black      = "opponent",
      initialFen = "startpos",
      moves      = "",
      wtime      = 300000,
      btime      = 300000,
      status     = "started"
    )
    val handler = GameLoopHandler(
      token         = "tok",
      game          = gameRef,
      botUsername   = botUsername,
      gameStream    = StubLichessGameStream(List(Right(gameFull))),
      aiClient      = StubAiServiceClient(Right(UciMove("e2e4"))),
      lichessClient = client,
      stateRef      = makeStateRef(),
      snapshotHub   = makeHub()
    )
    handler.run.unsafeRunSync()
    submitted should contain("e2e4")
  }

  it should "not submit a move when it is the opponent's turn (black has first move, bot is white)" in {
    var submitted = List.empty[String]
    val client = new ControllableLichessClient():
      override def submitMove(token: String, gameId: String, move: String): IO[Either[LichessError, Unit]] =
        IO { submitted = move :: submitted } >> IO.pure(Right(()))

    val gameFull = LichessGameEvent.GameFull(
      id = "g1", white = "testbot", black = "opponent",
      initialFen = "startpos", moves = "e7e5",
      wtime = 300000, btime = 300000, status = "started"
    )
    val handler = GameLoopHandler(
      token = "tok", game = gameRef, botUsername = botUsername,
      gameStream = StubLichessGameStream(List(Right(gameFull))),
      aiClient = StubAiServiceClient(Right(UciMove("e2e4"))),
      lichessClient = client,
      stateRef = makeStateRef(),
      snapshotHub   = makeHub()
    )
    handler.run.unsafeRunSync()
    submitted shouldBe empty
  }

  // ── GameState handling ────────────────────────────────────────────────────────

  it should "submit a move on GameState when it is the bot's turn" in {
    var submitted = List.empty[String]
    val client = new ControllableLichessClient():
      override def submitMove(token: String, gameId: String, move: String): IO[Either[LichessError, Unit]] =
        IO { submitted = move :: submitted } >> IO.pure(Right(()))

    val gameFull = LichessGameEvent.GameFull(
      id = "g1", white = "testbot", black = "opponent",
      initialFen = "startpos", moves = "e7e5",
      wtime = 300000, btime = 300000, status = "started"
    )
    val gameState = LichessGameEvent.GameState(
      moves = "e7e5 e2e4", wtime = 290000, btime = 295000, status = "started"
    )
    val handler = GameLoopHandler(
      token = "tok", game = gameRef, botUsername = botUsername,
      gameStream = StubLichessGameStream(List(Right(gameFull), Right(gameState))),
      aiClient = StubAiServiceClient(Right(UciMove("d2d4"))),
      lichessClient = client,
      stateRef = makeStateRef(),
      snapshotHub   = makeHub()
    )
    handler.run.unsafeRunSync()
    submitted should contain("d2d4")
  }

  // ── Duplicate-move prevention ─────────────────────────────────────────────────

  it should "not submit twice when the same moves string arrives in two consecutive events" in {
    var submitCount = 0
    val client = new ControllableLichessClient():
      override def submitMove(token: String, gameId: String, move: String): IO[Either[LichessError, Unit]] =
        IO { submitCount += 1 } >> IO.pure(Right(()))

    val event = LichessGameEvent.GameState(
      moves = "e2e4", wtime = 290000, btime = 295000, status = "started"
    )
    val gameFull = LichessGameEvent.GameFull(
      id = "g1", white = "opponent", black = "testbot",
      initialFen = "startpos", moves = "e2e4",
      wtime = 300000, btime = 300000, status = "started"
    )
    val handler = GameLoopHandler(
      token = "tok", game = LichessGameRef("g1", "", "opp", Some("black")),
      botUsername = botUsername,
      gameStream = StubLichessGameStream(List(Right(gameFull), Right(event))),
      aiClient = StubAiServiceClient(Right(UciMove("e7e5"))),
      lichessClient = client,
      stateRef = makeStateRef(),
      snapshotHub   = makeHub()
    )
    handler.run.unsafeRunSync()
    submitCount shouldBe 1
  }

  // ── AI failure does not crash fiber ──────────────────────────────────────────

  it should "continue without crashing when AI service returns an error" in {
    val gameFull = LichessGameEvent.GameFull(
      id = "g1", white = "testbot", black = "opponent",
      initialFen = "startpos", moves = "",
      wtime = 300000, btime = 300000, status = "started"
    )
    val stateRef = makeStateRef()
    val handler = GameLoopHandler(
      token = "tok", game = gameRef, botUsername = botUsername,
      gameStream = StubLichessGameStream(List(Right(gameFull))),
      aiClient = StubAiServiceClient(Left(AiError.ServiceError("down"))),
      lichessClient = ControllableLichessClient(),
      stateRef = stateRef,
      snapshotHub   = makeHub()
    )
    handler.run.unsafeRunSync()
    succeed
  }

  it should "continue without crashing when the AI service throws an exception" in {
    val gameFull = LichessGameEvent.GameFull(
      id = "g1", white = "testbot", black = "opponent",
      initialFen = "startpos", moves = "",
      wtime = 300000, btime = 300000, status = "started"
    )
    val stateRef = makeStateRef()
    val handler = GameLoopHandler(
      token = "tok", game = gameRef, botUsername = botUsername,
      gameStream = StubLichessGameStream(List(Right(gameFull))),
      aiClient = FailingAiServiceClient(),
      lichessClient = ControllableLichessClient(),
      stateRef = stateRef,
      snapshotHub   = makeHub()
    )
    handler.run.unsafeRunSync()
    succeed
  }

  // ── Terminal status ───────────────────────────────────────────────────────────

  it should "not submit a move when the game status is terminal" in {
    var submitted = List.empty[String]
    val client = new ControllableLichessClient():
      override def submitMove(token: String, gameId: String, move: String): IO[Either[LichessError, Unit]] =
        IO { submitted = move :: submitted } >> IO.pure(Right(()))

    val gameFull = LichessGameEvent.GameFull(
      id = "g1", white = "testbot", black = "opponent",
      initialFen = "startpos", moves = "",
      wtime = 0, btime = 0, status = "mate"
    )
    val handler = GameLoopHandler(
      token = "tok", game = gameRef, botUsername = botUsername,
      gameStream = StubLichessGameStream(List(Right(gameFull))),
      aiClient = StubAiServiceClient(Right(UciMove("e2e4"))),
      lichessClient = client,
      stateRef = makeStateRef(),
      snapshotHub   = makeHub()
    )
    handler.run.unsafeRunSync()
    submitted shouldBe empty
  }

  // ── Parse error in stream does not crash ──────────────────────────────────────

  it should "log parse errors and continue processing subsequent events" in {
    val parseErr = Left(ParseError("bad line", "{corrupted}"))
    val gameFull = LichessGameEvent.GameFull(
      id = "g1", white = "testbot", black = "opponent",
      initialFen = "startpos", moves = "",
      wtime = 300000, btime = 300000, status = "mate"
    )
    val stateRef = makeStateRef()
    val handler = GameLoopHandler(
      token = "tok", game = gameRef, botUsername = botUsername,
      gameStream = StubLichessGameStream(List(parseErr, Right(gameFull))),
      aiClient = StubAiServiceClient(),
      lichessClient = ControllableLichessClient(),
      stateRef = stateRef,
      snapshotHub   = makeHub()
    )
    handler.run.unsafeRunSync()
    succeed
  }

  // ── submitMove failure does not crash fiber ───────────────────────────────────

  it should "log submitMove failure and not crash the fiber" in {
    val gameFull = LichessGameEvent.GameFull(
      id = "g1", white = "testbot", black = "opponent",
      initialFen = "startpos", moves = "",
      wtime = 300000, btime = 300000, status = "started"
    )
    val stateRef = makeStateRef()
    val handler = GameLoopHandler(
      token = "tok", game = gameRef, botUsername = botUsername,
      gameStream = StubLichessGameStream(List(Right(gameFull))),
      aiClient = StubAiServiceClient(Right(UciMove("e2e4"))),
      lichessClient = ControllableLichessClient(submitMoveResult = Left(LichessError.UnexpectedResponse(400, "illegal"))),
      stateRef = stateRef,
      snapshotHub   = makeHub()
    )
    handler.run.unsafeRunSync()
    succeed
  }

  // ── WorkerState metadata updated ─────────────────────────────────────────────

  it should "update lastSubmittedMove in WorkerState after a successful submitMove" in {
    val gameFull = LichessGameEvent.GameFull(
      id = "g1", white = "testbot", black = "opponent",
      initialFen = "startpos", moves = "",
      wtime = 300000, btime = 300000, status = "started"
    )
    val stateRef = makeStateRef()
    val handler = GameLoopHandler(
      token = "tok", game = gameRef, botUsername = botUsername,
      gameStream = StubLichessGameStream(List(Right(gameFull))),
      aiClient = StubAiServiceClient(Right(UciMove("e2e4"))),
      lichessClient = ControllableLichessClient(),
      stateRef = stateRef,
      snapshotHub   = makeHub()
    )
    handler.run.unsafeRunSync()
    val state = stateRef.get.unsafeRunSync()
    state.activeGames.get("g1").flatMap(_.lastSubmittedMove) shouldBe Some("e2e4")
  }
