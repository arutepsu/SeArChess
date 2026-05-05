package chess.application.migration

import chess.application.session.model.SessionIds.{GameId, SessionId}

import java.time.Instant
import java.time.Duration
import java.util.UUID

final case class MigrationRunId(value: String) extends AnyVal

object MigrationRunId:
  def random(): MigrationRunId =
    MigrationRunId(UUID.randomUUID().toString)

enum MigrationMode:
  case DryRun
  case Execute
  case ValidateOnly

enum MigrationConflictPolicy:
  case SkipEquivalentElseConflict

enum MigrationReadPhase:
  case SourceGameLoad
  case TargetSessionLoad
  case TargetGameLoad

enum TargetComparison:
  case Missing
  case Equivalent
  case Conflict(reason: String)

final case class SessionMigrationCursor(value: String) extends AnyVal

final case class SessionMigrationBatch(
    sessions: List[chess.application.session.model.GameSession],
    nextCursor: Option[SessionMigrationCursor]
)

enum MigrationRunFailure:
  case ReaderFailure(message: String)
  case InvalidBatch(message: String)

enum MigrationFinalStatus:
  case Success
  case CompletedWithConflicts
  case Failed

enum MigrationValidationResult:
  case Passed
  case Failed

enum MigrationItemResult:
  case WouldMigrate(sessionId: SessionId, gameId: GameId)
  case Migrated(sessionId: SessionId, gameId: GameId)
  case SkippedEquivalent(sessionId: SessionId, gameId: GameId)
  case ValidatedEquivalent(sessionId: SessionId, gameId: GameId)
  case Conflict(sessionId: SessionId, gameId: GameId, reason: String)
  case ValidationMismatch(sessionId: SessionId, gameId: GameId, reason: String)
  case SourceGameStateMissing(sessionId: SessionId, gameId: GameId)
  case TargetWriteFailed(sessionId: SessionId, gameId: GameId, message: String)
  case ReadFailed(
      sessionId: SessionId,
      gameId: GameId,
      phase: MigrationReadPhase,
      message: String
  )

final case class MigrationReport(
                                  runId: MigrationRunId = MigrationRunId.random(),
                                  mode: MigrationMode,
                                  conflictPolicy: MigrationConflictPolicy = MigrationConflictPolicy.SkipEquivalentElseConflict,
                                  sourceAdapterName: String,
                                  targetAdapterName: String,
                                  startedAt: Instant,
                                  finishedAt: Instant,
                                  batchSize: Int,
                                  batchCount: Int,
                                  sourceSessionCount: Int,
                                  sourceGameLoadCount: Int,
                                  migratedCount: Int,
                                  skippedEquivalentCount: Int,
                                  conflictCount: Int,
                                  validatedEquivalentCount: Int,
                                  validationMismatchCount: Int,
                                  sourceDataMissingCount: Int,
                                  writeFailureCount: Int,
                                  readerFailureCount: Int,
                                  storageFailureCount: Int,
                                  validationRan: Boolean,
                                  itemResults: List[MigrationItemResult],
                                  fatalFailure: Option[MigrationRunFailure]
                                ):
  def wouldMigrateCount: Int =
    itemResults.count {
      case MigrationItemResult.WouldMigrate(_, _) => true
      case _                                      => false
    }

  def readFailureCount: Int =
    storageFailureCount

  def scannedCount: Int =
    sourceSessionCount

  def failedCount: Int =
    sourceDataMissingCount + writeFailureCount + readerFailureCount + storageFailureCount +
      fatalFailure.count:
        case MigrationRunFailure.InvalidBatch(_) => true
        case _                                   => false

  def duration: Duration =
    Duration.between(startedAt, finishedAt)

  def validationResult: Option[MigrationValidationResult] =
    mode match
      case MigrationMode.ValidateOnly =>
        if validationMismatchCount == 0 && failedCount == 0 then
          Some(MigrationValidationResult.Passed)
        else
          Some(MigrationValidationResult.Failed)
      case MigrationMode.Execute if validationRan =>
        if validationMismatchCount == 0 && failedCount == 0 then
          Some(MigrationValidationResult.Passed)
        else
          Some(MigrationValidationResult.Failed)
      case MigrationMode.DryRun | MigrationMode.Execute =>
        None

  def finalStatus: MigrationFinalStatus =
    if failedCount > 0 then
      MigrationFinalStatus.Failed
    else if conflictCount > 0 || validationMismatchCount > 0 then
      MigrationFinalStatus.CompletedWithConflicts
    else
      MigrationFinalStatus.Success

  def withValidationReport(validationReport: MigrationReport): MigrationReport =
    copy(
      finishedAt = validationReport.finishedAt,
      validatedEquivalentCount = validationReport.validatedEquivalentCount,
      validationMismatchCount = validationReport.validationMismatchCount,
      sourceDataMissingCount = sourceDataMissingCount + validationReport.sourceDataMissingCount,
      readerFailureCount = readerFailureCount + validationReport.readerFailureCount,
      storageFailureCount = storageFailureCount + validationReport.storageFailureCount,
      validationRan = true,
      fatalFailure = fatalFailure.orElse(validationReport.fatalFailure)
    )
object MigrationReport:

  def fromItems(
                 mode: MigrationMode,
                 conflictPolicy: MigrationConflictPolicy = MigrationConflictPolicy.SkipEquivalentElseConflict,
                 sourceAdapterName: String,
                 targetAdapterName: String,
                 startedAt: Instant,
                 finishedAt: Instant,
                 batchSize: Int,
                 batchCount: Int,
                 itemResults: List[MigrationItemResult],
                 fatalFailure: Option[MigrationRunFailure],
                 runId: MigrationRunId = MigrationRunId.random()
               ): MigrationReport =
    MigrationReport(
      runId = runId,
      mode = mode,
      conflictPolicy = conflictPolicy,
      sourceAdapterName = sourceAdapterName,
      targetAdapterName = targetAdapterName,
      startedAt = startedAt,
      finishedAt = finishedAt,
      batchSize = batchSize,
      batchCount = batchCount,
      sourceSessionCount = itemResults.size,
      sourceGameLoadCount = itemResults.size,
      migratedCount = itemResults.count {
        case MigrationItemResult.Migrated(_, _) => true
        case _                                  => false
      },
      skippedEquivalentCount = itemResults.count {
        case MigrationItemResult.SkippedEquivalent(_, _) => true
        case _                                           => false
      },
      conflictCount = itemResults.count {
        case MigrationItemResult.Conflict(_, _, _) => true
        case _                                     => false
      },
      validatedEquivalentCount = itemResults.count {
        case MigrationItemResult.ValidatedEquivalent(_, _) => true
        case _                                             => false
      },
      validationMismatchCount = itemResults.count {
        case MigrationItemResult.ValidationMismatch(_, _, _) => true
        case _                                               => false
      },
      sourceDataMissingCount = itemResults.count {
        case MigrationItemResult.SourceGameStateMissing(_, _) => true
        case _                                                => false
      },
      writeFailureCount = itemResults.count {
        case MigrationItemResult.TargetWriteFailed(_, _, _) => true
        case _                                              => false
      },
      readerFailureCount = fatalFailure.count {
        case MigrationRunFailure.ReaderFailure(_) => true
        case _                                    => false
      },
      storageFailureCount = itemResults.count {
        case MigrationItemResult.ReadFailed(_, _, _, _) => true
        case _                                          => false
      },
      validationRan = mode == MigrationMode.ValidateOnly,
      itemResults = itemResults,
      fatalFailure = fatalFailure
    )
