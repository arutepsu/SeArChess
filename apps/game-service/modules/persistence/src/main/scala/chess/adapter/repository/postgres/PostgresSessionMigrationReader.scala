package chess.adapter.repository.postgres

import chess.adapter.repository.slick.SlickSessionMigrationReader
import slick.jdbc.PostgresProfile.api.*

import scala.concurrent.duration.Duration

class PostgresSessionMigrationReader(
    db: Database,
    timeout: Duration = Duration.Inf,
    schema: Option[String] = None
) extends SlickSessionMigrationReader(
      PostgresSlickSupport.profile
    )(
      db,
      PostgresSlickSupport.tablesFor(schema),
      timeout,
      cursorStoreName = "Postgres"
    )

object PostgresSessionMigrationReader:
  def apply(
      db: Database,
      timeout: Duration = Duration.Inf,
      schema: Option[String] = None
  ): PostgresSessionMigrationReader =
    new PostgresSessionMigrationReader(db, timeout, schema)
