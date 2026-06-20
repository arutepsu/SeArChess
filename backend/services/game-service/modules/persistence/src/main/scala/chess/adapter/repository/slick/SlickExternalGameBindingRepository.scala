package chess.adapter.repository.slick

import _root_.slick.dbio.DBIO
import _root_.slick.jdbc.JdbcProfile
import chess.application.external.{BindingId, BindingStatus, ExternalGameBinding}
import chess.application.port.repository.{ExternalGameBindingRepository, RepositoryError}
import chess.application.session.model.ExternalPlatform
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

/** Generic Slick implementation of [[ExternalGameBindingRepository]].
  *
  * Postgres support is provided by [[chess.adapter.repository.postgres.PostgresExternalGameBindingRepository]],
  * which extends this class with the Postgres profile and schema support.
  */
class SlickExternalGameBindingRepository(
    val profile: JdbcProfile
)(
    db: profile.backend.Database,
    val bindingTables: SlickExternalBindingTables,
    timeout: Duration = Duration.Inf
) extends ExternalGameBindingRepository:

  import profile.api.*
  import bindingTables.*

  private given ExecutionContext = ExecutionContext.global

  override def create(binding: ExternalGameBinding): Either[RepositoryError, Unit] =
    run("Binding already exists for (platform, externalGameId)") {
      (ExternalBindings += toRow(binding)).map(_ => Right(()))
    }

  override def findByExternalId(
      platform: ExternalPlatform,
      externalGameId: String
  ): Either[RepositoryError, Option[ExternalGameBinding]] =
    run {
      ExternalBindings
        .filter(b => b.platform === platform.toString && b.externalGameId === externalGameId)
        .result
        .headOption
        .map {
          case None    => Right(None)
          case Some(r) => fromRow(r).map(Some.apply)
        }
    }

  override def findById(
      bindingId: BindingId
  ): Either[RepositoryError, Option[ExternalGameBinding]] =
    run {
      ExternalBindings
        .filter(_.bindingId === bindingId.value)
        .result
        .headOption
        .map {
          case None    => Right(None)
          case Some(r) => fromRow(r).map(Some.apply)
        }
    }

  override def markFinished(bindingId: BindingId, now: Instant): Either[RepositoryError, Unit] =
    run {
      ExternalBindings
        .filter(_.bindingId === bindingId.value)
        .map(b => (b.status, b.statusReason, b.updatedAt))
        .update(("Finished", None, Timestamp.from(now)))
        .map {
          case 0 => Left(RepositoryError.NotFound(bindingId.value.toString))
          case _ => Right(())
        }
    }

  override def markFailed(
      bindingId: BindingId,
      reason: String,
      now: Instant
  ): Either[RepositoryError, Unit] =
    run {
      ExternalBindings
        .filter(_.bindingId === bindingId.value)
        .map(b => (b.status, b.statusReason, b.updatedAt))
        .update(("Failed", Some(reason), Timestamp.from(now)))
        .map {
          case 0 => Left(RepositoryError.NotFound(bindingId.value.toString))
          case _ => Right(())
        }
    }

  // ── row conversions ───────────────────────────────────────────────────────

  private[repository] def toRow(binding: ExternalGameBinding): SlickExternalBindingRow =
    val (statusStr, reasonOpt) = statusColumns(binding.status)
    SlickExternalBindingRow(
      bindingId        = binding.bindingId.value,
      platform         = binding.platform.toString,
      externalGameId   = binding.externalGameId,
      internalGameId   = binding.internalGameId.value,
      sessionId        = binding.sessionId.value,
      ourActorId       = binding.ourActorId,
      status           = statusStr,
      statusReason     = reasonOpt,
      lastProcessedPly = binding.lastProcessedPly,
      createdAt        = Timestamp.from(binding.createdAt),
      updatedAt        = Timestamp.from(binding.updatedAt)
    )

  private def fromRow(row: SlickExternalBindingRow): Either[RepositoryError, ExternalGameBinding] =
    parsePlatform(row.platform).map { platform =>
      import chess.application.session.model.SessionIds.{GameId, SessionId}
      ExternalGameBinding(
        bindingId        = BindingId(row.bindingId),
        platform         = platform,
        externalGameId   = row.externalGameId,
        internalGameId   = GameId(row.internalGameId),
        sessionId        = SessionId(row.sessionId),
        ourActorId       = row.ourActorId,
        status           = parseStatus(row.status, row.statusReason),
        lastProcessedPly = row.lastProcessedPly,
        createdAt        = row.createdAt.toInstant,
        updatedAt        = row.updatedAt.toInstant
      )
    }

  private def statusColumns(status: BindingStatus): (String, Option[String]) = status match
    case BindingStatus.Active         => ("Active", None)
    case BindingStatus.Finished       => ("Finished", None)
    case BindingStatus.Failed(reason) => ("Failed", Some(reason))

  private def parseStatus(status: String, reason: Option[String]): BindingStatus = status match
    case "Active"   => BindingStatus.Active
    case "Finished" => BindingStatus.Finished
    case "Failed"   => BindingStatus.Failed(reason.getOrElse(""))
    case other      => BindingStatus.Failed(s"Unknown status in DB: $other")

  private def parsePlatform(value: String): Either[RepositoryError, ExternalPlatform] =
    ExternalPlatform.values
      .find(_.toString == value)
      .toRight(RepositoryError.StorageFailure(s"Unknown external platform in DB: $value"))

  // ── run helper ────────────────────────────────────────────────────────────

  private def run[A](action: DBIO[Either[RepositoryError, A]]): Either[RepositoryError, A] =
    SlickRepositorySupport.run(profile)(db, timeout)(action)

  private def run[A](
      conflictMsg: String
  )(
      action: DBIO[Either[RepositoryError, A]]
  ): Either[RepositoryError, A] =
    SlickRepositorySupport.run(profile)(db, timeout, Some(conflictMsg))(action)
