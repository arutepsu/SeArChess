package chess.tournamentservice

import java.time.Instant

enum TournamentJobStatus:
  case Queued, Running, Succeeded, Failed, Cancelled

  def json: String = this match
    case Queued    => "queued"
    case Running   => "running"
    case Succeeded => "succeeded"
    case Failed    => "failed"
    case Cancelled => "cancelled"

object TournamentJobStatus:
  def terminal(status: TournamentJobStatus): Boolean =
    status == Succeeded || status == Failed || status == Cancelled

enum TournamentAnalysisStatus:
  case NotRequested, Queued, Running, Succeeded, Failed

  def json: String = this match
    case NotRequested => "not_requested"
    case Queued       => "queued"
    case Running      => "running"
    case Succeeded    => "succeeded"
    case Failed       => "failed"

final case class BotDescriptor(
    botId: String,
    displayName: String,
    family: String,
    strategyType: String,
    engineType: String,
    modelVersion: String,
    available: Boolean,
    unavailableReason: Option[String]
)

final case class CreateTournamentRequest(
    name: Option[String],
    botIds: List[String],
    mode: String,
    repetitions: Int,
    maxPly: Int,
    seed: Option[Long]
)

final case class TournamentJob(
    jobId: String,
    name: Option[String],
    status: TournamentJobStatus,
    createdAt: Instant,
    startedAt: Option[Instant],
    finishedAt: Option[Instant],
    selectedBotIds: List[String],
    mode: String,
    repetitions: Int,
    maxPly: Int,
    seed: Option[Long],
    plannedGames: Int,
    completedGames: Int,
    outputPath: Option[String],
    errorMessage: Option[String],
    analysisStatus: TournamentAnalysisStatus,
    analyticsRunId: Option[String],
    analyticsOutputPath: Option[String],
    analyticsErrorMessage: Option[String],
    analyzeUrl: Option[String],
    eventsUrl: Option[String],
    resultSummary: Option[String]
)

final case class AnalyzeTournamentRequest(outputPath: Option[String])

final case class TournamentAnalyticsRunRequest(
    tournamentJobId: String,
    inputPath: String,
    outputPath: String
)

final case class TournamentAnalyticsRunResult(
    analyticsRunId: String,
    inputPath: String,
    outputPath: String
)

trait TournamentAnalyticsRunner:
  def runAnalytics(request: TournamentAnalyticsRunRequest): cats.effect.IO[TournamentAnalyticsRunResult]

enum AnalyzeTournamentResult:
  case Queued(job: TournamentJob)
  case Current(job: TournamentJob)
  case Rejected(statusCode: Int, code: String, message: String)
