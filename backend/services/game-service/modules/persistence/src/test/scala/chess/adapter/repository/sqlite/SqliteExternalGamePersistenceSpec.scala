package chess.adapter.repository.sqlite

import chess.adapter.repository.contract.{
  ExternalGameBindingRepositoryContract,
  ExternalGameCommandStoreContract
}
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.Files

/** SQLite integration tests for the external-game persistence adapters.
  *
  * Each test gets its own temp-file database so tests are fully isolated.
  */
class SqliteExternalGamePersistenceSpec
    extends AnyFlatSpec
    with Matchers
    with EitherValues
    with ExternalGameBindingRepositoryContract
    with ExternalGameCommandStoreContract:

  // ── helpers ───────────────────────────────────────────────────────────────

  private def tempPath(): String =
    Files.createTempFile("chess-ext-test-", ".db").toAbsolutePath.toString

  private def freshDs(path: String): SqliteDataSource =
    val ds = SqliteDataSource(path)
    ds.withConnection(SqliteSchema.createTables)
    ds

  // ── ExternalGameBindingRepositoryContract wiring ──────────────────────────

  override def repoName: String = "SqliteExternalGameBindingRepository"

  override def freshBindingFixture(): BindingRepoFixture =
    val ds = freshDs(tempPath())
    BindingRepoFixture(
      sessionRepository = SqliteSessionRepository(ds),
      gameRepository    = SqliteGameRepository(ds),
      bindingRepository = SqliteExternalGameBindingRepository(ds)
    )

  // ── ExternalGameCommandStoreContract wiring ───────────────────────────────

  override def storeName: String = "SqliteExternalGameCommandStore"

  override def freshCommandStore(): CommandStoreFixture =
    val ds          = freshDs(tempPath())
    val sessionRepo = SqliteSessionRepository(ds)
    val gameRepo    = SqliteGameRepository(ds)
    val bindingRepo = SqliteExternalGameBindingRepository(ds)
    val store       = SqliteExternalGameCommandStore(ds, sessionRepo, gameRepo, bindingRepo)
    CommandStoreFixture(sessionRepo, gameRepo, bindingRepo, store)
