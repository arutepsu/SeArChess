package chess.arena.core

import chess.arena.events.EventEmitter

/** Runs all matchups in a Tournament sequentially, emitting all events into one stream.
  *
  * Delegates each individual game to GameRunner. Does not own or know the write destination;
  * callers supply any EventEmitter (e.g. JsonlFileWriter, in-memory collector).
  */
final class TournamentRunner(
    emitter: EventEmitter,
    maxPlyPerGame: Int = 500,
    onGameCompleted: Matchup => Unit = _ => (),
    shouldContinue: () => Boolean = () => true
):

  def run(tournament: Tournament): Unit =
    tournament.matchups.foreach { matchup =>
      if shouldContinue() then
        GameRunner(matchup.white, matchup.black, emitter, tournament.tournamentId, maxPlyPerGame)
          .run(matchup.gameId)
        onGameCompleted(matchup)
    }
