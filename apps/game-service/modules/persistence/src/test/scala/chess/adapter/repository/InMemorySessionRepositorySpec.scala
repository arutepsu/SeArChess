package chess.adapter.repository

import chess.adapter.repository.contract.SessionRepositoryContract
import chess.application.port.repository.SessionRepository
import org.scalatest.flatspec.AnyFlatSpec

class InMemorySessionRepositorySpec extends AnyFlatSpec with SessionRepositoryContract:

  override def repositoryName: String = "InMemorySessionRepository"

  override def freshRepository(): SessionRepository =
    InMemorySessionRepository()
