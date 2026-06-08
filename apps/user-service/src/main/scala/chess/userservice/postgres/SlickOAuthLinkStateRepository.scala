package chess.userservice.postgres

import chess.userservice.application.OAuthLinkStateRepository
import chess.userservice.domain.OAuthLinkState
import slick.jdbc.PostgresProfile.api.*

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*
import scala.util.control.NonFatal
import java.sql.Timestamp
import java.util.UUID

class SlickOAuthLinkStateRepository(db: Database, schema: Option[String] = None)
    extends OAuthLinkStateRepository:

  private val schemaName = schema.map(_.trim).filter(_.nonEmpty)
  private val table      = TableQuery(tag => OAuthLinkStatesTable(tag, schemaName))

  override def insert(state: OAuthLinkState): Either[String, Unit] =
    run(table += stateToRow(state)).map(_ => ())

  /** Atomically finds then deletes the row in a single transaction. */
  override def findAndDelete(state: String): Either[String, Option[OAuthLinkState]] =
    val q      = table.filter(_.state === state)
    val action = (for
      found <- q.result.headOption
      _     <- q.delete
    yield found).transactionally
    run(action).map(_.map(rowToState))

  private def run[T](action: DBIO[T]): Either[String, T] =
    try Right(Await.result(db.run(action), 30.seconds))
    catch case NonFatal(e) => Left(safeMessage(e))

  private def stateToRow(s: OAuthLinkState): OAuthLinkStateRow =
    OAuthLinkStateRow(
      s.state, s.userId, s.codeVerifier, s.redirectAfterSuccess,
      Timestamp.from(s.expiresAt), Timestamp.from(s.createdAt)
    )

  private def rowToState(r: OAuthLinkStateRow): OAuthLinkState =
    OAuthLinkState(
      r.state, r.userId, r.codeVerifier, r.redirectAfterSuccess,
      r.expiresAt.toInstant, r.createdAt.toInstant
    )

  private def safeMessage(e: Throwable): String =
    Option(e.getMessage).map(_.trim).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)

private[postgres] final case class OAuthLinkStateRow(
    state: String,
    userId: UUID,
    codeVerifier: String,
    redirectAfterSuccess: String,
    expiresAt: Timestamp,
    createdAt: Timestamp
)

private[postgres] final class OAuthLinkStatesTable(tag: Tag, schema: Option[String])
    extends Table[OAuthLinkStateRow](tag, schema, "oauth_link_states"):
  def state                = column[String]("state", O.PrimaryKey)
  def userId               = column[UUID]("user_id")
  def codeVerifier         = column[String]("code_verifier")
  def redirectAfterSuccess = column[String]("redirect_after_success")
  def expiresAt            = column[Timestamp]("expires_at")
  def createdAt            = column[Timestamp]("created_at")
  def * = (state, userId, codeVerifier, redirectAfterSuccess, expiresAt, createdAt)
    .mapTo[OAuthLinkStateRow]
