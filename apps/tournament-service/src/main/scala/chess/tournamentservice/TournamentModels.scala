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
    analyticsRunId: Option[String],
    eventsUrl: Option[String],
    resultSummary: Option[String]
)
