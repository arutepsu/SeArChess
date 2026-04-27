package chess.adapter.repository.postgres

import slick.jdbc.PostgresProfile.api.*

import java.util.UUID

private[postgres] final case class PostgresGameStateRow(
    gameId: UUID,
    stateJson: String
)

private[postgres] class PostgresGameStateTable(tag: Tag)
    extends Table[PostgresGameStateRow](tag, "game_states"):

  def gameId = column[UUID]("game_id", O.PrimaryKey)

  def stateJson = column[String]("state_json")

  def * = (gameId, stateJson).mapTo[PostgresGameStateRow]
