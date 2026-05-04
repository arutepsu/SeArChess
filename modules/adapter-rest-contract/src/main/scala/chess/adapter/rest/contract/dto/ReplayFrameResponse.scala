package chess.adapter.rest.contract.dto

import ujson.Value

final case class ReplayFrameResponse(
    game: GameSnapshot,
    ply: Int,
    totalPlies: Int,
    rawMoves: List[MoveHistoryEntry]
)

object ReplayFrameResponse:
  def toJson(r: ReplayFrameResponse): Value =
    val moveJson = (m: MoveHistoryEntry) =>
      ujson.Obj(
        "from" -> ujson.Str(m.from),
        "to" -> ujson.Str(m.to),
        "promotion" -> m.promotion.fold(ujson.Null: Value)(ujson.Str(_))
      )

    ujson.Obj(
      "game" -> GameSnapshot.toJson(r.game),
      "ply" -> ujson.Num(r.ply.toDouble),
      "totalPlies" -> ujson.Num(r.totalPlies.toDouble),
      "rawMoves" -> ujson.Arr.from(r.rawMoves.map(moveJson))
    )
