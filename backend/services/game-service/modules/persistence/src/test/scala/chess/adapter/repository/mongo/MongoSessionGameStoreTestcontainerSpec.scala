package chess.adapter.repository.mongo

import chess.adapter.repository.contract.SessionGameStoreContract
import chess.adapter.repository.testcontainers.MongoTestcontainerFixture
import org.scalatest.Assertions.cancel
import org.scalatest.BeforeAndAfterAll
import org.scalatest.Outcome
import org.scalatest.flatspec.AnyFlatSpec

class MongoSessionGameStoreTestcontainerSpec
    extends AnyFlatSpec
    with SessionGameStoreContract
    with BeforeAndAfterAll:

  private val mongo = MongoTestcontainerFixture()

  override def storeName: String =
    "MongoSessionGameStore Testcontainers adapter"

  override def freshStore(): StoreFixture =
    val parts = mongo.freshStoreParts()
    StoreFixture(parts.sessionRepository, parts.gameRepository, parts.store)

  override protected def withFixture(test: NoArgTest): Outcome =
    if !mongo.isDockerAvailable then
      cancel("Docker/Testcontainers unavailable; skipping MongoDB container integration tests")
    mongo.start()
    super.withFixture(test)

  override protected def afterAll(): Unit =
    mongo.stop()
    super.afterAll()
