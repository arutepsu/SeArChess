package chess.arena.core

/** Generates double round-robin matchup lists.
  *
  * For n participants and r repetitions, produces n*(n-1)*r matchups:
  * every ordered (white, black) pair, repeated r times.
  * Self-play (same botId) is excluded.
  * Game IDs are assigned sequentially: tournamentId-game-1, tournamentId-game-2, ...
  */
object RoundRobinScheduler:

  def schedule(
      tournamentId: String,
      participants: List[BotPlayer],
      repetitions: Int = 1
  ): List[Matchup] =
    val pairs = for
      white <- participants
      black <- participants
      if white.profile.botId != black.profile.botId
      _     <- List.fill(repetitions)(())
    yield (white, black)

    pairs.zipWithIndex.map { case ((white, black), idx) =>
      Matchup(white, black, s"$tournamentId-game-${idx + 1}")
    }
