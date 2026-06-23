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
    val existing = table.filter(r => r.tournamentId === p.tournamentId && r.botId === p.botId)
    run(existing.result.headOption).flatMap {
      case Some(row) if row.userId == p.userId => Right(rowToParticipant(row))
      case Some(_)                             => Left("bot_already_claimed_by_another_user")
      case None =>
        run((table += participantToRow(p)).asTry).flatMap {
          case util.Success(_) => Right(p)
          case util.Failure(_) =>
            run(existing.result.headOption).flatMap {
              case Some(row) if row.userId == p.userId => Right(rowToParticipant(row))
              case Some(_)                             => Left("bot_already_claimed_by_another_user")
              case None                                => Left("Concurrent insert failed: participant not found after conflict")
            }
        }
    }

  private def run[T](action: DBIO[T]): Either[String, T] =
    try Right(Await.result(db.run(action), 30.seconds))
    catch case NonFatal(e) => Left(safeMessage(e))

  private def participantToRow(p: PublicTournamentParticipant): PublicTournamentParticipantRow =
    PublicTournamentParticipantRow(p.tournamentId, p.botId, p.botName, p.userId, p.displayName, Timestamp.from(p.joinedAt))

  private def rowToParticipant(r: PublicTournamentParticipantRow): PublicTournamentParticipant =
    PublicTournamentParticipant(r.tournamentId, r.botId, r.botName, r.userId, r.displayName, r.joinedAt.toInstant)

  private def safeMessage(e: Throwable): String =
    Option(e.getMessage).map(_.trim).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)

private[postgres] final case class PublicTournamentParticipantRow(
  tournamentId: String,
  botId: String,
  botName: String,
  userId: UUID,
  displayName: String,
  joinedAt: Timestamp
)

private[postgres] final class PublicTournamentParticipantTable(tag: Tag, schema: Option[String])
    extends Table[PublicTournamentParticipantRow](tag, schema, "public_tournament_participant"):
  def tournamentId = column[String]("tournament_id")
  def botId        = column[String]("bot_id")
  def botName      = column[String]("bot_name")
  def userId       = column[UUID]("user_id")
  def displayName  = column[String]("display_name")
  def joinedAt     = column[Timestamp]("joined_at")
  def pk           = primaryKey("pk_public_tournament_participant", (tournamentId, botId))
  def *            = (tournamentId, botId, botName, userId, displayName, joinedAt).mapTo[PublicTournamentParticipantRow]
