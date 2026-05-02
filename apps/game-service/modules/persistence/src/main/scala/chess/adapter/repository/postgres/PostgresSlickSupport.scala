package chess.adapter.repository.postgres

import chess.adapter.repository.slick.SlickTables
import slick.jdbc.PostgresProfile

private[postgres] object PostgresSlickSupport:
  val profile: PostgresProfile.type = PostgresProfile
  val tables: SlickTables = SlickTables(profile)
