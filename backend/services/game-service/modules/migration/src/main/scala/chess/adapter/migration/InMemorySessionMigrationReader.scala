package chess.adapter.migration

import chess.application.migration.{
  SessionMigrationBatch,
  SessionMigrationCursor,
  SessionMigrationReader
}
import chess.application.port.repository.RepositoryError
import chess.application.session.model.GameSession

final class InMemorySessionMigrationReader(
    sessionsInStableOrder: List[GameSession]
) extends SessionMigrationReader:

  private val sessions = sessionsInStableOrder.toVector

  override def readBatch(
      cursor: Option[SessionMigrationCursor],
      batchSize: Int
  ): Either[RepositoryError, SessionMigrationBatch] =
    if batchSize <= 0 then Left(RepositoryError.StorageFailure("batchSize must be positive"))
    else
      offsetFrom(cursor).map { offset =>
        val batchSessions = sessions.slice(offset, offset + batchSize).toList
        val nextOffset = offset + batchSessions.size
        val nextCursor =
          if nextOffset >= sessions.size then None
          else Some(SessionMigrationCursor(nextOffset.toString))

        SessionMigrationBatch(batchSessions, nextCursor)
      }

  private def offsetFrom(
      cursor: Option[SessionMigrationCursor]
  ): Either[RepositoryError, Int] =
    cursor match
      case None => Right(0)
      case Some(value) =>
        value.value.toIntOption match
          case Some(offset) if offset >= 0 => Right(offset)
          case _ =>
            Left(
              RepositoryError.StorageFailure(
                s"Invalid in-memory migration cursor: ${value.value}"
              )
            )
object InMemorySessionMigrationReader:
  def apply(sessionsInStableOrder: List[GameSession]): InMemorySessionMigrationReader =
    new InMemorySessionMigrationReader(sessionsInStableOrder)
