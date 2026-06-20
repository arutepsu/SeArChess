package chess.adapter.repository.postgres

import chess.adapter.repository.slick.SlickSchema
import slick.jdbc.PostgresProfile.api.*

import scala.concurrent.duration.Duration

object PostgresSessionSchema:

  def createIfNotExists(db: Database, timeout: Duration = Duration.Inf, schema: Option[String] = None): Unit =
    SlickSchema.createSessionsIfNotExists(
      PostgresSlickSupport.profile,
      db,
      PostgresSlickSupport.tablesFor(schema),
      timeout
    )

  def recreate(db: Database, timeout: Duration = Duration.Inf, schema: Option[String] = None): Unit =
    SlickSchema.recreateSessions(
      PostgresSlickSupport.profile,
      db,
      PostgresSlickSupport.tablesFor(schema),
      timeout
    )
