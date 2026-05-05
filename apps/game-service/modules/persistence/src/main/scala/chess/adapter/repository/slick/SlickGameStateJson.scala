package chess.adapter.repository.slick

import chess.application.port.repository.RepositoryError
import chess.domain.model.{Board, Color, DrawReason, GameStatus, Move, Piece, PieceType, Position}
import chess.domain.state.{CastlingRights, EnPassantState, GameState}
import ujson.Value

object SlickGameStateJson:

  def encode(state: GameState): String =
    ujson.write(
      ujson.Obj(
        "currentPlayer" -> colorString(state.currentPlayer),
        "status" -> statusJson(state.status),
        "board" -> boardJson(state.board),
        "moveHistory" -> ujson.Arr(state.moveHistory.map(moveJson)*),
        "castlingRights" -> castlingJson(state.castlingRights),
        "enPassant" -> state.enPassantState.fold(ujson.Null: Value)(enPassantJson),
        "halfmoveClock" -> state.halfmoveClock,
        "fullmoveNumber" -> state.fullmoveNumber
      )
    )

  def decode(json: String): Either[RepositoryError, GameState] =
    try
      val obj = ujson.read(json).obj
      for
        currentPlayer <- readColor(obj("currentPlayer").str)
        status <- readStatus(obj("status").obj)
        board <- readBoard(obj("board").arr)
        moveHistory <- readMoveHistory(obj("moveHistory").arr)
        castlingRights <- readCastling(obj("castlingRights").obj)
        enPassantState <- readEnPassant(obj("enPassant"))
        halfmoveClock = obj("halfmoveClock").num.toInt
        fullmoveNumber = obj("fullmoveNumber").num.toInt
      yield GameState(
        board = board,
        currentPlayer = currentPlayer,
        moveHistory = moveHistory,
        status = status,
        castlingRights = castlingRights,
        enPassantState = enPassantState,
        halfmoveClock = halfmoveClock,
        fullmoveNumber = fullmoveNumber
      )
    catch case e: Exception =>
      Left(RepositoryError.StorageFailure(s"GameState decode failed: ${e.getMessage}"))

  private def colorString(color: Color): String =
    color match
      case Color.White => "White"
      case Color.Black => "Black"

  private def pieceTypeString(pieceType: PieceType): String =
    pieceType match
      case PieceType.Pawn   => "Pawn"
      case PieceType.Knight => "Knight"
      case PieceType.Bishop => "Bishop"
      case PieceType.Rook   => "Rook"
      case PieceType.Queen  => "Queen"
      case PieceType.King   => "King"

  private def drawReasonString(reason: DrawReason): String =
    reason match
      case DrawReason.Stalemate => "Stalemate"

  private def statusJson(status: GameStatus): ujson.Obj =
    status match
      case GameStatus.Ongoing(inCheck) =>
        ujson.Obj("type" -> "Ongoing", "inCheck" -> inCheck)
      case GameStatus.Checkmate(winner) =>
        ujson.Obj("type" -> "Checkmate", "winner" -> colorString(winner))
      case GameStatus.Draw(reason) =>
        ujson.Obj("type" -> "Draw", "reason" -> drawReasonString(reason))
      case GameStatus.Resigned(winner) =>
        ujson.Obj("type" -> "Resigned", "winner" -> colorString(winner))

  private def boardJson(board: Board): ujson.Arr =
    ujson.Arr(board.pieces.map { case (position, piece) =>
      ujson.Obj(
        "square" -> position.toString,
        "color" -> colorString(piece.color),
        "pieceType" -> pieceTypeString(piece.pieceType)
      )
    }*)

  private def moveJson(move: Move): ujson.Obj =
    ujson.Obj(
      "from" -> move.from.toString,
      "to" -> move.to.toString,
      "promotion" -> move.promotion.fold(ujson.Null: Value)(pt => ujson.Str(pieceTypeString(pt)))
    )

  private def castlingJson(rights: CastlingRights): ujson.Obj =
    ujson.Obj(
      "wk" -> rights.whiteKingSide,
      "wq" -> rights.whiteQueenSide,
      "bk" -> rights.blackKingSide,
      "bq" -> rights.blackQueenSide
    )

  private def enPassantJson(state: EnPassantState): ujson.Obj =
    ujson.Obj(
      "target" -> state.targetSquare.toString,
      "capturablePawn" -> state.capturablePawnSquare.toString,
      "pawnColor" -> colorString(state.pawnColor)
    )

  private def readColor(value: String): Either[RepositoryError, Color] =
    value match
      case "White" => Right(Color.White)
      case "Black" => Right(Color.Black)
      case other   => storageFailure(s"Unknown color: $other")

  private def readPieceType(value: String): Either[RepositoryError, PieceType] =
    value match
      case "Pawn"   => Right(PieceType.Pawn)
      case "Knight" => Right(PieceType.Knight)
      case "Bishop" => Right(PieceType.Bishop)
      case "Rook"   => Right(PieceType.Rook)
      case "Queen"  => Right(PieceType.Queen)
      case "King"   => Right(PieceType.King)
      case other    => storageFailure(s"Unknown piece type: $other")

  private def readPosition(value: String): Either[RepositoryError, Position] =
    Position.fromAlgebraic(value).left.map(error => RepositoryError.StorageFailure(error.toString))

  private def readStatus(
      obj: collection.mutable.Map[String, Value]
  ): Either[RepositoryError, GameStatus] =
    obj("type").str match
      case "Ongoing"   => Right(GameStatus.Ongoing(obj("inCheck").bool))
      case "Checkmate" => readColor(obj("winner").str).map(GameStatus.Checkmate.apply)
      case "Draw"      => readDrawReason(obj("reason").str).map(GameStatus.Draw.apply)
      case "Resigned"  => readColor(obj("winner").str).map(GameStatus.Resigned.apply)
      case other       => storageFailure(s"Unknown status type: $other")

  private def readDrawReason(value: String): Either[RepositoryError, DrawReason] =
    value match
      case "Stalemate" => Right(DrawReason.Stalemate)
      case other       => storageFailure(s"Unknown draw reason: $other")

  private def readBoard(
      values: collection.mutable.ArrayBuffer[Value]
  ): Either[RepositoryError, Board] =
    values.foldLeft(Right(Board.empty): Either[RepositoryError, Board]) { (acc, value) =>
      acc.flatMap { board =>
        val obj = value.obj
        for
          position <- readPosition(obj("square").str)
          color <- readColor(obj("color").str)
          pieceType <- readPieceType(obj("pieceType").str)
        yield board.place(position, Piece(color, pieceType))
      }
    }

  private def readMove(value: Value): Either[RepositoryError, Move] =
    val obj = value.obj
    for
      from <- readPosition(obj("from").str)
      to <- readPosition(obj("to").str)
      promotion <- obj("promotion") match
        case ujson.Null => Right(None)
        case raw        => readPieceType(raw.str).map(Some.apply)
    yield Move(from, to, promotion)

  private def readMoveHistory(
      values: collection.mutable.ArrayBuffer[Value]
  ): Either[RepositoryError, List[Move]] =
    values.foldLeft(Right(List.empty[Move]): Either[RepositoryError, List[Move]]) { (acc, value) =>
      acc.flatMap(moves => readMove(value).map(move => moves :+ move))
    }

  private def readCastling(
      obj: collection.mutable.Map[String, Value]
  ): Either[RepositoryError, CastlingRights] =
    Right(
      CastlingRights(
        whiteKingSide = obj("wk").bool,
        whiteQueenSide = obj("wq").bool,
        blackKingSide = obj("bk").bool,
        blackQueenSide = obj("bq").bool
      )
    )

  private def readEnPassant(value: Value): Either[RepositoryError, Option[EnPassantState]] =
    value match
      case ujson.Null => Right(None)
      case raw =>
        val obj = raw.obj
        for
          target <- readPosition(obj("target").str)
          capturablePawn <- readPosition(obj("capturablePawn").str)
          pawnColor <- readColor(obj("pawnColor").str)
        yield Some(EnPassantState(target, capturablePawn, pawnColor))

  private def storageFailure[A](message: String): Either[RepositoryError, A] =
    Left(RepositoryError.StorageFailure(message))
