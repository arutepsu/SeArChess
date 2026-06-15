package chess.tournamentservice

import cats.effect.{FiberIO, IO, Ref}
import cats.effect.std.Queue
import cats.syntax.all.*
import chess.arena.core.{BotPlayer, GameRunner, RoundRobinScheduler}
import chess.arena.writer.jsonl.JsonlFileWriter

import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import java.util.UUID

final class TournamentJobService private (
    registry: BotRegistry,
    config: TournamentServiceConfig,
    analyticsRunner: TournamentAnalyticsRunner,
    jobs: Ref[IO, Map[String, TournamentJob]],
    queue: Queue[IO, String],
    analyticsQueue: Queue[IO, String]
):

  def listBots(): IO[List[BotDescriptor]] =
    IO(registry.listBots())

  def create(request: CreateTournamentRequest): IO[Either[String, TournamentJob]] =
    validate(request).flatMap {
      case Left(error) =>
        IO.pure(Left(error))
      case Right(valid) =>
        for
          now   <- IO(Instant.now())
          jobId <- IO(UUID.randomUUID().toString)
          path   = eventPath(jobId)
          job    = TournamentJob(
            jobId          = jobId,
            name           = valid.name.map(_.trim).filter(_.nonEmpty),
            status         = TournamentJobStatus.Queued,
            createdAt      = now,
            startedAt      = None,
            finishedAt     = None,
            selectedBotIds = valid.botIds,
            mode           = valid.mode,
            repetitions    = valid.repetitions,
            maxPly         = valid.maxPly,
            seed           = valid.seed,
            plannedGames   = plannedGames(valid.botIds, valid.repetitions),
            completedGames = 0,
            outputPath     = Some(path.toString),
            errorMessage   = None,
            analysisStatus = TournamentAnalysisStatus.NotRequested,
            analyticsRunId = None,
            analyticsOutputPath = None,
            analyticsErrorMessage = None,
            analyzeUrl     = Some(s"/api/tournaments/$jobId/analyze"),
            eventsUrl      = Some(s"/api/tournaments/$jobId/events"),
            resultSummary  = None
          )
          _ <- jobs.update(_ + (jobId -> job))
          _ <- queue.offer(jobId)
        yield Right(job)
    }

  def listJobs(): IO[List[TournamentJob]] =
    jobs.get.map(_.values.toList.sortBy(j => -j.createdAt.toEpochMilli))

  def getJob(jobId: String): IO[Option[TournamentJob]] =
    jobs.get.map(_.get(jobId))

  def cancel(jobId: String): IO[Option[TournamentJob]] =
    jobs.modify { current =>
      current.get(jobId) match
        case None =>
          current -> None
        case Some(job) if TournamentJobStatus.terminal(job.status) =>
          current -> Some(job)
        case Some(job) =>
          val updated = job.copy(
            status        = TournamentJobStatus.Cancelled,
            finishedAt    = if job.status == TournamentJobStatus.Queued then Some(Instant.now()) else job.finishedAt,
            errorMessage  = None,
            resultSummary = Some("cancelled")
          )
          current.updated(jobId, updated) -> Some(updated)
    }

  def startWorker(): IO[FiberIO[Nothing]] =
    queue.take.flatMap(processJob).foreverM.start

  def startAnalyticsWorkers(): IO[List[FiberIO[Nothing]]] =
    List.fill(config.maxParallelAnalyticsJobs)(analyticsQueue.take.flatMap(processAnalysisJob).foreverM)
      .parTraverse(_.start)

  def analyze(jobId: String, request: AnalyzeTournamentRequest): IO[AnalyzeTournamentResult] =
    if !config.analyticsEnabled then
      IO.pure(AnalyzeTournamentResult.Rejected(409, "ANALYTICS_DISABLED", "Tournament analytics execution is disabled"))
    else
      val requestedOutput = request.outputPath.map(_.trim).filter(_.nonEmpty)
      val action = jobs.modify { current =>
        current.get(jobId) match
          case None =>
            current -> AnalyzeTournamentResult.Rejected(404, "JOB_NOT_FOUND", s"Tournament job not found: $jobId")
          case Some(job) if job.status != TournamentJobStatus.Succeeded =>
            current -> AnalyzeTournamentResult.Rejected(409, "JOB_NOT_SUCCEEDED", "Tournament job must have succeeded before analytics can run")
          case Some(job) if job.outputPath.forall(path => !Files.exists(Paths.get(path))) =>
            current -> AnalyzeTournamentResult.Rejected(409, "TOURNAMENT_OUTPUT_NOT_FOUND", "Tournament JSONL output does not exist")
          case Some(job) if job.analysisStatus == TournamentAnalysisStatus.Queued || job.analysisStatus == TournamentAnalysisStatus.Running =>
            current -> AnalyzeTournamentResult.Rejected(409, "ANALYSIS_ALREADY_RUNNING", "Tournament analytics is already queued or running")
          case Some(job) if job.analysisStatus == TournamentAnalysisStatus.Succeeded =>
            current -> AnalyzeTournamentResult.Current(job)
          case Some(job) =>
            val output = requestedOutput.getOrElse(analyticsOutputPath(jobId).toString)
            val updated = job.copy(
              analysisStatus        = TournamentAnalysisStatus.Queued,
              analyticsRunId        = None,
              analyticsOutputPath   = Some(output),
              analyticsErrorMessage = None
            )
            current.updated(jobId, updated) -> AnalyzeTournamentResult.Queued(updated)
      }
      action.flatTap {
        case AnalyzeTournamentResult.Queued(_) => analyticsQueue.offer(jobId)
        case _                                 => IO.unit
      }

  private def validate(request: CreateTournamentRequest): IO[Either[String, CreateTournamentRequest]] =
    IO {
      val known = registry.listBots().map(b => b.botId -> b).toMap
      val ids   = request.botIds.map(_.trim).filter(_.nonEmpty)
      if request.name.exists(_.trim.isEmpty) then Left("name must be non-empty when provided")
      else if ids.distinct.size < 2 then Left("botIds must contain at least 2 unique bots")
      else if ids.size != ids.distinct.size then Left("botIds must be unique")
      else if request.mode != "double-round-robin" then Left("mode must be double-round-robin")
      else if request.repetitions <= 0 then Left("repetitions must be positive")
      else if request.maxPly <= 0 || request.maxPly > 1000 then Left("maxPly must be between 1 and 1000")
      else
        ids.find(id => !known.contains(id)) match
          case Some(id) => Left(s"Unknown botId: $id")
          case None =>
            ids.flatMap(id => known.get(id).filterNot(_.available)).headOption match
              case Some(bot) => Left(bot.unavailableReason.getOrElse(s"Bot is unavailable: ${bot.botId}"))
              case None      => Right(request.copy(botIds = ids))
    }

  private def processJob(jobId: String): IO[Unit] =
    for
      shouldRun <- markRunning(jobId)
      _ <- if shouldRun then runJob(jobId) else IO.unit
    yield ()

  private def markRunning(jobId: String): IO[Boolean] =
    jobs.modify { current =>
      current.get(jobId) match
        case Some(job) if job.status == TournamentJobStatus.Queued =>
          val updated = job.copy(status = TournamentJobStatus.Running, startedAt = Some(Instant.now()))
          current.updated(jobId, updated) -> true
        case _ =>
          current -> false
    }

  private def runJob(jobId: String): IO[Unit] =
    getJob(jobId).flatMap {
      case None =>
        IO.unit
      case Some(job) =>
        val output = eventPath(jobId)
        for
          botsOrError <- createParticipants(job)
          _ <- botsOrError match
            case Left(error) =>
              markFailed(jobId, error)
            case Right(bots) =>
              val matchups = RoundRobinScheduler.schedule(jobId, bots, job.repetitions)
              for
                _ <- IO.blocking(Files.createDirectories(output.getParent))
                _ <- runMatchups(jobId, job.maxPly, output, matchups)
              yield ()
        yield ()
    }

  private def createParticipants(job: TournamentJob): IO[Either[String, List[BotPlayer]]] =
    IO {
      job.selectedBotIds.zipWithIndex.traverse { case (botId, idx) =>
        val botSeed = job.seed.map(_ + idx.toLong)
        registry.createBot(botId, botSeed)
      }
    }

  private def runMatchups(
      jobId: String,
      maxPly: Int,
      output: Path,
      matchups: List[chess.arena.core.Matchup]
  ): IO[Unit] =
    val writer = JsonlFileWriter(output)

    def loop(remaining: List[chess.arena.core.Matchup]): IO[Unit] =
      remaining match
        case Nil =>
          markSucceeded(jobId)
        case matchup :: tail =>
          getJob(jobId).flatMap {
            case Some(job) if job.status == TournamentJobStatus.Cancelled =>
              markCancelled(jobId)
            case Some(job) if job.status == TournamentJobStatus.Running =>
              IO.blocking(GameRunner(matchup.white, matchup.black, writer, jobId, maxPly).run(matchup.gameId)).attempt.flatMap {
                case Right(_) =>
                  incrementCompleted(jobId) >> loop(tail)
                case Left(e) =>
                  markFailed(jobId, Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
              }
            case _ =>
              IO.unit
          }

    loop(matchups)

  private def incrementCompleted(jobId: String): IO[Unit] =
    jobs.update { current =>
      current.get(jobId) match
        case Some(job) =>
          current.updated(jobId, job.copy(completedGames = job.completedGames + 1))
        case None =>
          current
    }

  private def markSucceeded(jobId: String): IO[Unit] =
    jobs.update { current =>
      current.get(jobId) match
        case Some(job) if job.status == TournamentJobStatus.Running =>
          current.updated(
            jobId,
            job.copy(
              status        = TournamentJobStatus.Succeeded,
              finishedAt    = Some(Instant.now()),
              resultSummary = Some(s"${job.completedGames}/${job.plannedGames} games completed")
            )
          )
        case _ => current
    }

  private def markCancelled(jobId: String): IO[Unit] =
    jobs.update { current =>
      current.get(jobId) match
        case Some(job) =>
          current.updated(jobId, job.copy(finishedAt = Some(Instant.now()), resultSummary = Some("cancelled")))
        case None => current
    }

  private def markFailed(jobId: String, message: String): IO[Unit] =
    jobs.update { current =>
      current.get(jobId) match
        case Some(job) =>
          current.updated(
            jobId,
            job.copy(status = TournamentJobStatus.Failed, finishedAt = Some(Instant.now()), errorMessage = Some(message))
          )
        case None => current
    }

  private def processAnalysisJob(jobId: String): IO[Unit] =
    for
      request <- markAnalysisRunning(jobId)
      _ <- request match
        case Some(runRequest) => runAnalysis(jobId, runRequest)
        case None             => IO.unit
    yield ()

  private def markAnalysisRunning(jobId: String): IO[Option[TournamentAnalyticsRunRequest]] =
    jobs.modify { current =>
      current.get(jobId) match
        case Some(job) if job.analysisStatus == TournamentAnalysisStatus.Queued =>
          val updated = job.copy(analysisStatus = TournamentAnalysisStatus.Running)
          val request = for
            input  <- updated.outputPath
            output <- updated.analyticsOutputPath
          yield TournamentAnalyticsRunRequest(jobId, input, output)
          current.updated(jobId, updated) -> request
        case _ =>
          current -> None
    }

  private def runAnalysis(jobId: String, request: TournamentAnalyticsRunRequest): IO[Unit] =
    analyticsRunner.runAnalytics(request).attempt.flatMap {
      case Right(result) => markAnalysisSucceeded(jobId, result)
      case Left(e)      => markAnalysisFailed(jobId, Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
    }

  private def markAnalysisSucceeded(jobId: String, result: TournamentAnalyticsRunResult): IO[Unit] =
    jobs.update { current =>
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

  private def markAnalysisFailed(jobId: String, message: String): IO[Unit] =
    jobs.update { current =>
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

  private def plannedGames(botIds: List[String], repetitions: Int): Int =
    botIds.distinct.size * (botIds.distinct.size - 1) * repetitions

  private def eventPath(jobId: String): Path =
    Paths.get(config.outputBasePath, jobId, "game-events.jsonl")

  private def analyticsOutputPath(jobId: String): Path =
    Paths.get(config.analyticsOutputBasePath, jobId)

object TournamentJobService:
  def create(
      registry: BotRegistry,
      config: TournamentServiceConfig,
      analyticsRunner: TournamentAnalyticsRunner
  ): IO[TournamentJobService] =
    for
      jobs           <- Ref.of[IO, Map[String, TournamentJob]](Map.empty)
      queue          <- Queue.unbounded[IO, String]
      analyticsQueue <- Queue.unbounded[IO, String]
    yield TournamentJobService(registry, config, analyticsRunner, jobs, queue, analyticsQueue)
