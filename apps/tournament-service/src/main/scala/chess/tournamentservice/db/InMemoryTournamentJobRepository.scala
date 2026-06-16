package chess.tournamentservice.db

import cats.effect.{IO, Ref}
import chess.tournamentservice.*

import java.time.Instant
import java.util.UUID

final class InMemoryTournamentJobRepository private (
    jobs: Ref[IO, Map[String, TournamentJob]],
    outbox: Ref[IO, Vector[OutboxEvent]],
    analysisIds: Ref[IO, Map[String, String]]
) extends TournamentJobRepository:

  def recordedEvents(): IO[Vector[OutboxEvent]] = outbox.get

  def createJob(job: TournamentJob): IO[Unit] =
    jobs.update(_ + (job.jobId -> job)) >>
    outbox.update(_ :+ TournamentOutboxEvent.jobCreated(job))

  def listJobs(): IO[List[TournamentJob]] =
    jobs.get.map(_.values.toList.sortBy(j => -j.createdAt.toEpochMilli))

  def getJob(jobId: String): IO[Option[TournamentJob]] =
    jobs.get.map(_.get(jobId))

  def markRunning(jobId: String): IO[Boolean] =
    for
      now     <- IO(Instant.now())
      didMark <- jobs.modify { current =>
        current.get(jobId) match
          case Some(job) if job.status == TournamentJobStatus.Queued =>
            val updated = job.copy(status = TournamentJobStatus.Running, startedAt = Some(now))
            (current.updated(jobId, updated), true)
          case _ =>
            (current, false)
      }
      _ <- if didMark then outbox.update(_ :+ TournamentOutboxEvent.jobStarted(jobId, now))
           else IO.unit
    yield didMark

  def incrementCompletedGames(jobId: String): IO[Unit] =
    for
      now        <- IO(Instant.now())
      updatedOpt <- jobs.modify { current =>
        current.get(jobId) match
          case Some(job) =>
            val updated = job.copy(completedGames = job.completedGames + 1)
            (current.updated(jobId, updated), Some(updated))
          case None =>
            (current, None)
      }
      _ <- updatedOpt match
        case Some(job) =>
          outbox.update(_ :+ TournamentOutboxEvent.gameCompleted(jobId, job.completedGames, job.plannedGames, now))
        case None => IO.unit
    yield ()

  def markSucceeded(jobId: String): IO[Unit] =
    for
      now    <- IO(Instant.now())
      jobOpt <- jobs.modify { current =>
        current.get(jobId) match
          case Some(job) if job.status == TournamentJobStatus.Running =>
            val updated = job.copy(
              status        = TournamentJobStatus.Succeeded,
              finishedAt    = Some(now),
              resultSummary = Some(s"${job.completedGames}/${job.plannedGames} games completed")
            )
            (current.updated(jobId, updated), Some(updated))
          case _ => (current, None)
      }
      _ <- jobOpt match
        case Some(job) => outbox.update(_ :+ TournamentOutboxEvent.jobSucceeded(jobId, job.outputPath, now))
        case None      => IO.unit
    yield ()

  def markFailed(jobId: String, message: String): IO[Unit] =
    for
      now     <- IO(Instant.now())
      didMark <- jobs.modify { current =>
        current.get(jobId) match
          case Some(job) =>
            val updated = job.copy(
              status       = TournamentJobStatus.Failed,
              finishedAt   = Some(now),
              errorMessage = Some(message)
            )
            (current.updated(jobId, updated), true)
          case None => (current, false)
      }
      _ <- if didMark then outbox.update(_ :+ TournamentOutboxEvent.jobFailed(jobId, message, now))
           else IO.unit
    yield ()

  def cancelJob(jobId: String): IO[Option[TournamentJob]] =
    for
      now    <- IO(Instant.now())
      result <- jobs.modify { current =>
        current.get(jobId) match
          case None =>
            (current, (None, false))
          case Some(job) if TournamentJobStatus.terminal(job.status) =>
            (current, (Some(job), false))
          case Some(job) =>
            val updated = job.copy(
              status        = TournamentJobStatus.Cancelled,
              finishedAt    = if job.status == TournamentJobStatus.Queued then Some(now) else job.finishedAt,
              errorMessage  = None,
              resultSummary = Some("cancelled")
            )
            val emitNow = job.status == TournamentJobStatus.Queued
            (current.updated(jobId, updated), (Some(updated), emitNow))
      }
      (jobOpt, emitNow) = result
      _ <- if emitNow then outbox.update(_ :+ TournamentOutboxEvent.jobCancelled(jobId, now))
           else IO.unit
    yield jobOpt

  def finalizeCancelled(jobId: String): IO[Unit] =
    for
      now <- IO(Instant.now())
      _   <- jobs.update { current =>
        current.get(jobId) match
          case Some(job) =>
            current.updated(jobId, job.copy(finishedAt = Some(now), resultSummary = Some("cancelled")))
          case None => current
      }
      _   <- outbox.update(_ :+ TournamentOutboxEvent.jobCancelled(jobId, now))
    yield ()

  def queueAnalysis(jobId: String, inputPath: String, analyticsOutputPath: String): IO[Unit] =
    for
      now        <- IO(Instant.now())
      analysisId <- IO(UUID.randomUUID().toString)
      _          <- jobs.update { current =>
        current.get(jobId) match
          case Some(job) =>
            current.updated(
              jobId,
              job.copy(
                analysisStatus        = TournamentAnalysisStatus.Queued,
                analyticsRunId        = None,
                analyticsOutputPath   = Some(analyticsOutputPath),
                analyticsErrorMessage = None
              )
            )
          case None => current
      }
      _          <- analysisIds.update(_ + (jobId -> analysisId))
      _          <- outbox.update(_ :+ TournamentOutboxEvent.analysisQueued(analysisId, jobId, inputPath, analyticsOutputPath, now))
    yield ()

  def markAnalysisRunning(jobId: String): IO[Option[TournamentAnalyticsRunRequest]] =
    for
      now    <- IO(Instant.now())
      aIdOpt <- analysisIds.get.map(_.get(jobId))
      result <- jobs.modify { current =>
        current.get(jobId) match
          case Some(job) if job.analysisStatus == TournamentAnalysisStatus.Queued =>
            val updated = job.copy(analysisStatus = TournamentAnalysisStatus.Running)
            val request = for
              input  <- job.outputPath
              output <- job.analyticsOutputPath
            yield TournamentAnalyticsRunRequest(jobId, input, output)
            (current.updated(jobId, updated), request)
          case _ =>
            (current, None)
      }
      _ <- result match
        case Some(_) =>
          aIdOpt match
            case Some(aId) => outbox.update(_ :+ TournamentOutboxEvent.analysisStarted(aId, jobId, now))
            case None      => IO.unit
        case None => IO.unit
    yield result

  def markAnalysisSucceeded(jobId: String, result: TournamentAnalyticsRunResult): IO[Unit] =
    for
      now    <- IO(Instant.now())
      aIdOpt <- analysisIds.get.map(_.get(jobId))
      _      <- jobs.update { current =>
        current.get(jobId) match
          case Some(job) =>
            current.updated(
              jobId,
              job.copy(
                analysisStatus        = TournamentAnalysisStatus.Succeeded,
                analyticsRunId        = Some(result.analyticsRunId),
                analyticsOutputPath   = Some(result.outputPath),
                analyticsErrorMessage = None
              )
            )
          case None => current
      }
      _      <- aIdOpt match
        case Some(aId) =>
          outbox.update(_ :+ TournamentOutboxEvent.analysisSucceeded(aId, jobId, result.analyticsRunId, result.outputPath, now))
        case None => IO.unit
    yield ()

  def markAnalysisFailed(jobId: String, message: String): IO[Unit] =
    for
      now    <- IO(Instant.now())
      aIdOpt <- analysisIds.get.map(_.get(jobId))
      _      <- jobs.update { current =>
        current.get(jobId) match
          case Some(job) =>
            current.updated(
              jobId,
              job.copy(
                analysisStatus        = TournamentAnalysisStatus.Failed,
                analyticsErrorMessage = Some(message)
              )
            )
          case None => current
      }
      _      <- aIdOpt match
        case Some(aId) =>
          outbox.update(_ :+ TournamentOutboxEvent.analysisFailed(aId, jobId, message, now))
        case None => IO.unit
    yield ()

object InMemoryTournamentJobRepository:
  def create(): IO[InMemoryTournamentJobRepository] =
    for
      jobs        <- Ref.of[IO, Map[String, TournamentJob]](Map.empty)
      outbox      <- Ref.of[IO, Vector[OutboxEvent]](Vector.empty)
      analysisIds <- Ref.of[IO, Map[String, String]](Map.empty)
    yield new InMemoryTournamentJobRepository(jobs, outbox, analysisIds)
