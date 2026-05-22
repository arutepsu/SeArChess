package chess.history.postgres

import org.flywaydb.core.Flyway

object HistoryFlywaySchemaInitializer:
  val MigrationResourcePath: String = "classpath:db/migration/history"

  final case class Result(migrationsExecuted: Int, schemaVersion: Option[String])

  def migrate(url: String, user: String, password: String, schema: Option[String]): Result =
    val schemaName = schema.map(_.trim).filter(_.nonEmpty)
    val builder = Flyway
      .configure()
      .dataSource(url, user, password)
      .locations(MigrationResourcePath)
    val flyway = schemaName
      .fold(builder)(s => builder.schemas(s).defaultSchema(s).createSchemas(true))
      .load()
    val result = flyway.migrate()
    Result(
      migrationsExecuted = result.migrationsExecuted,
      schemaVersion = Option(flyway.info().current()).map(_.getVersion.toString)
    )
