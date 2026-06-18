package chess.adapter.repository.postgres

import chess.adapter.repository.contract.SessionGameStoreContract
import chess.adapter.repository.testcontainers.PostgresTestcontainerFixture
import org.scalatest.Assertions.cancel
import org.scalatest.BeforeAndAfterAll
import org.scalatest.Outcome
import org.scalatest.flatspec.AnyFlatSpec

class PostgresSessionGameStoreTestcontainerSpec
    extends AnyFlatSpec
    with SessionGameStoreContract
    with BeforeAndAfterAll:

  private val postgres = PostgresTestcontainerFixture()

  override def storeName: String =
    "PostgresSessionGameStore Testcontainers adapter"

  override def freshStore(): StoreFixture =
    val parts = postgres.freshStoreParts()
    StoreFixture(parts.sessionRepository, parts.gameRepository, parts.store)

  override protected def withFixture(test: NoArgTest): Outcome =
    if !postgres.isDockerAvailable then
      cancel("Docker/Testcontainers unavailable; skipping PostgreSQL container integration tests")
    postgres.start()
    super.withFixture(test)

  override protected def afterAll(): Unit =
    postgres.stop()
    super.afterAll()
