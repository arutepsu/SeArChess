package chess.adapter.repository.postgres

import slick.jdbc.PostgresProfile.api.*

import java.sql.Timestamp
import java.util.UUID

private[postgres] final case class PostgresSessionRow(
    sessionId: UUID,
    gameId: UUID,
    mode: String,
    whiteControllerKind: String,
    whiteControllerEngineId: Option[String],
    blackControllerKind: String,
    blackControllerEngineId: Option[String],
    lifecycle: String,
    createdAt: Timestamp,
    updatedAt: Timestamp
)

private[postgres] class PostgresSessionTable(tag: Tag)
    extends Table[PostgresSessionRow](tag, "sessions"):

  def sessionId = column[UUID]("session_id", O.PrimaryKey)

  def gameId = column[UUID]("game_id", O.Unique)

  def mode = column[String]("mode")

  def whiteControllerKind = column[String]("white_controller_kind")

  def whiteControllerEngineId = column[Option[String]]("white_controller_engine_id")

  def blackControllerKind = column[String]("black_controller_kind")

  def blackControllerEngineId = column[Option[String]]("black_controller_engine_id")

  def lifecycle = column[String]("lifecycle")

  def createdAt = column[Timestamp]("created_at")

  def updatedAt = column[Timestamp]("updated_at")

  def * =
    (
      sessionId,
      gameId,
      mode,
      whiteControllerKind,
      whiteControllerEngineId,
      blackControllerKind,
      blackControllerEngineId,
      lifecycle,
      createdAt,
      updatedAt
    ).mapTo[PostgresSessionRow]
