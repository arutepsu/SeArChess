package chess.adapter.repository

import chess.adapter.repository.contract.GameRepositoryContract
import chess.application.port.repository.GameRepository
import org.scalatest.flatspec.AnyFlatSpec

class InMemoryGameRepositorySpec extends AnyFlatSpec with GameRepositoryContract:

  override def repositoryName: String = "InMemoryGameRepository"

  override def freshRepository(): GameRepository =
    InMemoryGameRepository()
