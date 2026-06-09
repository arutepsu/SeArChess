package chess.lichessbridge

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.unsafe.implicits.global
import chess.lichessbridge.LichessError.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.concurrent.duration.*

class LichessBridgeWorkerSpec extends AnyFlatSpec with Matchers:

  // ── Fixtures ──────────────────────────────────────────────────────────────────

  private val enabledConfig = LichessBridgeConfig(
    enabled            = true,
    lichessApiBaseUrl  = "https://lichess.org",
    lichessBotUsername = Some("testbot"),
    lichessBotToken    = Some("lip_test"),
    aiServiceUrl       = "http://ai-service:8765",
    maxConcurrentGames = 2,
    host               = "0.0.0.0",
    port               = 8090,
    acceptChallenges   = true
  )

  private val disabledConfig = enabledConfig.copy(enabled = false)

  private val standardChallenge = LichessChallenge(
    challengeId        = "ch1",
    challengerUsername = "opponent",
    variant            = "standard",
    speed              = "blitz",
    rated              = false,
    timeControl        = LichessTimeControl("clock", Some(300), Some(0)),
    color              = None
  )

  private def makeRef(state: WorkerState = WorkerState.empty): Ref[IO, WorkerState] =
    IO.ref(state).unsafeRunSync()

  /** Runs the worker with a finite stream and waits for the stream to finish before returning.
    * The resource value is a join-handle: `.use(join => join)` blocks until the stream terminates.
    */
  private def runResource(
      config: LichessBridgeConfig,
      client: LichessClient[IO],
      stream: LichessEventStream[IO],
      policy: ChallengePolicy[IO],
      stateRef: Ref[IO, WorkerState]
  ): WorkerState =
    LichessBridgeWorker
      .resource(config, client, stream, policy, stateRef)
      .use(join => join)  // wait for the finite test stream to process all events
      .unsafeRunSync()
    stateRef.get.unsafeRunSync()

  // ── Worker startup ────────────────────────────────────────────────────────────

  "LichessBridgeWorker" should "not start and leave state empty when bridge is disabled" in {
    val stateRef = makeRef()
    runResource(
      disabledConfig,
      StubLichessClient(),
      StubLichessEventStream(),
      AcceptAllChallengePolicy(),
      stateRef
    )
    val state = stateRef.get.unsafeRunSync()
    state.running     shouldBe false
    state.activeGames shouldBe empty
  }

  it should "not start and leave state empty when enabled but no token" in {
    val config   = enabledConfig.copy(lichessBotToken = None)
    val stateRef = makeRef()
    runResource(config, StubLichessClient(), StubLichessEventStream(), AcceptAllChallengePolicy(), stateRef)
    val state = stateRef.get.unsafeRunSync()
    state.running shouldBe false
  }

  it should "update state.lastError when token validation fails" in {
    val stateRef = makeRef()
    val client   = ControllableLichessClient(validateResult = Left(Unauthorized("bad token")))
    runResource(enabledConfig, client, StubLichessEventStream(), AcceptAllChallengePolicy(), stateRef)
    val state = stateRef.get.unsafeRunSync()
    state.running                  shouldBe false
    state.lastError.isDefined      shouldBe true
  }

  // ── Challenge handling ────────────────────────────────────────────────────────

  it should "accept a challenge when policy allows" in {
    var accepted = List.empty[String]
    val client = new ControllableLichessClient(
      validateResult = Right(true),
      acceptResult   = Right(())
    ):
      override def acceptChallenge(token: String, challengeId: String): IO[Either[LichessError, Unit]] =
        IO { accepted = challengeId :: accepted } >> IO.pure(Right(()))

    val events: List[Either[ParseError, LichessBotEvent]] =
      List(Right(LichessBotEvent.ChallengeCreated(standardChallenge)))

    val stateRef = makeRef()
    runResource(
      enabledConfig,
      client,
      StubLichessEventStream(events),
      AcceptAllChallengePolicy(),
      stateRef
    )
    accepted should contain("ch1")
  }

  it should "decline a challenge when policy rejects" in {
    var declined = List.empty[(String, String)]
    val client = new ControllableLichessClient(
      validateResult = Right(true),
      declineResult  = Right(())
    ):
      override def declineChallenge(token: String, challengeId: String, reason: String): IO[Either[LichessError, Unit]] =
        IO { declined = (challengeId, reason) :: declined } >> IO.pure(Right(()))

    val events: List[Either[ParseError, LichessBotEvent]] =
      List(Right(LichessBotEvent.ChallengeCreated(standardChallenge)))

    val stateRef = makeRef()
    runResource(
      enabledConfig,
      client,
      StubLichessEventStream(events),
      DeclineAllChallengePolicy(DeclineReason.MaxGamesReached),
      stateRef
    )
    declined should contain(("ch1", "later"))
  }

  // ── Game tracking ─────────────────────────────────────────────────────────────

  it should "register an active game on GameStart and remove it on GameFinish" in {
    val gameRef = LichessGameRef("g1", "https://lichess.org/g1", "human1", Some("white"))
    val events: List[Either[ParseError, LichessBotEvent]] = List(
      Right(LichessBotEvent.GameStart(gameRef)),
      Right(LichessBotEvent.GameFinish(gameRef))
    )

    val stateRef = makeRef()
    val client   = ControllableLichessClient(validateResult = Right(true))
    runResource(
      enabledConfig,
      client,
      StubLichessEventStream(events),
      AcceptAllChallengePolicy(),
      stateRef
    )
    stateRef.get.unsafeRunSync().activeGames shouldBe empty
  }

  it should "register an active game and keep it if GameFinish not emitted" in {
    val gameRef = LichessGameRef("g2", "https://lichess.org/g2", "human2", Some("black"))
    val events: List[Either[ParseError, LichessBotEvent]] =
      List(Right(LichessBotEvent.GameStart(gameRef)))

    val stateRef = makeRef()
    val client   = ControllableLichessClient(validateResult = Right(true))
    runResource(
      enabledConfig,
      client,
      StubLichessEventStream(events),
      AcceptAllChallengePolicy(),
      stateRef
    )
    val state = stateRef.get.unsafeRunSync()
    state.activeGames.keys should contain("g2")
    state.activeGames("g2").opponent shouldBe "human2"
  }

  // ── Parse error handling ──────────────────────────────────────────────────────

  it should "not crash on a parse error in the stream — continues processing remaining events" in {
    val gameRef = LichessGameRef("g3", "https://lichess.org/g3", "human3", None)
    val events: List[Either[ParseError, LichessBotEvent]] = List(
      Left(ParseError("bad json", "{broken}")),
      Right(LichessBotEvent.GameStart(gameRef))
    )

    val stateRef = makeRef()
    val client   = ControllableLichessClient(validateResult = Right(true))
    runResource(
      enabledConfig,
      client,
      StubLichessEventStream(events),
      AcceptAllChallengePolicy(),
      stateRef
    )
    stateRef.get.unsafeRunSync().activeGames.keys should contain("g3")
  }

  // ── Stream reconnection ───────────────────────────────────────────────────────

  it should "handle a transient network disconnect and not propagate the error" in {
    // Stream that emits one event then disconnects; worker catches and retries once before stopping.
    val gameRef = LichessGameRef("g4", "https://lichess.org/g4", "human4", None)

    // First call emits one event then errors; second call (retry) emits nothing.
    var callCount = 0
    val stream = new LichessEventStream[IO]:
      def streamBotEvents(token: String): fs2.Stream[IO, Either[ParseError, LichessBotEvent]] =
        callCount += 1
        if callCount == 1 then
          fs2.Stream(Right(LichessBotEvent.GameStart(gameRef))) ++
          fs2.Stream.raiseError[IO](LichessNetworkException("connection reset"))
        else
          fs2.Stream.empty

    val stateRef = makeRef()
    val client   = ControllableLichessClient(validateResult = Right(true))
    // join waits for the stream to terminate (after emit + disconnect + retry + empty stream)
    LichessBridgeWorker
      .resource(enabledConfig, client, stream, AcceptAllChallengePolicy(), stateRef)
      .use(join => join)
      .unsafeRunSync()

    stateRef.get.unsafeRunSync().activeGames.keys should contain("g4")
    callCount should be >= 2
  }

  it should "not retry on a fatal auth error" in {
    var callCount = 0
    val stream = new LichessEventStream[IO]:
      def streamBotEvents(token: String): fs2.Stream[IO, Either[ParseError, LichessBotEvent]] =
        callCount += 1
        fs2.Stream.raiseError[IO](LichessAuthException("HTTP 401"))

    val stateRef = makeRef()
    val client   = ControllableLichessClient(validateResult = Right(true))
    LichessBridgeWorker
      .resource(enabledConfig, client, stream, AcceptAllChallengePolicy(), stateRef)
      .use(join => join)
      .unsafeRunSync()

    callCount shouldBe 1  // no retry after fatal auth error
    stateRef.get.unsafeRunSync().lastError.isDefined shouldBe true
  }

  // ── Status: no token leakage ──────────────────────────────────────────────────

  it should "never expose the token in WorkerState" in {
    val stateRef = makeRef()
    val client   = ControllableLichessClient(validateResult = Right(true))
    LichessBridgeWorker
      .resource(enabledConfig, client, StubLichessEventStream(), AcceptAllChallengePolicy(), stateRef)
      .use(join => join)
      .unsafeRunSync()

    val state = stateRef.get.unsafeRunSync()
    state.toString should not include "lip_test"
    state.lastError.foreach(_ should not include "lip_test")
  }
