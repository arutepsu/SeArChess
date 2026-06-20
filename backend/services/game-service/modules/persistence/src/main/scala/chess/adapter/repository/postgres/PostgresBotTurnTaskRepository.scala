package chess.adapter.repository.postgres

import chess.adapter.repository.slick.SlickBotTurnTaskRepository
import slick.jdbc.PostgresProfile.api.*

import scala.concurrent.duration.Duration

class PostgresBotTurnTaskRepository(
    db: Database,
    timeout: Duration = Duration.Inf,
    schema: Option[String] = None
) extends SlickBotTurnTaskRepository(
      PostgresSlickSupport.profile
    )(
      db,
      PostgresSlickSupport.tablesFor(schema),
      timeout,
      schema
    )
