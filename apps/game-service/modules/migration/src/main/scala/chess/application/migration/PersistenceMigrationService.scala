package chess.application.migration

import chess.application.port.repository.RepositoryError
import chess.application.session.model.GameSession
import chess.domain.state.GameState

import java.time.Instant
import scala.collection.mutable.ListBuffer

final class PersistenceMigrationService(
    clock: () => Instant = () => Instant.now()
):

  def run(
      source: MigrationSourceAdapter,
      target: MigrationTargetAdapter,
      mode: MigrationMode,
      batchSize: Int,
      conflictPolicy: MigrationConflictPolicy =
        MigrationConflictPolicy.SkipEquivalentElseConflict
  ): MigrationReport =
    require(batchSize > 0, "batchSize must be positive")

    val startedAt = clock()
    val itemResults = ListBuffer.empty[MigrationItemResult]

    var batchCount = 0
    var cursor: Option[SessionMigrationCursor] = None
    var fatalFailure = Option.empty[MigrationRunFailure]
    var continue = true

    while continue do
      source.sessionReader.readBatch(cursor, batchSize) match
        case Left(error) =>
          fatalFailure = Some(MigrationRunFailure.ReaderFailure(errorMessage(error)))
          continue = false

        case Right(batch) =>
          batchCount += 1

          if batch.sessions.isEmpty then
            batch.nextCursor match
              case Some(_) =>
                fatalFailure = Some(
                  MigrationRunFailure.InvalidBatch(
                    "SessionMigrationReader returned an empty non-final batch"
                  )
                )
              case None =>
                ()
            continue = false
          else
            batch.sessions.foreach { session =>
              itemResults += migrateSession(session, source, target, mode, conflictPolicy)
            }

            cursor = batch.nextCursor
            continue = batch.nextCursor.nonEmpty

    MigrationReport.fromItems(
      mode = mode,
      conflictPolicy = conflictPolicy,
      sourceAdapterName = source.name,
      targetAdapterName = target.name,
      startedAt = startedAt,
      finishedAt = clock(),
      batchSize = batchSize,
      batchCount = batchCount,
      itemResults = itemResults.toList,
      fatalFailure = fatalFailure
    )

  private def migrateSession(
      session: GameSession,
      source: MigrationSourceAdapter,
      target: MigrationTargetAdapter,
      mode: MigrationMode,
      conflictPolicy: MigrationConflictPolicy
  ): MigrationItemResult =
    source.gameRepository.load(session.gameId) match
      case Left(RepositoryError.NotFound(_)) =>
        MigrationItemResult.SourceGameStateMissing(session.sessionId, session.gameId)

      case Left(error) =>
        MigrationItemResult.ReadFailed(
          session.sessionId,
          session.gameId,
          MigrationReadPhase.SourceGameLoad,
          errorMessage(error)
        )

      case Right(state) =>
        compareTarget(session, state, target, conflictPolicy) match
          case Left(readFailure) => readFailure
          case Right(TargetComparison.Missing) =>
            handleMissingTarget(session, state, target, mode)
          case Right(TargetComparison.Equivalent) =>
            mode match
              case MigrationMode.ValidateOnly =>
                MigrationItemResult.ValidatedEquivalent(session.sessionId, session.gameId)
              case MigrationMode.DryRun | MigrationMode.Execute =>
                MigrationItemResult.SkippedEquivalent(session.sessionId, session.gameId)
          case Right(TargetComparison.Conflict(reason)) =>
            mode match
              case MigrationMode.ValidateOnly =>
                MigrationItemResult.ValidationMismatch(session.sessionId, session.gameId, reason)
              case MigrationMode.DryRun | MigrationMode.Execute =>
                MigrationItemResult.Conflict(session.sessionId, session.gameId, reason)

  private def handleMissingTarget(
      session: GameSession,
      state: GameState,
      target: MigrationTargetAdapter,
      mode: MigrationMode
  ): MigrationItemResult =
    mode match
      case MigrationMode.DryRun =>
        MigrationItemResult.WouldMigrate(session.sessionId, session.gameId)
      case MigrationMode.Execute =>
        target.store.save(session, state) match
          case Right(_) =>
            MigrationItemResult.Migrated(session.sessionId, session.gameId)
          case Left(error) =>
            MigrationItemResult.TargetWriteFailed(
              session.sessionId,
              session.gameId,
              errorMessage(error)
            )
      case MigrationMode.ValidateOnly =>
        MigrationItemResult.ValidationMismatch(
          session.sessionId,
          session.gameId,
          "Target aggregate is missing"
        )

  private def compareTarget(
      sourceSession: GameSession,
      sourceState: GameState,
      target: MigrationTargetAdapter,
      conflictPolicy: MigrationConflictPolicy
  ): Either[MigrationItemResult, TargetComparison] =
    target.sessionRepository.load(sourceSession.sessionId) match
      case Left(RepositoryError.NotFound(_)) =>
        compareWithoutTargetSession(sourceSession, sourceState, target, conflictPolicy)
      case Left(error) =>
        Left(
          MigrationItemResult.ReadFailed(
            sourceSession.sessionId,
            sourceSession.gameId,
            MigrationReadPhase.TargetSessionLoad,
            errorMessage(error)
          )
        )
      case Right(targetSession) =>
        target.gameRepository.load(sourceSession.gameId) match
          case Left(RepositoryError.NotFound(_)) =>
            Right(TargetComparison.Conflict("Target contains session but missing game state"))
          case Left(error) =>
            Left(
              MigrationItemResult.ReadFailed(
                sourceSession.sessionId,
                sourceSession.gameId,
                MigrationReadPhase.TargetGameLoad,
                errorMessage(error)
              )
            )
          case Right(targetState) =>
            Right(compareLoadedTarget(sourceSession, sourceState, targetSession, targetState, conflictPolicy))

  private def compareWithoutTargetSession(
      sourceSession: GameSession,
      sourceState: GameState,
      target: MigrationTargetAdapter,
      conflictPolicy: MigrationConflictPolicy
  ): Either[MigrationItemResult, TargetComparison] =
    target.gameRepository.load(sourceSession.gameId) match
      case Left(RepositoryError.NotFound(_)) =>
        Right(TargetComparison.Missing)
      case Left(error) =>
        Left(
          MigrationItemResult.ReadFailed(
            sourceSession.sessionId,
            sourceSession.gameId,
            MigrationReadPhase.TargetGameLoad,
            errorMessage(error)
          )
        )
      case Right(_) =>
        Right(TargetComparison.Conflict("Target contains game state but missing session"))

  private def compareLoadedTarget(
      sourceSession: GameSession,
      sourceState: GameState,
      targetSession: GameSession,
      targetState: GameState,
      conflictPolicy: MigrationConflictPolicy
  ): TargetComparison =
    conflictPolicy match
      case MigrationConflictPolicy.SkipEquivalentElseConflict =>
        if targetSession == sourceSession && targetState == sourceState then
          TargetComparison.Equivalent
        else
          TargetComparison.Conflict("Target aggregate differs from source")

  private def errorMessage(error: RepositoryError): String =
    error match
      case RepositoryError.NotFound(id)       => s"Not found: $id"
      case RepositoryError.Conflict(message)  => message
      case RepositoryError.StorageFailure(msg) => msg
