package chess.adapter.repository.postgres

import chess.adapter.repository.slick.{SlickExternalBindingTables, SlickTables}
import slick.jdbc.PostgresProfile

private[postgres] object PostgresSlickSupport:
  val profile: PostgresProfile.type = PostgresProfile
  val tables: SlickTables = SlickTables(profile)
  val externalBindingTables: SlickExternalBindingTables = SlickExternalBindingTables(profile)

  def tablesFor(schema: Option[String]): SlickTables =
    schema.map(_.trim).filter(_.nonEmpty) match
      case Some(value) => SlickTables(profile, Some(value))
      case None        => tables

  def externalBindingTablesFor(schema: Option[String]): SlickExternalBindingTables =
    schema.map(_.trim).filter(_.nonEmpty) match
      case Some(value) => SlickExternalBindingTables(profile, Some(value))
      case None        => externalBindingTables
