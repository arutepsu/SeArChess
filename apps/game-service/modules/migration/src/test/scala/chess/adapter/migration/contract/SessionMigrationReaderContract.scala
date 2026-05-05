package chess.adapter.migration.contract

import chess.application.migration.{SessionMigrationBatch, SessionMigrationCursor, SessionMigrationReader}
import chess.application.port.repository.RepositoryError
import chess.application.session.model.{GameSession, SessionLifecycle, SessionMode, SideController}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.UUID

trait SessionMigrationReaderContract extends AnyFlatSpecLike with Matchers with EitherValues:

  final case class ReaderFixture(
      reader: SessionMigrationReader,
      expectedOrder: List[GameSession]
  )

  def readerName: String

  def freshReaderFixture(sessions: List[GameSession]): ReaderFixture

  readerName should "return all sessions across multiple batches" in {
    val sessions = sampleSessions()
    val fixture = freshReaderFixture(sessions)

    val collected = readAll(fixture.reader, batchSize = 2)

    collected shouldBe fixture.expectedOrder
  }

  it should "include active and terminal sessions" in {
    val sessions = sampleSessions()
    val fixture = freshReaderFixture(sessions)

    val collected = readAll(fixture.reader, batchSize = 10)

    collected.map(_.lifecycle).toSet shouldBe Set(
      SessionLifecycle.Active,
      SessionLifecycle.Finished,
      SessionLifecycle.Cancelled
    )
  }

  it should "preserve exact GameSession values" in {
    val sessions = sampleSessions()
    val fixture = freshReaderFixture(sessions)

    readAll(fixture.reader, batchSize = 10) shouldBe fixture.expectedOrder
  }

  it should "return nextCursor while more records remain" in {
    val sessions = sampleSessions()
    val fixture = freshReaderFixture(sessions)

    val firstBatch = fixture.reader.readBatch(None, 1).value

    firstBatch.sessions should have size 1
    firstBatch.nextCursor should not be empty
  }

  it should "return nextCursor as None at the end" in {
    val sessions = sampleSessions()
    val fixture = freshReaderFixture(sessions)

    val lastBatch = readBatches(fixture.reader, batchSize = 2).last

    lastBatch.nextCursor shouldBe None
  }

  it should "not duplicate sessions within one traversal" in {
    val sessions = sampleSessions()
    val fixture = freshReaderFixture(sessions)

    val collected = readAll(fixture.reader, batchSize = 2)

    collected.map(_.sessionId).distinct should have size collected.size
  }

  it should "report invalid cursor behavior cleanly" in {
    val fixture = freshReaderFixture(sampleSessions())

    fixture.reader.readBatch(Some(SessionMigrationCursor("not-a-valid-cursor")), 2).left.value shouldBe
      a[RepositoryError.StorageFailure]
  }

  protected def sampleSessions(): List[GameSession] =
    List(
      session(
        "00000000-0000-0000-0000-000000000003",
        "10000000-0000-0000-0000-000000000003",
        SessionLifecycle.Cancelled,
        Instant.parse("2024-01-01T00:03:00Z"),
        Instant.parse("2024-01-01T00:13:00Z")
      ),
      session(
        "00000000-0000-0000-0000-000000000001",
        "10000000-0000-0000-0000-000000000001",
        SessionLifecycle.Active,
        Instant.parse("2024-01-01T00:01:00Z"),
        Instant.parse("2024-01-01T00:11:00Z")
      ),
      session(
        "00000000-0000-0000-0000-000000000002",
        "10000000-0000-0000-0000-000000000002",
        SessionLifecycle.Finished,
        Instant.parse("2024-01-01T00:02:00Z"),
        Instant.parse("2024-01-01T00:12:00Z")
      )
    )

  private def readAll(reader: SessionMigrationReader, batchSize: Int): List[GameSession] =
    readBatches(reader, batchSize).flatMap(_.sessions)

  private def readBatches(reader: SessionMigrationReader, batchSize: Int): List[SessionMigrationBatch] =
    def loop(cursor: Option[SessionMigrationCursor], acc: List[SessionMigrationBatch]): List[SessionMigrationBatch] =
      reader.readBatch(cursor, batchSize).value match
        case batch if batch.nextCursor.isEmpty => acc :+ batch
        case batch                             => loop(batch.nextCursor, acc :+ batch)

    loop(None, Nil)

  private def session(
      sessionId: String,
      gameId: String,
      lifecycle: SessionLifecycle,
      createdAt: Instant,
      updatedAt: Instant
  ): GameSession =
    GameSession(
      sessionId = SessionId(UUID.fromString(sessionId)),
      gameId = GameId(UUID.fromString(gameId)),
      mode = SessionMode.HumanVsHuman,
      whiteController = SideController.HumanLocal,
      blackController = SideController.HumanRemote,
      lifecycle = lifecycle,
      createdAt = createdAt,
      updatedAt = updatedAt
    )
