package chess.application.ai.policy

import chess.application.session.model.{GameSession, SideController}
import chess.domain.model.Color

/** Pure policy for determining whether the current turn belongs to an AI controller.
 *
 *  A turn is an AI turn when the [[SideController]] assigned to `currentPlayer`
 *  in the session is [[SideController.AI]].  Engine identity (`engineId`) is not
 *  examined here — that is an [[AIProvider]] concern.
 *
 *  This policy complements [[chess.application.session.policy.ActorControlPolicy]]:
 *  `ActorControlPolicy` answers "can this controller act?";
 *  `AITurnPolicy` answers "should the AI service act now?".
 */
object AITurnPolicy:

  /** Returns `true` when `currentPlayer`'s side is controlled by an AI.
   *
   *  @param session       the current session (provides controller assignments)
   *  @param currentPlayer the [[Color]] that is currently to move
   */
  def isAITurn(session: GameSession, currentPlayer: Color): Boolean =
    val controller = currentPlayer match
      case Color.White => session.whiteController
      case Color.Black => session.blackController
    controller match
      case SideController.AI(_) => true
      case _                    => false
