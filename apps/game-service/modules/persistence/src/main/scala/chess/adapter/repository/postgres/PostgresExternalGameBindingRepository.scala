package chess.adapter.repository.postgres

import chess.adapter.repository.slick.SlickExternalGameBindingRepository
import slick.jdbc.PostgresProfile.api.*

import scala.concurrent.duration.Duration

class PostgresExternalGameBindingRepository(
    db: Database,
    timeout: Duration = Duration.Inf,
    schema: Option[String] = None
) extends SlickExternalGameBindingRepository(
      PostgresSlickSupport.profile
    )(
      db,
      PostgresSlickSupport.externalBindingTablesFor(schema),
      timeout
    )
