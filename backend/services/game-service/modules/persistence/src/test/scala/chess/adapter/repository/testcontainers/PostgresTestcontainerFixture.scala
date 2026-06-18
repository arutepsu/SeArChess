package chess.adapter.repository.testcontainers

import chess.adapter.repository.postgres.{
  PostgresExternalGameBindingRepository,
  PostgresExternalGameCommandStore,
  PostgresFlywaySchemaInitializer,
  PostgresGameRepository,
  PostgresSessionGameStore,
  PostgresSessionMigrationReader,
  PostgresSessionRepository
}
import chess.application.migration.SessionMigrationReader
import chess.application.port.repository.{
  ExternalGameBindingRepository,
  ExternalGameCommandStore,
  GameRepository,
  SessionGameStore,
  SessionRepository
}
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import slick.jdbc.PostgresProfile.api.*

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

final class SearchessPostgresContainer
    extends PostgreSQLContainer[SearchessPostgresContainer](
      DockerImageName.parse("postgres:16-alpine")
    )

final class PostgresTestcontainerFixture:
  private val container = SearchessPostgresContainer()
  private lazy val sharedDatabase: Database = database()
  private var started = false

  def isDockerAvailable: Boolean =
    DockerClientFactory.instance().isDockerAvailable

  def start(): Unit =
    if !started then
      container.start()
      started = true

  def stop(): Unit =
    if started then
      sharedDatabase.close()
      container.stop()
      started = false

  def jdbcUrl: String = container.getJdbcUrl

  def username: String = container.getUsername

  def password: String = container.getPassword

  def withDatabase[A](use: Database => A): A =
    val db = database()
    try use(db)
    finally db.close()

  def freshExternalGameParts(schema: Option[String] = None): ExternalGameParts =
    resetWithFlyway(schema)
    ExternalGameParts(
      sessionRepository = PostgresSessionRepository(sharedDatabase, 10.seconds, schema),
      gameRepository    = PostgresGameRepository(sharedDatabase, 10.seconds, schema),
      bindingRepository = PostgresExternalGameBindingRepository(sharedDatabase, 10.seconds, schema),
      commandStore      = PostgresExternalGameCommandStore(sharedDatabase, 10.seconds, schema)
    )

  def freshStoreParts(schema: Option[String] = None): StoreParts =
    resetWithFlyway(schema)
    StoreParts(
      sessionRepository = PostgresSessionRepository(sharedDatabase, 10.seconds, schema),
      gameRepository = PostgresGameRepository(sharedDatabase, 10.seconds, schema),
      store = PostgresSessionGameStore(sharedDatabase, 10.seconds, schema)
    )

  def freshRuntime(schema: Option[String] = None): PostgresRuntime =
    resetWithFlyway(schema)
    val db = database()
    PostgresRuntime(
      db = db,
      reader = PostgresSessionMigrationReader(db, 10.seconds, schema),
      sessionRepository = PostgresSessionRepository(db, 10.seconds, schema),
      gameRepository = PostgresGameRepository(db, 10.seconds, schema),
      store = PostgresSessionGameStore(db, 10.seconds, schema)
    )

  def resetWithFlyway(schema: Option[String] = None): Unit =
    withDatabase { db =>
      Await.result(
        db.run(
          schema.map(_.trim).filter(_.nonEmpty) match
            case Some(value) =>
              sqlu"""drop schema if exists "#$value" cascade"""
            case None =>
              // Drop external_game_bindings before sessions/game_states (FK order).
              sqlu"""
                drop table if exists flyway_schema_history, external_game_bindings, game_states, sessions cascade
              """
        ),
        10.seconds
      )
    }
    PostgresFlywaySchemaInitializer.migrate(jdbcUrl, username, password, schema)

  private def database(): Database =
    Database.forURL(
      url = jdbcUrl,
      user = username,
      password = password,
      driver = "org.postgresql.Driver"
    )

final case class PostgresRuntime(
    db: Database,
    reader: SessionMigrationReader,
    sessionRepository: SessionRepository,
    gameRepository: GameRepository,
    store: SessionGameStore
):
  def close(): Unit =
    db.close()

final case class StoreParts(
    sessionRepository: SessionRepository,
    gameRepository: GameRepository,
    store: SessionGameStore
)

final case class ExternalGameParts(
    sessionRepository: SessionRepository,
    gameRepository: GameRepository,
    bindingRepository: ExternalGameBindingRepository,
    commandStore: ExternalGameCommandStore
)
