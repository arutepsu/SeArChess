package chess.adapter.repository

import chess.adapter.repository.contract.SessionGameStoreContract
import org.scalatest.flatspec.AnyFlatSpec

class InMemorySessionGameStoreSpec extends AnyFlatSpec with SessionGameStoreContract:

  override def storeName: String = "InMemorySessionGameStore"

  override def freshStore(): StoreFixture =
    val sessionRepository = InMemorySessionRepository()
    val gameRepository = InMemoryGameRepository()
    StoreFixture(
      sessionRepository = sessionRepository,
      gameRepository = gameRepository,
      store = InMemorySessionGameStore(sessionRepository, gameRepository)
    )
