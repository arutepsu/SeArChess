package chess.tournamentservice.db

import chess.tournamentservice.*
import ujson.{Arr, Null, Obj, Str, Value}

import java.time.Instant

object TournamentOutboxEvent:

  private val tournamentJob = "tournament_job"
  private val analysisJob   = "analysis_job"

  def jobCreated(job: TournamentJob): OutboxEvent =
    OutboxEvent.pending(
      aggregateType = tournamentJob,
      aggregateId   = job.jobId,
      eventType     = "TournamentJobCreated",
      payloadJson   = ujson.write(Obj(
        "jobId"          -> job.jobId,
        "name"           -> job.name.map(Str(_)).getOrElse(Null),
        "selectedBotIds" -> Arr(job.selectedBotIds.map(Str(_))*),
        "mode"           -> job.mode,
        "repetitions"    -> job.repetitions.toDouble,
        "maxPly"         -> job.maxPly.toDouble,
        "seed"           -> job.seed.map(s => s.toDouble: Value).getOrElse(Null),
        "plannedGames"   -> job.plannedGames.toDouble,
        "createdAt"      -> job.createdAt.toString
      )),
      createdAt = job.createdAt
    )

  def jobStarted(jobId: String, startedAt: Instant): OutboxEvent =
    OutboxEvent.pending(
      aggregateType = tournamentJob,
      aggregateId   = jobId,
      eventType     = "TournamentJobStarted",
      payloadJson   = ujson.write(Obj(
        "jobId"     -> jobId,
        "startedAt" -> startedAt.toString
      )),
      createdAt = startedAt
    )

  def gameCompleted(jobId: String, completedGames: Int, plannedGames: Int, occurredAt: Instant): OutboxEvent =
    OutboxEvent.pending(
      aggregateType = tournamentJob,
      aggregateId   = jobId,
      eventType     = "TournamentGameCompleted",
      payloadJson   = ujson.write(Obj(
        "jobId"          -> jobId,
        "completedGames" -> completedGames.toDouble,
        "plannedGames"   -> plannedGames.toDouble,
        "occurredAt"     -> occurredAt.toString
      )),
      createdAt = occurredAt
    )

  def jobSucceeded(jobId: String, outputPath: Option[String], finishedAt: Instant): OutboxEvent =
    OutboxEvent.pending(
      aggregateType = tournamentJob,
      aggregateId   = jobId,
      eventType     = "TournamentJobSucceeded",
      payloadJson   = ujson.write(Obj(
        "jobId"      -> jobId,
        "outputPath" -> outputPath.map(Str(_)).getOrElse(Null),
        "finishedAt" -> finishedAt.toString
      )),
      createdAt = finishedAt
    )

  def jobFailed(jobId: String, errorMessage: String, finishedAt: Instant): OutboxEvent =
    OutboxEvent.pending(
      aggregateType = tournamentJob,
      aggregateId   = jobId,
      eventType     = "TournamentJobFailed",
      payloadJson   = ujson.write(Obj(
        "jobId"        -> jobId,
        "errorMessage" -> errorMessage,
        "finishedAt"   -> finishedAt.toString
      )),
      createdAt = finishedAt
    )

  def jobCancelled(jobId: String, finishedAt: Instant): OutboxEvent =
    OutboxEvent.pending(
      aggregateType = tournamentJob,
      aggregateId   = jobId,
      eventType     = "TournamentJobCancelled",
      payloadJson   = ujson.write(Obj(
        "jobId"      -> jobId,
        "finishedAt" -> finishedAt.toString
      )),
      createdAt = finishedAt
    )

  def analysisQueued(
      analysisJobId: String,
      tournamentJobId: String,
      inputPath: String,
      outputPath: String,
      createdAt: Instant
  ): OutboxEvent =
    OutboxEvent.pending(
      aggregateType = analysisJob,
      aggregateId   = analysisJobId,
      eventType     = "AnalysisJobQueued",
      payloadJson   = ujson.write(Obj(
        "analysisJobId"   -> analysisJobId,
        "tournamentJobId" -> tournamentJobId,
        "inputPath"       -> inputPath,
        "outputPath"      -> outputPath,
        "createdAt"       -> createdAt.toString
      )),
      createdAt = createdAt
    )

  def analysisStarted(analysisJobId: String, tournamentJobId: String, startedAt: Instant): OutboxEvent =
    OutboxEvent.pending(
      aggregateType = analysisJob,
      aggregateId   = analysisJobId,
      eventType     = "AnalysisJobStarted",
      payloadJson   = ujson.write(Obj(
        "analysisJobId"   -> analysisJobId,
        "tournamentJobId" -> tournamentJobId,
        "startedAt"       -> startedAt.toString
      )),
      createdAt = startedAt
    )

  def analysisSucceeded(
      analysisJobId: String,
      tournamentJobId: String,
      analyticsRunId: String,
      outputPath: String,
      finishedAt: Instant
  ): OutboxEvent =
    OutboxEvent.pending(
      aggregateType = analysisJob,
      aggregateId   = analysisJobId,
      eventType     = "AnalysisJobSucceeded",
      payloadJson   = ujson.write(Obj(
        "analysisJobId"   -> analysisJobId,
        "tournamentJobId" -> tournamentJobId,
        "analyticsRunId"  -> analyticsRunId,
        "outputPath"      -> outputPath,
        "finishedAt"      -> finishedAt.toString
      )),
      createdAt = finishedAt
    )

  def analysisFailed(
      analysisJobId: String,
      tournamentJobId: String,
      errorMessage: String,
      finishedAt: Instant
  ): OutboxEvent =
    OutboxEvent.pending(
      aggregateType = analysisJob,
      aggregateId   = analysisJobId,
      eventType     = "AnalysisJobFailed",
      payloadJson   = ujson.write(Obj(
        "analysisJobId"   -> analysisJobId,
        "tournamentJobId" -> tournamentJobId,
        "errorMessage"    -> errorMessage,
        "finishedAt"      -> finishedAt.toString
      )),
      createdAt = finishedAt
    )
