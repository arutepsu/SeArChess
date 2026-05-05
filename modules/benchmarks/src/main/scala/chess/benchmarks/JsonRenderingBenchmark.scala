package chess.benchmarks

import chess.adapter.http4s.mapper.{GameMapper, SessionMapper}
import chess.adapter.rest.contract.dto.{GameResponse, LegalMovesResponse, SessionStateResponse}
import chess.application.query.game.{GameView, LegalMovesView}
import chess.application.session.service.PersistentSessionAggregate
import chess.domain.rules.GameStateRules
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
class JsonRenderingBenchmark:

  private var initialGameResponse: chess.adapter.rest.contract.dto.GameSnapshot = uninitialized
  private var midgameGameResponse: chess.adapter.rest.contract.dto.GameSnapshot = uninitialized
  private var legalMovesResponse: LegalMovesResponse = uninitialized
  private var sessionStateResponse: SessionStateResponse = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    val serviceFixture = BenchmarkFixtures.freshServiceFixture()
    val initialState = BenchmarkFixtures.initialState
    val midgameState = BenchmarkFixtures.midgameState
    val initialView = GameView.fromState(serviceFixture.gameId, initialState)
    val midgameView = GameView.fromState(serviceFixture.gameId, midgameState)
    val legalMovesView = LegalMovesView(
      gameId = serviceFixture.gameId,
      currentPlayer = initialState.currentPlayer,
      moves = GameStateRules.legalMoves(initialState)
    )

    initialGameResponse = GameMapper.toGameResponse(initialView)
    midgameGameResponse = GameMapper.toGameResponse(midgameView)
    legalMovesResponse = GameMapper.toLegalMovesResponse(legalMovesView)
    sessionStateResponse = SessionMapper.toSessionStateResponse(
      PersistentSessionAggregate(serviceFixture.session, midgameState)
    )

  @Benchmark
  def renderGameResponseJson_initialPosition(blackhole: Blackhole): Unit =
    blackhole.consume(ujson.write(GameResponse.toJson(initialGameResponse)))

  @Benchmark
  def renderGameResponseJson_midgamePosition(blackhole: Blackhole): Unit =
    blackhole.consume(ujson.write(GameResponse.toJson(midgameGameResponse)))

  @Benchmark
  def renderLegalMovesResponseJson(blackhole: Blackhole): Unit =
    blackhole.consume(ujson.write(LegalMovesResponse.toJson(legalMovesResponse)))

  @Benchmark
  def renderSessionStateResponseJson(blackhole: Blackhole): Unit =
    blackhole.consume(ujson.write(SessionStateResponse.toJson(sessionStateResponse)))
