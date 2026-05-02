package chess.adapter.repository.slick

import _root_.slick.dbio.DBIO
import _root_.slick.jdbc.JdbcProfile
import chess.application.port.repository.{RepositoryError, SessionGameStore}
import chess.application.session.model.GameSession
import chess.domain.state.GameState

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

class SlickSessionGameStore(
    val profile: JdbcProfile
)(
    db: profile.backend.Database,
    val tables: SlickTables,
    timeout: Duration = Duration.Inf
) extends SessionGameStore:

  import profile.api.*
  import tables.*

  private given ExecutionContext = ExecutionContext.global

  override def save(session: GameSession, state: GameState): Either[RepositoryError, Unit] =
    run {
      val sessionRow = SlickSessionMapper.toRow(tables)(session)
      val gameRow =
        SlickGameStateRow(
          gameId = session.gameId.value,
          stateJson = SlickGameStateJson.encode(state)
        )
      val owner =
        Sessions
          .filter(_.gameId === session.gameId.value)
          .map(_.sessionId)
          .result
          .headOption

      owner
        .flatMap {
          case Some(existingSessionId) if existingSessionId != session.sessionId.value =>
            DBIO.successful(
              Left(
                RepositoryError.Conflict(
                  s"GameId ${session.gameId.value} is already owned by session $existingSessionId"
                )
              )
            )
          case _ =>
            for
              _ <- Sessions.insertOrUpdate(sessionRow)
              _ <- GameStates.insertOrUpdate(gameRow)
            yield Right(())
        }
        .transactionally
    }

  private def run[A](action: DBIO[Either[RepositoryError, A]]): Either[RepositoryError, A] =
    SlickRepositorySupport.run(profile)(
      db,
      timeout,
      Some("GameId is already owned by another session")
    )(action)
