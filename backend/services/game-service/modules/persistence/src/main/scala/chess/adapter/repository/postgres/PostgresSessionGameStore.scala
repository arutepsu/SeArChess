package chess.adapter.repository.postgres

import chess.adapter.repository.slick.SlickSessionGameStore
import slick.jdbc.PostgresProfile.api.*

import scala.concurrent.duration.Duration

class PostgresSessionGameStore(
    db: Database,
    timeout: Duration = Duration.Inf,
    schema: Option[String] = None
) extends SlickSessionGameStore(
      PostgresSlickSupport.profile
    )(
      db,
      PostgresSlickSupport.tablesFor(schema),
      timeout
    )
