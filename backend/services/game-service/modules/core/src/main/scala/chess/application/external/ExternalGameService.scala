package chess.application.external

import chess.application.ai.policy.AITurnPolicy
import chess.application.ai.service.AITurnError
import chess.application.event.AppEvent
import chess.application.port.ai.{AIRequestContext, AiMoveSuggestionClient}
import chess.application.port.event.EventPublisher
import chess.application.port.repository.{ExternalGameBindingRepository, ExternalGameCommandStore, GameRepository, RepositoryError}
import chess.application.session.model.{GameSession, SessionLifecycle, SessionMode, SideController}
import chess.application.session.model.SessionIds.GameId
import chess.application.session.service.{SessionLifecycleService, SessionMoveError}
import chess.domain.model.{Color, GameStatus, Move, PieceType, Position}
import chess.domain.state.{GameState, GameStateFactory}
import java.time.Instant

/** Application service for external-game lifecycle operations.
  *
  * ===Responsibilities===
  *   - Create Game Service sessions bound to external games ([[startExternalGame]]).
  *   - Apply moves from external platform streams idempotently ([[ingestExternalMoves]]).
  *   - Trigger internal AI moves and advance the ply watermark ([[requestAiMove]]).
  *   - Finish external games ([[finishExternalGame]]).
  *
  * ===Move application path===
  * Move validation reuses [[SessionLifecycleService.applyMove]] — the same pure entry point used
  * by [[chess.application.session.service.SessionGameCommandService]]. Persistence uses
  * [[ExternalGameCommandStore]] rather than [[chess.application.port.repository.SessionGameStore]]
  * so that the ply watermark is advanced in the same atomic write as session+state.
  *
  * ===Event publication===
  * [[AppEvent.MoveApplied]] and [[AppEvent.GameFinished]] are published after successful
  * persists. AI-specific events ([[AppEvent.AITurnRequested]] etc.) are intentionally not
  * published here; that is a Phase C concern once the full event model is aligned.
  *
  * @param bindingRepo     read/update binding records
  * @param commandStore    atomic multi-table write boundary
  * @param lifecycleService pure move validation + lifecycle computation
  * @param gameRepository  read game states
  * @param aiClient        optional AI move provider; [[requestAiMove]] returns
  *                        [[ExternalGameError.AiFailure]] when absent
  * @param publisher       fire-and-forget event publication
  */
class ExternalGameService(
    bindingRepo: ExternalGameBindingRepository,
    commandStore: ExternalGameCommandStore,
    lifecycleService: SessionLifecycleService,
    gameRepository: GameRepository,
    aiClient: Option[AiMoveSuggestionClient],
    publisher: EventPublisher
) extends ExternalGameServiceApi:

  // ── startExternalGame ─────────────────────────────────────────────────────

  def startExternalGame(
      request: StartExternalGameRequest
  ): Either[ExternalGameError, ExternalGameBinding] =
    if !isExternalMode(request.mode) then
      Left(
        ExternalGameError.InvalidRequest(
          s"Mode ${request.mode} is not valid for external games; use AiVsExternal or ExternalVsExternal"
        )
      )
    else
      bindingRepo.findByExternalId(request.platform, request.externalGameId) match
        case Left(err)             => Left(ExternalGameError.RepositoryFailure(err))
        case Right(Some(existing)) => Right(existing)
        case Right(None)           => createNewBinding(request)

  // ── getExternalGame ───────────────────────────────────────────────────────

  def getExternalGame(
      platform: chess.application.session.model.ExternalPlatform,
      externalGameId: String
  ): Either[ExternalGameError, ExternalGameBinding] =
    bindingRepo
      .findByExternalId(platform, externalGameId)
      .left.map(ExternalGameError.RepositoryFailure(_))
      .flatMap(_.toRight(ExternalGameError.BindingNotFound(platform, externalGameId)))

  // ── ingestExternalMoves ───────────────────────────────────────────────────

  def ingestExternalMoves(
      caller: VerifiedExternalCaller,
      platform: chess.application.session.model.ExternalPlatform,
      externalGameId: String,
      uciMoves: String,
      now: Instant = Instant.now()
  ): Either[ExternalGameError, IngestMovesResult] =
    for
      binding <- findActiveBinding(platform, externalGameId)
      _       <- checkCaller(caller, binding)
      session <- loadSession(binding)
      state   <- loadState(binding)
      result  <- processIncomingMoves(binding, session, state, uciMoves, now)
    yield result

  // ── requestAiMove ─────────────────────────────────────────────────────────

  def requestAiMove(
      caller: VerifiedExternalCaller,
      platform: chess.application.session.model.ExternalPlatform,
      externalGameId: String,
      now: Instant = Instant.now()
  ): Either[ExternalGameError, AiMoveResult] =
    for
      binding  <- findActiveBinding(platform, externalGameId)
      _        <- checkCaller(caller, binding)
      session  <- loadSession(binding)
      state    <- loadState(binding)
      client   <- aiClient.toRight(ExternalGameError.AiFailure(AITurnError.NotConfigured))
      _        <- Either.cond(
                    AITurnPolicy.isAITurn(session, state.currentPlayer),
                    (),
                    ExternalGameError.InvalidRequest("It is not the AI's turn")
                  )
      context   = AIRequestContext.fromSession(session, state)
      response <- client.suggestMove(context)
                    .left.map(e => ExternalGameError.AiFailure(AITurnError.ProviderFailure(e)))
      (nextState, nextSession) <- lifecycleService.applyMove(
                    session, state, response.move,
                    SideController.AI(context.engineId),
                    now
                  ).left.map(ExternalGameError.MoveRejected(_))
      newPly    = binding.lastProcessedPly + 1
      _         <- commandStore.saveMoveWithPlyAdvance(nextSession, nextState, binding.bindingId, newPly, now)
                    .left.map(ExternalGameError.RepositoryFailure(_))
    yield
      publisher.publish(AppEvent.MoveApplied(session.sessionId, session.gameId, response.move, state.currentPlayer))
      if isTerminal(nextState) then
        publisher.publish(AppEvent.GameFinished(session.sessionId, session.gameId, nextState.status))
      AiMoveResult(
        uciMove          = moveToUci(response.move),
        lastProcessedPly = newPly,
        gameStatus       = nextState.status,
        nextAction       = computeNextAction(nextSession, nextState)
      )

  // ── finishExternalGame ────────────────────────────────────────────────────

  def finishExternalGame(
      caller: VerifiedExternalCaller,
      platform: chess.application.session.model.ExternalPlatform,
      externalGameId: String,
      now: Instant = Instant.now()
  ): Either[ExternalGameError, ExternalGameBinding] =
    for
      binding <- findActiveBinding(platform, externalGameId)
      _       <- checkCaller(caller, binding)
      session <- loadSession(binding)
      state   <- loadState(binding)
      finishedSession = GameSession.withLifecycle(session, SessionLifecycle.Finished, now)
      _       <- commandStore.saveFinishedWithBinding(finishedSession, state, binding.bindingId, now)
                  .left.map(ExternalGameError.RepositoryFailure(_))
    yield binding.copy(status = BindingStatus.Finished, updatedAt = now)

  // ── private helpers ───────────────────────────────────────────────────────

  private def isExternalMode(mode: SessionMode): Boolean =
    mode == SessionMode.AiVsExternal || mode == SessionMode.ExternalVsExternal

  private def createNewBinding(request: StartExternalGameRequest): Either[ExternalGameError, ExternalGameBinding] =
    val (whiteController, blackController) = deriveControllers(request)
    val gameId  = GameId.random()
    val session = GameSession.create(gameId, request.mode, whiteController, blackController, request.now)
    val state   = GameStateFactory.initial()
    val binding = ExternalGameBinding(
      bindingId        = BindingId.random(),
      platform         = request.platform,
      externalGameId   = request.externalGameId,
      internalGameId   = gameId,
      sessionId        = session.sessionId,
      ourActorId       = request.ourActorId,
      status           = BindingStatus.Active,
      lastProcessedPly = 0,
      createdAt        = request.now,
      updatedAt        = request.now
    )
    commandStore.createWithBinding(session, state, binding)
      .left.map(ExternalGameError.RepositoryFailure(_))
      .map(_ => binding)

  private def deriveControllers(request: StartExternalGameRequest): (SideController, SideController) =
    import chess.application.session.model.SideController.*
    (request.mode, request.ourColor) match
      case (SessionMode.AiVsExternal, Color.White) =>
        (AI(), External(request.platform, request.opponentActorId))
      case (SessionMode.AiVsExternal, Color.Black) =>
        (External(request.platform, request.opponentActorId), AI())
      case (SessionMode.ExternalVsExternal, Color.White) =>
        (External(request.platform, request.ourActorId), External(request.platform, request.opponentActorId))
      case (SessionMode.ExternalVsExternal, Color.Black) =>
        (External(request.platform, request.opponentActorId), External(request.platform, request.ourActorId))
      case _ =>
        // Unreachable: guarded by isExternalMode check in startExternalGame
        (AI(), AI())

  private def findActiveBinding(
      platform: chess.application.session.model.ExternalPlatform,
      externalGameId: String
  ): Either[ExternalGameError, ExternalGameBinding] =
    for
      opt     <- bindingRepo.findByExternalId(platform, externalGameId)
                   .left.map(ExternalGameError.RepositoryFailure(_))
      binding <- opt.toRight(ExternalGameError.BindingNotFound(platform, externalGameId))
      _       <- Either.cond(
                   binding.status == BindingStatus.Active,
                   (),
                   ExternalGameError.BindingNotActive(binding.status)
                 )
    yield binding

  private def checkCaller(
      caller: VerifiedExternalCaller,
      binding: ExternalGameBinding
  ): Either[ExternalGameError, Unit] =
    if caller.platform == binding.platform && caller.actorId == binding.ourActorId then Right(())
    else Left(ExternalGameError.Unauthorized(caller.actorId, binding.ourActorId))

  private def loadSession(binding: ExternalGameBinding): Either[ExternalGameError, GameSession] =
    lifecycleService.getSession(binding.sessionId)
      .left.map(ExternalGameError.SessionFailure(_))

  private def loadState(binding: ExternalGameBinding): Either[ExternalGameError, GameState] =
    gameRepository.load(binding.internalGameId)
      .left.map(ExternalGameError.RepositoryFailure(_))

  private def processIncomingMoves(
      binding: ExternalGameBinding,
      session: GameSession,
      state: GameState,
      uciMoves: String,
      now: Instant
  ): Either[ExternalGameError, IngestMovesResult] =
    val allMoveStrings     = uciMoves.trim.split(" ").filter(_.nonEmpty).toList
    val incomingPlyCount   = allMoveStrings.length

    if incomingPlyCount <= binding.lastProcessedPly then
      Right(IngestMovesResult(
        lastProcessedPly = binding.lastProcessedPly,
        currentPlayer    = state.currentPlayer,
        gameStatus       = state.status,
        nextAction       = computeNextAction(session, state)
      ))
    else
      val newMoveStrings = allMoveStrings.drop(binding.lastProcessedPly)
      applyNewMoves(binding, session, state, newMoveStrings, binding.lastProcessedPly, now)

  private def applyNewMoves(
      binding: ExternalGameBinding,
      initialSession: GameSession,
      initialState: GameState,
      newMoveStrings: List[String],
      startPly: Int,
      now: Instant
  ): Either[ExternalGameError, IngestMovesResult] =
    newMoveStrings
      .foldLeft[Either[ExternalGameError, (GameSession, GameState, Int)]](
        Right((initialSession, initialState, startPly))
      ) {
        case (Left(err), _) => Left(err)
        case (Right((session, state, ply)), uciStr) =>
          for
            move       <- parseUciMove(uciStr)
            controller  = lifecycleService.controllerFor(session, state.currentPlayer)
            (nextState, nextSession) <- lifecycleService.applyMove(session, state, move, controller, now)
                             .left.map(ExternalGameError.MoveRejected(_))
            newPly      = ply + 1
            _          <- commandStore.saveMoveWithPlyAdvance(nextSession, nextState, binding.bindingId, newPly, now)
                             .left.map(ExternalGameError.RepositoryFailure(_))
          yield
            publisher.publish(AppEvent.MoveApplied(session.sessionId, session.gameId, move, state.currentPlayer))
            if isTerminal(nextState) then
              publisher.publish(AppEvent.GameFinished(session.sessionId, session.gameId, nextState.status))
            (nextSession, nextState, newPly)
      }
      .map { (finalSession, finalState, finalPly) =>
        IngestMovesResult(
          lastProcessedPly = finalPly,
          currentPlayer    = finalState.currentPlayer,
          gameStatus       = finalState.status,
          nextAction       = computeNextAction(finalSession, finalState)
        )
      }

  private def computeNextAction(session: GameSession, state: GameState): NextAction =
    state.status match
      case GameStatus.Ongoing(_) =>
        if AITurnPolicy.isAITurn(session, state.currentPlayer) then NextAction.TriggerAI
        else NextAction.AwaitExternalMove
      case _ => NextAction.GameFinished

  private def isTerminal(state: GameState): Boolean =
    state.status match
      case GameStatus.Checkmate(_) | GameStatus.Draw(_) | GameStatus.Resigned(_) => true
      case _                                                                      => false

  private def parseUciMove(uci: String): Either[ExternalGameError, Move] =
    if uci.length < 4 || uci.length > 5 then
      Left(ExternalGameError.InvalidExternalMoveFormat(uci, s"Expected 4-5 characters, got ${uci.length}"))
    else
      val fromStr = uci.substring(0, 2)
      val toStr   = uci.substring(2, 4)
      val fromE   = Position.fromAlgebraic(fromStr)
          .left.map(_ => ExternalGameError.InvalidExternalMoveFormat(uci, s"Invalid source square: $fromStr"))
      val toE     = Position.fromAlgebraic(toStr)
          .left.map(_ => ExternalGameError.InvalidExternalMoveFormat(uci, s"Invalid target square: $toStr"))
      val promoE: Either[ExternalGameError, Option[PieceType]] =
        if uci.length == 5 then
          uci.charAt(4).toLower match
            case 'q' => Right(Some(PieceType.Queen))
            case 'r' => Right(Some(PieceType.Rook))
            case 'b' => Right(Some(PieceType.Bishop))
            case 'n' => Right(Some(PieceType.Knight))
            case c   => Left(ExternalGameError.InvalidExternalMoveFormat(uci, s"Unknown promotion piece: '$c'"))
        else Right(None)
      for
        from      <- fromE
        to        <- toE
        promotion <- promoE
      yield Move(from, to, promotion)

  private def moveToUci(move: Move): String =
    val base = s"${move.from}${move.to}"
    move.promotion match
      case Some(PieceType.Queen)  => base + "q"
      case Some(PieceType.Rook)   => base + "r"
      case Some(PieceType.Bishop) => base + "b"
      case Some(PieceType.Knight) => base + "n"
      case Some(PieceType.King) | Some(PieceType.Pawn) | None => base
