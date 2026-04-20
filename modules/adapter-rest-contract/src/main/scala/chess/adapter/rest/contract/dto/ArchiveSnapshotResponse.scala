package chess.adapter.rest.contract.dto

import ujson.Value

final case class CastlingRightsDto(
  whiteKingSide:  Boolean,
  whiteQueenSide: Boolean,
  blackKingSide:  Boolean,
  blackQueenSide: Boolean
)

final case class EnPassantDto(
  targetSquare:         String,
  capturablePawnSquare: String,
  pawnColor:            String
)

final case class ArchiveGameStateResponse(
  game:            GameResponse,
  castlingRights:  CastlingRightsDto,
  enPassant:       Option[EnPassantDto]
)

final case class ArchiveClosureResponse(
  kind:       String,
  winner:     Option[String],
  drawReason: Option[String]
)

final case class ArchiveSnapshotResponse(
  sessionId:       String,
  gameId:          String,
  mode:            String,
  whiteController: String,
  blackController: String,
  closure:         ArchiveClosureResponse,
  finalState:      ArchiveGameStateResponse,
  createdAt:       String,
  closedAt:        String
)

object ArchiveSnapshotResponse:
  def toJson(r: ArchiveSnapshotResponse): Value =
    ujson.Obj(
      "sessionId"       -> r.sessionId,
      "gameId"          -> r.gameId,
      "mode"            -> r.mode,
      "whiteController" -> r.whiteController,
      "blackController" -> r.blackController,
      "closure"         -> ujson.Obj(
        "kind"       -> r.closure.kind,
        "winner"     -> r.closure.winner.fold(ujson.Null: Value)(ujson.Str(_)),
        "drawReason" -> r.closure.drawReason.fold(ujson.Null: Value)(ujson.Str(_))
      ),
      "finalState"      -> ujson.Obj(
        "game"           -> GameResponse.toJson(r.finalState.game),
        "castlingRights" -> ujson.Obj(
          "whiteKingSide"  -> r.finalState.castlingRights.whiteKingSide,
          "whiteQueenSide" -> r.finalState.castlingRights.whiteQueenSide,
          "blackKingSide"  -> r.finalState.castlingRights.blackKingSide,
          "blackQueenSide" -> r.finalState.castlingRights.blackQueenSide
        ),
        "enPassant"      -> r.finalState.enPassant.fold(ujson.Null: Value)(ep =>
          ujson.Obj(
            "targetSquare"         -> ep.targetSquare,
            "capturablePawnSquare" -> ep.capturablePawnSquare,
            "pawnColor"            -> ep.pawnColor
          )
        )
      ),
      "createdAt"       -> r.createdAt,
      "closedAt"        -> r.closedAt
    )
