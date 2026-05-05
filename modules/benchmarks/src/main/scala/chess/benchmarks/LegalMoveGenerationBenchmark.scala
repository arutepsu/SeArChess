package chess.benchmarks

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
class LegalMoveGenerationBenchmark:

  private var initial: GameState = uninitialized
  private var midgame: GameState = uninitialized
  private var checkPressure: GameState = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    initial = BenchmarkFixtures.initialState
    midgame = BenchmarkFixtures.midgameState
    checkPressure = BenchmarkFixtures.checkPressureState

  @Benchmark
  def legalMoves_initialPosition(blackhole: Blackhole): Unit =
    blackhole.consume(GameStateRules.legalMoves(initial))

  @Benchmark
  def legalMoves_midgamePosition(blackhole: Blackhole): Unit =
    blackhole.consume(GameStateRules.legalMoves(midgame))

  @Benchmark
  def legalMoves_checkPressurePosition(blackhole: Blackhole): Unit =
    blackhole.consume(GameStateRules.legalMoves(checkPressure))
