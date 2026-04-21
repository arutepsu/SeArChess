package chess.adapter.repository.sqlite

import chess.application.port.repository.RepositoryError
import chess.domain.model.{Board, Color, DrawReason, GameStatus, Move, Piece, PieceType, Position}
import chess.domain.state.{CastlingRights, EnPassantState, GameState}
import ujson.Value

/** Bidirectional JSON codec for [[GameState]].
 *
 *  Serialization uses explicit `ujson.Obj` construction — same approach as
 *  [[chess.adapter.event.AppEventSerializer]] — to keep the storage shape
 *  visible and prevent accidental drift when domain types are renamed.
 *
 *  The stored JSON is internal to this adapter; it is NOT part of the public
 *  wire contract defined in `docs/contracts/game-events-v1.md`.
 */
object GameStateJson:

  // ── Serialization ─────────────────────────────────────────────────────────

  def encode(state: GameState): String =
    ujson.write(
      ujson.Obj(
        "currentPlayer"  -> colorStr(state.currentPlayer),
        "status"         -> statusJson(state.status),
        "board"          -> boardJson(state.board),
        "moveHistory"    -> ujson.Arr(state.moveHistory.map(moveJson)*),
        "castlingRights" -> castlingJson(state.castlingRights),
        "enPassant"      -> state.enPassantState.fold(ujson.Null: Value)(enPassantJson),
        "halfmoveClock"  -> state.halfmoveClock,
        "fullmoveNumber" -> state.fullmoveNumber
      )
    )

  private def colorStr(c: Color): String = c match
    case Color.White => "White"
    case Color.Black => "Black"

  private def pieceTypeStr(pt: PieceType): String = pt match
    case PieceType.Pawn   => "Pawn"
    case PieceType.Knight => "Knight"
    case PieceType.Bishop => "Bishop"
    case PieceType.Rook   => "Rook"
    case PieceType.Queen  => "Queen"
    case PieceType.King   => "King"

  private def statusJson(s: GameStatus): ujson.Obj = s match
    case GameStatus.Ongoing(inCheck)  =>
      ujson.Obj("type" -> "Ongoing",   "inCheck" -> inCheck)
    case GameStatus.Checkmate(winner) =>
      ujson.Obj("type" -> "Checkmate", "winner"  -> colorStr(winner))
    case GameStatus.Draw(reason)      =>
      ujson.Obj("type" -> "Draw",      "reason"  -> drawReasonStr(reason))
    case GameStatus.Resigned(winner)  =>
      ujson.Obj("type" -> "Resigned",  "winner"  -> colorStr(winner))

  private def drawReasonStr(r: DrawReason): String = r match
    case DrawReason.Stalemate => "Stalemate"

  private def boardJson(board: Board): ujson.Arr =
    ujson.Arr(board.pieces.map { case (pos, piece) =>
      ujson.Obj(
        "square"    -> pos.toString,
        "color"     -> colorStr(piece.color),
        "pieceType" -> pieceTypeStr(piece.pieceType)
      )
    }*)

  private def moveJson(m: Move): ujson.Obj =
    ujson.Obj(
      "from"      -> m.from.toString,
      "to"        -> m.to.toString,
      "promotion" -> m.promotion.fold(ujson.Null: Value)(pt => ujson.Str(pieceTypeStr(pt)))
    )

  private def castlingJson(r: CastlingRights): ujson.Obj =
    ujson.Obj("wk" -> r.whiteKingSide, "wq" -> r.whiteQueenSide,
              "bk" -> r.blackKingSide, "bq" -> r.blackQueenSide)

  private def enPassantJson(ep: EnPassantState): ujson.Obj =
    ujson.Obj(
      "target"          -> ep.targetSquare.toString,
      "capturablePawn"  -> ep.capturablePawnSquare.toString,
      "pawnColor"       -> colorStr(ep.pawnColor)
    )

  // ── Deserialization ────────────────────────────────────────────────────────

  def decode(json: String): Either[RepositoryError, GameState] =
    try
      val obj = ujson.read(json).obj
      for
        currentPlayer  <- readColor(obj("currentPlayer").str)
        status         <- readStatus(obj("status").obj)
        board          <- readBoard(obj("board").arr)
        moveHistory    <- readMoveHistory(obj("moveHistory").arr)
        castling       <- readCastling(obj("castlingRights").obj)
        enPassant      <- readEnPassant(obj("enPassant"))
        halfmoveClock   = obj("halfmoveClock").num.toInt
        fullmoveNumber  = obj("fullmoveNumber").num.toInt
      yield GameState(
        board          = board,
        currentPlayer  = currentPlayer,
        moveHistory    = moveHistory,
        status         = status,
        castlingRights = castling,
        enPassantState = enPassant,
        halfmoveClock  = halfmoveClock,
        fullmoveNumber = fullmoveNumber
      )
    catch
      case e: Exception =>
        Left(RepositoryError.StorageFailure(s"GameState decode failed: ${e.getMessage}"))

  private def readColor(s: String): Either[RepositoryError, Color] = s match
    case "White" => Right(Color.White)
    case "Black" => Right(Color.Black)
    case other   => Left(RepositoryError.StorageFailure(s"Unknown color: $other"))

  private def readPieceType(s: String): Either[RepositoryError, PieceType] = s match
    case "Pawn"   => Right(PieceType.Pawn)
    case "Knight" => Right(PieceType.Knight)
    case "Bishop" => Right(PieceType.Bishop)
    case "Rook"   => Right(PieceType.Rook)
    case "Queen"  => Right(PieceType.Queen)
    case "King"   => Right(PieceType.King)
    case other    => Left(RepositoryError.StorageFailure(s"Unknown piece type: $other"))

  private def readPosition(s: String): Either[RepositoryError, Position] =
    Position.fromAlgebraic(s).left.map(e => RepositoryError.StorageFailure(e.toString))

  private def readStatus(obj: collection.mutable.Map[String, Value]): Either[RepositoryError, GameStatus] =
    obj("type").str match
      case "Ongoing"   => Right(GameStatus.Ongoing(obj("inCheck").bool))
      case "Checkmate" => readColor(obj("winner").str).map(GameStatus.Checkmate.apply)
      case "Draw"      => readDrawReason(obj("reason").str).map(GameStatus.Draw.apply)
      case "Resigned"  => readColor(obj("winner").str).map(GameStatus.Resigned.apply)
      case other       => Left(RepositoryError.StorageFailure(s"Unknown status type: $other"))

  private def readDrawReason(s: String): Either[RepositoryError, DrawReason] = s match
    case "Stalemate" => Right(DrawReason.Stalemate)
    case other       => Left(RepositoryError.StorageFailure(s"Unknown draw reason: $other"))

  private def readBoard(arr: collection.mutable.ArrayBuffer[Value]): Either[RepositoryError, Board] =
    arr.foldLeft(Right(Board.empty): Either[RepositoryError, Board]) { (acc, v) =>
      acc.flatMap { board =>
        val obj = v.obj
        for
          pos   <- readPosition(obj("square").str)
          color <- readColor(obj("color").str)
          pt    <- readPieceType(obj("pieceType").str)
        yield board.place(pos, Piece(color, pt))
      }
    }

  private def readMove(v: Value): Either[RepositoryError, Move] =
    val obj = v.obj
    for
      from      <- readPosition(obj("from").str)
      to        <- readPosition(obj("to").str)
      promotion <- obj("promotion") match
                     case ujson.Null => Right(None)
                     case s          => readPieceType(s.str).map(Some.apply)
    yield Move(from, to, promotion)

  private def readMoveHistory(
    arr: collection.mutable.ArrayBuffer[Value]
  ): Either[RepositoryError, List[Move]] =
    arr.foldLeft(Right(List.empty[Move]): Either[RepositoryError, List[Move]]) { (acc, v) =>
      acc.flatMap(moves => readMove(v).map(m => moves :+ m))
    }

  private def readCastling(obj: collection.mutable.Map[String, Value]): Either[RepositoryError, CastlingRights] =
    Right(CastlingRights(
      whiteKingSide  = obj("wk").bool,
      whiteQueenSide = obj("wq").bool,
      blackKingSide  = obj("bk").bool,
      blackQueenSide = obj("bq").bool
    ))

  private def readEnPassant(v: Value): Either[RepositoryError, Option[EnPassantState]] =
    v match
      case ujson.Null => Right(None)
      case obj        =>
        val m = obj.obj
        for
          target         <- readPosition(m("target").str)
          capturablePawn <- readPosition(m("capturablePawn").str)
          pawnColor      <- readColor(m("pawnColor").str)
        yield Some(EnPassantState(target, capturablePawn, pawnColor))
