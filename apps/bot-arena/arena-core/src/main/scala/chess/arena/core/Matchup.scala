package chess.arena.core

/** One scheduled game: a pair of bots, their color assignment, and a stable game identifier. */
final case class Matchup(white: BotPlayer, black: BotPlayer, gameId: String)
