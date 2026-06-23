package chess.tournamentservice

enum RunnerActionStatus:
  case Submitted, Skipped, Failed

final case class RunnerAction(
  gameId:                String,
  tournamentServerBotId: String,
  uciMove:               Option[String],
  status:                RunnerActionStatus,
  reason:                Option[String]
)

final case class PublicTournamentRunnerTickResult(
  tournamentId: String,
  round:        Int,
  gamesFound:   Int,
  actions:      List[RunnerAction]
)
