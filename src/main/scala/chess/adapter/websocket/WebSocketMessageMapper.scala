package chess.adapter.websocket

import chess.application.event.AppEvent
import chess.application.event.AppEvent.*
import chess.domain.model.{GameStatus, Move}

/** Maps [[AppEvent]] values to outbound WebSocket JSON messages.
 *
 *  === Message structure ===
 *  Every message includes:
 *  - `eventType`  — the event case class name (e.g. `"MoveApplied"`)
 *  - `sessionId`  — UUID string
 *  - `gameId`     — UUID string
 *  - event-specific fields
 *
 *  === Field conventions ===
 *  Consistent with the REST adapter:
 *  - Moves: `{"from": "e2", "to": "e4"}` using algebraic notation; includes
 *    `"promotion"` field only when present.
 *  - Game status: `"status"` string (`"Ongoing"` / `"Checkmate"` / `"Draw"`)
 *    plus `"winner"` or `"drawReason"` as appropriate, matching [[chess.adapter.rest.mapper.GameMapper]].
 *  - Colors, lifecycles, modes: `.toString` on the enum case.
 */
object WebSocketMessageMapper:

  /** Convert an [[AppEvent]] to a JSON string ready for WebSocket delivery. */
  def toMessage(event: AppEvent): String = ujson.write(toJson(event))

  /** Build the [[ujson.Obj]] representation of `event`. */
  def toJson(event: AppEvent): ujson.Obj = event match

    case SessionCreated(sid, gid, mode, white, black) =>
      ujson.Obj(
        "eventType"       -> "SessionCreated",
        "sessionId"       -> sid.value.toString,
        "gameId"          -> gid.value.toString,
        "mode"            -> mode.toString,
        "whiteController" -> white.toString,
        "blackController" -> black.toString
      )

    case SessionLifecycleChanged(sid, gid, from, to) =>
      ujson.Obj(
        "eventType" -> "SessionLifecycleChanged",
        "sessionId" -> sid.value.toString,
        "gameId"    -> gid.value.toString,
        "from"      -> from.toString,
        "to"        -> to.toString
      )

    case MoveApplied(sid, gid, move, player) =>
      ujson.Obj(
        "eventType"     -> "MoveApplied",
        "sessionId"     -> sid.value.toString,
        "gameId"        -> gid.value.toString,
        "move"          -> moveJson(move),
        "currentPlayer" -> player.toString
      )

    case PromotionPending(sid, gid) =>
      ujson.Obj(
        "eventType" -> "PromotionPending",
        "sessionId" -> sid.value.toString,
        "gameId"    -> gid.value.toString
      )

    case GameFinished(sid, gid, status) =>
      val obj = ujson.Obj(
        "eventType" -> "GameFinished",
        "sessionId" -> sid.value.toString,
        "gameId"    -> gid.value.toString
      )
      addStatusFields(obj, status)
      obj

    case AITurnRequested(sid, gid, player) =>
      ujson.Obj(
        "eventType"     -> "AITurnRequested",
        "sessionId"     -> sid.value.toString,
        "gameId"        -> gid.value.toString,
        "currentPlayer" -> player.toString
      )

    case AITurnCompleted(sid, gid, move) =>
      ujson.Obj(
        "eventType" -> "AITurnCompleted",
        "sessionId" -> sid.value.toString,
        "gameId"    -> gid.value.toString,
        "move"      -> moveJson(move)
      )

    case AITurnFailed(sid, gid, reason) =>
      ujson.Obj(
        "eventType" -> "AITurnFailed",
        "sessionId" -> sid.value.toString,
        "gameId"    -> gid.value.toString,
        "reason"    -> reason
      )

  // ── private helpers ─────────────────────────────────────────────────────────

  private def moveJson(move: Move): ujson.Obj =
    val obj = ujson.Obj("from" -> move.from.toString, "to" -> move.to.toString)
    move.promotion.foreach(pt => obj("promotion") = pt.toString)
    obj

  /** Mutate `obj` in-place with the status-specific fields.
   *
   *  Although [[AppEvent.GameFinished]] is only published for terminal states,
   *  all three [[GameStatus]] variants are handled for exhaustiveness.
   */
  private def addStatusFields(obj: ujson.Obj, status: GameStatus): Unit = status match
    case GameStatus.Ongoing(inCheck) =>
      obj("status")  = "Ongoing"
      obj("inCheck") = inCheck
    case GameStatus.Checkmate(winner) =>
      obj("status") = "Checkmate"
      obj("winner") = winner.toString
    case GameStatus.Draw(reason) =>
      obj("status")     = "Draw"
      obj("drawReason") = reason.toString
