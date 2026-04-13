package chess.notation.json


import chess.domain.state._
import chess.domain.model._
import chess.notation.api._
import scala.util.{Try, Success, Failure}
import ujson.{Value, Obj, Arr}


object JsonNotationFacade extends NotationFacade[GameState]:

  override def parse(format: NotationFormat, input: String): Either[ParseFailure, ParsedNotation] =
    format match
      case NotationFormat.JSON =>
        Right(ParsedNotation.ParsedJsonGame(input))
      case other =>
        Left(ParseFailure.StructuralError(s"No parser for format: $other"))

  override def executeImport(
    parsed: ParsedNotation,
    target: ImportTarget
  ): Either[NotationFailure, ImportResult[GameState]] =
    (parsed, target) match
      case (ParsedNotation.ParsedJsonGame(raw), ImportTarget.GameTarget) =>
        Try(ujson.read(raw)) match {
          case Success(json) =>
            fromJsonGameState(json) match {
              case Right(gs) =>
                Right(
                  ImportResult.GameImportResult(
                    data = gs,
                    sourceFormat = NotationFormat.JSON,
                    metadata = GameImportMetadata(),
                    replay = None,
                    warnings = Nil
                  )
                )
              case Left(msg) =>
                Left(ImportFailure.MappingError(msg))
            }
          case Failure(e) =>
            Left(ImportFailure.MappingError(s"Invalid JSON: ${e.getMessage}"))
        }
      case (ParsedNotation.ParsedJsonPosition(raw), ImportTarget.PositionTarget) =>
        Left(ImportFailure.MappingError("JSON position import not implemented yet"))
      case _ =>
        Left(ImportFailure.IncompatibleTarget(
          parsedKind = parsed.kind,
          target = target,
          message = "JSON import: incompatible target or IR"
        ))

  override def executeExport(
    data: GameState,
    format: NotationFormat
  ): Either[NotationFailure, ExportResult] =
    format match
      case NotationFormat.JSON =>
        val json = toJsonGameState(data)
        Right(ExportResult(ujson.write(json, indent = 2), NotationFormat.JSON))
      case other =>
        Left(ExportFailure.UnsupportedExportFormat(other, s"Export to $other is not implemented"))

  // --- JSON helpers ---

  private def toJsonGameState(gs: GameState): Obj = {
    Obj(
      "board"          -> toJsonBoard(gs.board),
      "currentPlayer"  -> gs.currentPlayer.toString,
      "moveHistory"    -> Arr(gs.moveHistory.map(toJsonMove)*),
      "status"         -> toJsonGameStatus(gs.status),
      "castlingRights" -> toJsonCastlingRights(gs.castlingRights),
      "enPassantState" -> gs.enPassantState.map(toJsonEnPassantState).getOrElse(ujson.Null),
      "halfmoveClock"  -> gs.halfmoveClock,
      "fullmoveNumber" -> gs.fullmoveNumber
    )
  }

  private def toJsonBoard(board: Board): Arr =
    Arr(board.pieces.map { case (pos, piece) =>
      Obj(
        "pos"   -> toJsonPosition(pos),
        "piece" -> toJsonPiece(piece)
      )
    }*)

  private def toJsonPosition(pos: Position): Obj =
    Obj("file" -> pos.file, "rank" -> pos.rank)

  private def toJsonPiece(piece: Piece): Obj =
    Obj("color" -> piece.color.toString, "pieceType" -> piece.pieceType.toString)

  private def toJsonMove(move: Move): Obj =
    Obj(
      "from"      -> toJsonPosition(move.from),
      "to"        -> toJsonPosition(move.to),
      "promotion" -> move.promotion.map(pt => ujson.Str(pt.toString)).getOrElse(ujson.Null)
    )

  private def toJsonGameStatus(status: GameStatus): Obj = status match {
    case GameStatus.Ongoing(inCheck) =>
      Obj("type" -> "Ongoing", "inCheck" -> inCheck)
    case GameStatus.Checkmate(winner) =>
      Obj("type" -> "Checkmate", "winner" -> winner.toString)
    case GameStatus.Draw(reason) =>
      Obj("type" -> "Draw", "reason" -> reason.toString)
  }

  private def toJsonCastlingRights(cr: CastlingRights): Obj =
    Obj(
      "whiteKingSide"  -> cr.whiteKingSide,
      "whiteQueenSide" -> cr.whiteQueenSide,
      "blackKingSide"  -> cr.blackKingSide,
      "blackQueenSide" -> cr.blackQueenSide
    )

  private def toJsonEnPassantState(eps: EnPassantState): Obj =
    Obj(
      "targetSquare"         -> toJsonPosition(eps.targetSquare),
      "capturablePawnSquare" -> toJsonPosition(eps.capturablePawnSquare),
      "pawnColor"            -> eps.pawnColor.toString
    )

  private def fromJsonGameState(json: Value): Either[String, GameState] =
    try {
      for {
        board <- fromJsonBoard(json("board"))
        currentPlayer <- Color.values.find(_.toString == json("currentPlayer").str).toRight("Invalid currentPlayer")
        moveHistory <- fromJsonMoveHistory(json("moveHistory"))
        status <- fromJsonGameStatus(json("status"))
        castlingRights <- fromJsonCastlingRights(json("castlingRights"))
        enPassantState <- (
          if (json.obj.contains("enPassantState") && !json("enPassantState").isNull)
            fromJsonEnPassantState(json("enPassantState")).map(Some(_))
          else Right(None)
        )
        halfmoveClock = json("halfmoveClock").num.toInt
        fullmoveNumber = json("fullmoveNumber").num.toInt
      } yield GameState(
        board,
        currentPlayer,
        moveHistory,
        status,
        castlingRights,
        enPassantState,
        halfmoveClock,
        fullmoveNumber
      )
    } catch {
      case e: Exception => Left(e.getMessage)
    }

  private def fromJsonBoard(json: Value): Either[String, Board] = {
    val pairsOrErrors: Seq[Either[String, (Position, Piece)]] = json.arr.toSeq.map { entry =>
      for {
        pos <- fromJsonPosition(entry("pos"))
        piece <- fromJsonPiece(entry("piece"))
      } yield (pos, piece)
    }
    val errors = pairsOrErrors.collect { case Left(err) => err }
    if (errors.nonEmpty) Left(errors.mkString(", "))
    else {
      val pairs = pairsOrErrors.collect { case Right(pair) => pair }
      // Build up the board using place
      val board = pairs.foldLeft(Board.empty) { case (b, (pos, piece)) => b.place(pos, piece) }
      Right(board)
    }
  }

  private def fromJsonPosition(json: Value): Either[String, Position] =
    for {
      file <- Try(json("file").num.toInt).toEither.left.map(_.getMessage)
      rank <- Try(json("rank").num.toInt).toEither.left.map(_.getMessage)
      pos  <- Position.from(file, rank).left.map(_.toString)
    } yield pos

  private def fromJsonPiece(json: Value): Either[String, Piece] =
    for {
      color <- Color.values.find(_.toString == json("color").str).toRight("Invalid color")
      pt    <- PieceType.values.find(_.toString == json("pieceType").str).toRight("Invalid pieceType")
    } yield Piece(color, pt)

  private def fromJsonMoveHistory(json: Value): Either[String, List[Move]] = {
    val movesOrErrors = json.arr.toList.map(fromJsonMove)
    val errors = movesOrErrors.collect { case Left(err) => err }
    if (errors.nonEmpty) Left(errors.mkString(", "))
    else Right(movesOrErrors.collect { case Right(m) => m })
  }

  private def fromJsonMove(json: Value): Either[String, Move] =
    for {
      from <- fromJsonPosition(json("from"))
      to   <- fromJsonPosition(json("to"))
      promotion = if (json.obj.contains("promotion") && !json("promotion").isNull)
        PieceType.values.find(_.toString == json("promotion").str)
      else None
    } yield Move(from, to, promotion)

  private def fromJsonGameStatus(json: Value): Either[String, GameStatus] =
    json("type").str match {
      case "Ongoing"   => Right(GameStatus.Ongoing(json("inCheck").bool))
      case "Checkmate" => Color.values.find(_.toString == json("winner").str)
        .map(GameStatus.Checkmate(_)).toRight("Invalid winner in Checkmate")
      case "Draw"      => DrawReason.values.find(_.toString == json("reason").str)
        .map(GameStatus.Draw(_)).toRight("Invalid reason in Draw")
      case other        => Left(s"Unknown GameStatus type: $other")
    }

  private def fromJsonCastlingRights(json: Value): Either[String, CastlingRights] =
    Try(CastlingRights(
      whiteKingSide  = json("whiteKingSide").bool,
      whiteQueenSide = json("whiteQueenSide").bool,
      blackKingSide  = json("blackKingSide").bool,
      blackQueenSide = json("blackQueenSide").bool
    )).toEither.left.map(_.getMessage)

  private def fromJsonEnPassantState(json: Value): Either[String, EnPassantState] =
    for {
      target <- fromJsonPosition(json("targetSquare"))
      pawn   <- fromJsonPosition(json("capturablePawnSquare"))
      color  <- Color.values.find(_.toString == json("pawnColor").str).toRight("Invalid pawnColor")
    } yield EnPassantState(target, pawn, color)
