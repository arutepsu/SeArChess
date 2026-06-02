package chess.application.port.repository

import chess.application.external.{BindingId, BindingStatus, ExternalGameBinding}
import chess.application.session.model.ExternalPlatform
import java.time.Instant

/** Outbound port for reading and updating [[ExternalGameBinding]] records.
  *
  * ===Separation of write responsibilities===
  * This port handles single-record lookups and status updates. Writes that must be atomic with
  * session + game-state changes (move application, game finish) belong in
  * [[ExternalGameCommandStore]], which wraps those multi-table writes in a single transaction.
  *
  * [[create]] is intentionally only in this trait (not in [[ExternalGameCommandStore]]) for the
  * simple case where only the binding record needs to be inserted. In practice,
  * [[ExternalGameCommandStore.createWithBinding]] is used for the full atomic creation flow.
  *
  * ===Uniqueness invariant===
  * Implementations must enforce a UNIQUE constraint on `(platform, externalGameId)` and return
  * [[RepositoryError.Conflict]] if a duplicate creation is attempted.
  */
trait ExternalGameBindingRepository:

  /** Persist a new binding record.
    *
    * Returns [[RepositoryError.Conflict]] if a binding with the same `(platform, externalGameId)`
    * already exists.
    */
  def create(binding: ExternalGameBinding): Either[RepositoryError, Unit]

  /** Find the binding for a given external game, or [[None]] if no binding exists. */
  def findByExternalId(
      platform: ExternalPlatform,
      externalGameId: String
  ): Either[RepositoryError, Option[ExternalGameBinding]]

  /** Find a binding by its internal [[BindingId]], or [[None]] if not found. */
  def findById(bindingId: BindingId): Either[RepositoryError, Option[ExternalGameBinding]]

  /** Advance `status` to [[BindingStatus.Finished]] and update `updatedAt`. */
  def markFinished(bindingId: BindingId, now: Instant): Either[RepositoryError, Unit]

  /** Advance `status` to [[BindingStatus.Failed]] with a reason string and update `updatedAt`. */
  def markFailed(bindingId: BindingId, reason: String, now: Instant): Either[RepositoryError, Unit]
