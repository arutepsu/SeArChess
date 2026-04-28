package chess.adapter.repository.postgres

import org.flywaydb.core.Flyway

object PostgresFlywaySchemaInitializer:
  val MigrationResourcePath: String = "classpath:db/migration/postgres"

  final case class Result(
      migrationsExecuted: Int,
      schemaVersion: Option[String]
  )

  def migrate(
      url: String,
      user: String,
      password: String
  ): Result =
    val flyway =
      Flyway
        .configure()
        .dataSource(url, user, password)
        .locations(MigrationResourcePath)
        .load()

    val result = flyway.migrate()
    Result(
      migrationsExecuted = result.migrationsExecuted,
      schemaVersion = Option(flyway.info().current()).map(_.getVersion.toString)
    )
