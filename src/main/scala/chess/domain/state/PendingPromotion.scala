package chess.domain.state

import chess.domain.model.{Color, Move, Position}

/** Holds the context of an in-progress promotion workflow.
 *
 *  @param square the board square where the pawn landed (and will be replaced)
 *  @param color  the color of the promoting pawn
 *  @param move   the original move that triggered the promotion (stored so it
 *                can be appended to move history only after the choice is made)
 */
final case class PendingPromotion(square: Position, color: Color, move: Move)
