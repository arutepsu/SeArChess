package chess.adapter.repository.postgres

import chess.adapter.repository.slick.SlickSessionRepository
import slick.jdbc.PostgresProfile.api.*

import scala.concurrent.duration.Duration

class PostgresSessionRepository(
    db: Database,
    timeout: Duration = Duration.Inf
) extends SlickSessionRepository(
      PostgresSlickSupport.profile
    )(
      db,
      PostgresSlickSupport.tables,
      timeout
    )

object PostgresSessionRepository:
  private[postgres] val Sessions = PostgresSlickSupport.tables.Sessions
