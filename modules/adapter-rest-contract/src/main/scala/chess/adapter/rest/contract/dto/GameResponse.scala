package chess.adapter.rest.contract.dto

import ujson.Value

/** A single move in the game's move history. */
final case class MoveHistoryEntry(from: String, to: String, promotion: Option[String])

/** A single occupied square on the board. */
final case class PieceDto(square: String, color: String, pieceType: String)

/** Transport representation of a [[chess.domain.state.GameState]].
 *
 *  @param gameId              opaque UUID string identifying the game record
 *  @param currentPlayer       "White" or "Black"
 *  @param status              "Ongoing", "Checkmate", or "Draw"
 *  @param inCheck             true when status is Ongoing and the current player is in check
 *  @param winner              present when status is "Checkmate"; "White" or "Black"
 *  @param drawReason          present when status is "Draw"; e.g. "Stalemate"
 *  @param fullmoveNumber      incremented after each Black move (starts at 1)
 *  @param halfmoveClock       half-moves since last capture or pawn advance
 *  @param board               all occupied squares
 *  @param moveHistory         ordered list of moves played so far
 *  @param lastMove            the most recent move, if any
 *  @param promotionPending    true when the position requires a promotion choice before
 *                             play can continue; false in all current REST v1 flows
 *  @param legalTargetsByFrom  legal target squares for each current-player source square;
 *                             keys are source squares with at least one legal move
 */
final case class GameResponse(
  gameId:             String,
  currentPlayer:      String,
  status:             String,
  inCheck:            Boolean,
  winner:             Option[String],
  drawReason:         Option[String],
  fullmoveNumber:     Int,
  halfmoveClock:      Int,
  board:              List[PieceDto],
  moveHistory:        List[MoveHistoryEntry],
  lastMove:           Option[MoveHistoryEntry],
  promotionPending:   Boolean,
  legalTargetsByFrom: Map[String, List[String]]
)

object GameResponse:
  def toJson(r: GameResponse): Value =
    def moveEntryJson(m: MoveHistoryEntry): Value =
      ujson.Obj(
        "from"      -> ujson.Str(m.from),
        "to"        -> ujson.Str(m.to),
        "promotion" -> m.promotion.fold(ujson.Null: Value)(ujson.Str(_))
      )

    ujson.Obj(
      "gameId"             -> ujson.Str(r.gameId),
      "currentPlayer"      -> ujson.Str(r.currentPlayer),
      "status"             -> ujson.Str(r.status),
      "inCheck"            -> ujson.Bool(r.inCheck),
      "winner"             -> r.winner.fold(ujson.Null: Value)(ujson.Str(_)),
      "drawReason"         -> r.drawReason.fold(ujson.Null: Value)(ujson.Str(_)),
      "fullmoveNumber"     -> ujson.Num(r.fullmoveNumber.toDouble),
      "halfmoveClock"      -> ujson.Num(r.halfmoveClock.toDouble),
      "board"              -> ujson.Arr.from(r.board.map(p =>
        ujson.Obj(
          "square"    -> ujson.Str(p.square),
          "color"     -> ujson.Str(p.color),
          "pieceType" -> ujson.Str(p.pieceType)
        )
      )),
      "moveHistory"        -> ujson.Arr.from(r.moveHistory.map(moveEntryJson)),
      "lastMove"           -> r.lastMove.fold(ujson.Null: Value)(moveEntryJson),
      "promotionPending"   -> ujson.Bool(r.promotionPending),
      "legalTargetsByFrom" -> ujson.Obj.from(r.legalTargetsByFrom.map { case (sq, targets) =>
        sq -> (ujson.Arr.from(targets.map(ujson.Str(_))): Value)
      })
    )
