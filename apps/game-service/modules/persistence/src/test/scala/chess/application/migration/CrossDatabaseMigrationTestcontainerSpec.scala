package chess.application.migration

import chess.adapter.repository.testcontainers.{
  MongoRuntime,
  MongoTestcontainerFixture,
  PostgresRuntime,
  PostgresTestcontainerFixture,
  TestPersistenceSamples
}
import chess.application.port.repository.{GameRepository, SessionGameStore, SessionRepository}
import chess.application.session.model.GameSession
import chess.domain.state.GameState
import org.scalatest.BeforeAndAfterAll
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class CrossDatabaseMigrationTestcontainerSpec
    extends AnyFlatSpec
    with Matchers
    with EitherValues
    with TestPersistenceSamples
    with BeforeAndAfterAll:

  private val postgres = PostgresTestcontainerFixture()
  private val mongo = MongoTestcontainerFixture()
  private val service = PersistenceMigrationService(() => Instant.parse("2026-04-26T12:00:00Z"))

  override protected def beforeAll(): Unit =
    super.beforeAll()
    postgres.start()
    mongo.start()

  override protected def afterAll(): Unit =
    mongo.stop()
    postgres.stop()
    super.afterAll()

  "PersistenceMigrationService with Testcontainers" should "migrate from Postgres to Mongo" in {
    val fixture = freshFixture(MigrationPath.PostgresToMongo)
    try
      val session = sampleSession()
      val state = sampleState()
      fixture.seedSource(session, state)

      val report = service.run(fixture.sourceAdapter, fixture.targetAdapter, MigrationMode.Execute, 10)

      report.itemResults shouldBe List(MigrationItemResult.Migrated(session.sessionId, session.gameId))
      fixture.targetSessionRepository.load(session.sessionId).value shouldBe session
      fixture.targetGameRepository.load(session.gameId).value shouldBe state
    finally fixture.close()
  }

  it should "migrate from Mongo to Postgres" in {
    val fixture = freshFixture(MigrationPath.MongoToPostgres)
    try
      val session = sampleSession(
        sessionId = "00000000-0000-0000-0000-00000000a002",
        gameId = "10000000-0000-0000-0000-00000000a002"
      )
      val state = sampleState(fullmoveNumber = 22)
      fixture.seedSource(session, state)

      val report = service.run(fixture.sourceAdapter, fixture.targetAdapter, MigrationMode.Execute, 10)

      report.itemResults shouldBe List(MigrationItemResult.Migrated(session.sessionId, session.gameId))
      fixture.targetSessionRepository.load(session.sessionId).value shouldBe session
      fixture.targetGameRepository.load(session.gameId).value shouldBe state
    finally fixture.close()
  }

  private def freshFixture(path: MigrationPath): Fixture =
    val postgresRuntime = postgres.freshRuntime()
    val mongoRuntime = mongo.freshRuntime()
    path match
      case MigrationPath.PostgresToMongo =>
        Fixture(
          closeAction = () =>
            postgresRuntime.close()
            mongoRuntime.close(),
          sourceAdapter = MigrationSourceAdapter(
            name = "postgres",
            sessionReader = postgresRuntime.reader,
            gameRepository = postgresRuntime.gameRepository
          ),
          targetAdapter = MigrationTargetAdapter(
            name = "mongo",
            sessionRepository = mongoRuntime.sessionRepository,
            gameRepository = mongoRuntime.gameRepository,
            store = mongoRuntime.store
          ),
          sourceSessionRepository = postgresRuntime.sessionRepository,
          sourceGameRepository = postgresRuntime.gameRepository,
          targetSessionRepository = mongoRuntime.sessionRepository,
          targetGameRepository = mongoRuntime.gameRepository,
          targetStore = mongoRuntime.store
        )
      case MigrationPath.MongoToPostgres =>
        Fixture(
          closeAction = () =>
            postgresRuntime.close()
            mongoRuntime.close(),
          sourceAdapter = MigrationSourceAdapter(
            name = "mongo",
            sessionReader = mongoRuntime.reader,
            gameRepository = mongoRuntime.gameRepository
          ),
          targetAdapter = MigrationTargetAdapter(
            name = "postgres",
            sessionRepository = postgresRuntime.sessionRepository,
            gameRepository = postgresRuntime.gameRepository,
            store = postgresRuntime.store
          ),
          sourceSessionRepository = mongoRuntime.sessionRepository,
          sourceGameRepository = mongoRuntime.gameRepository,
          targetSessionRepository = postgresRuntime.sessionRepository,
          targetGameRepository = postgresRuntime.gameRepository,
          targetStore = postgresRuntime.store
        )

  private final case class Fixture(
      closeAction: () => Unit,
      sourceAdapter: MigrationSourceAdapter,
      targetAdapter: MigrationTargetAdapter,
      sourceSessionRepository: SessionRepository,
      sourceGameRepository: GameRepository,
      targetSessionRepository: SessionRepository,
      targetGameRepository: GameRepository,
      targetStore: SessionGameStore
  ):
    def close(): Unit =
      closeAction()

    def seedSource(session: GameSession, state: GameState): Unit =
      sourceSessionRepository.save(session).fold(error => fail(error.toString), identity)
      sourceGameRepository.save(session.gameId, state).fold(error => fail(error.toString), identity)
