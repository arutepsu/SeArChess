package chess.adapter.repository.sqlite

import chess.application.ChessService
import chess.application.port.repository.RepositoryError
import chess.application.session.model.{GameSession, SessionLifecycle, SessionMode, SideController}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.domain.model.{Color, Move, Position}
import chess.domain.state.GameState
import org.scalatest.EitherValues
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files
import java.time.Instant
import java.util.UUID

/** Tests for SQLite-backed repositories.
 *
 *  Each test uses a private temp file so tests are fully isolated.
 *  Restart survival tests open two separate [[SqliteDataSource]] instances
 *  against the same file path to simulate a JVM restart.
 */
class SqliteRepositoriesSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues:

  // ── Helpers ───────────────────────────────────────────────────────────────

  private def tempPath(): String =
    Files.createTempFile("chess-test-", ".db").toAbsolutePath.toString

  private def freshDs(path: String): SqliteDataSource =
    val ds = SqliteDataSource(path)
    ds.withConnection(SqliteSchema.createTables)
    ds

  private def freshState: GameState = ChessService.createNewGame()

  private def freshSession(gameId: GameId): GameSession =
    GameSession.create(gameId, SessionMode.HumanVsHuman,
      SideController.HumanLocal, SideController.HumanLocal,
      now = Instant.parse("2024-01-01T00:00:00Z"))

  private def pos(algebraic: String): Position =
    Position.fromAlgebraic(algebraic).value

  // ── GameStateJson round-trip ──────────────────────────────────────────────

  "GameStateJson" should "round-trip the initial game state" in {
    val state  = freshState
    val json   = GameStateJson.encode(state)
    val result = GameStateJson.decode(json).value
    result shouldBe state
  }

  it should "round-trip a state with a move history entry" in {
    val state  = freshState.copy(moveHistory = List(Move(pos("e2"), pos("e4"))))
    val result = GameStateJson.decode(GameStateJson.encode(state)).value
    result.moveHistory shouldBe state.moveHistory
  }

  it should "round-trip a state with a promotion in move history" in {
    import chess.domain.model.PieceType
    val move   = Move(pos("e7"), pos("e8"), Some(PieceType.Queen))
    val state  = freshState.copy(moveHistory = List(move))
    val result = GameStateJson.decode(GameStateJson.encode(state)).value
    result.moveHistory.head.promotion.value shouldBe PieceType.Queen
  }

  it should "return StorageFailure for corrupt JSON" in {
    GameStateJson.decode("{not valid json}").left.value shouldBe a [RepositoryError.StorageFailure]
  }

  // ── SqliteGameRepository ──────────────────────────────────────────────────

  "SqliteGameRepository.load" should "return NotFound for unknown GameId" in {
    val ds   = freshDs(tempPath())
    val repo = SqliteGameRepository(ds)
    val id   = GameId.random()
    repo.load(id).left.value shouldBe RepositoryError.NotFound(id.value.toString)
    ds.close()
  }

  "SqliteGameRepository.save / load" should "round-trip a GameState" in {
    val ds   = freshDs(tempPath())
    val repo = SqliteGameRepository(ds)
    val id   = GameId.random()
    val st   = freshState
    repo.save(id, st).value
    repo.load(id).value shouldBe st
    ds.close()
  }

  it should "overwrite existing state on repeated saves" in {
    val ds   = freshDs(tempPath())
    val repo = SqliteGameRepository(ds)
    val id   = GameId.random()
    repo.save(id, freshState).value
    val updated = freshState.copy(fullmoveNumber = 7)
    repo.save(id, updated).value
    repo.load(id).value.fullmoveNumber shouldBe 7
    ds.close()
  }

  it should "survive a simulated restart (re-instantiation)" in {
    val path  = tempPath()
    val id    = GameId.random()
    val state = freshState.copy(fullmoveNumber = 3)

    val ds1 = freshDs(path)
    SqliteGameRepository(ds1).save(id, state).value
    ds1.close()

    val ds2   = freshDs(path)
    val loaded = SqliteGameRepository(ds2).load(id).value
    ds2.close()

    loaded shouldBe state
  }

  // ── SqliteSessionRepository ───────────────────────────────────────────────

  "SqliteSessionRepository.load" should "return NotFound for unknown SessionId" in {
    val ds   = freshDs(tempPath())
    val repo = SqliteSessionRepository(ds)
    val id   = SessionId.random()
    repo.load(id).left.value shouldBe RepositoryError.NotFound(id.value.toString)
    ds.close()
  }

  "SqliteSessionRepository.save / load" should "round-trip a GameSession" in {
    val ds      = freshDs(tempPath())
    val repo    = SqliteSessionRepository(ds)
    val session = freshSession(GameId.random())
    repo.save(session).value
    repo.load(session.sessionId).value shouldBe session
    ds.close()
  }

  it should "update an existing session on repeated saves" in {
    val ds      = freshDs(tempPath())
    val repo    = SqliteSessionRepository(ds)
    val session = freshSession(GameId.random())
    repo.save(session).value
    val updated = session.copy(lifecycle = SessionLifecycle.Active)
    repo.save(updated).value
    repo.load(session.sessionId).value.lifecycle shouldBe SessionLifecycle.Active
    ds.close()
  }

  it should "survive a simulated restart (re-instantiation)" in {
    val path    = tempPath()
    val session = freshSession(GameId.random())

    val ds1 = freshDs(path)
    SqliteSessionRepository(ds1).save(session).value
    ds1.close()

    val ds2    = freshDs(path)
    val loaded = SqliteSessionRepository(ds2).load(session.sessionId).value
    ds2.close()

    loaded shouldBe session
  }

  "SqliteSessionRepository.loadByGameId" should "find a session by its GameId" in {
    val ds      = freshDs(tempPath())
    val repo    = SqliteSessionRepository(ds)
    val session = freshSession(GameId.random())
    repo.save(session).value
    repo.loadByGameId(session.gameId).value shouldBe session
    ds.close()
  }

  it should "return NotFound for an unregistered GameId" in {
    val ds   = freshDs(tempPath())
    val repo = SqliteSessionRepository(ds)
    val gid  = GameId.random()
    repo.loadByGameId(gid).left.value shouldBe RepositoryError.NotFound(gid.value.toString)
    ds.close()
  }

  "SqliteSessionRepository.listActive" should "return only non-Finished sessions" in {
    val ds      = freshDs(tempPath())
    val repo    = SqliteSessionRepository(ds)
    val active  = freshSession(GameId.random())
    val finished = active.copy(
      sessionId = SessionId.random(), gameId = GameId.random(),
      lifecycle = SessionLifecycle.Finished
    )
    repo.save(active).value
    repo.save(finished).value
    val list = repo.listActive().value
    list.map(_.sessionId) should contain (active.sessionId)
    list.map(_.sessionId) should not contain (finished.sessionId)
    ds.close()
  }

  it should "return an empty list when no active sessions exist" in {
    val ds   = freshDs(tempPath())
    val repo = SqliteSessionRepository(ds)
    repo.listActive().value shouldBe empty
    ds.close()
  }

  "SqliteSessionRepository" should "preserve AI controller with engine id" in {
    val ds      = freshDs(tempPath())
    val repo    = SqliteSessionRepository(ds)
    val session = freshSession(GameId.random()).copy(
      whiteController = SideController.AI(Some("stockfish-15")),
      blackController = SideController.AI(None)
    )
    repo.save(session).value
    val loaded = repo.load(session.sessionId).value
    loaded.whiteController shouldBe SideController.AI(Some("stockfish-15"))
    loaded.blackController shouldBe SideController.AI(None)
    ds.close()
  }

  // ── SqliteSessionGameStore — combined write ────────────────────────────────

  "SqliteSessionGameStore.save" should "persist both session and game state atomically" in {
    val ds      = freshDs(tempPath())
    val sessRepo = SqliteSessionRepository(ds)
    val gameRepo = SqliteGameRepository(ds)
    val store    = SqliteSessionGameStore(ds, sessRepo, gameRepo)

    val session  = freshSession(GameId.random())
    val state    = freshState
    store.save(session, state).value

    sessRepo.load(session.sessionId).value shouldBe session
    gameRepo.load(session.gameId).value    shouldBe state
    ds.close()
  }

  it should "survive a simulated restart — both records readable after re-instantiation" in {
    val path    = tempPath()
    val session = freshSession(GameId.random())
    val state   = freshState.copy(fullmoveNumber = 4)

    val ds1 = freshDs(path)
    SqliteSessionGameStore(ds1, SqliteSessionRepository(ds1), SqliteGameRepository(ds1))
      .save(session, state).value
    ds1.close()

    val ds2      = freshDs(path)
    val sessRepo2 = SqliteSessionRepository(ds2)
    val gameRepo2 = SqliteGameRepository(ds2)
    sessRepo2.load(session.sessionId).value shouldBe session
    gameRepo2.load(session.gameId).value    shouldBe state
    ds2.close()
  }

  it should "update both records correctly when called a second time (submit-move)" in {
    val ds       = freshDs(tempPath())
    val sessRepo = SqliteSessionRepository(ds)
    val gameRepo = SqliteGameRepository(ds)
    val store    = SqliteSessionGameStore(ds, sessRepo, gameRepo)

    val session  = freshSession(GameId.random())
    val state1   = freshState
    store.save(session, state1).value

    val session2 = session.copy(lifecycle = SessionLifecycle.Active)
    val state2   = state1.copy(fullmoveNumber = 2)
    store.save(session2, state2).value

    sessRepo.load(session.sessionId).value.lifecycle shouldBe SessionLifecycle.Active
    gameRepo.load(session.gameId).value.fullmoveNumber shouldBe 2
    ds.close()
  }

  it should "reflect resign/cancel lifecycle transition" in {
    val ds       = freshDs(tempPath())
    val sessRepo = SqliteSessionRepository(ds)
    val gameRepo = SqliteGameRepository(ds)
    val store    = SqliteSessionGameStore(ds, sessRepo, gameRepo)

    val session = freshSession(GameId.random())
    store.save(session, freshState).value

    val resigned = session.copy(lifecycle = SessionLifecycle.Finished)
    import chess.domain.model.GameStatus
    val finalState = freshState.copy(status = GameStatus.Resigned(Color.White))
    store.save(resigned, finalState).value

    sessRepo.load(session.sessionId).value.lifecycle shouldBe SessionLifecycle.Finished
    gameRepo.load(session.gameId).value.status        shouldBe GameStatus.Resigned(Color.White)
    ds.close()
  }
