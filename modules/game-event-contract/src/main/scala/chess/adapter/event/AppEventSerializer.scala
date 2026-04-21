package chess.adapter.event

import chess.application.event.AppEvent
import chess.application.port.event.TerminalEventJsonSerializer
import chess.application.session.model.{SessionMode, SideController}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.domain.model.{Color, DrawReason, GameStatus, Move, PieceType}
import ujson.Value

/** Serialises [[AppEvent]] instances to their JSON wire representation.
  *
  * Only the five boundary-crossing events defined in `docs/contracts/game-events-v1.md` are
  * serialised. All other variants return [[None]] — they are internal events that must not cross
  * service boundaries.
  *
  * Implements [[TerminalEventJsonSerializer]] so that it can be injected into application services
  * as the outbox-write serialiser without requiring those services to import adapter-layer types.
  *
  * ===Usage===
  * {{{
  *    AppEventSerializer.serialize(event) match
  *      case Some(json) => broker.publish(json)
  *      case None       => ()  // internal event; skip
  * }}}
  *
  * ===Design===
  * Explicit [[ujson]] construction is used throughout rather than type-class derivation. This makes
  * the wire shape visible at the call site and prevents accidental schema drift when domain types
  * are renamed. See `docs/contracts/game-events-v1.json` for the normative JSON Schema.
  *
  * Caller note: [[serialize]] never throws. The only exception path is [[AppEvent.GameFinished]]
  * carrying a non-terminal [[GameStatus]], which violates the [[AppEvent]] contract and is treated
  * as an `IllegalArgumentException`.
  */
object AppEventSerializer extends TerminalEventJsonSerializer:

  /** Serialise a boundary-relevant event to a JSON string.
    *
    * @return
    *   `Some(json)` for the five wire-contract events; `None` for all others
    */
  override def serialize(event: AppEvent): Option[String] = event match
    case e: AppEvent.SessionCreated   => Some(ujson.write(sessionCreatedJson(e)))
    case e: AppEvent.MoveApplied      => Some(ujson.write(moveAppliedJson(e)))
    case e: AppEvent.GameFinished     => Some(ujson.write(gameFinishedJson(e)))
    case e: AppEvent.GameResigned     => Some(ujson.write(gameResignedJson(e)))
    case e: AppEvent.SessionCancelled => Some(ujson.write(sessionCancelledJson(e)))
    case _                            => None

  // ── Per-event serialisers ─────────────────────────────────────────────────────

  private def sessionCreatedJson(e: AppEvent.SessionCreated): ujson.Obj =
    ujson.Obj(
      "type" -> "game.session.created.v1",
      "sessionId" -> e.sessionId.value.toString,
      "gameId" -> e.gameId.value.toString,
      "mode" -> sessionModeStr(e.mode),
      "whiteController" -> controllerStr(e.whiteController),
      "blackController" -> controllerStr(e.blackController)
    )

  private def moveAppliedJson(e: AppEvent.MoveApplied): ujson.Obj =
    ujson.Obj(
      "type" -> "game.move.applied.v1",
      "sessionId" -> e.sessionId.value.toString,
      "gameId" -> e.gameId.value.toString,
      "move" -> moveJson(e.move),
      "playerWhoMoved" -> colorStr(e.playerWhoMoved)
    )

  // GameFinished carries only Checkmate or Draw per AppEvent contract.
  // Resigned is a separate event (GameResigned); Ongoing should never appear.
  private def gameFinishedJson(e: AppEvent.GameFinished): ujson.Obj =
    e.status match
      case GameStatus.Checkmate(winner) =>
        ujson.Obj(
          "type" -> "game.finished.v1",
          "sessionId" -> e.sessionId.value.toString,
          "gameId" -> e.gameId.value.toString,
          "result" -> "Checkmate",
          "winner" -> colorStr(winner),
          "drawReason" -> ujson.Null
        )
      case GameStatus.Draw(reason) =>
        ujson.Obj(
          "type" -> "game.finished.v1",
          "sessionId" -> e.sessionId.value.toString,
          "gameId" -> e.gameId.value.toString,
          "result" -> "Draw",
          "winner" -> ujson.Null,
          "drawReason" -> drawReasonStr(reason)
        )
      case other =>
        throw IllegalArgumentException(
          s"GameFinished event carries non-terminal status: $other — only Checkmate and Draw are valid"
        )

  private def gameResignedJson(e: AppEvent.GameResigned): ujson.Obj =
    ujson.Obj(
      "type" -> "game.resigned.v1",
      "sessionId" -> e.sessionId.value.toString,
      "gameId" -> e.gameId.value.toString,
      "winner" -> colorStr(e.winner)
    )

  private def sessionCancelledJson(e: AppEvent.SessionCancelled): ujson.Obj =
    ujson.Obj(
      "type" -> "game.session.cancelled.v1",
      "sessionId" -> e.sessionId.value.toString,
      "gameId" -> e.gameId.value.toString
    )

  // ── Field encoders ────────────────────────────────────────────────────────────

  private def moveJson(move: Move): ujson.Obj =
    ujson.Obj(
      "from" -> ujson.Str(move.from.toString),
      "to" -> ujson.Str(move.to.toString),
      "promotion" -> move.promotion.fold(ujson.Null: Value)(pt => ujson.Str(pieceTypeStr(pt)))
    )

  private def colorStr(c: Color): String = c match
    case Color.White => "White"
    case Color.Black => "Black"

  private def sessionModeStr(m: SessionMode): String = m match
    case SessionMode.HumanVsHuman => "HumanVsHuman"
    case SessionMode.HumanVsAI    => "HumanVsAI"
    case SessionMode.AIVsAI       => "AIVsAI"

  private def controllerStr(c: SideController): String = c match
    case SideController.HumanLocal       => "HumanLocal"
    case SideController.HumanRemote      => "HumanRemote"
    case SideController.AI(Some(engine)) => s"AI:$engine"
    case SideController.AI(None)         => "AI"

  private def drawReasonStr(r: DrawReason): String = r match
    case DrawReason.Stalemate => "Stalemate"

  private def pieceTypeStr(pt: PieceType): String = pt match
    case PieceType.Queen  => "Queen"
    case PieceType.Rook   => "Rook"
    case PieceType.Bishop => "Bishop"
    case PieceType.Knight => "Knight"
    case PieceType.King   => "King"
    case PieceType.Pawn   => "Pawn"
