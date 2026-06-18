package chess.benchmarks

import chess.adapter.http4s.mapper.{GameMapper, SessionMapper}
import chess.application.query.game.{GameView, LegalMovesView}
import chess.application.session.service.PersistentSessionAggregate
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
class MappingBenchmark:

  private var initialState: GameState = uninitialized
  private var midgameState: GameState = uninitialized
  private var initialView: GameView = uninitialized
  private var midgameView: GameView = uninitialized
  private var legalMovesView: LegalMovesView = uninitialized
  private var sessionAggregate: PersistentSessionAggregate = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    val serviceFixture = BenchmarkFixtures.freshServiceFixture()
    initialState = BenchmarkFixtures.initialState
    midgameState = BenchmarkFixtures.midgameState
    initialView = GameView.fromState(serviceFixture.gameId, initialState)
    midgameView = GameView.fromState(serviceFixture.gameId, midgameState)
    legalMovesView = LegalMovesView(
      gameId = serviceFixture.gameId,
      currentPlayer = initialState.currentPlayer,
      moves = GameStateRules.legalMoves(initialState)
    )
    sessionAggregate = PersistentSessionAggregate(serviceFixture.session, midgameState)

  @Benchmark
  def mapGameState_initialPosition(blackhole: Blackhole): Unit =
    blackhole.consume(GameMapper.toGameResponse(initialView))

  @Benchmark
  def mapGameState_midgamePosition(blackhole: Blackhole): Unit =
    blackhole.consume(GameMapper.toGameResponse(midgameView))

  @Benchmark
  def mapLegalMoves_initialPosition(blackhole: Blackhole): Unit =
    blackhole.consume(GameMapper.toLegalMovesResponse(legalMovesView))

  @Benchmark
  def mapSessionState(blackhole: Blackhole): Unit =
    blackhole.consume(SessionMapper.toSessionStateResponse(sessionAggregate))
