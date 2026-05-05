package chess.adapter.repository.mongo

import chess.application.port.repository.RepositoryError
import chess.application.session.model.SessionIds.GameId
import chess.domain.model.{Board, Color, DrawReason, GameStatus, Move, Piece, PieceType, Position}
import chess.domain.state.{CastlingRights, EnPassantState, GameState}
import org.bson.Document

import scala.jdk.CollectionConverters.*

private[mongo] object MongoGameStateMapper:

  def toDocument(gameId: GameId, state: GameState): Document =
    Document()
      .append("_id", gameId.value.toString)
      .append("gameId", gameId.value.toString)
      .append("state", stateDocument(state))

  def toGameState(document: Document): Either[RepositoryError, GameState] =
    Option(document.get("state", classOf[Document])) match
      case Some(state) => readState(state)
      case None        => storageFailure("Missing GameState document")

  private def stateDocument(state: GameState): Document =
    val document = Document()
      .append("currentPlayer", colorString(state.currentPlayer))
      .append("status", statusDocument(state.status))
      .append("board", state.board.pieces.map { case (position, piece) =>
        pieceDocument(position, piece)
      }.asJava)
      .append("moveHistory", state.moveHistory.map(moveDocument).asJava)
      .append("castlingRights", castlingDocument(state.castlingRights))
      .append("halfmoveClock", state.halfmoveClock)
      .append("fullmoveNumber", state.fullmoveNumber)
    state.enPassantState.foreach(state => document.append("enPassant", enPassantDocument(state)))
    document

  private def pieceDocument(position: Position, piece: Piece): Document =
    Document()
      .append("square", position.toString)
      .append("color", colorString(piece.color))
      .append("pieceType", pieceTypeString(piece.pieceType))

  private def moveDocument(move: Move): Document =
    val document = Document()
      .append("from", move.from.toString)
      .append("to", move.to.toString)
    move.promotion.foreach(pieceType => document.append("promotion", pieceTypeString(pieceType)))
    document

  private def statusDocument(status: GameStatus): Document =
    status match
      case GameStatus.Ongoing(inCheck) =>
        Document("type", "Ongoing").append("inCheck", inCheck)
      case GameStatus.Checkmate(winner) =>
        Document("type", "Checkmate").append("winner", colorString(winner))
      case GameStatus.Draw(reason) =>
        Document("type", "Draw").append("reason", drawReasonString(reason))
      case GameStatus.Resigned(winner) =>
        Document("type", "Resigned").append("winner", colorString(winner))

  private def castlingDocument(rights: CastlingRights): Document =
    Document()
      .append("wk", rights.whiteKingSide)
      .append("wq", rights.whiteQueenSide)
      .append("bk", rights.blackKingSide)
      .append("bq", rights.blackQueenSide)

  private def enPassantDocument(state: EnPassantState): Document =
    Document()
      .append("target", state.targetSquare.toString)
      .append("capturablePawn", state.capturablePawnSquare.toString)
      .append("pawnColor", colorString(state.pawnColor))

  private def readState(document: Document): Either[RepositoryError, GameState] =
    for
      currentPlayer <- readColor(document.getString("currentPlayer"))
      status <- readStatus(document.get("status", classOf[Document]))
      board <- readBoard(document.getList("board", classOf[Document]).asScala.toList)
      moveHistory <- readMoveHistory(
        document.getList("moveHistory", classOf[Document]).asScala.toList
      )
      castlingRights <- readCastling(document.get("castlingRights", classOf[Document]))
      enPassantState <- readEnPassant(Option(document.get("enPassant", classOf[Document])))
      halfmoveClock <- readInt(document, "halfmoveClock")
      fullmoveNumber <- readInt(document, "fullmoveNumber")
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

  private def readBoard(pieces: List[Document]): Either[RepositoryError, Board] =
    pieces.foldLeft(Right(Board.empty): Either[RepositoryError, Board]) { (acc, document) =>
      acc.flatMap { board =>
        for
          position <- readPosition(document.getString("square"))
          color <- readColor(document.getString("color"))
          pieceType <- readPieceType(document.getString("pieceType"))
        yield board.place(position, Piece(color, pieceType))
      }
    }

  private def readMoveHistory(moves: List[Document]): Either[RepositoryError, List[Move]] =
    moves.foldLeft(Right(List.empty[Move]): Either[RepositoryError, List[Move]]) {
      (acc, document) =>
        acc.flatMap(history => readMove(document).map(move => history :+ move))
    }

  private def readMove(document: Document): Either[RepositoryError, Move] =
    for
      from <- readPosition(document.getString("from"))
      to <- readPosition(document.getString("to"))
      promotion <- Option(document.getString("promotion")) match
        case None        => Right(None)
        case Some(value) => readPieceType(value).map(Some.apply)
    yield Move(from, to, promotion)

  private def readStatus(document: Document): Either[RepositoryError, GameStatus] =
    Option(document) match
      case None => storageFailure("Missing GameStatus document")
      case Some(value) =>
        value.getString("type") match
          case "Ongoing" =>
            Right(GameStatus.Ongoing(value.getBoolean("inCheck")))
          case "Checkmate" =>
            readColor(value.getString("winner")).map(GameStatus.Checkmate.apply)
          case "Draw" =>
            readDrawReason(value.getString("reason")).map(GameStatus.Draw.apply)
          case "Resigned" =>
            readColor(value.getString("winner")).map(GameStatus.Resigned.apply)
          case other =>
            storageFailure(s"Unknown game status in Mongo document: $other")

  private def readCastling(document: Document): Either[RepositoryError, CastlingRights] =
    Option(document) match
      case None => storageFailure("Missing castling rights document")
      case Some(value) =>
        Right(
          CastlingRights(
            whiteKingSide = value.getBoolean("wk"),
            whiteQueenSide = value.getBoolean("wq"),
            blackKingSide = value.getBoolean("bk"),
            blackQueenSide = value.getBoolean("bq")
          )
        )

  private def readEnPassant(
      document: Option[Document]
  ): Either[RepositoryError, Option[EnPassantState]] =
    document match
      case None => Right(None)
      case Some(value) =>
        for
          target <- readPosition(value.getString("target"))
          capturablePawn <- readPosition(value.getString("capturablePawn"))
          pawnColor <- readColor(value.getString("pawnColor"))
        yield Some(EnPassantState(target, capturablePawn, pawnColor))

  private def readColor(value: String): Either[RepositoryError, Color] =
    value match
      case "White" => Right(Color.White)
      case "Black" => Right(Color.Black)
      case other   => storageFailure(s"Unknown color in Mongo document: $other")

  private def readPieceType(value: String): Either[RepositoryError, PieceType] =
    value match
      case "Pawn"   => Right(PieceType.Pawn)
      case "Knight" => Right(PieceType.Knight)
      case "Bishop" => Right(PieceType.Bishop)
      case "Rook"   => Right(PieceType.Rook)
      case "Queen"  => Right(PieceType.Queen)
      case "King"   => Right(PieceType.King)
      case other    => storageFailure(s"Unknown piece type in Mongo document: $other")

  private def readDrawReason(value: String): Either[RepositoryError, DrawReason] =
    value match
      case "Stalemate" => Right(DrawReason.Stalemate)
      case other       => storageFailure(s"Unknown draw reason in Mongo document: $other")

  private def readPosition(value: String): Either[RepositoryError, Position] =
    Position.fromAlgebraic(value).left.map(error => RepositoryError.StorageFailure(error.toString))

  private def readInt(document: Document, field: String): Either[RepositoryError, Int] =
    Option(document.get(field)) match
      case Some(value: java.lang.Integer) => Right(value.intValue)
      case Some(value: java.lang.Long)    => Right(value.intValue)
      case Some(value: java.lang.Double)  => Right(value.intValue)
      case Some(value)                    => storageFailure(s"Expected numeric $field, found $value")
      case None                           => storageFailure(s"Missing numeric field $field")

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

  private def storageFailure[A](message: String): Either[RepositoryError, A] =
    Left(RepositoryError.StorageFailure(message))
