package chess.userservice.postgres

import chess.userservice.application.TournamentBotOwnershipRepository
import chess.userservice.domain.TournamentBotOwnership
import slick.jdbc.PostgresProfile.api.*

import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.util.control.NonFatal
import java.sql.Timestamp
import java.util.UUID

class SlickTournamentBotOwnershipRepository(db: Database, schema: Option[String] = None)
    extends TournamentBotOwnershipRepository:

  private val schemaName = schema.map(_.trim).filter(_.nonEmpty)
  private val table      = TableQuery(tag => TournamentBotOwnershipTable(tag, schemaName))

  override def findAllByUserId(userId: UUID): Either[String, List[TournamentBotOwnership]] =
    run(table.filter(_.userId === userId).sortBy(_.createdAt).result)
      .map(_.map(rowToOwnership).toList)

  override def findByIdIn(ids: Set[UUID]): Either[String, List[TournamentBotOwnership]] =
    if ids.isEmpty then Right(Nil)
    else run(table.filter(_.id.inSet(ids)).result).map(_.map(rowToOwnership).toList)

  override def insertIfAbsent(ownership: TournamentBotOwnership): Either[String, TournamentBotOwnership] =
    val existing = table.filter(r => r.userId === ownership.userId && r.tournamentServerBotId === ownership.tournamentServerBotId)
    run(existing.result.headOption).flatMap {
      case Some(row) => Right(rowToOwnership(row))
      case None =>
        run((table += ownershipToRow(ownership)).asTry).flatMap {
          case util.Success(_) => Right(ownership)
          case util.Failure(_) =>
            run(existing.result.headOption).flatMap {
              case Some(row) => Right(rowToOwnership(row))
              case None      => Left("Concurrent insert failed: ownership record not found after conflict")
            }
        }
    }

  private def run[T](action: DBIO[T]): Either[String, T] =
    try Right(Await.result(db.run(action), 30.seconds))
    catch case NonFatal(e) => Left(safeMessage(e))

  private def ownershipToRow(o: TournamentBotOwnership): TournamentBotOwnershipRow =
    TournamentBotOwnershipRow(o.id, o.userId, o.tournamentServerBotId, o.tournamentServerBotName, o.searchessCatalogBotId, Timestamp.from(o.createdAt))

  private def rowToOwnership(r: TournamentBotOwnershipRow): TournamentBotOwnership =
    TournamentBotOwnership(r.id, r.userId, r.tournamentServerBotId, r.tournamentServerBotName, r.catalogBotId, r.createdAt.toInstant)

  private def safeMessage(e: Throwable): String =
    Option(e.getMessage).map(_.trim).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)

private[postgres] final case class TournamentBotOwnershipRow(
  id:                    UUID,
  userId:                UUID,
  tournamentServerBotId:   String,
  tournamentServerBotName: String,
  catalogBotId:            Option[String],
  createdAt:             Timestamp
)

private[postgres] final class TournamentBotOwnershipTable(tag: Tag, schema: Option[String])
    extends Table[TournamentBotOwnershipRow](tag, schema, "tournament_bot_ownership"):
  def id                    = column[UUID]("id", O.PrimaryKey)
  def userId                = column[UUID]("user_id")
  def tournamentServerBotId   = column[String]("bot_id")
  def tournamentServerBotName = column[String]("bot_name")
  def catalogBotId            = column[Option[String]]("catalog_bot_id")
  def createdAt             = column[Timestamp]("created_at")
  def *                     = (id, userId, tournamentServerBotId, tournamentServerBotName, catalogBotId, createdAt).mapTo[TournamentBotOwnershipRow]
