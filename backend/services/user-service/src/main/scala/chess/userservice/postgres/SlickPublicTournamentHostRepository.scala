package chess.userservice.postgres

import chess.userservice.application.PublicTournamentHostRepository
import chess.userservice.domain.PublicTournamentHostRecord
import slick.jdbc.PostgresProfile.api.*

import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.util.control.NonFatal
import java.sql.Timestamp
import java.util.UUID

class SlickPublicTournamentHostRepository(db: Database, schema: Option[String] = None)
    extends PublicTournamentHostRepository:

  private val schemaName = schema.map(_.trim).filter(_.nonEmpty)
  private val table      = TableQuery(tag => PublicTournamentHostTable(tag, schemaName))

  override def findByTournamentId(tournamentId: String): Either[String, Option[PublicTournamentHostRecord]] =
    run(table.filter(_.tournamentId === tournamentId).result.headOption)
      .map(_.map(rowToRecord))

  override def insertIfAbsent(r: PublicTournamentHostRecord): Either[String, PublicTournamentHostRecord] =
    val existing = table.filter(_.tournamentId === r.tournamentId)
    run(existing.result.headOption).flatMap {
      case Some(row) if row.hostSearchessUserId == r.hostSearchessUserId => Right(rowToRecord(row))
      case Some(_)   => Left("host_already_recorded_by_another_user")
      case None =>
        run((table += recordToRow(r)).asTry).flatMap {
          case util.Success(_) => Right(r)
          case util.Failure(_) =>
            run(existing.result.headOption).flatMap {
              case Some(row) if row.hostSearchessUserId == r.hostSearchessUserId => Right(rowToRecord(row))
              case Some(_)   => Left("host_already_recorded_by_another_user")
              case None      => Left("Concurrent insert failed: host record not found after conflict")
            }
        }
    }

  private def run[T](action: DBIO[T]): Either[String, T] =
    try Right(Await.result(db.run(action), 30.seconds))
    catch case NonFatal(e) => Left(safeMessage(e))

  private def recordToRow(r: PublicTournamentHostRecord): PublicTournamentHostRow =
    PublicTournamentHostRow(
      tournamentId                   = r.tournamentId,
      hostSearchessUserId             = r.hostSearchessUserId,
      hostKeycloakSub                = r.hostKeycloakSub,
      hostDisplayName                 = r.hostDisplayName,
      createdAt                       = Timestamp.from(r.createdAt),
      directorTournamentServerBotId   = r.directorTournamentServerBotId,
      directorTournamentServerBotName = r.directorTournamentServerBotName
    )

  private def rowToRecord(r: PublicTournamentHostRow): PublicTournamentHostRecord =
    PublicTournamentHostRecord(
      tournamentId                   = r.tournamentId,
      hostSearchessUserId             = r.hostSearchessUserId,
      hostKeycloakSub                = r.hostKeycloakSub,
      hostDisplayName                 = r.hostDisplayName,
      createdAt                       = r.createdAt.toInstant,
      directorTournamentServerBotId   = r.directorTournamentServerBotId,
      directorTournamentServerBotName = r.directorTournamentServerBotName
    )

  private def safeMessage(e: Throwable): String =
    Option(e.getMessage).map(_.trim).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)

private[postgres] final case class PublicTournamentHostRow(
  tournamentId:                   String,
  hostSearchessUserId:             UUID,
  hostKeycloakSub:                Option[String],
  hostDisplayName:                 String,
  createdAt:                       Timestamp,
  directorTournamentServerBotId:   Option[String],
  directorTournamentServerBotName: Option[String]
)

private[postgres] final class PublicTournamentHostTable(tag: Tag, schema: Option[String])
    extends Table[PublicTournamentHostRow](tag, schema, "public_tournament_host"):
  def tournamentId                   = column[String]("tournament_id", O.PrimaryKey)
  def hostSearchessUserId             = column[UUID]("host_searchess_user_id")
  def hostKeycloakSub                = column[Option[String]]("host_keycloak_sub")
  def hostDisplayName                 = column[String]("host_display_name")
  def createdAt                       = column[Timestamp]("created_at")
  def directorTournamentServerBotId   = column[Option[String]]("director_tournament_server_bot_id")
  def directorTournamentServerBotName = column[Option[String]]("director_tournament_server_bot_name")
  def * = (
    tournamentId, hostSearchessUserId, hostKeycloakSub, hostDisplayName, createdAt,
    directorTournamentServerBotId, directorTournamentServerBotName
  ).mapTo[PublicTournamentHostRow]
