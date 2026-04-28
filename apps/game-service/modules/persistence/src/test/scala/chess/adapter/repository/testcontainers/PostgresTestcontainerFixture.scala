package chess.adapter.repository.testcontainers

import chess.adapter.repository.postgres.{
  PostgresFlywaySchemaInitializer,
  PostgresGameRepository,
  PostgresSessionGameStore,
  PostgresSessionMigrationReader,
  PostgresSessionRepository
}
import chess.application.migration.SessionMigrationReader
import chess.application.port.repository.{GameRepository, SessionGameStore, SessionRepository}
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

  def start(): Unit =
    container.start()

  def stop(): Unit =
    sharedDatabase.close()
    container.stop()

  def jdbcUrl: String = container.getJdbcUrl

  def username: String = container.getUsername

  def password: String = container.getPassword

  def withDatabase[A](use: Database => A): A =
    val db = database()
    try use(db)
    finally db.close()

  def freshStoreParts(): StoreParts =
    resetWithFlyway()
    StoreParts(
      sessionRepository = PostgresSessionRepository(sharedDatabase, 10.seconds),
      gameRepository = PostgresGameRepository(sharedDatabase, 10.seconds),
      store = PostgresSessionGameStore(sharedDatabase, 10.seconds)
    )

  def freshRuntime(): PostgresRuntime =
    resetWithFlyway()
    val db = database()
    PostgresRuntime(
      db = db,
      reader = PostgresSessionMigrationReader(db, 10.seconds),
      sessionRepository = PostgresSessionRepository(db, 10.seconds),
      gameRepository = PostgresGameRepository(db, 10.seconds),
      store = PostgresSessionGameStore(db, 10.seconds)
    )

  def resetWithFlyway(): Unit =
    withDatabase { db =>
      Await.result(
        db.run(
          sqlu"""
            drop table if exists flyway_schema_history, game_states, sessions cascade
          """
        ),
        10.seconds
      )
    }
    PostgresFlywaySchemaInitializer.migrate(jdbcUrl, username, password)

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
