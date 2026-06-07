package chess.userservice.postgres

import chess.userservice.application.UserProfileRepository
import chess.userservice.domain.UserProfile
import slick.jdbc.PostgresProfile.api.*

import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.util.control.NonFatal
import java.sql.Timestamp
import java.util.UUID

class SlickUserProfileRepository(db: Database, schema: Option[String] = None) extends UserProfileRepository:

  private val schemaName = schema.map(_.trim).filter(_.nonEmpty)
  private val table      = TableQuery(tag => UserProfilesTable(tag, schemaName))

  override def findBySubject(sub: String): Either[String, Option[UserProfile]] =
    run(table.filter(_.keycloakSubject === sub).result.headOption).map(_.map(rowToProfile))

  override def insert(profile: UserProfile): Either[String, Unit] =
    run(table += profileToRow(profile)).map(_ => ())

  override def updateDisplayNameAndEmail(userId: UUID, displayName: String, email: Option[String]): Either[String, Unit] =
    val q = table
      .filter(_.userId === userId)
      .map(r => (r.displayName, r.email, r.updatedAt))
      .update((displayName, email, Timestamp.from(java.time.Instant.now())))
    run(q).map(_ => ())

  private def run[T](action: DBIO[T]): Either[String, T] =
    try Right(Await.result(db.run(action), 30.seconds))
    catch case NonFatal(e) => Left(safeMessage(e))

  private def profileToRow(p: UserProfile): UserProfileRow =
    UserProfileRow(p.userId, p.keycloakSubject, p.displayName, p.email, Timestamp.from(p.createdAt), Timestamp.from(p.updatedAt))

  private def rowToProfile(r: UserProfileRow): UserProfile =
    UserProfile(r.userId, r.keycloakSubject, r.displayName, r.email, r.createdAt.toInstant, r.updatedAt.toInstant)

  private def safeMessage(e: Throwable): String =
    Option(e.getMessage).map(_.trim).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)

private[postgres] final case class UserProfileRow(
    userId: UUID,
    keycloakSubject: String,
    displayName: String,
    email: Option[String],
    createdAt: Timestamp,
    updatedAt: Timestamp
)

private[postgres] final class UserProfilesTable(tag: Tag, schema: Option[String])
    extends Table[UserProfileRow](tag, schema, "user_profiles"):
  def userId          = column[UUID]("user_id", O.PrimaryKey)
  def keycloakSubject = column[String]("keycloak_subject")
  def displayName     = column[String]("display_name")
  def email           = column[Option[String]]("email")
  def createdAt       = column[Timestamp]("created_at")
  def updatedAt       = column[Timestamp]("updated_at")
  def *               = (userId, keycloakSubject, displayName, email, createdAt, updatedAt).mapTo[UserProfileRow]
