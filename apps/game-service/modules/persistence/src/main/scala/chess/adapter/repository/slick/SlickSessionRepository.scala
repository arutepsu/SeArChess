package chess.adapter.repository.slick

import _root_.slick.dbio.DBIO
import _root_.slick.jdbc.JdbcProfile
import chess.application.port.repository.{RepositoryError, SessionRepository}
import chess.application.session.model.GameSession
import chess.application.session.model.SessionIds.{GameId, SessionId}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

class SlickSessionRepository(
    val profile: JdbcProfile
)(
    db: profile.backend.Database,
    val tables: SlickTables,
    timeout: Duration = Duration.Inf
) extends SessionRepository:

  import profile.api.*
  import tables.*

  private given ExecutionContext = ExecutionContext.global

  override def save(session: GameSession): Either[RepositoryError, Unit] =
    run {
      val row = SlickSessionMapper.toRow(tables)(session)
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
            Sessions.insertOrUpdate(row).map(_ => Right(()))
        }
        .transactionally
    }

  override def load(id: SessionId): Either[RepositoryError, GameSession] =
    run {
      Sessions
        .filter(_.sessionId === id.value)
        .result
        .headOption
        .map:
          case Some(row) => SlickSessionMapper.toSession(tables)(row)
          case None      => Left(RepositoryError.NotFound(id.value.toString))
    }

  override def loadByGameId(id: GameId): Either[RepositoryError, GameSession] =
    run {
      Sessions
        .filter(_.gameId === id.value)
        .result
        .headOption
        .map:
          case Some(row) => SlickSessionMapper.toSession(tables)(row)
          case None      => Left(RepositoryError.NotFound(id.value.toString))
    }

  override def listActive(): Either[RepositoryError, List[GameSession]] =
    run {
      Sessions
        .filterNot(_.lifecycle inSet Set("Finished", "Cancelled"))
        .result
        .map(rows => SlickRepositorySupport.sequence(rows.toList.map(SlickSessionMapper.toSession(tables))))
    }

  private def run[A](action: DBIO[Either[RepositoryError, A]]): Either[RepositoryError, A] =
    SlickRepositorySupport.run(profile)(
      db,
      timeout,
      Some("GameId is already owned by another session")
    )(action)
