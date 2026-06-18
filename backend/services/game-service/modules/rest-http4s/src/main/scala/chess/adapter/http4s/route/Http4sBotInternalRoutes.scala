package chess.adapter.http4s.route

import cats.effect.IO
import chess.adapter.http4s.route.Http4sRouteSupport.*
import chess.application.bot.{BotTurnStatus, BotTurnTask, BotTurnTaskRepository}
import chess.application.port.repository.{GameRepository, RepositoryError, SessionRepository}
import chess.application.session.model.SideController
import chess.application.session.service.{GameSessionCommands, SessionMoveError}
import chess.domain.model.{Color, Move, PieceType, Position}
import chess.domain.rules.GameStateRules
import chess.notation.api.NotationFormat
import chess.notation.fen.FenSerializer
import org.http4s.*
import org.http4s.dsl.io.*
import java.time.Instant
import java.util.UUID

/** Internal REST routes for the bot-service worker.
  *
  * Two endpoints:
  *   - `GET /internal/bot/turns` — lease the next pending bot turn task and return it with legal
  *     moves and FEN so the bot-service can call the AI service.
  *   - `POST /internal/bot/turns/{turnTaskId}/move` — submit a bot move for a leased turn.
  *
  * Authentication is via `X-Bot-Api-Key` using [[BotWorkerAuth]].
  */
class Http4sBotInternalRoutes(
    auth: BotWorkerAuth,
    botTurnTaskRepository: BotTurnTaskRepository,
    sessionRepository: SessionRepository,
    gameRepository: GameRepository,
    commandService: GameSessionCommands,
    leaseSeconds: Int = 30
):

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "internal" / "bot" / "turns" =>
      auth.verify(req) match
        case Left(msg) => jsonError(Status.Unauthorized, "UNAUTHORIZED", msg)
        case Right(_)  =>
          val actorId = req.uri.query.params.getOrElse("actorId", "searchess-bot")
          val limit   = req.uri.query.params.get("limit").flatMap(_.toIntOption).getOrElse(1)
          handleLease(actorId, limit, Instant.now())

    case req @ POST -> Root / "internal" / "bot" / "turns" / turnTaskIdStr / "move" =>
      auth.verify(req) match
        case Left(msg) => jsonError(Status.Unauthorized, "UNAUTHORIZED", msg)
        case Right(_)  =>
          parseUUID(turnTaskIdStr) match
            case Left(msg) => jsonError(Status.BadRequest, "BAD_REQUEST", msg)
            case Right(taskId) =>
              req.bodyText.compile.string.flatMap { body =>
                handleSubmitMove(taskId, body, Instant.now())
              }
  }

  // ── lease handler ─────────────────────────────────────────────────────────

  private def handleLease(actorId: String, limit: Int, now: Instant): IO[Response[IO]] =
    botTurnTaskRepository.leaseNextPending(actorId, now, leaseSeconds, limit) match
      case Left(err) =>
        logTaskError("bot_turn_task_lease_failed", "actorId" -> actorId, "error" -> err.toString)
        jsonError(Status.InternalServerError, "INTERNAL_ERROR", repositoryMessage(err))
      case Right(Nil) =>
        IO.pure(Response[IO](Status.NoContent))
      case Right(task :: _) =>
        logTaskInfo("bot_turn_task_leased", taskFields(task)*)
        buildLeaseResponse(task, now)

  private def buildLeaseResponse(task: BotTurnTask, now: Instant): IO[Response[IO]] =
    (sessionRepository.loadByGameId(task.gameId), gameRepository.load(task.gameId)) match
      case (Left(err), _) =>
        logTaskError("bot_turn_task_failed", (taskFields(task) :+ ("reason" -> repositoryMessage(err)))*)
        jsonError(Status.InternalServerError, "INTERNAL_ERROR", repositoryMessage(err))
      case (_, Left(err)) =>
        logTaskError("bot_turn_task_failed", (taskFields(task) :+ ("reason" -> repositoryMessage(err)))*)
        jsonError(Status.InternalServerError, "INTERNAL_ERROR", repositoryMessage(err))
      case (Right(_), Right(state)) =>
        val legalMoves = GameStateRules.legalMoves(state).toList.sortBy(m =>
          (m.from.file, m.from.rank, m.to.file, m.to.rank, m.promotion.map(_.toString).getOrElse(""))
        )
        val fenOpt      = FenSerializer.exportNotation(state, NotationFormat.FEN).toOption.map(_.text)
        val movesJson   = ujson.Arr(legalMoves.map { m =>
          val obj = ujson.Obj("from" -> m.from.toString, "to" -> m.to.toString)
          m.promotion.foreach(p => obj("promotion") = p.toString.toLowerCase)
          obj
        }*)
        val leaseUntilStr =
          task.leaseUntil.map(_.toString).getOrElse(now.plusSeconds(leaseSeconds.toLong).toString)
        val json = ujson.Obj(
          "turnTaskId" -> task.id.toString,
          "sessionId"  -> task.sessionId.value.toString,
          "gameId"     -> task.gameId.value.toString,
          "sideToMove" -> task.sideToMove.toString,
          "legalMoves" -> movesJson,
          "leaseUntil" -> leaseUntilStr
        )
        fenOpt.foreach(fen => json("fen") = fen)
        jsonResponse(Status.Ok, json)

  // ── submit move handler ───────────────────────────────────────────────────

  private def handleSubmitMove(taskId: UUID, body: String, now: Instant): IO[Response[IO]] =
    botTurnTaskRepository.findById(taskId) match
      case Left(err) =>
        jsonError(Status.InternalServerError, "INTERNAL_ERROR", repositoryMessage(err))
      case Right(None) =>
        jsonError(Status.NotFound, "TASK_NOT_FOUND", s"Bot turn task not found: $taskId")
      case Right(Some(task)) =>
        if task.status == BotTurnStatus.Completed then
          logTaskInfo("bot_turn_task_completed", (taskFields(task) :+ ("note" -> "already_completed"))*)
          jsonResponse(Status.Ok, ujson.Obj("status" -> "ok", "note" -> "already_completed"))
        else if task.status != BotTurnStatus.Leased then
          logTaskError("bot_turn_task_failed", (taskFields(task) :+ ("reason" -> s"not_leased:${task.status}"))*)
          jsonError(Status.Conflict, "LEASE_INVALID", s"Task is not Leased (current: ${task.status})")
        else
          task.leaseUntil match
            case None =>
              logTaskError("bot_turn_task_failed", (taskFields(task) :+ ("reason" -> "missing_lease_until"))*)
              jsonError(Status.Conflict, "LEASE_INVALID", "Task has no lease_until timestamp")
            case Some(until) if until.isBefore(now) =>
              logTaskError("bot_turn_task_failed", (taskFields(task) :+ ("reason" -> "lease_expired"))*)
              jsonError(Status.Conflict, "LEASE_EXPIRED", "Lease has expired")
            case _ =>
              parseMoveBody(body) match
                case Left(msg) =>
                  logTaskError("bot_turn_task_failed", (taskFields(task) :+ ("reason" -> msg))*)
                  jsonError(Status.BadRequest, "BAD_REQUEST", msg)
                case Right(move) => applyBotMove(task, move, now)

  private def applyBotMove(task: BotTurnTask, move: Move, now: Instant): IO[Response[IO]] =
    (sessionRepository.loadByGameId(task.gameId), gameRepository.load(task.gameId)) match
      case (Left(err), _) =>
        logTaskError("bot_turn_task_failed", (taskFields(task) :+ ("reason" -> repositoryMessage(err)))*)
        jsonError(Status.InternalServerError, "INTERNAL_ERROR", repositoryMessage(err))
      case (_, Left(err)) =>
        logTaskError("bot_turn_task_failed", (taskFields(task) :+ ("reason" -> repositoryMessage(err)))*)
        jsonError(Status.InternalServerError, "INTERNAL_ERROR", repositoryMessage(err))
      case (Right(session), Right(state)) =>
        if state.currentPlayer != task.sideToMove then
          logTaskError(
            "bot_turn_task_failed",
            (taskFields(task) :+ ("reason" -> s"wrong_side_to_move:${state.currentPlayer}"))*
          )
          jsonError(
            Status.Conflict,
            "NOT_BOT_TURN",
            s"Expected ${task.sideToMove} to move but current player is ${state.currentPlayer}"
          )
        else
          commandService.submitMove(session, state, move, SideController.DeployedBot, now) match
            case Left(SessionMoveError.DomainRejection(cause)) =>
              logTaskError("bot_turn_task_failed", (taskFields(task) :+ ("reason" -> cause.toString))*)
              jsonError(Status.UnprocessableEntity, "MOVE_REJECTED", cause.toString)
            case Left(SessionMoveError.SessionFinished) =>
              logTaskError("bot_turn_task_failed", (taskFields(task) :+ ("reason" -> "session_finished"))*)
              jsonError(Status.Conflict, "SESSION_FINISHED", "Session is already finished.")
            case Left(SessionMoveError.UnauthorizedController(_, _)) =>
              logTaskError("bot_turn_task_failed", (taskFields(task) :+ ("reason" -> "unauthorized_controller"))*)
              jsonError(Status.Conflict, "NOT_BOT_TURN", "It is not the bot's turn.")
            case Left(SessionMoveError.PersistenceFailed(cause)) =>
              logTaskError("bot_turn_task_failed", (taskFields(task) :+ ("reason" -> cause.toString))*)
              jsonError(Status.InternalServerError, "INTERNAL_ERROR", cause.toString)
            case Right(_) =>
              logTaskInfo("bot_turn_move_submitted", (taskFields(task) ++ moveFields(move))*)
              botTurnTaskRepository.markCompleted(task.id, now) match
                case Left(err) =>
                  logTaskError("bot_turn_task_complete_failed", (taskFields(task) :+ ("error" -> err.toString))*)
                  jsonResponse(Status.Ok, ujson.Obj("status" -> "ok", "warning" -> "task_mark_failed"))
                case Right(()) =>
                  logTaskInfo("bot_turn_task_completed", taskFields(task)*)
                  jsonResponse(Status.Ok, ujson.Obj("status" -> "ok"))

  // ── parse helpers ─────────────────────────────────────────────────────────

  private def parseMoveBody(body: String): Either[String, Move] =
    try
      val json     = ujson.read(body)
      val fromStr  = json("from").str
      val toStr    = json("to").str
      val promoStr = json.obj.get("promotion").flatMap(_.strOpt)
      for
        from  <- Position.fromAlgebraic(fromStr).left.map(err => s"Invalid 'from' square: $err")
        to    <- Position.fromAlgebraic(toStr).left.map(err => s"Invalid 'to' square: $err")
        promo <- promoStr match
          case None      => Right(None)
          case Some(raw) =>
            PieceType.values
              .find(_.toString.equalsIgnoreCase(raw))
              .map(pt => Right(Some(pt)))
              .getOrElse(Left(s"Invalid promotion piece: '$raw'"))
      yield Move(from, to, promo)
    catch
      case _: Exception => Left("Invalid JSON body")

  private def repositoryMessage(err: RepositoryError): String = err match
    case RepositoryError.NotFound(id)        => s"Record not found: $id"
    case RepositoryError.Conflict(msg)       => msg
    case RepositoryError.StorageFailure(msg) => msg

  private def taskFields(task: BotTurnTask): Seq[(String, String)] =
    Seq(
      "taskId" -> task.id.toString,
      "sessionId" -> task.sessionId.value.toString,
      "gameId" -> task.gameId.value.toString,
      "botActorId" -> task.botActorId,
      "sideToMove" -> task.sideToMove.toString,
      "status" -> task.status.toString,
      "attemptCount" -> task.attemptCount.toString,
      "leaseUntil" -> task.leaseUntil.map(_.toString).getOrElse("")
    )

  private def moveFields(move: Move): Seq[(String, String)] =
    Seq(
      "from" -> move.from.toString,
      "to" -> move.to.toString,
      "promotion" -> move.promotion.map(_.toString).getOrElse("")
    )

  private def logTaskInfo(event: String, fields: (String, String)*): Unit =
    println(botTaskLogLine("info", event, fields*))

  private def logTaskError(event: String, fields: (String, String)*): Unit =
    System.err.println(botTaskLogLine("error", event, fields*))

  private def botTaskLogLine(level: String, event: String, fields: (String, String)*): String =
    (Seq("level" -> level, "service" -> "game-service", "event" -> event) ++ fields)
      .map { case (key, value) => s"$key=$value" }
      .mkString("[BotTurnTask] ", " ", "")
