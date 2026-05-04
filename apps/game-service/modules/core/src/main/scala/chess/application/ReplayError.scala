package chess.application

import chess.application.session.model.SessionIds.GameId

enum ReplayError:
  case GameNotFound(gameId: GameId)
  case InvalidPly(requested: Int, totalPlies: Int)
  case ReconstructionFailed(message: String)
