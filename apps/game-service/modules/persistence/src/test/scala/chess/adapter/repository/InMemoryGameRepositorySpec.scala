package chess.adapter.repository

import chess.application.ChessService
import chess.application.port.repository.RepositoryError
import chess.application.session.model.SessionIds.GameId
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class InMemoryGameRepositorySpec extends AnyFlatSpec with Matchers with EitherValues:

  private def freshRepo  = InMemoryGameRepository()
  private def freshState = ChessService.createNewGame()

  "InMemoryGameRepository.load" should "return NotFound for an unknown GameId" in {
    val unknown = GameId.random()
    freshRepo.load(unknown).left.value shouldBe RepositoryError.NotFound(unknown.value.toString)
  }

  "InMemoryGameRepository.save / load" should "round-trip a GameState" in {
    val repo   = freshRepo
    val gameId = GameId.random()
    val state  = freshState
    repo.save(gameId, state)
    repo.load(gameId).value shouldBe state
  }

  it should "overwrite an existing entry on repeated saves" in {
    val repo   = freshRepo
    val gameId = GameId.random()
    val state1 = freshState
    val state2 = freshState.copy(fullmoveNumber = 5)
    repo.save(gameId, state1)
    repo.save(gameId, state2)
    repo.load(gameId).value.fullmoveNumber shouldBe 5
  }

  it should "store independent entries under different GameIds" in {
    val repo   = freshRepo
    val id1    = GameId.random()
    val id2    = GameId.random()
    val state1 = freshState
    val state2 = freshState.copy(fullmoveNumber = 3)
    repo.save(id1, state1)
    repo.save(id2, state2)
    repo.load(id1).value.fullmoveNumber shouldBe 1
    repo.load(id2).value.fullmoveNumber shouldBe 3
  }
