package chess.history.slick

import chess.application.session.model.SessionIds.GameId
import chess.history.{ArchiveRecord, ArchiveRecordJson, ArchiveRepository, ArchiveRepositoryError}
import slick.jdbc.PostgresProfile.api.*

import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.util.control.NonFatal
import java.sql.Timestamp
import java.util.UUID

class SlickPostgresArchiveRepository(db: Database, schema: Option[String] = None) extends ArchiveRepository:

  private val schemaName = schema.map(_.trim).filter(_.nonEmpty)
  private val archives   = TableQuery(tag => ArchivesTable(tag, schemaName))

  override def upsert(record: ArchiveRecord): Either[ArchiveRepositoryError, Unit] =
    val row = ArchiveRow(
      gameId         = record.gameId.value,
      sessionId      = record.sessionId.value,
      recordJson     = ujson.write(ArchiveRecordJson.toJson(record)),
      ownerUserId    = record.ownerUserId,
      ownerNicknameSnapshot = record.ownerNicknameSnapshot,
      source         = record.source,
      createdAt      = Timestamp.from(record.createdAt),
      closedAt       = Timestamp.from(record.closedAt),
      materializedAt = Timestamp.from(record.materializedAt)
    )
    try
      Await.result(db.run(archives.insertOrUpdate(row)), 30.seconds)
      Right(())
    catch case NonFatal(e) => Left(ArchiveRepositoryError.StorageFailure(safeMessage(e)))

  override def findByGameId(gameId: GameId): Either[ArchiveRepositoryError, Option[ArchiveRecord]] =
    try
      val query  = archives.filter(_.gameId === gameId.value).result.headOption
      val rowOpt = Await.result(db.run(query), 30.seconds)
      rowOpt match
        case None      => Right(None)
        case Some(row) =>
          ArchiveRecordJson
            .fromJson(ujson.read(row.recordJson))
            .left
            .map(ArchiveRepositoryError.StorageFailure(_))
            .map(Some(_))
    catch case NonFatal(e) => Left(ArchiveRepositoryError.StorageFailure(safeMessage(e)))

  override def findByOwner(ownerUserId: UUID): Either[ArchiveRepositoryError, List[ArchiveRecord]] =
    try
      val rows = Await.result(db.run(archives.filter(_.ownerUserId === ownerUserId).result), 30.seconds)
      sequence(rows.toList.map(row =>
        ArchiveRecordJson
          .fromJson(ujson.read(row.recordJson))
          .left
          .map(ArchiveRepositoryError.StorageFailure(_))
      ))
    catch case NonFatal(e) => Left(ArchiveRepositoryError.StorageFailure(safeMessage(e)))

  private def sequence[A](values: List[Either[ArchiveRepositoryError, A]]): Either[ArchiveRepositoryError, List[A]] =
    values.foldRight(Right(Nil): Either[ArchiveRepositoryError, List[A]]) { (next, acc) =>
      for
        value <- next
        rest <- acc
      yield value :: rest
    }

  private def safeMessage(e: Throwable): String =
    Option(e.getMessage).map(_.trim).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)

final case class ArchiveRow(
    gameId:         UUID,
    sessionId:      UUID,
    recordJson:     String,
    ownerUserId:    Option[UUID],
    ownerNicknameSnapshot: Option[String],
    source:         String,
    createdAt:      Timestamp,
    closedAt:       Timestamp,
    materializedAt: Timestamp
)

final class ArchivesTable(tag: Tag, schema: Option[String])
    extends Table[ArchiveRow](tag, schema, "history_archives"):
  def gameId         = column[UUID]("game_id", O.PrimaryKey)
  def sessionId      = column[UUID]("session_id")
  def recordJson     = column[String]("record_json")
  def ownerUserId    = column[Option[UUID]]("owner_user_id")
  def ownerNicknameSnapshot = column[Option[String]]("owner_nickname_snapshot")
  def source         = column[String]("source")
  def createdAt      = column[Timestamp]("created_at")
  def closedAt       = column[Timestamp]("closed_at")
  def materializedAt = column[Timestamp]("materialized_at")
  def * = (gameId, sessionId, recordJson, ownerUserId, ownerNicknameSnapshot, source, createdAt, closedAt, materializedAt).mapTo[ArchiveRow]
