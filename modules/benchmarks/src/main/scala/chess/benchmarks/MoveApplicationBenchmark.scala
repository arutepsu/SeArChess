package chess.benchmarks

import chess.domain.model.{Move, PieceType}
import chess.domain.rules.GameStateRules
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
@State(Scope.Thread)
class MoveApplicationBenchmark:

  private var openingState: GameState = uninitialized
  private var captureState: GameState = uninitialized
  private var promotionState: GameState = uninitialized
  private var openingMove: Move = uninitialized
  private var captureMove: Move = uninitialized
  private var promotionMove: Move = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    openingState = BenchmarkFixtures.initialState
    captureState = BenchmarkFixtures.captureReadyState
    promotionState = BenchmarkFixtures.promotionReadyState
    openingMove = BenchmarkFixtures.move("e2", "e4")
    captureMove = BenchmarkFixtures.move("e4", "d5")
    promotionMove = BenchmarkFixtures.move("a7", "a8", Some(PieceType.Queen))

  @Benchmark
  def applyMove_openingMove(blackhole: Blackhole): Unit =
    blackhole.consume(GameStateRules.applyMove(openingState, openingMove))

  @Benchmark
  def applyMove_capture(blackhole: Blackhole): Unit =
    blackhole.consume(GameStateRules.applyMove(captureState, captureMove))

  @Benchmark
  def applyMove_promotion(blackhole: Blackhole): Unit =
    blackhole.consume(GameStateRules.applyMove(promotionState, promotionMove))
