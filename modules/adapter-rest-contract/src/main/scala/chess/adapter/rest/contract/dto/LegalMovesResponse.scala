package chess.adapter.rest.contract.dto

import ujson.Value

/** A legal move available in the current game state. */
final case class LegalMoveDto(from: String, to: String, promotion: Option[String])

/** Transport representation of the legal moves for a game.
 *
 *  @param gameId             opaque UUID string identifying the game
 *  @param currentPlayer      "White" or "Black"
 *  @param moves              legal moves for the current player
 *  @param legalTargetsByFrom legal target squares grouped by source square
 */
final case class LegalMovesResponse(
  gameId:             String,
  currentPlayer:      String,
  moves:              List[LegalMoveDto],
  legalTargetsByFrom: Map[String, List[String]]
)

object LegalMovesResponse:
  def toJson(r: LegalMovesResponse): Value =
    def moveJson(m: LegalMoveDto): Value =
      ujson.Obj(
        "from"      -> ujson.Str(m.from),
        "to"        -> ujson.Str(m.to),
        "promotion" -> m.promotion.fold(ujson.Null: Value)(ujson.Str(_))
      )

    ujson.Obj(
      "gameId"             -> ujson.Str(r.gameId),
      "currentPlayer"      -> ujson.Str(r.currentPlayer),
      "moves"              -> ujson.Arr.from(r.moves.map(moveJson)),
      "legalTargetsByFrom" -> ujson.Obj.from(r.legalTargetsByFrom.map { case (sq, targets) =>
        sq -> (ujson.Arr.from(targets.map(ujson.Str(_))): Value)
      })
    )
