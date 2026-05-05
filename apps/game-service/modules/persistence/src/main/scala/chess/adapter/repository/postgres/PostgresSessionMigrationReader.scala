package chess.adapter.repository.postgres

import chess.adapter.repository.slick.SlickSessionMigrationReader
import slick.jdbc.PostgresProfile.api.*

import scala.concurrent.duration.Duration

class PostgresSessionMigrationReader(
    db: Database,
    timeout: Duration = Duration.Inf
) extends SlickSessionMigrationReader(
      PostgresSlickSupport.profile
    )(
      db,
      PostgresSlickSupport.tables,
      timeout,
      cursorStoreName = "Postgres"
    )

object PostgresSessionMigrationReader:
  def apply(
      db: Database,
      timeout: Duration = Duration.Inf
  ): PostgresSessionMigrationReader =
    new PostgresSessionMigrationReader(db, timeout)
