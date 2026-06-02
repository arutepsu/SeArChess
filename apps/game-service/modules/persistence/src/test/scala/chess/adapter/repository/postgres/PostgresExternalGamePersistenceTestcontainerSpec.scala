package chess.adapter.repository.postgres

import chess.adapter.repository.contract.{
  ExternalGameBindingRepositoryContract,
  ExternalGameCommandStoreContract
}
import chess.adapter.repository.testcontainers.PostgresTestcontainerFixture
import org.scalatest.Assertions.cancel
import org.scalatest.BeforeAndAfterAll
import org.scalatest.Outcome
import org.scalatest.flatspec.AnyFlatSpec

/** Testcontainers integration tests for the external-game Postgres adapters.
  *
  * Cancels cleanly when Docker is unavailable. Both [[ExternalGameBindingRepositoryContract]] and
  * [[ExternalGameCommandStoreContract]] run against a real Postgres instance with the Flyway schema
  * (V1 + V2) applied by [[PostgresTestcontainerFixture.freshExternalGameParts]].
  */
class PostgresExternalGamePersistenceTestcontainerSpec
    extends AnyFlatSpec
    with ExternalGameBindingRepositoryContract
    with ExternalGameCommandStoreContract
    with BeforeAndAfterAll:

  private val postgres = PostgresTestcontainerFixture()

  override protected def withFixture(test: NoArgTest): Outcome =
    if !postgres.isDockerAvailable then
      cancel("Docker/Testcontainers unavailable; skipping PostgreSQL container integration tests")
    postgres.start()
    super.withFixture(test)

  override protected def afterAll(): Unit =
    postgres.stop()
    super.afterAll()

  // ── ExternalGameBindingRepositoryContract wiring ──────────────────────────

  override def repoName: String = "PostgresExternalGameBindingRepository"

  override def freshBindingFixture(): BindingRepoFixture =
    val parts = postgres.freshExternalGameParts()
    BindingRepoFixture(parts.sessionRepository, parts.gameRepository, parts.bindingRepository)

  // ── ExternalGameCommandStoreContract wiring ───────────────────────────────

  override def storeName: String = "PostgresExternalGameCommandStore"

  override def freshCommandStore(): CommandStoreFixture =
    val parts = postgres.freshExternalGameParts()
    CommandStoreFixture(parts.sessionRepository, parts.gameRepository, parts.bindingRepository, parts.commandStore)
