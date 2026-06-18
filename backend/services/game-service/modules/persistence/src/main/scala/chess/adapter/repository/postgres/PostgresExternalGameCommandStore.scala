package chess.adapter.repository.postgres

import _root_.slick.dbio.DBIO
import chess.adapter.repository.slick.{
  SlickGameStateJson,
  SlickRepositorySupport,
  SlickSessionMapper
}
import chess.application.external.{BindingId, BindingStatus, ExternalGameBinding}
import chess.application.port.repository.{ExternalGameCommandStore, RepositoryError}
import chess.application.session.model.GameSession
import chess.domain.state.GameState
import slick.jdbc.PostgresProfile.api.*

import java.sql.Timestamp
import java.time.Instant
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

/** Postgres-backed [[ExternalGameCommandStore]].
  *
  * All three write operations — session row, game-state row, and binding row/update — are wrapped
  * in a single Slick `.transactionally` block so they either all land or none do.
  *
  * ===Atomicity===
  *   - [[createWithBinding]]: `insertOrUpdate` session + game state, then plain `insert` binding.
  *     The unique constraint on `(platform, external_game_id)` raises a PSQLException (SQLState
  *     23505) if a duplicate is attempted; the whole transaction is rolled back.
  *   - [[saveMoveWithPlyAdvance]]: conditional `UPDATE` binding first (only if
  *     `status = 'Active'` and `last_processed_ply < newPly`), then `insertOrUpdate` session +
  *     game state. Returns [[RepositoryError.Conflict]] when zero rows are updated, before any
  *     session/game writes are attempted.
  *   - [[saveFinishedWithBinding]]: conditional `UPDATE` binding first (only if
  *     `status = 'Active'`), then `insertOrUpdate` session + game state.
  */
class PostgresExternalGameCommandStore(
    db: Database,
    timeout: Duration = Duration.Inf,
    schema: Option[String] = None
) extends ExternalGameCommandStore:

  private val profile = PostgresSlickSupport.profile
  private val tables  = PostgresSlickSupport.tablesFor(schema)
  private val bTables = PostgresSlickSupport.externalBindingTablesFor(schema)

  import profile.api.*
  import tables.{GameStates, Sessions, SlickGameStateRow}
  import bTables.{ExternalBindings, SlickExternalBindingRow}

  private given ExecutionContext = ExecutionContext.global

  override def createWithBinding(
      session: GameSession,
      state: GameState,
      binding: ExternalGameBinding
  ): Either[RepositoryError, Unit] =
    run("Binding already exists for (platform, externalGameId)") {
      val sessionRow = SlickSessionMapper.toRow(tables)(session)
      val gameRow    = SlickGameStateRow(session.gameId.value, SlickGameStateJson.encode(state))
      val bindRow    = toBindingRow(binding)
      (for
        _ <- Sessions.insertOrUpdate(sessionRow)
        _ <- GameStates.insertOrUpdate(gameRow)
        _ <- ExternalBindings += bindRow
      yield Right(())).transactionally
    }

  override def saveMoveWithPlyAdvance(
      session: GameSession,
      state: GameState,
      bindingId: BindingId,
      newPly: Int,
      now: Instant
  ): Either[RepositoryError, Unit] =
    run {
      val sessionRow = SlickSessionMapper.toRow(tables)(session)
      val gameRow    = SlickGameStateRow(session.gameId.value, SlickGameStateJson.encode(state))
      (for
        rowsUpdated <- ExternalBindings
          .filter(b =>
            b.bindingId === bindingId.value &&
              b.status === "Active" &&
              b.lastProcessedPly < newPly
          )
          .map(b => (b.lastProcessedPly, b.updatedAt))
          .update((newPly, Timestamp.from(now)))
        result <-
        if rowsUpdated == 0 then
          DBIO.successful(Left(RepositoryError.Conflict(
            s"Ply advance rejected for binding ${bindingId.value}: binding not Active or ply $newPly is not greater than current"
          )))
        else
          for
            _ <- Sessions.insertOrUpdate(sessionRow)
            _ <- GameStates.insertOrUpdate(gameRow)
          yield Right(())
      yield result
      ).transactionally
    }

  override def saveFinishedWithBinding(
      session: GameSession,
      state: GameState,
      bindingId: BindingId,
      now: Instant
  ): Either[RepositoryError, Unit] =
    run {
      val sessionRow = SlickSessionMapper.toRow(tables)(session)
      val gameRow    = SlickGameStateRow(session.gameId.value, SlickGameStateJson.encode(state))
      (for
        rowsUpdated <- ExternalBindings
          .filter(b => b.bindingId === bindingId.value && b.status === "Active")
          .map(b => (b.status, b.statusReason, b.updatedAt))
          .update(("Finished", None, Timestamp.from(now)))
        result <-
        if rowsUpdated == 0 then
          DBIO.successful(Left(RepositoryError.Conflict(
            s"Cannot finish binding ${bindingId.value}: not found or not Active"
          )))
        else
          for
            _ <- Sessions.insertOrUpdate(sessionRow)
            _ <- GameStates.insertOrUpdate(gameRow)
          yield Right(())
      yield result
      ).transactionally
    }

  private def toBindingRow(binding: ExternalGameBinding): SlickExternalBindingRow =
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

  private def statusColumns(status: BindingStatus): (String, Option[String]) = status match
    case BindingStatus.Active         => ("Active", None)
    case BindingStatus.Finished       => ("Finished", None)
    case BindingStatus.Failed(reason) => ("Failed", Some(reason))

  private def run[A](action: DBIO[Either[RepositoryError, A]]): Either[RepositoryError, A] =
    SlickRepositorySupport.run(profile)(db, timeout)(action)

  private def run[A](
      conflictMsg: String
  )(
      action: DBIO[Either[RepositoryError, A]]
  ): Either[RepositoryError, A] =
    SlickRepositorySupport.run(profile)(db, timeout, Some(conflictMsg))(action)
