package chess.adapter.http4s.mapper

import chess.adapter.rest.contract.dto.{
  CastlingRightsDto,
  CreateSessionResponse,
  EnPassantDto,
  GameResponse,
  SessionResponse,
  SessionStateResponse
}
import chess.application.query.game.GameView
import chess.application.session.service.PersistentSessionAggregate
import chess.application.query.session.SessionView
import chess.application.session.model.{GameSession, SessionLifecycle, SessionMode, SideController}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.domain.model.{Color, DrawReason, GameStatus, Move, Piece, PieceType, Position}
import chess.domain.state.{CastlingRights, EnPassantState}
import java.time.Instant
import java.util.UUID

/** Maps between session application models and REST DTOs.
  *
  * Session creation defaults:
  *   - HumanVsHuman: both sides HumanLocal unless explicitly set to a human controller
  *   - HumanVsAI: White human, Black server AI
  *   - AIVsAI: both sides server AI
  *
  * REST v1 does not accept the literal "AI" as an inbound controller value. AI controllers are
  * derived from [[SessionMode]] by [[resolveCreateControllers]].
  */
object SessionMapper:

  // ── inbound parsing ───────────────────────────────────────────────────────

  /** Parse a [[SessionMode]] from an optional request string. Absent or "HumanVsHuman" ->
    * [[SessionMode.HumanVsHuman]] (default).
    */
  def parseMode(s: Option[String]): Either[String, SessionMode] =
    s match
      case None | Some("HumanVsHuman") => Right(SessionMode.HumanVsHuman)
      case Some("HumanVsAI")           => Right(SessionMode.HumanVsAI)
      case Some("AIVsAI")              => Right(SessionMode.AIVsAI)
      case Some(other) =>
        Left(s"Unknown mode: '$other'. Expected HumanVsHuman, HumanVsAI, or AIVsAI")

  /** Parse a human [[SideController]] from an optional request string. Absent or "HumanLocal" ->
    * [[SideController.HumanLocal]] (default).
    *
    * "AI" is intentionally not accepted here. REST v1 derives AI-controlled seats from
    * [[SessionMode]] in [[resolveCreateControllers]].
    */
  def parseController(s: Option[String]): Either[String, SideController] =
    s match
      case None | Some("HumanLocal") => Right(SideController.HumanLocal)
      case Some("HumanRemote")       => Right(SideController.HumanRemote)
      case Some(other) =>
        Left(s"Unknown controller: '$other'. Expected HumanLocal or HumanRemote")

  /** Resolve session-creation controllers from REST v1 mode and optional fields.
    *
    * Rule: mode determines AI seats server-side. Controller fields are accepted only for
    * human-controlled seats; clients cannot request AI engine identity or override an AI seat
    * through the REST contract.
    */
  def resolveCreateControllers(
      mode: SessionMode,
      white: Option[String],
      black: Option[String]
  ): Either[String, (SideController, SideController)] =
    mode match
      case SessionMode.HumanVsHuman =>
        for
          whiteController <- parseController(white)
          blackController <- parseController(black)
        yield (whiteController, blackController)

      case SessionMode.HumanVsAI =>
        black match
          case Some(_) =>
            Left("blackController is server-controlled AI for HumanVsAI; omit blackController")
          case None =>
            parseController(white).map(whiteController => (whiteController, SideController.AI()))

      case SessionMode.AIVsAI =>
        if white.isDefined || black.isDefined then
          Left(
            "Controllers are server-controlled AI for AIVsAI; omit whiteController and blackController"
          )
        else Right((SideController.AI(), SideController.AI()))

  /** Parse a [[Color]] side from a request string.
    *
    * Expected values: "White" or "Black".
    */
  def parseSide(s: String): Either[String, Color] =
    Color.values
      .find(_.toString == s)
      .toRight(s"Unknown side: '$s'. Expected 'White' or 'Black'")

  /** Parse a fully persisted aggregate body into application models. */
  def toPersistentSessionAggregate(
      dto: SessionStateResponse
  ): Either[String, PersistentSessionAggregate] =
    for
      sessionId <- parseSessionId(dto.session.sessionId)
      sessionGameId <- parseGameId(dto.session.gameId)
      gameId <- parseGameId(dto.game.gameId)
      _ <- Either.cond(
        sessionGameId == gameId,
        (),
        s"session.gameId ${dto.session.gameId} does not match game.gameId ${dto.game.gameId}"
      )
      mode <- parseMode(Some(dto.session.mode))
      lifecycle <- parseLifecycle(dto.session.lifecycle)
      whiteController <- parseAnyController(dto.session.whiteController)
      blackController <- parseAnyController(dto.session.blackController)
      createdAt <- parseInstant("createdAt", dto.session.createdAt)
      updatedAt <- parseInstant("updatedAt", dto.session.updatedAt)
      currentPlayer <- parseColor("currentPlayer", dto.game.currentPlayer)
      status <- parseStatus(dto.game)
      board <- parseBoard(dto.game.board)
      moveHistory <- dto.game.moveHistory.foldLeft[Either[String, List[Move]]](Right(Nil)) {
        case (acc, entry) =>
          for
            moves <- acc
            move <- parseMove(entry)
          yield moves :+ move
      }
      enPassant <- parseEnPassant(dto.enPassant)
      view = GameView(
        gameId = gameId,
        currentPlayer = currentPlayer,
        status = status,
        board = board,
        moveHistory = moveHistory,
        castlingRights = CastlingRights(
          whiteKingSide = dto.castlingRights.whiteKingSide,
          whiteQueenSide = dto.castlingRights.whiteQueenSide,
          blackKingSide = dto.castlingRights.blackKingSide,
          blackQueenSide = dto.castlingRights.blackQueenSide
        ),
        enPassantState = enPassant,
        halfmoveClock = dto.game.halfmoveClock,
        fullmoveNumber = dto.game.fullmoveNumber,
        legalMoves = Set.empty
      )
    yield PersistentSessionAggregate(
      session = GameSession(
        sessionId = sessionId,
        gameId = gameId,
        mode = mode,
        whiteController = whiteController,
        blackController = blackController,
        lifecycle = lifecycle,
        createdAt = createdAt,
        updatedAt = updatedAt
      ),
      state = view.toGameState
    )

  // ── outbound mapping ──────────────────────────────────────────────────────

  /** Map a [[GameSession]] to its transport representation. */
  def toSessionResponse(session: GameSession): SessionResponse =
    toSessionResponse(SessionView.fromSession(session))

  /** Map a Game Service session read model to its transport representation. */
  def toSessionResponse(session: SessionView): SessionResponse =
    SessionResponse(
      sessionId = session.sessionId.value.toString,
      gameId = session.gameId.value.toString,
      mode = session.mode.toString,
      lifecycle = session.lifecycle.toString,
      whiteController = controllerToString(session.whiteController),
      blackController = controllerToString(session.blackController),
      createdAt = session.createdAt.toString,
      updatedAt = session.updatedAt.toString
    )

  /** Combine session metadata with the initial game state into a creation response. */
  def toCreateSessionResponse(
      session: GameSession,
      gameId: GameId,
      gameResp: GameResponse
  ): CreateSessionResponse =
    CreateSessionResponse(
      session = toSessionResponse(session),
      game = gameResp
    )

  /** Combine persisted session metadata with the current game snapshot for resume flows. */
  def toSessionStateResponse(
      aggregate: PersistentSessionAggregate
  ): SessionStateResponse =
    val view = GameView.fromState(aggregate.session.gameId, aggregate.state)
    SessionStateResponse(
      session = toSessionResponse(aggregate.session),
      game = GameMapper.toGameResponse(view),
      castlingRights = CastlingRightsDto(
        whiteKingSide = aggregate.state.castlingRights.whiteKingSide,
        whiteQueenSide = aggregate.state.castlingRights.whiteQueenSide,
        blackKingSide = aggregate.state.castlingRights.blackKingSide,
        blackQueenSide = aggregate.state.castlingRights.blackQueenSide
      ),
      enPassant = aggregate.state.enPassantState.map(ep =>
        EnPassantDto(
          targetSquare = ep.targetSquare.toString,
          capturablePawnSquare = ep.capturablePawnSquare.toString,
          pawnColor = ep.pawnColor.toString
        )
      )
    )

  // ── private helpers ───────────────────────────────────────────────────────

  /** Serialize a [[SideController]] to a stable transport string.
    *
    * AI engine ids are not surfaced in Phase 7.
    */
  private[mapper] def controllerToString(c: SideController): String =
    c match
      case SideController.HumanLocal  => "HumanLocal"
      case SideController.HumanRemote => "HumanRemote"
      case SideController.AI(_)       => "AI"

  private def parseSessionId(value: String): Either[String, SessionId] =
    try Right(SessionId(UUID.fromString(value)))
    catch case _: IllegalArgumentException => Left(s"Invalid sessionId: '$value'")

  private def parseGameId(value: String): Either[String, GameId] =
    try Right(GameId(UUID.fromString(value)))
    catch case _: IllegalArgumentException => Left(s"Invalid gameId: '$value'")

  private def parseLifecycle(value: String): Either[String, SessionLifecycle] =
    SessionLifecycle.values
      .find(_.toString == value)
      .toRight(s"Unknown lifecycle: '$value'")

  private def parseAnyController(value: String): Either[String, SideController] =
    value match
      case "HumanLocal"  => Right(SideController.HumanLocal)
      case "HumanRemote" => Right(SideController.HumanRemote)
      case "AI"          => Right(SideController.AI())
      case other         => Left(s"Unknown controller: '$other'")

  private def parseInstant(field: String, value: String): Either[String, Instant] =
    try Right(Instant.parse(value))
    catch case _: Exception => Left(s"Invalid $field instant: '$value'")

  private def parseColor(field: String, value: String): Either[String, Color] =
    Color.values
      .find(_.toString == value)
      .toRight(s"Unknown $field: '$value'")

  private def parsePieceType(value: String): Either[String, PieceType] =
    PieceType.values
      .find(_.toString == value)
      .toRight(s"Unknown pieceType: '$value'")

  private def parsePosition(field: String, value: String): Either[String, Position] =
    Position.fromAlgebraic(value).left.map(_ => s"Invalid $field square: '$value'")

  private def parseMove(entry: chess.adapter.rest.contract.dto.MoveHistoryEntry): Either[String, Move] =
    for
      from <- parsePosition("move.from", entry.from)
      to <- parsePosition("move.to", entry.to)
      promotion <- entry.promotion match
        case None => Right(None)
        case Some(value) =>
          MoveMapper.parsePieceType(value).map(Some(_))
    yield Move(from, to, promotion)

  private def parseBoard(
      pieces: List[chess.adapter.rest.contract.dto.PieceDto]
  ): Either[String, Seq[(Position, Piece)]] =
    pieces.foldLeft[Either[String, List[(Position, Piece)]]](Right(Nil)) { case (acc, piece) =>
      for
        current <- acc
        pos <- parsePosition("board.square", piece.square)
        color <- parseColor("board.color", piece.color)
        pieceType <- parsePieceType(piece.pieceType)
      yield current :+ (pos -> Piece(color, pieceType))
    }

  private def parseStatus(
      dto: chess.adapter.rest.contract.dto.GameSnapshot
  ): Either[String, GameStatus] =
    dto.status match
      case "Ongoing"   => Right(GameStatus.Ongoing(dto.inCheck))
      case "Checkmate" => dto.winner.toRight("winner is required for Checkmate").flatMap(parseColor("winner", _)).map(GameStatus.Checkmate(_))
      case "Resigned"  => dto.winner.toRight("winner is required for Resigned").flatMap(parseColor("winner", _)).map(GameStatus.Resigned(_))
      case "Draw"      => dto.drawReason.toRight("drawReason is required for Draw").flatMap(parseDrawReason).map(GameStatus.Draw(_))
      case other       => Left(s"Unknown status: '$other'")

  private def parseDrawReason(value: String): Either[String, DrawReason] =
    DrawReason.values
      .find(_.toString == value)
      .toRight(s"Unknown drawReason: '$value'")

  private def parseEnPassant(value: Option[EnPassantDto]): Either[String, Option[EnPassantState]] =
    value match
      case None => Right(None)
      case Some(ep) =>
        for
          target <- parsePosition("enPassant.targetSquare", ep.targetSquare)
          pawn <- parsePosition("enPassant.capturablePawnSquare", ep.capturablePawnSquare)
          color <- parseColor("enPassant.pawnColor", ep.pawnColor)
        yield Some(EnPassantState(target, pawn, color))
