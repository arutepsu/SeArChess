package chess.arena.core

import chess.arena.events.BotFamily

final case class BotProfile(
    botId: String,
    family: BotFamily,
    strategyType: String,
    engineType: String,
    modelVersion: String
)
