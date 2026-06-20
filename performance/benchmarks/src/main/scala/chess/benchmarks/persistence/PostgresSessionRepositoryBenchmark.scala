package chess.benchmarks.persistence

import chess.adapter.repository.postgres.{PostgresGameRepository, PostgresSessionRepository}
import chess.application.port.repository.{GameRepository, SessionRepository}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.application.session.model.*
import chess.benchmarks.BenchmarkFixtures
import chess.domain.state.GameState
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.time.Instant
import java.util.concurrent.TimeUnit
import scala.compiletime.uninitialized
import scala.concurrent.duration.DurationInt

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
class PostgresSessionRepositoryBenchmark:

  @Benchmark
  def saveSession(state: SessionRepositoryState, blackhole: Blackhole): Unit =
    blackhole.consume(state.sessionRepository.save(state.saveSessionFixture))

  @Benchmark
  def loadSession(state: SessionRepositoryState, blackhole: Blackhole): Unit =
    blackhole.consume(state.sessionRepository.load(state.loadSessionId))

  @Benchmark
  def loadSessionWithGame(state: SessionRepositoryState, blackhole: Blackhole): Unit =
    val sessionResult = state.sessionRepository.load(state.linkedSessionId)
    val gameResult = sessionResult.flatMap(session => state.gameRepository.load(session.gameId))
    blackhole.consume((sessionResult, gameResult))

@State(Scope.Benchmark)
class SessionRepositoryState:
  private var postgres: PostgresBenchmarkDatabase = uninitialized

  var sessionRepository: SessionRepository = uninitialized
  var gameRepository: GameRepository = uninitialized
  var saveSessionFixture: GameSession = uninitialized
  var loadSessionId: SessionId = uninitialized
  var linkedSessionId: SessionId = uninitialized
  var linkedGameState: GameState = uninitialized

  private val fixedNow: Instant = Instant.parse("2026-05-06T00:00:00Z")

  @Setup(Level.Trial)
  def setup(): Unit =
    postgres = PostgresBenchmarkDatabase()
    val database = postgres.start()
    sessionRepository = PostgresSessionRepository(database, 10.seconds)
    gameRepository = PostgresGameRepository(database, 10.seconds)
    linkedGameState = BenchmarkFixtures.midgameState

    val loadSession = newSession(GameId.random())
    val linkedSession = newSession(GameId.random())
    loadSessionId = loadSession.sessionId
    linkedSessionId = linkedSession.sessionId

    requireRight(sessionRepository.save(loadSession))
    requireRight(sessionRepository.save(linkedSession))
    requireRight(gameRepository.save(linkedSession.gameId, linkedGameState))

  @Setup(Level.Invocation)
  def setupInvocation(): Unit =
    saveSessionFixture = newSession(GameId.random())

  @TearDown(Level.Trial)
  def tearDown(): Unit =
    postgres.stop()

  private def newSession(gameId: GameId): GameSession =
    GameSession(
      sessionId = SessionId.random(),
      gameId = gameId,
      mode = SessionMode.HumanVsHuman,
      whiteController = SideController.HumanLocal,
      blackController = SideController.HumanLocal,
      lifecycle = SessionLifecycle.Created,
      createdAt = fixedNow,
      updatedAt = fixedNow
    )

  private def requireRight[A](value: Either[?, A]): A =
    value.fold(error => throw IllegalStateException(error.toString), identity)
