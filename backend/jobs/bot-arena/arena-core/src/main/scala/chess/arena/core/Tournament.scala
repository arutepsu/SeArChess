package chess.arena.core

/** A scheduled tournament: a set of participants and the ordered list of games to play. */
trait Tournament:
  def tournamentId: String
  def participants: List[BotPlayer]
  def matchups: List[Matchup]
