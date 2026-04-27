package chess.adapter.repository.postgres

import chess.adapter.repository.contract.GameRepositoryContract
import chess.application.port.repository.GameRepository
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import slick.jdbc.PostgresProfile.api.*

import java.util.UUID
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class PostgresGameRepositorySpec
    extends AnyFlatSpec
    with GameRepositoryContract
    with BeforeAndAfterAll:

  private val schemaName: String =
    s"searchess_game_contract_${UUID.randomUUID().toString.replace("-", "")}"

  private lazy val database: Option[Database] =
    PostgresGameRepositorySpec.config.map { config =>
      createSchema(config, schemaName)
      Database.forURL(
        url = PostgresGameRepositorySpec.withCurrentSchema(config.url, schemaName),
        user = config.user,
        password = config.password,
        driver = "org.postgresql.Driver"
      )
    }

  override def repositoryName: String = "PostgresGameRepository"

  override def freshRepository(): GameRepository =
    val db = database.getOrElse(
      cancel("Set SEARCHESS_POSTGRES_URL to run the Postgres GameRepository contract tests")
    )
    PostgresGameSchema.recreate(db, 10.seconds)
    PostgresGameRepository(db, 10.seconds)

  override protected def afterAll(): Unit =
    database.foreach(_.close())
    PostgresGameRepositorySpec.config.foreach(dropSchema(_, schemaName))
    super.afterAll()

  private def createSchema(config: PostgresGameRepositorySpec.Config, schema: String): Unit =
    val db = PostgresGameRepositorySpec.database(config)
    try Await.result(db.run(sqlu"create schema if not exists #$schema"), 10.seconds)
    finally db.close()

  private def dropSchema(config: PostgresGameRepositorySpec.Config, schema: String): Unit =
    val db = PostgresGameRepositorySpec.database(config)
    try Await.result(db.run(sqlu"drop schema if exists #$schema cascade"), 10.seconds)
    finally db.close()

private object PostgresGameRepositorySpec:

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
