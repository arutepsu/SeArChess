package chess.adapter.repository.postgres

import chess.application.port.repository.{GameRepository, RepositoryError}
import chess.application.session.model.SessionIds.GameId
import chess.domain.state.GameState
import slick.jdbc.PostgresProfile.api.*

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal

class PostgresGameRepository(
    db: Database,
    timeout: Duration = Duration.Inf
) extends GameRepository:

  import PostgresGameRepository.*

  private given ExecutionContext = ExecutionContext.global

  override def save(gameId: GameId, state: GameState): Either[RepositoryError, Unit] =
    run {
      val row = PostgresGameStateRow(
        gameId = gameId.value,
        stateJson = PostgresGameStateJson.encode(state)
      )
      GameStates.insertOrUpdate(row).map(_ => Right(()))
    }

  override def load(gameId: GameId): Either[RepositoryError, GameState] =
    run {
      GameStates
        .filter(_.gameId === gameId.value)
        .map(_.stateJson)
        .result
        .headOption
        .map:
          case Some(json) => PostgresGameStateJson.decode(json)
          case None       => Left(RepositoryError.NotFound(gameId.value.toString))
    }

  private def run[A](action: DBIO[Either[RepositoryError, A]]): Either[RepositoryError, A] =
    try Await.result(db.run(action), timeout)
    catch case NonFatal(e) =>
      Left(RepositoryError.StorageFailure(Option(e.getMessage).getOrElse(e.getClass.getSimpleName)))

object PostgresGameRepository:
  private[postgres] val GameStates = TableQuery[PostgresGameStateTable]
