package chess.server.migration

import chess.adapter.repository.postgres.PostgresFlywaySchemaInitializer

import scala.util.control.NonFatal

object PostgresSchemaMigrationMain:

  def main(args: Array[String]): Unit =
    if args.nonEmpty then
      Console.err.println("Usage: no arguments; configure Postgres with SEARCHESS_POSTGRES_*")
      sys.exit(1)

    val exitCode =
      MigrationConfigLoader.loadPostgresConfig() match
        case Left(error) =>
          Console.err.println(error)
          1
        case Right(config) =>
          try
            val result =
              PostgresFlywaySchemaInitializer.migrate(
                url = config.url,
                user = config.user,
                password = config.password
              )
            println(
              s"Postgres Flyway migration complete. " +
                s"Migrations executed: ${result.migrationsExecuted}. " +
                s"Schema version: ${result.schemaVersion.getOrElse("none")}."
            )
            0
          catch case NonFatal(error) =>
            Console.err.println(
              Option(error.getMessage)
                .map(_.trim)
                .filter(_.nonEmpty)
                .getOrElse(error.getClass.getSimpleName)
            )
            1

    sys.exit(exitCode)
