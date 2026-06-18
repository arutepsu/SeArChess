package chess.lichessbridge

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class GameLoopHandlerChatSpec extends AnyFlatSpec with Matchers:

  private val botUsername = Some("testbot")
  private val gameRef     = LichessGameRef("g1", "https://lichess.org/g1", "opponent", Some("white"))

  private def makeStateRef(): Ref[IO, WorkerState] =
    IO.ref(WorkerState.empty.addGame(ActiveGame("g1", "opponent", Some("white"), Instant.now())))
      .unsafeRunSync()

  private def makeHub(): BotGameSnapshotHub =
    BotGameSnapshotHub.create.unsafeRunSync()

  private def gameFull(
      status: String = "started",
      initialFen: String = "startpos",
      moves: String = ""
  ): LichessGameEvent.GameFull =
    LichessGameEvent.GameFull(
      id = "g1", white = "testbot", black = "opponent",
      initialFen = initialFen, moves = moves,
      wtime = 300000, btime = 300000, status = status
    )

  // ── Chat disabled ─────────────────────────────────────────────────────────────

  "GameLoopHandler with chatEnabled=false" should "never call sendChatMessage" in {
    var chatCalls = List.empty[String]
    val client = new ControllableLichessClient():
      override def sendChatMessage(token: String, gameId: String, text: String): IO[Either[LichessError, Unit]] =
        IO { chatCalls = text :: chatCalls } >> IO.pure(Right(()))

    val handler = GameLoopHandler(
      token = "tok", game = gameRef, botUsername = botUsername,
      gameStream    = StubLichessGameStream(List(Right(gameFull()))),
      aiClient      = StubAiServiceClient(Right(UciMove("e2e4"))),
      lichessClient = client,
      stateRef      = makeStateRef(),
      snapshotHub   = makeHub(),
      chatEnabled   = false
    )
    handler.run.unsafeRunSync()
    chatCalls shouldBe empty
  }

  // ── Greeting ─────────────────────────────────────────────────────────────────

  "GameLoopHandler with chatEnabled=true" should "send greeting once on GameFull when bot color is known" in {
    var chatCalls = List.empty[String]
    val client = new ControllableLichessClient():
      override def sendChatMessage(token: String, gameId: String, text: String): IO[Either[LichessError, Unit]] =
        IO { chatCalls = text :: chatCalls } >> IO.pure(Right(()))

    val handler = GameLoopHandler(
      token = "tok", game = gameRef, botUsername = botUsername,
      gameStream    = StubLichessGameStream(List(Right(gameFull()))),
      aiClient      = StubAiServiceClient(Right(UciMove("e2e4"))),
      lichessClient = client,
      stateRef      = makeStateRef(),
      snapshotHub   = makeHub(),
      chatEnabled   = true
    )
    handler.run.unsafeRunSync()
    chatCalls.count(_ == "Hallo und viel Glück!") shouldBe 1
  }

  it should "not send the greeting twice when GameFull arrives twice" in {
    var chatCalls = List.empty[String]
    val client = new ControllableLichessClient():
      override def sendChatMessage(token: String, gameId: String, text: String): IO[Either[LichessError, Unit]] =
        IO { chatCalls = text :: chatCalls } >> IO.pure(Right(()))

    val events = List(Right(gameFull()), Right(gameFull()))
    val handler = GameLoopHandler(
      token = "tok", game = gameRef, botUsername = botUsername,
      gameStream    = StubLichessGameStream(events),
      aiClient      = StubAiServiceClient(Right(UciMove("e2e4"))),
      lichessClient = client,
      stateRef      = makeStateRef(),
      snapshotHub   = makeHub(),
      chatEnabled   = true
    )
    handler.run.unsafeRunSync()
    chatCalls.count(_ == "Hallo und viel Glück!") shouldBe 1
  }

  // ── Farewell ──────────────────────────────────────────────────────────────────

  it should "send farewell once on a terminal GameFull (no greeting for finished game)" in {
    var chatCalls = List.empty[String]
    val client = new ControllableLichessClient():
      override def sendChatMessage(token: String, gameId: String, text: String): IO[Either[LichessError, Unit]] =
        IO { chatCalls = text :: chatCalls } >> IO.pure(Right(()))

    val handler = GameLoopHandler(
      token = "tok", game = gameRef, botUsername = botUsername,
      gameStream    = StubLichessGameStream(List(Right(gameFull(status = "mate")))),
      aiClient      = StubAiServiceClient(Right(UciMove("e2e4"))),
      lichessClient = client,
      stateRef      = makeStateRef(),
      snapshotHub   = makeHub(),
      chatEnabled   = true
    )
    handler.run.unsafeRunSync()
    chatCalls.count(_ == "Gut gespielt!") shouldBe 1
    chatCalls should not contain "Hallo und viel Glück!"
  }

  it should "send farewell once on a terminal GameState" in {
    var chatCalls = List.empty[String]
    val client = new ControllableLichessClient():
      override def sendChatMessage(token: String, gameId: String, text: String): IO[Either[LichessError, Unit]] =
        IO { chatCalls = text :: chatCalls } >> IO.pure(Right(()))

    val gameState = LichessGameEvent.GameState(
      moves = "e2e4 e7e5", wtime = 290000, btime = 295000, status = "resign"
    )
    val events = List(Right(gameFull()), Right(gameState))
    val handler = GameLoopHandler(
      token = "tok", game = gameRef, botUsername = botUsername,
      gameStream    = StubLichessGameStream(events),
      aiClient      = StubAiServiceClient(Right(UciMove("d2d4"))),
      lichessClient = client,
      stateRef      = makeStateRef(),
      snapshotHub   = makeHub(),
      chatEnabled   = true
    )
    handler.run.unsafeRunSync()
    chatCalls.count(_ == "Gut gespielt!") shouldBe 1
  }

  it should "not send farewell twice when two terminal events arrive" in {
    var chatCalls = List.empty[String]
    val client = new ControllableLichessClient():
      override def sendChatMessage(token: String, gameId: String, text: String): IO[Either[LichessError, Unit]] =
        IO { chatCalls = text :: chatCalls } >> IO.pure(Right(()))

    val gs1 = LichessGameEvent.GameState(moves = "e2e4", wtime = 290000, btime = 295000, status = "resign")
    val gs2 = LichessGameEvent.GameState(moves = "e2e4", wtime = 290000, btime = 295000, status = "resign")
    val events = List(Right(gameFull()), Right(gs1), Right(gs2))
    val handler = GameLoopHandler(
      token = "tok", game = gameRef, botUsername = botUsername,
      gameStream    = StubLichessGameStream(events),
      aiClient      = StubAiServiceClient(Right(UciMove("e2e4"))),
      lichessClient = client,
      stateRef      = makeStateRef(),
      snapshotHub   = makeHub(),
      chatEnabled   = true
    )
    handler.run.unsafeRunSync()
    chatCalls.count(_ == "Gut gespielt!") shouldBe 1
  }

  // ── Check announcement ────────────────────────────────────────────────────────

  it should "announce check after bot move puts opponent in check" in {
    // FEN: white queen c1 + king b1; black king a8 — queen to a3 checks king on a8 via a-file
    var chatCalls = List.empty[String]
    val client = new ControllableLichessClient():
      override def sendChatMessage(token: String, gameId: String, text: String): IO[Either[LichessError, Unit]] =
        IO { chatCalls = text :: chatCalls } >> IO.pure(Right(()))

    val checkFen = "k7/8/8/8/8/8/8/1KQ5 w - - 0 1"
    val handler = GameLoopHandler(
      token = "tok", game = gameRef, botUsername = botUsername,
      gameStream    = StubLichessGameStream(List(Right(gameFull(initialFen = checkFen)))),
      aiClient      = StubAiServiceClient(Right(UciMove("c1a3"))),
      lichessClient = client,
      stateRef      = makeStateRef(),
      snapshotHub   = makeHub(),
      chatEnabled   = true
    )
    handler.run.unsafeRunSync()
    chatCalls should contain("Schach!")
  }

  it should "not announce check when the bot move does not give check" in {
    var chatCalls = List.empty[String]
    val client = new ControllableLichessClient():
      override def sendChatMessage(token: String, gameId: String, text: String): IO[Either[LichessError, Unit]] =
        IO { chatCalls = text :: chatCalls } >> IO.pure(Right(()))

    val handler = GameLoopHandler(
      token = "tok", game = gameRef, botUsername = botUsername,
      gameStream    = StubLichessGameStream(List(Right(gameFull()))),
      aiClient      = StubAiServiceClient(Right(UciMove("e2e4"))),
      lichessClient = client,
      stateRef      = makeStateRef(),
      snapshotHub   = makeHub(),
      chatEnabled   = true
    )
    handler.run.unsafeRunSync()
    chatCalls should not contain "Schach!"
  }

  // ── Blunder detection ─────────────────────────────────────────────────────────

  it should "announce blunder when opponent left a piece hanging after their move" in {
    // After "e1f1 f5g6": white rook d1 can capture black queen d4 for free (black king g6 too far)
    var chatCalls = List.empty[String]
    val client = new ControllableLichessClient():
      override def sendChatMessage(token: String, gameId: String, text: String): IO[Either[LichessError, Unit]] =
        IO { chatCalls = text :: chatCalls } >> IO.pure(Right(()))

    val blunderFen = "8/8/8/5k2/3q4/8/8/3RK3 w - - 0 1"
    val handler = GameLoopHandler(
      token = "tok", game = gameRef, botUsername = botUsername,
      gameStream    = StubLichessGameStream(List(Right(gameFull(initialFen = blunderFen, moves = "e1f1 f5g6")))),
      aiClient      = StubAiServiceClient(Right(UciMove("d1d2"))),
      lichessClient = client,
      stateRef      = makeStateRef(),
      snapshotHub   = makeHub(),
      chatEnabled   = true
    )
    handler.run.unsafeRunSync()
    chatCalls should contain("Hoppla, deine Dame steht im Visier!")
  }

  it should "not announce blunder when it is the first move of the game (moveCount=0)" in {
    var chatCalls = List.empty[String]
    val client = new ControllableLichessClient():
      override def sendChatMessage(token: String, gameId: String, text: String): IO[Either[LichessError, Unit]] =
        IO { chatCalls = text :: chatCalls } >> IO.pure(Right(()))

    // startpos, no moves — the initial position has no hanging pieces, but we also guard moveCount=0
    val handler = GameLoopHandler(
      token = "tok", game = gameRef, botUsername = botUsername,
      gameStream    = StubLichessGameStream(List(Right(gameFull()))),
      aiClient      = StubAiServiceClient(Right(UciMove("e2e4"))),
      lichessClient = client,
      stateRef      = makeStateRef(),
      snapshotHub   = makeHub(),
      chatEnabled   = true
    )
    handler.run.unsafeRunSync()
    val blunderMessages = List(
      "Hoppla, deine Dame steht im Visier!",
      "Oh, da hast du deinen Turm ungeschützt gelassen.",
      "Achtung, dein Läufer ist in Gefahr!",
      "Dein Springer steht etwas unglücklich, oder?",
      "Ups, ein freier Bauer!"
    )
    blunderMessages.foreach(msg => chatCalls should not contain msg)
  }

  // ── Chat failure resilience ───────────────────────────────────────────────────

  it should "continue normally and still submit moves when sendChatMessage returns a failure" in {
    var submitted = List.empty[String]
    val client = new ControllableLichessClient(sendChatResult = Left(LichessError.NetworkError("chat down"))):
      override def submitMove(token: String, gameId: String, move: String): IO[Either[LichessError, Unit]] =
        IO { submitted = move :: submitted } >> IO.pure(Right(()))

    val handler = GameLoopHandler(
      token = "tok", game = gameRef, botUsername = botUsername,
      gameStream    = StubLichessGameStream(List(Right(gameFull()))),
      aiClient      = StubAiServiceClient(Right(UciMove("e2e4"))),
      lichessClient = client,
      stateRef      = makeStateRef(),
      snapshotHub   = makeHub(),
      chatEnabled   = true
    )
    handler.run.unsafeRunSync()
    submitted should contain("e2e4")
  }

  it should "continue normally when sendChatMessage throws an exception" in {
    var submitted = List.empty[String]
    val client = new ControllableLichessClient():
      override def sendChatMessage(token: String, gameId: String, text: String): IO[Either[LichessError, Unit]] =
        IO.raiseError(RuntimeException("chat service unavailable"))
      override def submitMove(token: String, gameId: String, move: String): IO[Either[LichessError, Unit]] =
        IO { submitted = move :: submitted } >> IO.pure(Right(()))

    val handler = GameLoopHandler(
      token = "tok", game = gameRef, botUsername = botUsername,
      gameStream    = StubLichessGameStream(List(Right(gameFull()))),
      aiClient      = StubAiServiceClient(Right(UciMove("e2e4"))),
      lichessClient = client,
      stateRef      = makeStateRef(),
      snapshotHub   = makeHub(),
      chatEnabled   = true
    )
    handler.run.unsafeRunSync()
    submitted should contain("e2e4")
  }
