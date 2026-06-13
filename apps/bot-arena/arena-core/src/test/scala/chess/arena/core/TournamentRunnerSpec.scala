package chess.arena.core

import chess.arena.events.{BotFamily, EventEmitter, GameEvent, GameFinished, GameStarted, MovePlayed}
import chess.domain.model.Move
import chess.domain.rules.GameStateRules
import chess.domain.state.GameState
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TournamentRunnerSpec extends AnyFlatSpec with Matchers:

  // ── Test doubles ──────────────────────────────────────────────────────────────

  private class CollectingEmitter extends EventEmitter:
    private val buffer = scala.collection.mutable.ArrayBuffer[GameEvent]()
    def emit(event: GameEvent): Unit = { buffer += event; () }
    def events: List[GameEvent] = buffer.toList

  private def mkBot(id: String): BotPlayer = new BotPlayer:
    val profile: BotProfile = BotProfile(id, BotFamily.Heuristic, "test", "none", "none")
    def selectMove(state: GameState): Move =
      GameStateRules.legalMoves(state).minBy(m => s"${m.from}${m.to}")

  private def mkTournament(tid: String, bots: List[BotPlayer]): Tournament =
    new Tournament:
      val tournamentId: String          = tid
      val participants: List[BotPlayer] = bots
      val matchups: List[Matchup]       = RoundRobinScheduler.schedule(tid, bots)

  private def countStarts(events: List[GameEvent]): Int    = events.collect { case _: GameStarted  => 1 }.sum
  private def countFinishes(events: List[GameEvent]): Int  = events.collect { case _: GameFinished => 1 }.sum

  // ── Tests ─────────────────────────────────────────────────────────────────────

  "TournamentRunner.run" should "emit one GameStarted per matchup" in {
    val emitter    = new CollectingEmitter
    val tournament = mkTournament("t1", List(mkBot("A"), mkBot("B"), mkBot("C")))
    TournamentRunner(emitter, maxPlyPerGame = 2).run(tournament)
    countStarts(emitter.events) shouldBe 6
  }

  it should "emit one GameFinished per matchup" in {
    val emitter    = new CollectingEmitter
    val tournament = mkTournament("t1", List(mkBot("A"), mkBot("B"), mkBot("C")))
    TournamentRunner(emitter, maxPlyPerGame = 2).run(tournament)
    countFinishes(emitter.events) shouldBe 6
  }

  it should "emit events for all matchups in order (all game-1 events before game-2 events)" in {
    val emitter    = new CollectingEmitter
    val tournament = mkTournament("order-test", List(mkBot("X"), mkBot("Y")))
    TournamentRunner(emitter, maxPlyPerGame = 2).run(tournament)
    val starts = emitter.events.collect { case gs: GameStarted => gs }
    starts.map(_.gameId) shouldBe List("order-test-game-1", "order-test-game-2")
  }

  it should "propagate the same tournamentId to every event" in {
    val emitter    = new CollectingEmitter
    val tournament = mkTournament("fixed-id", List(mkBot("P"), mkBot("Q")))
    TournamentRunner(emitter, maxPlyPerGame = 2).run(tournament)
    emitter.events.foreach { event =>
      event.tournamentId shouldBe "fixed-id"
    }
  }

  it should "assign distinct gameIds to each game" in {
    val emitter    = new CollectingEmitter
    val tournament = mkTournament("t2", List(mkBot("A"), mkBot("B"), mkBot("C")))
    TournamentRunner(emitter, maxPlyPerGame = 2).run(tournament)
    val gameIds = emitter.events.collect { case gs: GameStarted => gs.gameId }
    gameIds.distinct.length shouldBe 6
  }

  it should "emit MovePlayed events between each game's GameStarted and GameFinished" in {
    val emitter    = new CollectingEmitter
    val tournament = mkTournament("t3", List(mkBot("A"), mkBot("B")))
    TournamentRunner(emitter, maxPlyPerGame = 4).run(tournament)
    val events      = emitter.events
    val gameIds     = events.collect { case gs: GameStarted => gs.gameId }
    gameIds.foreach { gid =>
      val gameEvents = events.filter(_.gameId == gid)
      gameEvents.head shouldBe a[GameStarted]
      gameEvents.last shouldBe a[GameFinished]
      gameEvents.drop(1).dropRight(1).foreach { e => e shouldBe a[MovePlayed] }
    }
  }

  it should "run doubled matchups when repetitions=2" in {
    val emitter = new CollectingEmitter
    val bots    = List(mkBot("A"), mkBot("B"))
    val tournament = new Tournament:
      val tournamentId: String          = "rep2"
      val participants: List[BotPlayer] = bots
      val matchups: List[Matchup]       = RoundRobinScheduler.schedule(tournamentId, participants, repetitions = 2)
    TournamentRunner(emitter, maxPlyPerGame = 2).run(tournament)
    countStarts(emitter.events) shouldBe 4 // 2*(2-1)*2 = 4
  }
