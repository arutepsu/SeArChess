package chess.adapter.repository.slick

import _root_.slick.jdbc.JdbcProfile
import chess.application.port.repository.{GameRepository, RepositoryError}
import chess.application.session.model.SessionIds.GameId
import chess.domain.state.GameState

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration

class SlickGameRepository(
    val profile: JdbcProfile
)(
    db: profile.backend.Database,
    tables: SlickTables,
    timeout: Duration = Duration.Inf
) extends GameRepository:

  import profile.api.*
  import tables.*

  override def load(gameId: GameId): Either[RepositoryError, GameState] =
    SlickRepositorySupport.run(profile)(db, timeout) {
      GameStates
        .filter(_.gameId === gameId.value)
        .result
        .headOption
        .map:
          case Some(row) => SlickGameStateJson.decode(row.stateJson)
          case None      => Left(RepositoryError.NotFound(gameId.value.toString))
    }

  override def save(gameId: GameId, state: GameState): Either[RepositoryError, Unit] =
    SlickRepositorySupport.run(profile)(db, timeout) {
      val row = SlickGameStateRow(
        gameId = gameId.value,
        stateJson = SlickGameStateJson.encode(state)
      )
      GameStates.insertOrUpdate(row).map(_ => Right(()))
    }
