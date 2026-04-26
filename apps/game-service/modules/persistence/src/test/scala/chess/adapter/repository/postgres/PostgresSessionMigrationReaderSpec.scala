package chess.adapter.repository.postgres

import chess.adapter.migration.contract.SessionMigrationReaderContract
import chess.application.migration.SessionMigrationReader
import chess.application.session.model.GameSession
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import slick.jdbc.PostgresProfile.api.*

import java.util.UUID
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class PostgresSessionMigrationReaderSpec
    extends AnyFlatSpec
    with SessionMigrationReaderContract
    with BeforeAndAfterAll:

  private val schemaName: String =
    s"searchess_session_migration_reader_${UUID.randomUUID().toString.replace("-", "")}"

  private lazy val database: Option[Database] =
    PostgresSessionMigrationReaderSpec.config.map { config =>
      createSchema(config, schemaName)
      Database.forURL(
        url = PostgresSessionMigrationReaderSpec.withCurrentSchema(config.url, schemaName),
        user = config.user,
        password = config.password,
        driver = "org.postgresql.Driver"
      )
    }

  override def readerName: String = "PostgresSessionMigrationReader"

  override def freshReaderFixture(sessions: List[GameSession]): ReaderFixture =
    val db = database.getOrElse(
      cancel("Set SEARCHESS_POSTGRES_URL to run the Postgres SessionMigrationReader tests")
    )
    PostgresSessionSchema.recreate(db, 10.seconds)
    val sessionRepository = PostgresSessionRepository(db, 10.seconds)
    sessions.foreach(sessionRepository.save(_).fold(error => fail(error.toString), identity))
    ReaderFixture(
      reader = PostgresSessionMigrationReader(db, 10.seconds),
      expectedOrder = sessions.sortBy(_.sessionId.value.toString)
    )

  override protected def afterAll(): Unit =
    database.foreach(_.close())
    PostgresSessionMigrationReaderSpec.config.foreach(dropSchema(_, schemaName))
    super.afterAll()

  private def createSchema(
      config: PostgresSessionMigrationReaderSpec.Config,
      schema: String
  ): Unit =
    val db = PostgresSessionMigrationReaderSpec.database(config)
    try Await.result(db.run(sqlu"create schema if not exists #$schema"), 10.seconds)
    finally db.close()

  private def dropSchema(
      config: PostgresSessionMigrationReaderSpec.Config,
      schema: String
  ): Unit =
    val db = PostgresSessionMigrationReaderSpec.database(config)
    try Await.result(db.run(sqlu"drop schema if exists #$schema cascade"), 10.seconds)
    finally db.close()

private object PostgresSessionMigrationReaderSpec:

  final case class Config(url: String, user: String, password: String)

  def config: Option[Config] =
    sys.env.get("SEARCHESS_POSTGRES_URL").map { url =>
      Config(
        url = url,
        user = sys.env.getOrElse("SEARCHESS_POSTGRES_USER", "postgres"),
        password = sys.env.getOrElse("SEARCHESS_POSTGRES_PASSWORD", "postgres")
      )
    }

  def database(config: Config): Database =
    Database.forURL(
      url = config.url,
      user = config.user,
      password = config.password,
      driver = "org.postgresql.Driver"
    )

  def withCurrentSchema(url: String, schema: String): String =
    val separator = if url.contains("?") then "&" else "?"
    s"${url}${separator}currentSchema=${schema}"
