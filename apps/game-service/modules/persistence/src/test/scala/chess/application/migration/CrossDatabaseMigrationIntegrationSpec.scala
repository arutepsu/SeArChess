package chess.application.migration

import chess.adapter.repository.mongo.{
  MongoGameRepository,
  MongoGameSchema,
  MongoSessionGameStore,
  MongoSessionMigrationReader,
  MongoSessionRepository,
  MongoSessionSchema
}
import chess.adapter.repository.postgres.{
  PostgresGameRepository,
  PostgresSessionGameSchema,
  PostgresSessionGameStore,
  PostgresSessionMigrationReader,
  PostgresSessionRepository
}
import chess.application.port.repository.{GameRepository, SessionGameStore, SessionRepository}
import chess.application.session.model.{GameSession, SessionLifecycle, SessionMode, SideController}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.domain.model.{Board, Color, GameStatus, Move, Piece, PieceType, Position}
import chess.domain.state.{CastlingRights, EnPassantState, GameState}
import com.mongodb.client.{MongoClient, MongoClients, MongoCollection}
import org.bson.Document
import org.scalatest.BeforeAndAfterAll
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import slick.jdbc.PostgresProfile.api.*

import java.time.Instant
import java.util.UUID
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class CrossDatabaseMigrationIntegrationSpec
    extends AnyFlatSpec
    with Matchers
    with EitherValues
    with BeforeAndAfterAll:

  private val service = new PersistenceMigrationService(() => Instant.parse("2026-04-26T12:00:00Z"))

  private val pgSchemaName: String =
    s"searchess_cross_migration_${UUID.randomUUID().toString.replace("-", "")}"
  private val mongoDatabaseName: String =
    s"searchess_cross_migration_${UUID.randomUUID().toString.replace("-", "")}"

  private lazy val postgresDatabase: Option[Database] =
    config.map { cfg =>
      createPostgresSchema(cfg.postgres, pgSchemaName)
      Database.forURL(
        url = withCurrentSchema(cfg.postgres.url, pgSchemaName),
        user = cfg.postgres.user,
        password = cfg.postgres.password,
        driver = "org.postgresql.Driver"
      )
    }

  private lazy val mongoClient: Option[MongoClient] =
    config.map(cfg => MongoClients.create(cfg.mongo.uri))

  "PersistenceMigrationService" should "migrate one aggregate from Mongo to Postgres" in {
    val fixture = freshFixture(MigrationPath.MongoToPostgres)
    val session = sampleSession(
      "00000000-0000-0000-0000-000000000001",
      "10000000-0000-0000-0000-000000000001",
      SessionLifecycle.Active
    )
    val state = sampleState()
    fixture.seedSource(session, state)

    val report = service.run(fixture.sourceAdapter, fixture.targetAdapter, MigrationMode.Execute, 10)

    report.itemResults shouldBe List(MigrationItemResult.Migrated(session.sessionId, session.gameId))
    fixture.targetSessionRepository.load(session.sessionId).value shouldBe session
    fixture.targetGameRepository.load(session.gameId).value shouldBe state
  }

  it should "migrate one aggregate from Postgres to Mongo" in {
    val fixture = freshFixture(MigrationPath.PostgresToMongo)
    val session = sampleSession(
      "00000000-0000-0000-0000-000000000002",
      "10000000-0000-0000-0000-000000000002",
      SessionLifecycle.Finished
    )
    val state = sampleState(fullmoveNumber = 22)
    fixture.seedSource(session, state)

    val report = service.run(fixture.sourceAdapter, fixture.targetAdapter, MigrationMode.Execute, 10)

    report.itemResults shouldBe List(MigrationItemResult.Migrated(session.sessionId, session.gameId))
    fixture.targetSessionRepository.load(session.sessionId).value shouldBe session
    fixture.targetGameRepository.load(session.gameId).value shouldBe state
  }

  it should "report no migrated items for an empty source" in {
    val fixture = freshFixture(MigrationPath.MongoToPostgres)

    val report = service.run(fixture.sourceAdapter, fixture.targetAdapter, MigrationMode.Execute, 10)

    report.itemResults shouldBe Nil
    report.migratedCount shouldBe 0
  }

  it should "migrate multiple aggregates successfully" in {
    val fixture = freshFixture(MigrationPath.MongoToPostgres)
    val sessions = List(
      sampleSession(
        "00000000-0000-0000-0000-000000000011",
        "10000000-0000-0000-0000-000000000011",
        SessionLifecycle.Active
      ),
      sampleSession(
        "00000000-0000-0000-0000-000000000012",
        "10000000-0000-0000-0000-000000000012",
        SessionLifecycle.Finished
      ),
      sampleSession(
        "00000000-0000-0000-0000-000000000013",
        "10000000-0000-0000-0000-000000000013",
        SessionLifecycle.Cancelled
      )
    )
    val states = List(sampleState(11), sampleState(12), sampleState(13))
    sessions.zip(states).foreach { case (session, state) => fixture.seedSource(session, state) }

    val report = service.run(fixture.sourceAdapter, fixture.targetAdapter, MigrationMode.Execute, 2)

    report.migratedCount shouldBe 3
    sessions.zip(states).foreach { case (session, state) =>
      fixture.targetSessionRepository.load(session.sessionId).value shouldBe session
      fixture.targetGameRepository.load(session.gameId).value shouldBe state
    }
  }

  it should "skip equivalent data on rerun" in {
    val fixture = freshFixture(MigrationPath.PostgresToMongo)
    val session = sampleSession(
      "00000000-0000-0000-0000-000000000021",
      "10000000-0000-0000-0000-000000000021",
      SessionLifecycle.Active
    )
    val state = sampleState(21)
    fixture.seedSource(session, state)

    val first = service.run(fixture.sourceAdapter, fixture.targetAdapter, MigrationMode.Execute, 10)
    val second = service.run(fixture.sourceAdapter, fixture.targetAdapter, MigrationMode.Execute, 10)

    first.itemResults.head shouldBe MigrationItemResult.Migrated(session.sessionId, session.gameId)
    second.itemResults.head shouldBe MigrationItemResult.SkippedEquivalent(
      session.sessionId,
      session.gameId
    )
  }

  it should "write nothing in DryRun" in {
    val fixture = freshFixture(MigrationPath.MongoToPostgres)
    val session = sampleSession(
      "00000000-0000-0000-0000-000000000031",
      "10000000-0000-0000-0000-000000000031",
      SessionLifecycle.Active
    )
    fixture.seedSource(session, sampleState(31))

    val report = service.run(fixture.sourceAdapter, fixture.targetAdapter, MigrationMode.DryRun, 10)

    report.itemResults shouldBe List(MigrationItemResult.WouldMigrate(session.sessionId, session.gameId))
    fixture.targetSessionRepository.load(session.sessionId).left.value shouldBe
      chess.application.port.repository.RepositoryError.NotFound(session.sessionId.value.toString)
  }

  it should "validate successfully after migration" in {
    val fixture = freshFixture(MigrationPath.PostgresToMongo)
    val session = sampleSession(
      "00000000-0000-0000-0000-000000000041",
      "10000000-0000-0000-0000-000000000041",
      SessionLifecycle.Finished
    )
    val state = sampleState(41)
    fixture.seedSource(session, state)
    service.run(fixture.sourceAdapter, fixture.targetAdapter, MigrationMode.Execute, 10)

    val report =
      service.run(fixture.sourceAdapter, fixture.targetAdapter, MigrationMode.ValidateOnly, 10)

    report.itemResults shouldBe List(
      MigrationItemResult.ValidatedEquivalent(session.sessionId, session.gameId)
    )
  }

  it should "report conflict and not overwrite different target data" in {
    val fixture = freshFixture(MigrationPath.MongoToPostgres)
    val session = sampleSession(
      "00000000-0000-0000-0000-000000000051",
      "10000000-0000-0000-0000-000000000051",
      SessionLifecycle.Active
    )
    val sourceState = sampleState(51)
    val targetState = sampleState(99)
    fixture.seedSource(session, sourceState)
    fixture.seedTarget(session, targetState)

    val report = service.run(fixture.sourceAdapter, fixture.targetAdapter, MigrationMode.Execute, 10)

    report.itemResults.head shouldBe a[MigrationItemResult.Conflict]
    fixture.targetGameRepository.load(session.gameId).value shouldBe targetState
  }

  it should "report SourceGameStateMissing when a source session has no game state" in {
    val fixture = freshFixture(MigrationPath.PostgresToMongo)
    val session = sampleSession(
      "00000000-0000-0000-0000-000000000061",
      "10000000-0000-0000-0000-000000000061",
      SessionLifecycle.Cancelled
    )
    fixture.seedSourceSessionOnly(session)

    val report = service.run(fixture.sourceAdapter, fixture.targetAdapter, MigrationMode.Execute, 10)

    report.itemResults shouldBe List(
      MigrationItemResult.SourceGameStateMissing(session.sessionId, session.gameId)
    )
  }

  it should "treat a partial target aggregate as conflict in Execute and mismatch in ValidateOnly" in {
    val fixture = freshFixture(MigrationPath.MongoToPostgres)
    val session = sampleSession(
      "00000000-0000-0000-0000-000000000071",
      "10000000-0000-0000-0000-000000000071",
      SessionLifecycle.Active
    )
    val state = sampleState(71)
    fixture.seedSource(session, state)
    fixture.seedTargetSessionOnly(session)

    val executeReport =
      service.run(fixture.sourceAdapter, fixture.targetAdapter, MigrationMode.Execute, 10)
    val validateReport =
      service.run(fixture.sourceAdapter, fixture.targetAdapter, MigrationMode.ValidateOnly, 10)

    executeReport.itemResults.head shouldBe a[MigrationItemResult.Conflict]
    validateReport.itemResults.head shouldBe a[MigrationItemResult.ValidationMismatch]
  }

  it should "derive report counters from item results in a cross-database run" in {
    val fixture = freshFixture(MigrationPath.PostgresToMongo)
    val migrate = sampleSession(
      "00000000-0000-0000-0000-000000000081",
      "10000000-0000-0000-0000-000000000081",
      SessionLifecycle.Active
    )
    val skip = sampleSession(
      "00000000-0000-0000-0000-000000000082",
      "10000000-0000-0000-0000-000000000082",
      SessionLifecycle.Finished
    )
    val conflict = sampleSession(
      "00000000-0000-0000-0000-000000000083",
      "10000000-0000-0000-0000-000000000083",
      SessionLifecycle.Cancelled
    )
    val missing = sampleSession(
      "00000000-0000-0000-0000-000000000084",
      "10000000-0000-0000-0000-000000000084",
      SessionLifecycle.Active
    )

    fixture.seedSource(migrate, sampleState(81))
    fixture.seedSource(skip, sampleState(82))
    fixture.seedSource(conflict, sampleState(83))
    fixture.seedSourceSessionOnly(missing)
    fixture.seedTarget(skip, sampleState(82))
    fixture.seedTarget(conflict, sampleState(999))

    val report = service.run(fixture.sourceAdapter, fixture.targetAdapter, MigrationMode.Execute, 2)

    report.sourceSessionCount shouldBe 4
    report.migratedCount shouldBe 1
    report.skippedEquivalentCount shouldBe 1
    report.conflictCount shouldBe 1
    report.sourceDataMissingCount shouldBe 1
  }

  override protected def afterAll(): Unit =
    postgresDatabase.foreach(_.close())
    for
      cfg <- config
      _ = dropPostgresSchema(cfg.postgres, pgSchemaName)
      client <- mongoClient
    do
      client.getDatabase(mongoDatabaseName).drop()
      client.close()
    super.afterAll()

  private def freshFixture(path: MigrationPath): Fixture =
    config.fold[Fixture](
      cancel(
        "Set both SEARCHESS_POSTGRES_URL and SEARCHESS_MONGO_URI to run cross-database migration integration tests"
      )
    ) { _ =>
      val pgDb = postgresDatabase.fold[Database](
        cancel(
          "Set both SEARCHESS_POSTGRES_URL and SEARCHESS_MONGO_URI to run cross-database migration integration tests"
        )
      )(identity)
      val mongo = mongoClient.fold[MongoClient](
        cancel(
          "Set both SEARCHESS_POSTGRES_URL and SEARCHESS_MONGO_URI to run cross-database migration integration tests"
        )
      )(identity)

      val postgresRuntime = freshPostgresRuntime(pgDb)
      val mongoRuntime = freshMongoRuntime(mongo)

      path match
        case MigrationPath.MongoToPostgres =>
          Fixture(
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

        case MigrationPath.PostgresToMongo =>
          Fixture(
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
    }

  private def freshPostgresRuntime(db: Database): PostgresRuntime =
    PostgresSessionGameSchema.recreate(db, 10.seconds)
    val sessionRepository = PostgresSessionRepository(db, 10.seconds)
    val gameRepository = PostgresGameRepository(db, 10.seconds)
    PostgresRuntime(
      reader = PostgresSessionMigrationReader(db, 10.seconds),
      sessionRepository = sessionRepository,
      gameRepository = gameRepository,
      store = PostgresSessionGameStore(db, 10.seconds)
    )

  private def freshMongoRuntime(client: MongoClient): MongoRuntime =
    val database = client.getDatabase(mongoDatabaseName)
    database.drop()
    val sessionCollection = database.getCollection("sessions")
    val gameCollection = database.getCollection("games")
    MongoSessionSchema.initialize(sessionCollection).fold(error => fail(error.toString), identity)
    MongoGameSchema.initialize(gameCollection).fold(error => fail(error.toString), identity)
    val sessionRepository = MongoSessionRepository(sessionCollection)
    val gameRepository = MongoGameRepository(gameCollection)
    MongoRuntime(
      reader = MongoSessionMigrationReader(sessionCollection),
      sessionRepository = sessionRepository,
      gameRepository = gameRepository,
      store = MongoSessionGameStore(sessionRepository, gameRepository)
    )

  private def sampleSession(
      sessionId: String,
      gameId: String,
      lifecycle: SessionLifecycle
  ): GameSession =
    GameSession(
      sessionId = SessionId(UUID.fromString(sessionId)),
      gameId = GameId(UUID.fromString(gameId)),
      mode = SessionMode.HumanVsHuman,
      whiteController = SideController.HumanLocal,
      blackController = SideController.HumanRemote,
      lifecycle = lifecycle,
      createdAt = Instant.parse("2024-01-01T00:00:00Z"),
      updatedAt = Instant.parse("2024-01-01T00:05:00Z")
    )

  private def sampleState(fullmoveNumber: Int = 12): GameState =
    GameState(
      board = Board.empty
        .place(pos("e4"), Piece(Color.White, PieceType.Pawn))
        .place(pos("e8"), Piece(Color.Black, PieceType.King))
        .place(pos("g1"), Piece(Color.White, PieceType.King)),
      currentPlayer = Color.Black,
      moveHistory = List(Move(pos("e2"), pos("e4"))),
      status = GameStatus.Ongoing(inCheck = true),
      castlingRights = CastlingRights(
        whiteKingSide = false,
        whiteQueenSide = true,
        blackKingSide = true,
        blackQueenSide = false
      ),
      enPassantState = Some(EnPassantState(pos("e3"), pos("e4"), Color.White)),
      halfmoveClock = 7,
      fullmoveNumber = fullmoveNumber
    )

  private def pos(algebraic: String): Position =
    Position.fromAlgebraic(algebraic).value

  private def createPostgresSchema(
      config: CrossDatabaseMigrationIntegrationSpec.PostgresConfig,
      schema: String
  ): Unit =
    val db = Database.forURL(
      url = config.url,
      user = config.user,
      password = config.password,
      driver = "org.postgresql.Driver"
    )
    try Await.result(db.run(sqlu"create schema if not exists #$schema"), 10.seconds)
    finally db.close()

  private def dropPostgresSchema(
      config: CrossDatabaseMigrationIntegrationSpec.PostgresConfig,
      schema: String
  ): Unit =
    val db = Database.forURL(
      url = config.url,
      user = config.user,
      password = config.password,
      driver = "org.postgresql.Driver"
    )
    try Await.result(db.run(sqlu"drop schema if exists #$schema cascade"), 10.seconds)
    finally db.close()

  private def withCurrentSchema(url: String, schema: String): String =
    val separator = if url.contains("?") then "&" else "?"
    s"${url}${separator}currentSchema=${schema}"

  private def config: Option[CrossDatabaseMigrationIntegrationSpec.Config] =
    for
      postgresUrl <- sys.env.get("SEARCHESS_POSTGRES_URL")
      mongoUri <- sys.env.get("SEARCHESS_MONGO_URI")
    yield CrossDatabaseMigrationIntegrationSpec.Config(
      postgres = CrossDatabaseMigrationIntegrationSpec.PostgresConfig(
        url = postgresUrl,
        user = sys.env.getOrElse("SEARCHESS_POSTGRES_USER", "postgres"),
        password = sys.env.getOrElse("SEARCHESS_POSTGRES_PASSWORD", "postgres")
      ),
      mongo = CrossDatabaseMigrationIntegrationSpec.MongoConfig(mongoUri)
    )

  private final case class Fixture(
      sourceAdapter: MigrationSourceAdapter,
      targetAdapter: MigrationTargetAdapter,
      sourceSessionRepository: SessionRepository,
      sourceGameRepository: GameRepository,
      targetSessionRepository: SessionRepository,
      targetGameRepository: GameRepository,
      targetStore: SessionGameStore
  ):
    def seedSource(session: GameSession, state: GameState): Unit =
      sourceSessionRepository.save(session).fold(error => fail(error.toString), identity)
      sourceGameRepository.save(session.gameId, state).fold(error => fail(error.toString), identity)

    def seedSourceSessionOnly(session: GameSession): Unit =
      sourceSessionRepository.save(session).fold(error => fail(error.toString), identity)

    def seedTarget(session: GameSession, state: GameState): Unit =
      targetStore.save(session, state).fold(error => fail(error.toString), identity)

    def seedTargetSessionOnly(session: GameSession): Unit =
      targetSessionRepository.save(session).fold(error => fail(error.toString), identity)

  private final case class PostgresRuntime(
      reader: SessionMigrationReader,
      sessionRepository: SessionRepository,
      gameRepository: GameRepository,
      store: SessionGameStore
  )

  private final case class MongoRuntime(
      reader: SessionMigrationReader,
      sessionRepository: SessionRepository,
      gameRepository: GameRepository,
      store: SessionGameStore
  )

object CrossDatabaseMigrationIntegrationSpec:
  final case class PostgresConfig(url: String, user: String, password: String)
  final case class MongoConfig(uri: String)
  final case class Config(
      postgres: CrossDatabaseMigrationIntegrationSpec.PostgresConfig,
      mongo: CrossDatabaseMigrationIntegrationSpec.MongoConfig
  )

enum MigrationPath:
  case PostgresToMongo
  case MongoToPostgres
