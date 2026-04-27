package chess.adapter.rest.contract.dto

import ujson.Value

/** Canonical transport body for loading or replacing a persisted session aggregate.
  *
  * Includes the session metadata, current game snapshot, and the canonical state fields that are
  * required to reconstruct a full [[chess.domain.state.GameState]] on save.
  */
final case class SessionStateResponse(
    session: SessionResponse,
    game: GameSnapshot,
    castlingRights: CastlingRightsDto,
    enPassant: Option[EnPassantDto]
)

object SessionStateResponse:
  def toJson(r: SessionStateResponse): Value =
    ujson.Obj(
      "session" -> SessionResponse.toJson(r.session),
      "game" -> GameSnapshot.toJson(r.game),
      "castlingRights" -> ujson.Obj(
        "whiteKingSide" -> r.castlingRights.whiteKingSide,
        "whiteQueenSide" -> r.castlingRights.whiteQueenSide,
        "blackKingSide" -> r.castlingRights.blackKingSide,
        "blackQueenSide" -> r.castlingRights.blackQueenSide
      ),
      "enPassant" -> r.enPassant.fold(ujson.Null: Value)(ep =>
        ujson.Obj(
          "targetSquare" -> ep.targetSquare,
          "capturablePawnSquare" -> ep.capturablePawnSquare,
          "pawnColor" -> ep.pawnColor
        )
      )
    )

  def fromJson(body: String): Either[String, SessionStateResponse] =
    try
      val json = ujson.read(body)
      val sessionJson = json("session")
      val gameJson = json("game")
      val castlingJson = json("castlingRights")

      Right(
        SessionStateResponse(
          session = SessionResponse(
            sessionId = sessionJson("sessionId").str,
            gameId = sessionJson("gameId").str,
            mode = sessionJson("mode").str,
            lifecycle = sessionJson("lifecycle").str,
            whiteController = sessionJson("whiteController").str,
            blackController = sessionJson("blackController").str,
            createdAt = sessionJson("createdAt").str,
            updatedAt = sessionJson("updatedAt").str
          ),
          game = GameSnapshot(
            gameId = gameJson("gameId").str,
            currentPlayer = gameJson("currentPlayer").str,
            status = gameJson("status").str,
            inCheck = gameJson("inCheck").bool,
            winner = stringOpt(gameJson("winner")),
            drawReason = stringOpt(gameJson("drawReason")),
            fullmoveNumber = gameJson("fullmoveNumber").num.toInt,
            halfmoveClock = gameJson("halfmoveClock").num.toInt,
            board = gameJson("board").arr.toList.map(pieceFromJson),
            moveHistory = gameJson("moveHistory").arr.toList.map(moveFromJson),
            lastMove = valueOpt(gameJson("lastMove")).map(moveFromJson),
            promotionPending = gameJson("promotionPending").bool,
            legalTargetsByFrom = gameJson("legalTargetsByFrom").obj.view
              .mapValues(_.arr.toList.map(_.str))
              .toMap
          ),
          castlingRights = CastlingRightsDto(
            whiteKingSide = castlingJson("whiteKingSide").bool,
            whiteQueenSide = castlingJson("whiteQueenSide").bool,
            blackKingSide = castlingJson("blackKingSide").bool,
            blackQueenSide = castlingJson("blackQueenSide").bool
          ),
          enPassant = valueOpt(json("enPassant")).map(ep =>
            EnPassantDto(
              targetSquare = ep("targetSquare").str,
              capturablePawnSquare = ep("capturablePawnSquare").str,
              pawnColor = ep("pawnColor").str
            )
          )
        )
      )
    catch case _: Exception => Left("Malformed JSON in request body")

  private def stringOpt(value: ujson.Value): Option[String] =
    value match
      case ujson.Null => None
      case other      => Some(other.str)

  private def valueOpt(value: ujson.Value): Option[ujson.Value] =
    value match
      case ujson.Null => None
      case other      => Some(other)

  private def moveFromJson(json: ujson.Value): MoveHistoryEntry =
    MoveHistoryEntry(
      from = json("from").str,
      to = json("to").str,
      promotion = stringOpt(json("promotion"))
    )

  private def pieceFromJson(json: ujson.Value): PieceDto =
    PieceDto(
      square = json("square").str,
      color = json("color").str,
      pieceType = json("pieceType").str
    )
