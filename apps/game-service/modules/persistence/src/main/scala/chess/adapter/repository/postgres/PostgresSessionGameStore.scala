package chess.adapter.repository.postgres

import chess.application.port.repository.{RepositoryError, SessionGameStore}
import chess.application.session.model.GameSession
import chess.domain.state.GameState
import slick.jdbc.PostgresProfile.api.*

import java.sql.SQLException
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal

class PostgresSessionGameStore(
    db: Database,
    timeout: Duration = Duration.Inf
) extends SessionGameStore:

  private given ExecutionContext = ExecutionContext.global

  override def save(session: GameSession, state: GameState): Either[RepositoryError, Unit] =
    try
      run {
        val sessionRow = PostgresSessionMapper.toRow(session)
      val gameRow =
        PostgresGameRepository.PostgresGameStateRow(
          gameId = session.gameId.value,
          stateJson = PostgresGameStateJson.encode(state)
        )
        val owner =
          PostgresSessionRepository.Sessions
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
                _ <- PostgresSessionRepository.Sessions.insertOrUpdate(sessionRow)
                _ <- PostgresGameRepository.GameStates.insertOrUpdate(gameRow)
              yield Right(())
          }
          .transactionally
      }
    catch case NonFatal(e) => Left(toRepositoryError(e))

  private def run[A](action: DBIO[Either[RepositoryError, A]]): Either[RepositoryError, A] =
    try Await.result(db.run(action), timeout)
    catch case NonFatal(e) => Left(toRepositoryError(e))

  private def toRepositoryError(error: Throwable): RepositoryError =
    if isUniqueViolation(error) then
      RepositoryError.Conflict("GameId is already owned by another session")
    else
      RepositoryError.StorageFailure(
        Option(error.getMessage).getOrElse(error.getClass.getSimpleName)
      )

  private def isUniqueViolation(error: Throwable): Boolean =
    error match
      case sql: SQLException if sql.getSQLState == "23505" => true
      case _ =>
        Option(error.getCause).exists(isUniqueViolation)
