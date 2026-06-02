package chess.adapter.rest.contract.dto

import ujson.Value

/** Response body for `POST /games/{gameId}/ai-turns`.
  *
  * @param game             game state after the run completed
  * @param sessionLifecycle session lifecycle phase after the run
  * @param pliesRun         number of AI plies successfully applied
  * @param stopReason       why the loop stopped: "GameFinished" | "AwaitingHuman" |
  *                         "MaxPliesReached" | "MoveFailed"
  */
final case class RunAiTurnsResponse(
    game: GameSnapshot,
    sessionLifecycle: String,
    pliesRun: Int,
    stopReason: String
)

object RunAiTurnsResponse:
  def toJson(r: RunAiTurnsResponse): Value =
    ujson.Obj(
      "game"             -> GameSnapshot.toJson(r.game),
      "sessionLifecycle" -> ujson.Str(r.sessionLifecycle),
      "pliesRun"         -> ujson.Num(r.pliesRun.toDouble),
      "stopReason"       -> ujson.Str(r.stopReason)
    )
