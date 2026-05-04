package chess.adapter.repository.postgres

import chess.adapter.repository.slick.SlickGameRepository
import slick.jdbc.PostgresProfile.api.*

import scala.concurrent.duration.Duration

class PostgresGameRepository(
    db: Database,
    timeout: Duration = Duration.Inf
) extends SlickGameRepository(
      PostgresSlickSupport.profile
    )(
      db,
      PostgresSlickSupport.tables,
      timeout
    )

object PostgresGameRepository:
  private[postgres] val GameStates = PostgresSlickSupport.tables.GameStates
