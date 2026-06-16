package chess.arena.core

import chess.domain.model.Move
import chess.domain.state.GameState

trait BotPlayer:
  def profile: BotProfile
  def selectMove(state: GameState): Move
