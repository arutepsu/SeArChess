package chess.adapter.http4s.mapper

import chess.application.query.game.{GameArchiveSnapshot, GameClosure, GameView}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.application.session.model.{SessionMode, SideController}
import chess.domain.state.GameStateFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.time.Instant

class ArchiveSnapshotMapperSpec extends AnyFlatSpec with Matchers:

  "ArchiveSnapshotMapper.toResponse" should "serialize DeployedBot as DeployedBot" in {
    val gameId = GameId.random()
    val snapshot = GameArchiveSnapshot(
      sessionId = SessionId.random(),
      gameId = gameId,
      mode = SessionMode.HumanVsDeployedBot,
      whiteController = SideController.HumanLocal,
      blackController = SideController.DeployedBot,
      closure = GameClosure.Cancelled,
      finalState = GameView.fromState(gameId, GameStateFactory.initial()),
      createdAt = Instant.parse("2026-06-08T10:00:00Z"),
      closedAt = Instant.parse("2026-06-08T10:01:00Z")
    )

    val response = ArchiveSnapshotMapper.toResponse(snapshot)

    response.blackController shouldBe "DeployedBot"
  }
