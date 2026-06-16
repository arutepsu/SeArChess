package chess.tournamentservice.db

import cats.effect.unsafe.implicits.global
import chess.tournamentservice.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.UUID

class TournamentJobRepositorySpec extends AnyFlatSpec with Matchers:

  private def freshRepo(): InMemoryTournamentJobRepository =
    InMemoryTournamentJobRepository.create().unsafeRunSync()

  private def makeJob(
      jobId: String = UUID.randomUUID().toString,
      createdAt: Instant = Instant.now()
  ): TournamentJob =
    TournamentJob(
      jobId                 = jobId,
      name                  = None,
      status                = TournamentJobStatus.Queued,
      createdAt             = createdAt,
      startedAt             = None,
      finishedAt            = None,
      selectedBotIds        = List("random-bot", "capture-first"),
      mode                  = "double-round-robin",
      repetitions           = 1,
      maxPly                = 20,
      seed                  = None,
      plannedGames          = 2,
      completedGames        = 0,
      outputPath            = Some(s"/tmp/searchess-test/$jobId/game-events.jsonl"),
      errorMessage          = None,
      analysisStatus        = TournamentAnalysisStatus.NotRequested,
      analyticsRunId        = None,
      analyticsOutputPath   = None,
      analyticsErrorMessage = None,
      analyzeUrl            = Some(s"/api/tournaments/$jobId/analyze"),
      eventsUrl             = None,
      resultSummary         = None
    )

  private def get[A](opt: Option[A], label: String): A =
    opt.getOrElse(fail(s"expected $label to be defined"))

  // ── 1. createJob persists queued state ────────────────────────────────────

  "InMemoryTournamentJobRepository" should "persist a created job in queued state" in {
    val repo = freshRepo()
    val job  = makeJob()
    repo.createJob(job).unsafeRunSync()
    val found = get(repo.getJob(job.jobId).unsafeRunSync(), "job")
    found.status shouldBe TournamentJobStatus.Queued
    found.completedGames shouldBe 0
    found shouldBe job
  }

  // ── 2. worker transition queued -> running -> succeeded ───────────────────

  it should "reflect queued -> running -> succeeded transitions" in {
    val repo = freshRepo()
    val job  = makeJob()
    repo.createJob(job).unsafeRunSync()

    val didMark = repo.markRunning(job.jobId).unsafeRunSync()
    didMark shouldBe true
    get(repo.getJob(job.jobId).unsafeRunSync(), "job after markRunning").status shouldBe TournamentJobStatus.Running
    get(repo.getJob(job.jobId).unsafeRunSync(), "job startedAt").startedAt shouldBe defined

    repo.markSucceeded(job.jobId).unsafeRunSync()
    val done = get(repo.getJob(job.jobId).unsafeRunSync(), "succeeded job")
    done.status shouldBe TournamentJobStatus.Succeeded
    done.finishedAt shouldBe defined
    done.resultSummary shouldBe Some("0/2 games completed")
  }

  it should "not re-transition a job that is not queued" in {
    val repo = freshRepo()
    val job  = makeJob()
    repo.createJob(job).unsafeRunSync()
    repo.markRunning(job.jobId).unsafeRunSync()

    val second = repo.markRunning(job.jobId).unsafeRunSync()
    second shouldBe false
    get(repo.getJob(job.jobId).unsafeRunSync(), "job").status shouldBe TournamentJobStatus.Running
  }

  // ── 3. completedGames updates persist ────────────────────────────────────

  it should "persist completedGames increments" in {
    val repo = freshRepo()
    val job  = makeJob()
    repo.createJob(job).unsafeRunSync()
    repo.markRunning(job.jobId).unsafeRunSync()

    repo.incrementCompletedGames(job.jobId).unsafeRunSync()
    repo.incrementCompletedGames(job.jobId).unsafeRunSync()

    get(repo.getJob(job.jobId).unsafeRunSync(), "job").completedGames shouldBe 2
  }

  // ── 4. list jobs returns persisted jobs newest first ─────────────────────

  it should "return jobs sorted newest first" in {
    val repo = freshRepo()
    val t0   = Instant.now().minusSeconds(10)
    val t1   = Instant.now()
    val job1 = makeJob(createdAt = t0)
    val job2 = makeJob(createdAt = t1)
    repo.createJob(job1).unsafeRunSync()
    repo.createJob(job2).unsafeRunSync()

    val jobs = repo.listJobs().unsafeRunSync()
    jobs.map(_.jobId) shouldBe List(job2.jobId, job1.jobId)
  }

  // ── 5. get unknown job returns None ──────────────────────────────────────

  it should "return None for an unknown job ID" in {
    val repo = freshRepo()
    repo.getJob("550e8400-e29b-41d4-a716-446655440099").unsafeRunSync() shouldBe None
  }

  // ── 6. cancel queued job persists cancelled ───────────────────────────────

  it should "cancel a queued job and set finishedAt" in {
    val repo   = freshRepo()
    val job    = makeJob()
    repo.createJob(job).unsafeRunSync()
    val result = get(repo.cancelJob(job.jobId).unsafeRunSync(), "cancelled job")
    result.status shouldBe TournamentJobStatus.Cancelled
    result.finishedAt shouldBe defined
    result.resultSummary shouldBe Some("cancelled")
  }

  it should "return the unchanged job when cancelling a terminal job" in {
    val repo = freshRepo()
    val job  = makeJob()
    repo.createJob(job).unsafeRunSync()
    repo.markRunning(job.jobId).unsafeRunSync()
    repo.markSucceeded(job.jobId).unsafeRunSync()

    val result = get(repo.cancelJob(job.jobId).unsafeRunSync(), "job")
    result.status shouldBe TournamentJobStatus.Succeeded
  }

  it should "return None when cancelling an unknown job" in {
    val repo = freshRepo()
    repo.cancelJob("550e8400-e29b-41d4-a716-446655440099").unsafeRunSync() shouldBe None
  }

  // ── 7. analysis queued persists analysisStatus=queued ────────────────────

  it should "persist analysisStatus=queued and analyticsOutputPath after queueAnalysis" in {
    val repo = freshRepo()
    val job  = makeJob()
    repo.createJob(job).unsafeRunSync()

    repo.queueAnalysis(job.jobId, "/tmp/input.jsonl", "/tmp/analytics-out").unsafeRunSync()

    val updated = get(repo.getJob(job.jobId).unsafeRunSync(), "job")
    updated.analysisStatus shouldBe TournamentAnalysisStatus.Queued
    updated.analyticsOutputPath shouldBe Some("/tmp/analytics-out")
    updated.analyticsRunId shouldBe None
    updated.analyticsErrorMessage shouldBe None
  }

  it should "allow queueAnalysis to reset a failed analysis (retry)" in {
    val repo = freshRepo()
    val job  = makeJob()
    repo.createJob(job).unsafeRunSync()
    repo.queueAnalysis(job.jobId, "/tmp/input.jsonl", "/tmp/analytics-out").unsafeRunSync()
    repo.markAnalysisRunning(job.jobId).unsafeRunSync()
    repo.markAnalysisFailed(job.jobId, "spark exploded").unsafeRunSync()

    get(repo.getJob(job.jobId).unsafeRunSync(), "failed job").analysisStatus shouldBe TournamentAnalysisStatus.Failed

    repo.queueAnalysis(job.jobId, "/tmp/input.jsonl", "/tmp/analytics-out-v2").unsafeRunSync()
    val retried = get(repo.getJob(job.jobId).unsafeRunSync(), "retried job")
    retried.analysisStatus shouldBe TournamentAnalysisStatus.Queued
    retried.analyticsErrorMessage shouldBe None
    retried.analyticsOutputPath shouldBe Some("/tmp/analytics-out-v2")
  }

  // ── 8. analysis success persists analyticsRunId ───────────────────────────

  it should "persist analyticsRunId and succeeded status after markAnalysisSucceeded" in {
    val repo = freshRepo()
    val job  = makeJob()
    repo.createJob(job).unsafeRunSync()
    repo.queueAnalysis(job.jobId, "/tmp/input.jsonl", "/tmp/analytics-out").unsafeRunSync()
    val request = repo.markAnalysisRunning(job.jobId).unsafeRunSync()
    request shouldBe defined

    val result = TournamentAnalyticsRunResult("run-abc-123", "/tmp/input.jsonl", "/tmp/analytics-out")
    repo.markAnalysisSucceeded(job.jobId, result).unsafeRunSync()

    val updated = get(repo.getJob(job.jobId).unsafeRunSync(), "job")
    updated.analysisStatus shouldBe TournamentAnalysisStatus.Succeeded
    updated.analyticsRunId shouldBe Some("run-abc-123")
    updated.analyticsOutputPath shouldBe Some("/tmp/analytics-out")
    updated.analyticsErrorMessage shouldBe None
  }

  it should "return the correct TournamentAnalyticsRunRequest from markAnalysisRunning" in {
    val repo = freshRepo()
    val job  = makeJob()
    repo.createJob(job).unsafeRunSync()
    repo.queueAnalysis(job.jobId, "/tmp/input.jsonl", "/tmp/analytics-out").unsafeRunSync()

    val request = get(repo.markAnalysisRunning(job.jobId).unsafeRunSync(), "run request")
    request.inputPath shouldBe s"/tmp/searchess-test/${job.jobId}/game-events.jsonl"
    request.outputPath shouldBe "/tmp/analytics-out"
    request.tournamentJobId shouldBe job.jobId

    get(repo.getJob(job.jobId).unsafeRunSync(), "job").analysisStatus shouldBe TournamentAnalysisStatus.Running
  }

  // ── 9. analysis failure persists analyticsErrorMessage ────────────────────

  it should "persist analyticsErrorMessage after markAnalysisFailed" in {
    val repo = freshRepo()
    val job  = makeJob()
    repo.createJob(job).unsafeRunSync()
    repo.queueAnalysis(job.jobId, "/tmp/input.jsonl", "/tmp/analytics-out").unsafeRunSync()
    repo.markAnalysisRunning(job.jobId).unsafeRunSync()

    repo.markAnalysisFailed(job.jobId, "OOM in Spark driver").unsafeRunSync()

    val updated = get(repo.getJob(job.jobId).unsafeRunSync(), "job")
    updated.analysisStatus shouldBe TournamentAnalysisStatus.Failed
    updated.analyticsErrorMessage shouldBe Some("OOM in Spark driver")
  }

  // ── 10. no analysis row maps to analysisStatus=not_requested ──────────────

  it should "report analysisStatus=not_requested for a job with no analysis queued" in {
    val repo = freshRepo()
    val job  = makeJob()
    repo.createJob(job).unsafeRunSync()

    get(repo.getJob(job.jobId).unsafeRunSync(), "job").analysisStatus shouldBe TournamentAnalysisStatus.NotRequested
  }

  it should "report not_requested for all jobs in listJobs when no analysis has been queued" in {
    val repo = freshRepo()
    repo.createJob(makeJob()).unsafeRunSync()
    repo.createJob(makeJob()).unsafeRunSync()

    val jobs = repo.listJobs().unsafeRunSync()
    jobs.map(_.analysisStatus).distinct shouldBe List(TournamentAnalysisStatus.NotRequested)
  }

  // ── Outbox event tests ────────────────────────────────────────────────────

  it should "emit TournamentJobCreated when a job is created" in {
    val repo   = freshRepo()
    val job    = makeJob()
    repo.createJob(job).unsafeRunSync()
    val events = repo.recordedEvents().unsafeRunSync()
    events.size shouldBe 1
    events.head.eventType     shouldBe "TournamentJobCreated"
    events.head.aggregateId   shouldBe job.jobId
    events.head.aggregateType shouldBe "tournament_job"
    events.head.status        shouldBe "pending"
    events.head.payloadJson   should include(job.jobId)
  }

  it should "emit TournamentJobStarted when job transitions to running" in {
    val repo = freshRepo()
    val job  = makeJob()
    repo.createJob(job).unsafeRunSync()
    repo.markRunning(job.jobId).unsafeRunSync()
    val started = get(
      repo.recordedEvents().unsafeRunSync().find(_.eventType == "TournamentJobStarted"),
      "TournamentJobStarted event"
    )
    started.aggregateId   shouldBe job.jobId
    started.aggregateType shouldBe "tournament_job"
    started.status        shouldBe "pending"
  }

  it should "emit TournamentGameCompleted when a game is completed" in {
    val repo = freshRepo()
    val job  = makeJob()
    repo.createJob(job).unsafeRunSync()
    repo.markRunning(job.jobId).unsafeRunSync()
    repo.incrementCompletedGames(job.jobId).unsafeRunSync()
    val event = get(
      repo.recordedEvents().unsafeRunSync().find(_.eventType == "TournamentGameCompleted"),
      "TournamentGameCompleted event"
    )
    event.aggregateId   shouldBe job.jobId
    event.payloadJson   should include("completedGames")
    event.payloadJson   should include("plannedGames")
  }

  it should "emit TournamentJobSucceeded when job succeeds" in {
    val repo = freshRepo()
    val job  = makeJob()
    repo.createJob(job).unsafeRunSync()
    repo.markRunning(job.jobId).unsafeRunSync()
    repo.markSucceeded(job.jobId).unsafeRunSync()
    val event = get(
      repo.recordedEvents().unsafeRunSync().find(_.eventType == "TournamentJobSucceeded"),
      "TournamentJobSucceeded event"
    )
    event.aggregateId shouldBe job.jobId
    event.payloadJson should include("finishedAt")
  }

  it should "emit TournamentJobFailed when job fails" in {
    val repo = freshRepo()
    val job  = makeJob()
    repo.createJob(job).unsafeRunSync()
    repo.markRunning(job.jobId).unsafeRunSync()
    repo.markFailed(job.jobId, "something broke").unsafeRunSync()
    val event = get(
      repo.recordedEvents().unsafeRunSync().find(_.eventType == "TournamentJobFailed"),
      "TournamentJobFailed event"
    )
    event.aggregateId shouldBe job.jobId
    event.payloadJson should include("something broke")
  }

  it should "emit TournamentJobCancelled when a queued job is cancelled" in {
    val repo = freshRepo()
    val job  = makeJob()
    repo.createJob(job).unsafeRunSync()
    repo.cancelJob(job.jobId).unsafeRunSync()
    val event = get(
      repo.recordedEvents().unsafeRunSync().find(_.eventType == "TournamentJobCancelled"),
      "TournamentJobCancelled event"
    )
    event.aggregateId shouldBe job.jobId
    event.payloadJson should include("finishedAt")
  }

  it should "emit TournamentJobCancelled only via finalizeCancelled for a running job" in {
    val repo = freshRepo()
    val job  = makeJob()
    repo.createJob(job).unsafeRunSync()
    repo.markRunning(job.jobId).unsafeRunSync()
    repo.cancelJob(job.jobId).unsafeRunSync()

    val beforeFinalize = repo.recordedEvents().unsafeRunSync().map(_.eventType)
    beforeFinalize should not contain "TournamentJobCancelled"

    repo.finalizeCancelled(job.jobId).unsafeRunSync()
    val event = get(
      repo.recordedEvents().unsafeRunSync().find(_.eventType == "TournamentJobCancelled"),
      "TournamentJobCancelled event after finalizeCancelled"
    )
    event.aggregateId shouldBe job.jobId
  }

  it should "emit AnalysisJobQueued when analysis is queued" in {
    val repo = freshRepo()
    val job  = makeJob()
    repo.createJob(job).unsafeRunSync()
    repo.queueAnalysis(job.jobId, "/tmp/input.jsonl", "/tmp/analytics-out").unsafeRunSync()
    val event = get(
      repo.recordedEvents().unsafeRunSync().find(_.eventType == "AnalysisJobQueued"),
      "AnalysisJobQueued event"
    )
    event.aggregateType shouldBe "analysis_job"
    event.payloadJson   should include(job.jobId)
    event.payloadJson   should include("/tmp/input.jsonl")
  }

  it should "emit AnalysisJobStarted when analysis transitions to running" in {
    val repo = freshRepo()
    val job  = makeJob()
    repo.createJob(job).unsafeRunSync()
    repo.queueAnalysis(job.jobId, "/tmp/input.jsonl", "/tmp/analytics-out").unsafeRunSync()
    repo.markAnalysisRunning(job.jobId).unsafeRunSync()
    val event = get(
      repo.recordedEvents().unsafeRunSync().find(_.eventType == "AnalysisJobStarted"),
      "AnalysisJobStarted event"
    )
    event.aggregateType shouldBe "analysis_job"
    event.payloadJson   should include(job.jobId)
  }

  it should "emit AnalysisJobSucceeded when analysis succeeds" in {
    val repo   = freshRepo()
    val job    = makeJob()
    repo.createJob(job).unsafeRunSync()
    repo.queueAnalysis(job.jobId, "/tmp/input.jsonl", "/tmp/analytics-out").unsafeRunSync()
    repo.markAnalysisRunning(job.jobId).unsafeRunSync()
    val result = TournamentAnalyticsRunResult("run-xyz-789", "/tmp/input.jsonl", "/tmp/analytics-out")
    repo.markAnalysisSucceeded(job.jobId, result).unsafeRunSync()
    val event = get(
      repo.recordedEvents().unsafeRunSync().find(_.eventType == "AnalysisJobSucceeded"),
      "AnalysisJobSucceeded event"
    )
    event.aggregateType shouldBe "analysis_job"
    event.payloadJson   should include("run-xyz-789")
    event.payloadJson   should include(job.jobId)
  }

  it should "emit AnalysisJobFailed when analysis fails" in {
    val repo = freshRepo()
    val job  = makeJob()
    repo.createJob(job).unsafeRunSync()
    repo.queueAnalysis(job.jobId, "/tmp/input.jsonl", "/tmp/analytics-out").unsafeRunSync()
    repo.markAnalysisRunning(job.jobId).unsafeRunSync()
    repo.markAnalysisFailed(job.jobId, "out of memory").unsafeRunSync()
    val event = get(
      repo.recordedEvents().unsafeRunSync().find(_.eventType == "AnalysisJobFailed"),
      "AnalysisJobFailed event"
    )
    event.aggregateType shouldBe "analysis_job"
    event.payloadJson   should include("out of memory")
    event.payloadJson   should include(job.jobId)
  }

  it should "use the same analysisJobId across AnalysisJobQueued and AnalysisJobStarted" in {
    val repo = freshRepo()
    val job  = makeJob()
    repo.createJob(job).unsafeRunSync()
    repo.queueAnalysis(job.jobId, "/tmp/input.jsonl", "/tmp/analytics-out").unsafeRunSync()
    repo.markAnalysisRunning(job.jobId).unsafeRunSync()
    val allEvents = repo.recordedEvents().unsafeRunSync()
    val queued  = get(allEvents.find(_.eventType == "AnalysisJobQueued"), "AnalysisJobQueued")
    val started = get(allEvents.find(_.eventType == "AnalysisJobStarted"), "AnalysisJobStarted")
    queued.aggregateId shouldBe started.aggregateId
  }
