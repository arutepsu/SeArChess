package chess.tournamentservice

sealed trait MoveGenerationError:
  def describe: String

object MoveGenerationError:
  final case class InvalidFen(fen: String, reason: String) extends MoveGenerationError:
    def describe: String = s"Invalid FEN '$fen': $reason"

  final case class GameNotOngoing(status: String) extends MoveGenerationError:
    def describe: String = s"Game is not ongoing (status: '$status')"

  final case class BotFailed(reason: String) extends MoveGenerationError:
    def describe: String = s"Bot failed to select a move: $reason"
