package chess.adapter.repository.postgres

import chess.application.port.repository.{GameRepository, RepositoryError}
import chess.application.session.model.SessionIds.GameId
import chess.domain.state.GameState
import slick.jdbc.PostgresProfile.api.*

import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration

class PostgresGameRepository(
    db: Database,
    timeout: Duration = Duration.Inf
) extends GameRepository:

  import PostgresGameRepository.*

  override def load(gameId: GameId): Either[RepositoryError, GameState] =
    PostgresRepositorySupport.run(db, timeout) {
      GameStates
        .filter(_.gameId === gameId.value)
        .result
        .headOption
        .map:
          case Some(row) => PostgresGameStateJson.decode(row.stateJson)
          case None      => Left(RepositoryError.NotFound(gameId.value.toString))
    }

  override def save(gameId: GameId, state: GameState): Either[RepositoryError, Unit] =
    PostgresRepositorySupport.run(db, timeout) {
      val row = PostgresGameStateRow(
        gameId = gameId.value,
        stateJson = PostgresGameStateJson.encode(state)
      )
      GameStates.insertOrUpdate(row).map(_ => Right(()))
    }

object PostgresGameRepository:
  private[postgres] final case class PostgresGameStateRow(
      gameId: UUID,
      stateJson: String
  )

  private[postgres] final class PostgresGameTable(tag: Tag)
      extends Table[PostgresGameStateRow](tag, "game_states"):

    def gameId = column[UUID]("game_id", O.PrimaryKey)
    def stateJson = column[String]("state_json")
    def * = (gameId, stateJson).mapTo[PostgresGameStateRow]

  private[postgres] val GameStates = TableQuery[PostgresGameTable]
