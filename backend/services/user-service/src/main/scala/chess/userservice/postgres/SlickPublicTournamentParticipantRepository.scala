package chess.userservice.postgres

import chess.userservice.application.PublicTournamentParticipantRepository
import chess.userservice.domain.PublicTournamentParticipant
import slick.jdbc.PostgresProfile.api.*

import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.util.control.NonFatal
import java.sql.Timestamp
import java.util.UUID

class SlickPublicTournamentParticipantRepository(db: Database, schema: Option[String] = None)
    extends PublicTournamentParticipantRepository:

  private val schemaName = schema.map(_.trim).filter(_.nonEmpty)
  private val table      = TableQuery(tag => PublicTournamentParticipantTable(tag, schemaName))

  override def findByTournamentId(tournamentId: String): Either[String, List[PublicTournamentParticipant]] =
    run(table.filter(_.tournamentId === tournamentId).sortBy(_.joinedAt).result)
      .map(_.map(rowToParticipant).toList)

  override def insertIfAbsent(p: PublicTournamentParticipant): Either[String, PublicTournamentParticipant] =
    val existing = table.filter(r => r.tournamentId === p.tournamentId && r.tournamentServerBotId === p.tournamentServerBotId)
    run(existing.result.headOption).flatMap {
      case Some(row) if row.searchessUserId == p.searchessUserId => Right(rowToParticipant(row))
      case Some(_)                                               => Left("bot_already_claimed_by_another_user")
      case None =>
        run((table += participantToRow(p)).asTry).flatMap {
          case util.Success(_) => Right(p)
          case util.Failure(_) =>
            run(existing.result.headOption).flatMap {
              case Some(row) if row.searchessUserId == p.searchessUserId => Right(rowToParticipant(row))
              case Some(_)                                               => Left("bot_already_claimed_by_another_user")
              case None                                                  => Left("Concurrent insert failed: participant not found after conflict")
            }
        }
    }

  private def run[T](action: DBIO[T]): Either[String, T] =
    try Right(Await.result(db.run(action), 30.seconds))
    catch case NonFatal(e) => Left(safeMessage(e))

  private def participantToRow(p: PublicTournamentParticipant): PublicTournamentParticipantRow =
    PublicTournamentParticipantRow(
      tournamentId            = p.tournamentId,
      searchessUserId         = p.searchessUserId,
      displayName             = p.displayName,
      searchessBotId          = p.searchessBotId,
      tournamentServerUserId  = p.tournamentServerUserId,
      tournamentServerBotId   = p.tournamentServerBotId,
      tournamentServerBotName = p.tournamentServerBotName,
      joinedAt                = Timestamp.from(p.joinedAt)
    )

  private def rowToParticipant(r: PublicTournamentParticipantRow): PublicTournamentParticipant =
    PublicTournamentParticipant(
      tournamentId            = r.tournamentId,
      searchessUserId         = r.searchessUserId,
      displayName             = r.displayName,
      searchessBotId          = r.searchessBotId,
      tournamentServerUserId  = r.tournamentServerUserId,
      tournamentServerBotId   = r.tournamentServerBotId,
      tournamentServerBotName = r.tournamentServerBotName,
      joinedAt                = r.joinedAt.toInstant
    )

  private def safeMessage(e: Throwable): String =
    Option(e.getMessage).map(_.trim).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)

private[postgres] final case class PublicTournamentParticipantRow(
  tournamentId:            String,
  searchessUserId:         UUID,
  displayName:             String,
  searchessBotId:          Option[UUID],
  tournamentServerUserId:  Option[String],
  tournamentServerBotId:   String,
  tournamentServerBotName: String,
  joinedAt:                Timestamp
)

private[postgres] final class PublicTournamentParticipantTable(tag: Tag, schema: Option[String])
    extends Table[PublicTournamentParticipantRow](tag, schema, "public_tournament_participant"):
  def tournamentId            = column[String]("tournament_id")
  def searchessUserId         = column[UUID]("searchess_user_id")
  def displayName             = column[String]("display_name")
  def searchessBotId          = column[Option[UUID]]("searchess_bot_id")
  def tournamentServerUserId  = column[Option[String]]("tournament_server_user_id")
  def tournamentServerBotId   = column[String]("tournament_server_bot_id")
  def tournamentServerBotName = column[String]("tournament_server_bot_name")
  def joinedAt                = column[Timestamp]("joined_at")
  def pk                      = primaryKey("pk_public_tournament_participant", (tournamentId, tournamentServerBotId))
  def *                       = (
    tournamentId, searchessUserId, displayName,
    searchessBotId, tournamentServerUserId,
    tournamentServerBotId, tournamentServerBotName, joinedAt
  ).mapTo[PublicTournamentParticipantRow]
