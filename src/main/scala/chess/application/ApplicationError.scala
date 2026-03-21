package chess.application

import chess.domain.error.DomainError

sealed trait ApplicationError
object ApplicationError:
  case class  DomainFailure(error: DomainError) extends ApplicationError
  case object NotPlayersTurn                     extends ApplicationError
  case object PromotionChoiceRequired            extends ApplicationError
  case object NoPromotionPending                 extends ApplicationError
