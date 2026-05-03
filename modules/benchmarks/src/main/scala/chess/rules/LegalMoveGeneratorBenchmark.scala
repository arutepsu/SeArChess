package chess.rules

import org.openjdk.jmh.annotations._
import java.util.concurrent.TimeUnit
import chess.domain.model.Move
import chess.domain.rules.LegalMoveGenerator
import chess.domain.state.{GameState, GameStateFactory}
import chess.domain.model.Position
import scala.compiletime.uninitialized

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@SuppressWarnings(Array("org.wartremover.warts.All"))
class LegalMoveGeneratorBenchmark {

  var initialState: GameState = uninitialized
  var midgameState: GameState = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit = {
    // Initial board position
    initialState = GameStateFactory.initial()

    // A more complex midgame state could be set up here.
    // For simplicity we will benchmark on the initial state, testing pawn and knight moves.
    midgameState = GameStateFactory.initial()
  }

  @Benchmark
  def benchmarkLegalMovesFromE2Pawn(): Set[Move] = {
    // e2 pawn in initial position
    LegalMoveGenerator.legalMovesFrom(initialState, Position.from(4, 1).toOption.get)
  }

  @Benchmark
  def benchmarkLegalMovesFromG1Knight(): Set[Move] = {
    // g1 knight in initial position
    LegalMoveGenerator.legalMovesFrom(initialState, Position.from(6, 0).toOption.get)
  }

  @Benchmark
  def benchmarkLegalTargetsFromE2Pawn(): Set[Position] = {
    LegalMoveGenerator.legalTargetsFrom(initialState, Position.from(4, 1).toOption.get)
  }
}
