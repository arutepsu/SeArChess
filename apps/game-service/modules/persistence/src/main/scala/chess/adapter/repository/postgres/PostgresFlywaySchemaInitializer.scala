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
      password: String,
      schema: Option[String] = None
  ): Result =
    val builder =
      Flyway
        .configure()
        .dataSource(url, user, password)
        .locations(MigrationResourcePath)

    val flyway =
      schema.map(_.trim).filter(_.nonEmpty) match
        case Some(value) =>
          builder
            .schemas(value)
            .defaultSchema(value)
            .createSchemas(true)
            .load()
        case None =>
          builder.load()

    val result = flyway.migrate()
    Result(
      migrationsExecuted = result.migrationsExecuted,
      schemaVersion = Option(flyway.info().current()).map(_.getVersion.toString)
    )
