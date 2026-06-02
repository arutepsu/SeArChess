package chess.benchmarks.persistence

import chess.adapter.repository.postgres.PostgresFlywaySchemaInitializer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import slick.jdbc.PostgresProfile.api.*

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

private[persistence] final class SearchessBenchmarkPostgresContainer
    extends PostgreSQLContainer[SearchessBenchmarkPostgresContainer](
      DockerImageName.parse("postgres:16-alpine")
    )

private[persistence] final class PostgresBenchmarkDatabase:
  private val container = SearchessBenchmarkPostgresContainer()
  private var database: Option[Database] = None

  def start(): Database =
    container.start()
    PostgresFlywaySchemaInitializer.migrate(
      container.getJdbcUrl,
      container.getUsername,
      container.getPassword
    )
    val db = Database.forURL(
      url = container.getJdbcUrl,
      user = container.getUsername,
      password = container.getPassword,
      driver = "org.postgresql.Driver"
    )
    warmConnection(db)
    database = Some(db)
    db

  def stop(): Unit =
    database.foreach(_.close())
    database = None
    container.stop()

  private def warmConnection(db: Database): Unit =
    Await.result(db.run(sql"select 1".as[Int]), 10.seconds)
