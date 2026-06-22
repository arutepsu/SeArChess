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

  override def insertIfAbsent(ownership: TournamentBotOwnership): Either[String, TournamentBotOwnership] =
    // ON CONFLICT DO NOTHING: if the (user_id, bot_id) pair already exists, return the existing row.
    val existing = table.filter(r => r.userId === ownership.userId && r.botId === ownership.botId)
    run(existing.result.headOption).flatMap {
      case Some(row) => Right(rowToOwnership(row))
      case None =>
        run((table += ownershipToRow(ownership)).asTry).flatMap {
          case util.Success(_) => Right(ownership)
          case util.Failure(_) =>
            // Lost race — another insert won; return existing
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
    TournamentBotOwnershipRow(o.id, o.userId, o.botId, o.botName, Timestamp.from(o.createdAt))

  private def rowToOwnership(r: TournamentBotOwnershipRow): TournamentBotOwnership =
    TournamentBotOwnership(r.id, r.userId, r.botId, r.botName, r.createdAt.toInstant)

  private def safeMessage(e: Throwable): String =
    Option(e.getMessage).map(_.trim).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)

private[postgres] final case class TournamentBotOwnershipRow(
  id: UUID,
  userId: UUID,
  botId: String,
  botName: String,
  createdAt: Timestamp
)

private[postgres] final class TournamentBotOwnershipTable(tag: Tag, schema: Option[String])
    extends Table[TournamentBotOwnershipRow](tag, schema, "tournament_bot_ownership"):
  def id        = column[UUID]("id", O.PrimaryKey)
  def userId    = column[UUID]("user_id")
  def botId     = column[String]("bot_id")
  def botName   = column[String]("bot_name")
  def createdAt = column[Timestamp]("created_at")
  def *         = (id, userId, botId, botName, createdAt).mapTo[TournamentBotOwnershipRow]
