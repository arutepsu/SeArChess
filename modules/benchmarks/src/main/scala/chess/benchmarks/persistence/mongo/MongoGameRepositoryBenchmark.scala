package chess.benchmarks.persistence.mongo

import chess.adapter.repository.mongo.MongoGameRepository
import chess.application.session.model.SessionIds.GameId
import chess.benchmarks.BenchmarkFixtures
import chess.domain.state.GameState
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit
import scala.compiletime.uninitialized

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
class MongoGameRepositoryBenchmark:

  @Benchmark
  def saveGame(state: MongoGameRepositoryState, blackhole: Blackhole): Unit =
    blackhole.consume(state.gameRepository.save(state.saveGameId, state.midgameState))

  @Benchmark
  def loadGame(state: MongoGameRepositoryState, blackhole: Blackhole): Unit =
    blackhole.consume(state.gameRepository.load(state.loadGameId))

  @Benchmark
  def updateGameState(state: MongoGameRepositoryState, blackhole: Blackhole): Unit =
    blackhole.consume(state.gameRepository.save(state.updateGameId, state.checkPressureState))

  @Benchmark
  def saveAndLoadRoundTrip(state: MongoGameRepositoryState, blackhole: Blackhole): Unit =
    val saveResult = state.gameRepository.save(state.roundTripGameId, state.captureReadyState)
    val loadResult = state.gameRepository.load(state.roundTripGameId)
    blackhole.consume((saveResult, loadResult))

@State(Scope.Benchmark)
class MongoGameRepositoryState:
  private var mongo: MongoBenchmarkDatabase = uninitialized

  var gameRepository: MongoGameRepository = uninitialized
  var saveGameId: GameId = uninitialized
  var loadGameId: GameId = uninitialized
  var updateGameId: GameId = uninitialized
  var roundTripGameId: GameId = uninitialized
  var midgameState: GameState = uninitialized
  var checkPressureState: GameState = uninitialized
  var captureReadyState: GameState = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    mongo = MongoBenchmarkDatabase()
    val repositories = mongo.start()
    gameRepository = repositories.gameRepository
    midgameState = BenchmarkFixtures.midgameState
    checkPressureState = BenchmarkFixtures.checkPressureState
    captureReadyState = BenchmarkFixtures.captureReadyState
    saveGameId = GameId.random()
    loadGameId = GameId.random()
    updateGameId = GameId.random()
    roundTripGameId = GameId.random()
    requireRight(gameRepository.save(saveGameId, midgameState))
    requireRight(gameRepository.save(loadGameId, midgameState))
    requireRight(gameRepository.save(updateGameId, midgameState))
    requireRight(gameRepository.save(roundTripGameId, captureReadyState))

  @TearDown(Level.Trial)
  def tearDown(): Unit =
    mongo.stop()

  private def requireRight[A](value: Either[?, A]): A =
    value.fold(error => throw IllegalStateException(error.toString), identity)
