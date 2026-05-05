package chess.adapter.repository.mongo

import chess.adapter.repository.contract.SessionGameStoreContract
import chess.adapter.repository.testcontainers.MongoTestcontainerFixture
import org.scalatest.BeforeAndAfterAll
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

  override protected def beforeAll(): Unit =
    super.beforeAll()
    mongo.start()

  override protected def afterAll(): Unit =
    mongo.stop()
    super.afterAll()
