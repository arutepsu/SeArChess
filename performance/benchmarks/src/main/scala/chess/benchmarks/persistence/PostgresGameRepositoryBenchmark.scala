package chess.benchmarks.persistence

import chess.adapter.repository.postgres.PostgresGameRepository
import chess.application.port.repository.GameRepository
import chess.application.session.model.SessionIds.GameId
import chess.benchmarks.BenchmarkFixtures
import chess.domain.state.GameState
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit
import scala.compiletime.uninitialized
import scala.concurrent.duration.DurationInt

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
class PostgresGameRepositoryBenchmark:

  @Benchmark
  def saveGame(state: GameRepositoryState, blackhole: Blackhole): Unit =
    blackhole.consume(state.gameRepository.save(state.saveGameId, state.midgameState))

  @Benchmark
  def loadGame(state: GameRepositoryState, blackhole: Blackhole): Unit =
    blackhole.consume(state.gameRepository.load(state.loadGameId))

  @Benchmark
  def updateGameState(state: GameRepositoryState, blackhole: Blackhole): Unit =
    blackhole.consume(state.gameRepository.save(state.updateGameId, state.checkPressureState))

  @Benchmark
  def saveAndLoadRoundTrip(state: GameRepositoryState, blackhole: Blackhole): Unit =
    val saveResult = state.gameRepository.save(state.roundTripGameId, state.captureReadyState)
    val loadResult = state.gameRepository.load(state.roundTripGameId)
    blackhole.consume((saveResult, loadResult))

@State(Scope.Benchmark)
class GameRepositoryState:
  private var postgres: PostgresBenchmarkDatabase = uninitialized

  var gameRepository: GameRepository = uninitialized
  var loadGameId: GameId = uninitialized
  var updateGameId: GameId = uninitialized
  var saveGameId: GameId = uninitialized
  var roundTripGameId: GameId = uninitialized
  var midgameState: GameState = uninitialized
  var checkPressureState: GameState = uninitialized
  var captureReadyState: GameState = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    postgres = PostgresBenchmarkDatabase()
    val database = postgres.start()
    gameRepository = PostgresGameRepository(database, 10.seconds)
    midgameState = BenchmarkFixtures.midgameState
    checkPressureState = BenchmarkFixtures.checkPressureState
    captureReadyState = BenchmarkFixtures.captureReadyState
    loadGameId = GameId.random()
    updateGameId = GameId.random()
    requireRight(gameRepository.save(loadGameId, midgameState))
    requireRight(gameRepository.save(updateGameId, midgameState))

  @Setup(Level.Invocation)
  def setupInvocation(): Unit =
    saveGameId = GameId.random()
    roundTripGameId = GameId.random()

  @TearDown(Level.Trial)
  def tearDown(): Unit =
    postgres.stop()

  private def requireRight[A](value: Either[?, A]): A =
    value.fold(error => throw IllegalStateException(error.toString), identity)
