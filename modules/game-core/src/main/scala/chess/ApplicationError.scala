package chess.application

import chess.domain.error.DomainError

enum ApplicationError:
  case DomainFailure(error: DomainError)
  case NotPlayersTurn
