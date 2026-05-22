package chess.adapter.repository.postgres

import chess.adapter.repository.slick.SlickTables
import slick.jdbc.PostgresProfile

private[postgres] object PostgresSlickSupport:
  val profile: PostgresProfile.type = PostgresProfile
  val tables: SlickTables = SlickTables(profile)

  def tablesFor(schema: Option[String]): SlickTables =
    schema.map(_.trim).filter(_.nonEmpty) match
      case Some(value) => SlickTables(profile, Some(value))
      case None        => tables
