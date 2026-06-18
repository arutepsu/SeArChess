package chess.benchmarks

import chess.application.session.model.SideController
import chess.benchmarks.BenchmarkFixtures.ServiceFixture
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
class GameServiceBenchmark:

  private var legalMovesFixture: ServiceFixture = uninitialized
  private var submitMoveFixture: ServiceFixture = uninitialized

  @Setup(Level.Trial)
  def setupLegalMovesFixture(): Unit =
    legalMovesFixture = BenchmarkFixtures.freshServiceFixture()

  @Setup(Level.Invocation)
  def setupSubmitMoveFixture(): Unit =
    // submitMove persists a new game state, so each measured invocation receives
    // a fresh in-memory service fixture with a valid first move.
    submitMoveFixture = BenchmarkFixtures.freshServiceFixture()

  @Benchmark
  def gameService_getLegalMoves_inMemory(blackhole: Blackhole): Unit =
    blackhole.consume(legalMovesFixture.service.getLegalMoves(legalMovesFixture.gameId))

  @Benchmark
  def gameService_submitMove_inMemory(blackhole: Blackhole): Unit =
    blackhole.consume(
      submitMoveFixture.service.submitMove(
        submitMoveFixture.gameId,
        submitMoveFixture.openingMove,
        SideController.HumanLocal
      )
    )
