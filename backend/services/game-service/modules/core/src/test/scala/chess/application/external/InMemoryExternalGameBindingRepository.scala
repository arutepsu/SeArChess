package chess.application.external

import chess.application.port.repository.{ExternalGameBindingRepository, RepositoryError}
import chess.application.session.model.ExternalPlatform
import java.time.Instant
import scala.collection.mutable

/** In-memory [[ExternalGameBindingRepository]] for use in tests.
  *
  * Enforces the `(platform, externalGameId)` uniqueness constraint in memory.
  * Exposes [[advancePly]] as an extra package-private method for use by
  * [[InMemoryExternalGameCommandStore]].
  */
class InMemoryExternalGameBindingRepository extends ExternalGameBindingRepository:

  private val store = mutable.HashMap.empty[BindingId, ExternalGameBinding]

  override def create(binding: ExternalGameBinding): Either[RepositoryError, Unit] =
    val duplicate = store.values.exists(b =>
      b.platform == binding.platform && b.externalGameId == binding.externalGameId
    )
    if duplicate then
      Left(RepositoryError.Conflict(
        s"Binding already exists for (${binding.platform}, ${binding.externalGameId})"
      ))
    else
      store(binding.bindingId) = binding
      Right(())

  override def findByExternalId(
      platform: ExternalPlatform,
      externalGameId: String
  ): Either[RepositoryError, Option[ExternalGameBinding]] =
    Right(store.values.find(b => b.platform == platform && b.externalGameId == externalGameId))

  override def findById(bindingId: BindingId): Either[RepositoryError, Option[ExternalGameBinding]] =
    Right(store.get(bindingId))

  override def markFinished(bindingId: BindingId, now: Instant): Either[RepositoryError, Unit] =
    store.get(bindingId) match
      case None    => Left(RepositoryError.NotFound(bindingId.value.toString))
      case Some(b) => store(bindingId) = b.copy(status = BindingStatus.Finished, updatedAt = now); Right(())

  override def markFailed(bindingId: BindingId, reason: String, now: Instant): Either[RepositoryError, Unit] =
    store.get(bindingId) match
      case None    => Left(RepositoryError.NotFound(bindingId.value.toString))
      case Some(b) => store(bindingId) = b.copy(status = BindingStatus.Failed(reason), updatedAt = now); Right(())

  /** Advance `lastProcessedPly` to `newPly`. Used by [[InMemoryExternalGameCommandStore]]. */
  def advancePly(bindingId: BindingId, newPly: Int, now: Instant): Either[RepositoryError, Unit] =
    store.get(bindingId) match
      case None    => Left(RepositoryError.NotFound(bindingId.value.toString))
      case Some(b) => store(bindingId) = b.copy(lastProcessedPly = newPly, updatedAt = now); Right(())

  /** Snapshot of all stored bindings (for test assertions). */
  def all: List[ExternalGameBinding] = store.values.toList
