package chess.adapter.repository.postgres

import chess.adapter.repository.slick.SlickSchema
import slick.jdbc.PostgresProfile.api.*

import scala.concurrent.duration.Duration

object PostgresSessionGameSchema:

  def createIfNotExists(db: Database, timeout: Duration = Duration.Inf, schema: Option[String] = None): Unit =
    SlickSchema.createSessionGameIfNotExists(
      PostgresSlickSupport.profile,
      db,
      PostgresSlickSupport.tablesFor(schema),
      timeout
    )

  def recreate(db: Database, timeout: Duration = Duration.Inf, schema: Option[String] = None): Unit =
    SlickSchema.recreateSessionGame(
      PostgresSlickSupport.profile,
      db,
      PostgresSlickSupport.tablesFor(schema),
      timeout
    )
