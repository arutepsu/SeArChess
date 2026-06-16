package chess.arena.events

enum BotFamily:
  case Heuristic, UciEngine, AiService, ImportedExternal, FutureTournamentApi

  def toJsonString: String = this match
    case Heuristic           => "heuristic"
    case UciEngine           => "uci-engine"
    case AiService           => "ai-service"
    case ImportedExternal    => "imported-external"
    case FutureTournamentApi => "future-tournament-api"

object BotFamily:
  def fromJsonString(s: String): Either[String, BotFamily] = s match
    case "heuristic"             => Right(BotFamily.Heuristic)
    case "uci-engine"            => Right(BotFamily.UciEngine)
    case "ai-service"            => Right(BotFamily.AiService)
    case "imported-external"     => Right(BotFamily.ImportedExternal)
    case "future-tournament-api" => Right(BotFamily.FutureTournamentApi)
    case other                   => Left(s"Unknown BotFamily: $other")
