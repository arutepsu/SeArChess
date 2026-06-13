package chess.arena.core

import chess.arena.events.{BotFamily, EventEmitter, GameEvent, GameFinished, GameStarted, MovePlayed}
import chess.domain.model.{Color, GameStatus, Move, Position}
import chess.domain.rules.GameStateRules
import chess.domain.state.GameState
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class GameRunnerSpec extends AnyFlatSpec with Matchers:

  // ── Test doubles ──────────────────────────────────────────────────────────────

  private class CollectingEmitter extends EventEmitter:
    private val buffer = scala.collection.mutable.ArrayBuffer[GameEvent]()
    def emit(event: GameEvent): Unit = { buffer += event; () }
    def events: List[GameEvent] = buffer.toList

  private def testProfile(id: String): BotProfile =
    BotProfile(id, BotFamily.Heuristic, "test", "none", "none")

  // Always picks the lexicographically first legal move — deterministic.
  private class DeterministicBot(val profile: BotProfile) extends BotPlayer:
    def selectMove(state: GameState): Move =
      GameStateRules.legalMoves(state).minBy(m => s"${m.from}${m.to}")

  // Always plays from an empty square — guaranteed illegal from initial position.
  private class ForfeitBot(val profile: BotProfile) extends BotPlayer:
    def selectMove(state: GameState): Move =
      Move(safePos("a3"), safePos("a4"))

  private def safePos(s: String): Position =
    Position.fromAlgebraic(s) match
      case Right(p) => p
      case Left(_)  => fail(s"Invalid test position: $s")

  // ── Helpers ───────────────────────────────────────────────────────────────────

  private def runner(
      white: BotPlayer,
      black: BotPlayer,
      maxPly: Int = 6
  ): (GameRunner, CollectingEmitter) =
    val emitter = new CollectingEmitter
    (GameRunner(white, black, emitter, "t-test", maxPly), emitter)

  // ── Tests ─────────────────────────────────────────────────────────────────────

  "GameRunner.run" should "emit GameStarted as the first event" in {
    val (r, emitter) = runner(new DeterministicBot(testProfile("w")), new DeterministicBot(testProfile("b")))
    r.run("g1")
    emitter.events.head shouldBe a[GameStarted]
  }

  it should "emit GameFinished as the last event" in {
    val (r, emitter) = runner(new DeterministicBot(testProfile("w")), new DeterministicBot(testProfile("b")))
    r.run("g1")
    emitter.events.last shouldBe a[GameFinished]
  }

  it should "emit only MovePlayed events between GameStarted and GameFinished" in {
    val (r, emitter) = runner(new DeterministicBot(testProfile("w")), new DeterministicBot(testProfile("b")))
    r.run("g1")
    val middle = emitter.events.drop(1).dropRight(1)
    all(middle) shouldBe a[MovePlayed]
  }

  it should "emit exactly maxPly MovePlayed events when game does not end naturally" in {
    val (r, emitter) = runner(new DeterministicBot(testProfile("w")), new DeterministicBot(testProfile("b")), maxPly = 4)
    r.run("g1")
    val movePlayed = emitter.events.collect { case mp: MovePlayed => mp }
    movePlayed.length shouldBe 4
    emitter.events.last match
      case gf: GameFinished => gf.terminationReason shouldBe "max-ply"
      case other            => fail(s"Expected GameFinished, got $other")
  }

  it should "number MovePlayed events starting at 1 and incrementing" in {
    val (r, emitter) = runner(new DeterministicBot(testProfile("w")), new DeterministicBot(testProfile("b")), maxPly = 4)
    r.run("g1")
    val plyNumbers = emitter.events.collect { case mp: MovePlayed => mp.plyNumber }
    plyNumbers shouldBe List(1, 2, 3, 4)
  }

  it should "record the correct whiteBotId and blackBotId in GameStarted" in {
    val (r, emitter) = runner(new DeterministicBot(testProfile("white-bot")), new DeterministicBot(testProfile("black-bot")))
    r.run("g1")
    emitter.events.head match
      case gs: GameStarted =>
        gs.whiteBotId shouldBe "white-bot"
        gs.blackBotId shouldBe "black-bot"
      case other => fail(s"Expected GameStarted, got $other")
  }

  it should "set totalPly in GameFinished equal to the number of MovePlayed events" in {
    val (r, emitter) = runner(new DeterministicBot(testProfile("w")), new DeterministicBot(testProfile("b")), maxPly = 4)
    r.run("g1")
    val movesPlayed = emitter.events.collect { case _: MovePlayed => 1 }.sum
    emitter.events.last match
      case gf: GameFinished => gf.totalPly shouldBe movesPlayed
      case other            => fail(s"Expected GameFinished, got $other")
  }

  it should "emit only GameStarted and GameFinished when the first bot plays an illegal move" in {
    val (r, emitter) = runner(new ForfeitBot(testProfile("forfeit")), new DeterministicBot(testProfile("b")))
    r.run("g1")
    emitter.events.length shouldBe 2
  }

  it should "set terminationReason=illegal-move and result=black when White forfeits" in {
    val (r, emitter) = runner(new ForfeitBot(testProfile("forfeit-white")), new DeterministicBot(testProfile("black-bot")))
    r.run("g1")
    emitter.events.last match
      case gf: GameFinished =>
        gf.terminationReason shouldBe "illegal-move"
        gf.result            shouldBe "black"
        gf.winnerBotId       shouldBe Some("black-bot")
        gf.loserBotId        shouldBe Some("forfeit-white")
      case other => fail(s"Expected GameFinished, got $other")
  }

  it should "record the correct gameId in all events" in {
    val (r, emitter) = runner(new DeterministicBot(testProfile("w")), new DeterministicBot(testProfile("b")), maxPly = 2)
    r.run("my-game-id")
    emitter.events.foreach { event =>
      event.gameId shouldBe "my-game-id"
    }
  }
