package chess.tournamentservice.db

import cats.effect.IO
import chess.tournamentservice.*
import slick.jdbc.GetResult
import slick.jdbc.PostgresProfile.api.*

import java.time.Instant
import java.util.UUID
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*

final class SlickTournamentJobRepository(db: Database, schema: String) extends TournamentJobRepository:

  private val t = schema

  // Slick DBIO flatMap requires an implicit ExecutionContext for monadic chaining
  private given ec: ExecutionContext = ExecutionContext.global

  // ── GetResult: 22 columns from the LEFT JOIN projection ───────────────────

  private given GetResult[TournamentJob] = GetResult { r =>
    val jobId                = r.nextString()
    val name                 = r.nextStringOption()
    val statusStr            = r.nextString()
    val botIdsJson           = r.nextString()
    val mode                 = r.nextString()
    val repetitions          = r.nextInt()
    val maxPly               = r.nextInt()
    val seed                 = r.nextLongOption()
    val plannedGames         = r.nextInt()
    val completedGames       = r.nextInt()
    val outputPath           = r.nextStringOption()
    val errorMessage         = r.nextStringOption()
    val resultSummary        = r.nextStringOption()
    val eventsUrl            = r.nextStringOption()
    val analyzeUrl           = r.nextStringOption()
    val createdAt            = Instant.parse(r.nextString())
    val startedAt            = r.nextStringOption().map(Instant.parse)
    val finishedAt           = r.nextStringOption().map(Instant.parse)
    val analysisStatusStr    = r.nextStringOption()
    val analyticsOutputPath  = r.nextStringOption()
    val analyticsRunId       = r.nextStringOption()
    val analyticsErrorMsg    = r.nextStringOption()

    TournamentJob(
      jobId                 = jobId,
      name                  = name,
      status                = parseJobStatus(statusStr),
      createdAt             = createdAt,
      startedAt             = startedAt,
      finishedAt            = finishedAt,
      selectedBotIds        = ujson.read(botIdsJson).arr.map(_.str).toList,
      mode                  = mode,
      repetitions           = repetitions,
      maxPly                = maxPly,
      seed                  = seed,
      plannedGames          = plannedGames,
      completedGames        = completedGames,
      outputPath            = outputPath,
      errorMessage          = errorMessage,
      analysisStatus        = analysisStatusStr.map(parseAnalysisStatus).getOrElse(TournamentAnalysisStatus.NotRequested),
      analyticsRunId        = analyticsRunId,
      analyticsOutputPath   = analyticsOutputPath,
      analyticsErrorMessage = analyticsErrorMsg,
      analyzeUrl            = analyzeUrl,
      eventsUrl             = eventsUrl,
      resultSummary         = resultSummary
    )
  }

  // ── Schema initialization ──────────────────────────────────────────────────

  def initSchema(): IO[Unit] =
    val createTournamentJobs = sqlu"""
      CREATE TABLE IF NOT EXISTS #$t.tournament_jobs (
        job_id           TEXT PRIMARY KEY,
        name             TEXT,
        status           TEXT    NOT NULL,
        selected_bot_ids TEXT    NOT NULL,
        mode             TEXT    NOT NULL,
        repetitions      INT     NOT NULL,
        max_ply          INT     NOT NULL,
        seed             BIGINT,
        planned_games    INT     NOT NULL,
        completed_games  INT     NOT NULL DEFAULT 0,
        output_path      TEXT,
        error_message    TEXT,
        result_summary   TEXT,
        events_url       TEXT,
        analyze_url      TEXT,
        created_at       TEXT    NOT NULL,
        started_at       TEXT,
        finished_at      TEXT
      )
    """
    val createAnalysisJobs = sqlu"""
      CREATE TABLE IF NOT EXISTS #$t.analysis_jobs (
        analysis_job_id   TEXT PRIMARY KEY,
        tournament_job_id TEXT NOT NULL,
        status            TEXT NOT NULL,
        input_path        TEXT NOT NULL,
        output_path       TEXT,
        analytics_run_id  TEXT,
        error_message     TEXT,
        created_at        TEXT NOT NULL,
        started_at        TEXT,
        finished_at       TEXT,
        UNIQUE (tournament_job_id)
      )
    """
    val createOutboxEvents = sqlu"""
      CREATE TABLE IF NOT EXISTS #$t.tournament_outbox_events (
        event_id       TEXT PRIMARY KEY,
        aggregate_type TEXT NOT NULL,
        aggregate_id   TEXT NOT NULL,
        event_type     TEXT NOT NULL,
        payload_json   TEXT NOT NULL,
        status         TEXT NOT NULL DEFAULT 'pending',
        created_at     TEXT NOT NULL,
        published_at   TEXT,
        last_error     TEXT,
        attempt_count  INT  NOT NULL DEFAULT 0
      )
    """
    val createIdxStatus = sqlu"""
      CREATE INDEX IF NOT EXISTS idx_outbox_status_created
        ON #$t.tournament_outbox_events (status, created_at)
    """
    val createIdxAggregate = sqlu"""
      CREATE INDEX IF NOT EXISTS idx_outbox_aggregate
        ON #$t.tournament_outbox_events (aggregate_type, aggregate_id)
    """
    val createIdxEventType = sqlu"""
      CREATE INDEX IF NOT EXISTS idx_outbox_event_type
        ON #$t.tournament_outbox_events (event_type)
    """
    run(DBIO.seq(
      createTournamentJobs,
      createAnalysisJobs,
      createOutboxEvents,
      createIdxStatus,
      createIdxAggregate,
      createIdxEventType
    ))

  // ── Core CRUD ─────────────────────────────────────────────────────────────

  def createJob(job: TournamentJob): IO[Unit] =
    val botIdsJson = ujson.write(ujson.Arr(job.selectedBotIds.map(ujson.Str(_))*))
    val statusStr  = job.status.json
    val seedVal    = job.seed
    val createdAt  = job.createdAt.toString
    val startedAt  = job.startedAt.map(_.toString)
    val finishedAt = job.finishedAt.map(_.toString)
    val action = DBIO.seq(
      sqlu"""
        INSERT INTO #$t.tournament_jobs (
          job_id, name, status, selected_bot_ids, mode, repetitions, max_ply, seed,
          planned_games, completed_games, output_path, error_message, result_summary,
          events_url, analyze_url, created_at, started_at, finished_at
        ) VALUES (
          ${job.jobId}, ${job.name}, $statusStr, $botIdsJson, ${job.mode},
          ${job.repetitions}, ${job.maxPly}, $seedVal,
          ${job.plannedGames}, ${job.completedGames}, ${job.outputPath},
          ${job.errorMessage}, ${job.resultSummary},
          ${job.eventsUrl}, ${job.analyzeUrl}, $createdAt, $startedAt, $finishedAt
        )
      """,
      insertOutbox(TournamentOutboxEvent.jobCreated(job))
    )
    run(action.transactionally)

  def listJobs(): IO[List[TournamentJob]] =
    run(allJobsQuery).map(_.toList)

  def getJob(jobId: String): IO[Option[TournamentJob]] =
    run(singleJobQuery(jobId)).map(_.headOption)

  // ── Tournament state transitions ───────────────────────────────────────────

  def markRunning(jobId: String): IO[Boolean] =
    val now = Instant.now()
    val action: DBIO[Boolean] = for
      rows <- sqlu"""
        UPDATE #$t.tournament_jobs
        SET status = 'running', started_at = ${now.toString}
        WHERE job_id = $jobId AND status = 'queued'
      """
      _    <- emitIf(rows > 0, insertOutbox(TournamentOutboxEvent.jobStarted(jobId, now)))
    yield rows > 0
    run(action.transactionally)

  def incrementCompletedGames(jobId: String): IO[Unit] =
    val now = Instant.now()
    val action = for
      _ <- sqlu"UPDATE #$t.tournament_jobs SET completed_games = completed_games + 1 WHERE job_id = $jobId"
      counts <- sql"SELECT completed_games, planned_games FROM #$t.tournament_jobs WHERE job_id = $jobId"
                  .as[(Int, Int)]
      _ <- counts.headOption match
        case Some((completed, planned)) =>
          insertOutbox(TournamentOutboxEvent.gameCompleted(jobId, completed, planned, now))
        case None => DBIO.successful(0)
    yield ()
    run(action.transactionally)

  def markSucceeded(jobId: String): IO[Unit] =
    val now = Instant.now()
    val action = for
      jobs <- singleJobQuery(jobId)
      _ <- sqlu"""
        UPDATE #$t.tournament_jobs
        SET status = 'succeeded',
            finished_at = ${now.toString},
            result_summary = CAST(completed_games AS TEXT) || '/' || CAST(planned_games AS TEXT) || ' games completed'
        WHERE job_id = $jobId AND status = 'running'
      """
      _ <- jobs.headOption match
        case Some(job) => insertOutbox(TournamentOutboxEvent.jobSucceeded(jobId, job.outputPath, now))
        case None      => DBIO.successful(0)
    yield ()
    run(action.transactionally)

  def markFailed(jobId: String, message: String): IO[Unit] =
    val now = Instant.now()
    val action = DBIO.seq(
      sqlu"""
        UPDATE #$t.tournament_jobs
        SET status = 'failed', finished_at = ${now.toString}, error_message = $message
        WHERE job_id = $jobId
      """,
      insertOutbox(TournamentOutboxEvent.jobFailed(jobId, message, now))
    )
    run(action.transactionally)

  def cancelJob(jobId: String): IO[Option[TournamentJob]] =
    val action: DBIO[Option[TournamentJob]] =
      singleJobQuery(jobId).flatMap { jobs =>
        jobs.headOption match
          case None => DBIO.successful(None)
          case Some(job) if TournamentJobStatus.terminal(job.status) => DBIO.successful(Some(job))
          case Some(job) =>
            val now = Instant.now()
            val finishedAt: Option[String] =
              if job.status == TournamentJobStatus.Queued then Some(now.toString)
              else job.finishedAt.map(_.toString)
            val outboxAction: DBIO[Int] =
              if job.status == TournamentJobStatus.Queued then insertOutbox(TournamentOutboxEvent.jobCancelled(jobId, now))
              else DBIO.successful(0)
            for
              _ <- sqlu"""
                UPDATE #$t.tournament_jobs
                SET status = 'cancelled',
                    finished_at = $finishedAt,
                    error_message = NULL,
                    result_summary = 'cancelled'
                WHERE job_id = $jobId
              """
              _ <- outboxAction
              updated <- singleJobQuery(jobId)
            yield updated.headOption
      }
    run(action.transactionally)

  def finalizeCancelled(jobId: String): IO[Unit] =
    val now = Instant.now()
    val action = DBIO.seq(
      sqlu"""
        UPDATE #$t.tournament_jobs
        SET finished_at = ${now.toString}, result_summary = 'cancelled'
        WHERE job_id = $jobId
      """,
      insertOutbox(TournamentOutboxEvent.jobCancelled(jobId, now))
    )
    run(action.transactionally)

  // ── Analysis state transitions ─────────────────────────────────────────────

  def queueAnalysis(jobId: String, inputPath: String, analyticsOutputPath: String): IO[Unit] =
    val analysisId = UUID.randomUUID().toString
    val now        = Instant.now()
    val action = DBIO.seq(
      sqlu"""
        INSERT INTO #$t.analysis_jobs (
          analysis_job_id, tournament_job_id, status, input_path, output_path,
          analytics_run_id, error_message, created_at, started_at, finished_at
        ) VALUES (
          $analysisId, $jobId, 'queued', $inputPath, $analyticsOutputPath,
          NULL, NULL, ${now.toString}, NULL, NULL
        )
        ON CONFLICT (tournament_job_id) DO UPDATE SET
          analysis_job_id  = EXCLUDED.analysis_job_id,
          status           = 'queued',
          input_path       = EXCLUDED.input_path,
          output_path      = EXCLUDED.output_path,
          analytics_run_id = NULL,
          error_message    = NULL,
          started_at       = NULL,
          finished_at      = NULL
      """,
      insertOutbox(TournamentOutboxEvent.analysisQueued(analysisId, jobId, inputPath, analyticsOutputPath, now))
    )
    run(action.transactionally)

  def markAnalysisRunning(jobId: String): IO[Option[TournamentAnalyticsRunRequest]] =
    val now = Instant.now()
    val action: DBIO[Option[TournamentAnalyticsRunRequest]] =
      singleJobQuery(jobId).flatMap { jobs =>
        jobs.headOption match
          case Some(job) if job.analysisStatus == TournamentAnalysisStatus.Queued =>
            for
              aIdOpt <- getAnalysisJobId(jobId)
              rows   <- sqlu"""
                UPDATE #$t.analysis_jobs
                SET status = 'running', started_at = ${now.toString}
                WHERE tournament_job_id = $jobId AND status = 'queued'
              """
              _      <- emitIf(rows > 0, emitForAnalysis(aIdOpt)(TournamentOutboxEvent.analysisStarted(_, jobId, now)))
            yield if rows > 0 then
              for
                input  <- job.outputPath
                output <- job.analyticsOutputPath
              yield TournamentAnalyticsRunRequest(jobId, input, output)
            else None
          case _ =>
            DBIO.successful(None)
      }
    run(action.transactionally)

  def markAnalysisSucceeded(jobId: String, result: TournamentAnalyticsRunResult): IO[Unit] =
    val now = Instant.now()
    val action = for
      aIdOpt <- getAnalysisJobId(jobId)
      _ <- sqlu"""
        UPDATE #$t.analysis_jobs
        SET status = 'succeeded',
            finished_at = ${now.toString},
            analytics_run_id = ${result.analyticsRunId},
            output_path = ${result.outputPath},
            error_message = NULL
        WHERE tournament_job_id = $jobId
      """
      _ <- emitForAnalysis(aIdOpt)(TournamentOutboxEvent.analysisSucceeded(_, jobId, result.analyticsRunId, result.outputPath, now))
    yield ()
    run(action.transactionally)

  def markAnalysisFailed(jobId: String, message: String): IO[Unit] =
    val now = Instant.now()
    val action = for
      aIdOpt <- getAnalysisJobId(jobId)
      _ <- sqlu"""
        UPDATE #$t.analysis_jobs
        SET status = 'failed', finished_at = ${now.toString}, error_message = $message
        WHERE tournament_job_id = $jobId
      """
      _ <- emitForAnalysis(aIdOpt)(TournamentOutboxEvent.analysisFailed(_, jobId, message, now))
    yield ()
    run(action.transactionally)

  // ── Helpers ───────────────────────────────────────────────────────────────

  private def run[T](action: DBIO[T]): IO[T] =
    IO.blocking(Await.result(db.run(action), 30.seconds))

  private def insertOutbox(event: OutboxEvent): DBIO[Int] =
    sqlu"""
      INSERT INTO #$t.tournament_outbox_events (
        event_id, aggregate_type, aggregate_id, event_type, payload_json,
        status, created_at, published_at, last_error, attempt_count
      ) VALUES (
        ${event.eventId}, ${event.aggregateType}, ${event.aggregateId},
        ${event.eventType}, ${event.payloadJson},
        'pending', ${event.createdAt.toString}, NULL, NULL, 0
      )
    """

  // Conditionally run a DBIO action; used to avoid multi-line if-then-else in for-comprehensions.
  private def emitIf(cond: Boolean, action: => DBIO[Int]): DBIO[Int] =
    if cond then action else DBIO.successful(0)

  // Emit an outbox event only when an analysisJobId is present (JOIN succeeded).
  private def emitForAnalysis(aIdOpt: Option[String])(mkEvent: String => OutboxEvent): DBIO[Int] =
    aIdOpt.fold[DBIO[Int]](DBIO.successful(0))(aId => insertOutbox(mkEvent(aId)))

  private def getAnalysisJobId(tournamentJobId: String): DBIO[Option[String]] =
    sql"SELECT analysis_job_id FROM #$t.analysis_jobs WHERE tournament_job_id = $tournamentJobId"
      .as[String]
      .map(_.headOption)

  private val selectCols = """
    t.job_id, t.name, t.status, t.selected_bot_ids, t.mode, t.repetitions,
    t.max_ply, t.seed, t.planned_games, t.completed_games, t.output_path,
    t.error_message, t.result_summary, t.events_url, t.analyze_url,
    t.created_at, t.started_at, t.finished_at,
    a.status, a.output_path, a.analytics_run_id, a.error_message
  """

  private def allJobsQuery: DBIO[Seq[TournamentJob]] =
    sql"""
      SELECT #$selectCols
      FROM #$t.tournament_jobs t
      LEFT JOIN #$t.analysis_jobs a ON a.tournament_job_id = t.job_id
      ORDER BY t.created_at DESC
    """.as[TournamentJob]

  private def singleJobQuery(jobId: String): DBIO[Seq[TournamentJob]] =
    sql"""
      SELECT #$selectCols
      FROM #$t.tournament_jobs t
      LEFT JOIN #$t.analysis_jobs a ON a.tournament_job_id = t.job_id
      WHERE t.job_id = $jobId
    """.as[TournamentJob]

  private def parseJobStatus(s: String): TournamentJobStatus = s match
    case "queued"    => TournamentJobStatus.Queued
    case "running"   => TournamentJobStatus.Running
    case "succeeded" => TournamentJobStatus.Succeeded
    case "failed"    => TournamentJobStatus.Failed
    case "cancelled" => TournamentJobStatus.Cancelled
    case other       => throw IllegalStateException(s"Unknown tournament job status in DB: $other")

  private def parseAnalysisStatus(s: String): TournamentAnalysisStatus = s match
    case "queued"    => TournamentAnalysisStatus.Queued
    case "running"   => TournamentAnalysisStatus.Running
    case "succeeded" => TournamentAnalysisStatus.Succeeded
    case "failed"    => TournamentAnalysisStatus.Failed
    case other       => throw IllegalStateException(s"Unknown analysis status in DB: $other")
