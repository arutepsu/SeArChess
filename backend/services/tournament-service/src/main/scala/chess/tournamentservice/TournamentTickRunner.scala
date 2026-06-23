package chess.tournamentservice

import cats.effect.IO

// Minimal trait so the scheduler can be wired and tested independently of the
// concrete PublicTournamentBotRunner.
trait TournamentTickRunner:
  def tick(tournamentId: String): IO[PublicTournamentRunnerTickResult]
