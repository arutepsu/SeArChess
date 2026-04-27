package chess.adapter.migration

import chess.adapter.migration.contract.SessionMigrationReaderContract
import chess.application.migration.SessionMigrationCursor
import chess.application.session.model.{GameSession, SessionLifecycle, SessionMode, SideController}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.UUID

class InMemorySessionMigrationReaderSpec
    extends AnyFlatSpec
    with Matchers
    with EitherValues
    with SessionMigrationReaderContract:

  override def readerName: String = "InMemorySessionMigrationReader"

  override def freshReaderFixture(sessions: List[GameSession]): ReaderFixture =
    ReaderFixture(
      reader = InMemorySessionMigrationReader(sessions),
      expectedOrder = sessions
    )

  "InMemorySessionMigrationReader" should "read stable batches using cursor progression" in {
    val sessions = List(
      session("00000000-0000-0000-0000-000000000001", "10000000-0000-0000-0000-000000000001"),
      session("00000000-0000-0000-0000-000000000002", "10000000-0000-0000-0000-000000000002"),
      session("00000000-0000-0000-0000-000000000003", "10000000-0000-0000-0000-000000000003")
    )
    val reader = InMemorySessionMigrationReader(sessions)

    val first = reader.readBatch(None, 2).value
    first.sessions shouldBe sessions.take(2)
    first.nextCursor shouldBe Some(SessionMigrationCursor("2"))

    val second = reader.readBatch(first.nextCursor, 2).value
    second.sessions shouldBe sessions.drop(2)
    second.nextCursor shouldBe None
  }

  private def session(sessionId: String, gameId: String): GameSession =
    GameSession(
      sessionId = SessionId(UUID.fromString(sessionId)),
      gameId = GameId(UUID.fromString(gameId)),
      mode = SessionMode.HumanVsHuman,
      whiteController = SideController.HumanLocal,
      blackController = SideController.HumanLocal,
      lifecycle = SessionLifecycle.Active,
      createdAt = Instant.parse("2024-01-01T00:00:00Z"),
      updatedAt = Instant.parse("2024-01-01T00:05:00Z")
    )
