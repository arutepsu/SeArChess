package chess.userservice.postgres

import chess.userservice.application.ExternalAccountLinkRepository
import chess.userservice.domain.ExternalAccountLink
import slick.jdbc.PostgresProfile.api.*

import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.util.control.NonFatal
import java.sql.Timestamp
import java.util.UUID

class SlickExternalAccountLinkRepository(db: Database, schema: Option[String] = None)
    extends ExternalAccountLinkRepository:

  private val schemaName = schema.map(_.trim).filter(_.nonEmpty)
  private val table      = TableQuery(tag => ExternalAccountLinksTable(tag, schemaName))

  override def findAllByUserId(userId: UUID): Either[String, List[ExternalAccountLink]] =
    run(table.filter(_.userId === userId).result).map(_.map(rowToLink).toList)

  override def findByUserAndProvider(userId: UUID, provider: String): Either[String, Option[ExternalAccountLink]] =
    run(table.filter(r => r.userId === userId && r.provider === provider).result.headOption).map(_.map(rowToLink))

  override def insert(link: ExternalAccountLink): Either[String, Unit] =
    run(table += linkToRow(link)).map(_ => ())

  override def update(link: ExternalAccountLink): Either[String, Unit] =
    val q = table
      .filter(_.linkId === link.linkId)
      .map(r => (r.externalId, r.externalUsername, r.verified, r.verificationSource, r.linkedAt))
      .update((link.externalId, link.externalUsername, link.verified, link.verificationSource, Timestamp.from(link.linkedAt)))
    run(q).map(_ => ())

  override def delete(userId: UUID, provider: String): Either[String, Unit] =
    run(table.filter(r => r.userId === userId && r.provider === provider).delete).map(_ => ())

  private def run[T](action: DBIO[T]): Either[String, T] =
    try Right(Await.result(db.run(action), 30.seconds))
    catch case NonFatal(e) => Left(safeMessage(e))

  private def linkToRow(l: ExternalAccountLink): ExternalAccountLinkRow =
    ExternalAccountLinkRow(
      l.linkId, l.userId, l.provider, l.externalId, l.externalUsername,
      l.verified, l.verificationSource, Timestamp.from(l.linkedAt)
    )

  private def rowToLink(r: ExternalAccountLinkRow): ExternalAccountLink =
    ExternalAccountLink(
      r.linkId, r.userId, r.provider, r.externalId, r.externalUsername,
      r.verified, r.verificationSource, r.linkedAt.toInstant
    )

  private def safeMessage(e: Throwable): String =
    Option(e.getMessage).map(_.trim).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)

private[postgres] final case class ExternalAccountLinkRow(
    linkId: UUID,
    userId: UUID,
    provider: String,
    externalId: Option[String],
    externalUsername: String,
    verified: Boolean,
    verificationSource: String,
    linkedAt: Timestamp
)

private[postgres] final class ExternalAccountLinksTable(tag: Tag, schema: Option[String])
    extends Table[ExternalAccountLinkRow](tag, schema, "external_account_links"):
  def linkId             = column[UUID]("link_id", O.PrimaryKey)
  def userId             = column[UUID]("user_id")
  def provider           = column[String]("provider")
  def externalId         = column[Option[String]]("external_id")
  def externalUsername   = column[String]("external_username")
  def verified           = column[Boolean]("verified")
  def verificationSource = column[String]("verification_source")
  def linkedAt           = column[Timestamp]("linked_at")
  def * = (linkId, userId, provider, externalId, externalUsername, verified, verificationSource, linkedAt)
    .mapTo[ExternalAccountLinkRow]
