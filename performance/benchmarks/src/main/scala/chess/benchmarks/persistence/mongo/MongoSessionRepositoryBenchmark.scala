package chess.benchmarks.persistence.mongo

import chess.adapter.repository.mongo.{MongoGameRepository, MongoSessionRepository}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.application.session.model.*
import chess.benchmarks.BenchmarkFixtures
import chess.domain.state.GameState
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.time.Instant
import java.util.concurrent.TimeUnit
import scala.compiletime.uninitialized

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
class MongoSessionRepositoryBenchmark:

  @Benchmark
  def saveSession(state: MongoSessionRepositoryState, blackhole: Blackhole): Unit =
    blackhole.consume(state.sessionRepository.save(state.saveSessionFixture))

  @Benchmark
  def loadSession(state: MongoSessionRepositoryState, blackhole: Blackhole): Unit =
    blackhole.consume(state.sessionRepository.load(state.loadSessionId))

  @Benchmark
  def loadSessionWithGame(state: MongoSessionRepositoryState, blackhole: Blackhole): Unit =
    val sessionResult = state.sessionRepository.load(state.linkedSessionId)
    val gameResult = sessionResult.flatMap(session => state.gameRepository.load(session.gameId))
    blackhole.consume((sessionResult, gameResult))

@State(Scope.Benchmark)
class MongoSessionRepositoryState:
  private var mongo: MongoBenchmarkDatabase = uninitialized

  var sessionRepository: MongoSessionRepository = uninitialized
  var gameRepository: MongoGameRepository = uninitialized
  var saveSessionFixture: GameSession = uninitialized
  var loadSessionId: SessionId = uninitialized
  var linkedSessionId: SessionId = uninitialized
  var linkedGameState: GameState = uninitialized

  private val fixedNow: Instant = Instant.parse("2026-05-06T00:00:00Z")

  @Setup(Level.Trial)
  def setup(): Unit =
    mongo = MongoBenchmarkDatabase()
    val repositories = mongo.start()
    sessionRepository = repositories.sessionRepository
    gameRepository = repositories.gameRepository
    linkedGameState = BenchmarkFixtures.midgameState

    saveSessionFixture = newSession(GameId.random())
    val loadSession = newSession(GameId.random())
    val linkedSession = newSession(GameId.random())
    loadSessionId = loadSession.sessionId
    linkedSessionId = linkedSession.sessionId

    requireRight(sessionRepository.save(saveSessionFixture))
    requireRight(sessionRepository.save(loadSession))
    requireRight(sessionRepository.save(linkedSession))
    requireRight(gameRepository.save(linkedSession.gameId, linkedGameState))

  @TearDown(Level.Trial)
  def tearDown(): Unit =
    mongo.stop()

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
