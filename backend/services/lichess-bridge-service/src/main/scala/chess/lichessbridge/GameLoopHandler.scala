package chess.lichessbridge

import cats.effect.{IO, Ref}
import chess.domain.model.GameStatus
import chess.domain.rules.GameStateRules
import chess.observability.StructuredLog
import fs2.Stream

import java.time.Instant

/** Consumes the per-game Lichess NDJSON stream for a single active game and drives the AI move loop.
  *
  * Lifecycle (per game fiber):
  *   1. Opens streamGame(token, gameId).
  *   2. First event is GameFull — determines bot side, processes initial position.
  *   3. Subsequent GameState events — detect bot turn, call AI, submit move.
  *   4. ChatLine / OpponentGone / Unknown — logged, no action taken.
  *   5. Stream errors — logged, stream ends (fiber completes naturally).
  *
  * Duplicate-move prevention:
  *   lastMoves ref is updated to the current moves string before calling the AI.
  *   If the same moves string arrives again (e.g. duplicate keep-alive), it is skipped.
  *
  * Move safety:
  *   AI failures and submitMove failures are logged but do not crash the fiber.
  *   No fallback move is ever submitted on AI failure.
  *
  * Chat:
  *   When chatEnabled=true, the bot sends a greeting on game start, a farewell on game end,
  *   a "Schach!" after its own check-giving move, and a blunder comment when the opponent
  *   leaves a piece hanging.  All chat failures are logged and never propagate.
  */
final class GameLoopHandler(
    token: String,
    game: LichessGameRef,
    botUsername: Option[String],
    gameStream: LichessGameStream[IO],
    aiClient: AiServiceClient[IO],
    lichessClient: LichessClient[IO],
    stateRef: Ref[IO, WorkerState],
    snapshotHub: BotGameSnapshotHub,
    chatEnabled: Boolean = false
):

  private final case class LoopState(
      lastMoves: String,
      initialFen: String,
      botSide: Option[String],
      lastWtime: Option[Int] = None,
      lastBtime: Option[Int] = None,
      lastStatus: String = "started",
      greetingSent: Boolean = false,
      farewellSent: Boolean = false,
      lastCheckAnnouncedAtMoveCount: Option[Int] = None,
      lastBlunderAnnouncedAtMoveCount: Option[Int] = None
  )

  def run: IO[Unit] =
    IO.delay(StructuredLog.info("lichess-bridge-service", "game_loop_starting",
      "gameId"   -> game.gameId,
      "opponent" -> game.opponent)) >>
    Ref.of[IO, LoopState](LoopState(
      lastMoves  = "__initial__",
      initialFen = "startpos",
      botSide    = game.side
    )).flatMap { loopRef =>
      gameStream
        .streamGame(token, game.gameId)
        .evalMap {
          case Right(event) =>
            stateRef.update(_.updateGameMeta(game.gameId, _.withGameEventAt(Instant.now()))) >>
            handleEvent(event, loopRef)
          case Left(err) =>
            IO.delay(StructuredLog.warn("lichess-bridge-service", "game_event_parse_error",
              "gameId" -> game.gameId,
              "error"  -> err.message))
        }
        .handleErrorWith { err =>
          Stream.eval(
            IO.delay(StructuredLog.warn("lichess-bridge-service", "game_loop_stream_error",
              "gameId" -> game.gameId,
              "error"  -> Option(err.getMessage).getOrElse("stream error").take(150)))
          )
        }
        .compile
        .drain
    } >>
    IO.delay(StructuredLog.info("lichess-bridge-service", "game_loop_finished",
      "gameId" -> game.gameId))

  // ── Event dispatch ────────────────────────────────────────────────────────────

  private def handleEvent(event: LichessGameEvent, loopRef: Ref[IO, LoopState]): IO[Unit] =
    event match
      case LichessGameEvent.GameFull(_, white, black, initialFen, moves, wtime, btime, status) =>
        loopRef.update { ls =>
          val side = botUsername
            .flatMap(u => TurnDetector.determineBotSide(u, white, black))
            .orElse(ls.botSide)
          ls.copy(
            initialFen = initialFen,
            botSide    = side,
            lastWtime  = Some(wtime),
            lastBtime  = Some(btime),
            lastStatus = status
          )
        } >>
        loopRef.get.flatMap { ls =>
          processPosition(ls.initialFen, moves, status, ls.botSide, loopRef) >>
          publishSnapshot(ls.initialFen, moves, ls) >>
          (if !ls.greetingSent && ls.botSide.isDefined && !TurnDetector.isTerminal(status) then
            loopRef.update(_.copy(greetingSent = true)) >> sendChat("Hallo und viel Glück!")
          else IO.unit) >>
          (if TurnDetector.isTerminal(status) && !ls.farewellSent then
            loopRef.update(_.copy(farewellSent = true)) >> sendChat("Gut gespielt!")
          else IO.unit)
        }

      case LichessGameEvent.GameState(moves, wtime, btime, status) =>
        loopRef.update(ls => ls.copy(lastWtime = Some(wtime), lastBtime = Some(btime), lastStatus = status)) >>
        loopRef.get.flatMap { ls =>
          processPosition(ls.initialFen, moves, status, ls.botSide, loopRef) >>
          publishSnapshot(ls.initialFen, moves, ls) >>
          (if TurnDetector.isTerminal(status) && !ls.farewellSent then
            loopRef.update(_.copy(farewellSent = true)) >> sendChat("Gut gespielt!")
          else IO.unit)
        }

      case LichessGameEvent.ChatLine(username, text) =>
        IO.delay(StructuredLog.info("lichess-bridge-service", "game_chat",
          "gameId"   -> game.gameId,
          "username" -> username,
          "text"     -> text.take(100)))

      case LichessGameEvent.OpponentGone(gone, claimIn) =>
        IO.delay(StructuredLog.info("lichess-bridge-service", "opponent_gone",
          "gameId"      -> game.gameId,
          "gone"        -> gone,
          "claimInSec"  -> claimIn.fold("none")(_.toString)))

      case LichessGameEvent.Unknown(t, _) =>
        IO.delay(StructuredLog.info("lichess-bridge-service", "game_event_unknown",
          "gameId" -> game.gameId,
          "type"   -> t))

  // ── Position processing ───────────────────────────────────────────────────────

  private def processPosition(
      initialFen: String,
      moves: String,
      status: String,
      botSide: Option[String],
      loopRef: Ref[IO, LoopState]
  ): IO[Unit] =
    loopRef.get.flatMap { ls =>
      if moves == ls.lastMoves then IO.unit
      else
        loopRef.update(_.copy(lastMoves = moves)) >>
        (botSide match
          case None =>
            IO.delay(StructuredLog.warn("lichess-bridge-service", "game_bot_side_unknown",
              "gameId" -> game.gameId))
          case Some(side) =>
            val count = TurnDetector.countMoves(moves)
            if TurnDetector.isBotTurn(side, count, status) then
              announceBlunder(initialFen, moves, count, loopRef) >>
              attemptMove(initialFen, moves, count, loopRef)
            else IO.unit
        )
    }

  private def attemptMove(initialFen: String, moves: String, moveCount: Int, loopRef: Ref[IO, LoopState]): IO[Unit] =
    GamePositionAdapter.toGameState(initialFen, moves) match
      case Left(err) =>
        IO.delay(StructuredLog.warn("lichess-bridge-service", "game_position_replay_failed",
          "gameId" -> game.gameId,
          "error"  -> err.take(200)))
      case Right(gameState) =>
        val legalMoves = GamePositionAdapter.toLegalMoveDtos(gameState)
        if legalMoves.isEmpty then
          IO.delay(StructuredLog.warn("lichess-bridge-service", "game_no_legal_moves",
            "gameId" -> game.gameId))
        else
          val req = AiMoveRequest(
            gameId     = game.gameId,
            fen        = GamePositionAdapter.toCurrentFen(gameState),
            legalMoves = legalMoves,
            sideToMove = GamePositionAdapter.toSideToMove(gameState)
          )
          aiClient.suggestMove(req).flatMap {
            case Left(err) =>
              IO.delay(StructuredLog.warn("lichess-bridge-service", "game_ai_error",
                "gameId" -> game.gameId,
                "error"  -> err.toString))
            case Right(uciMove) =>
              doSubmitMove(uciMove, moveCount, loopRef)
          }

  private def doSubmitMove(uciMove: UciMove, moveCount: Int, loopRef: Ref[IO, LoopState]): IO[Unit] =
    lichessClient.submitMove(token, game.gameId, uciMove.value).flatMap {
      case Right(_) =>
        stateRef.update(_.updateGameMeta(game.gameId, _.withSubmittedMove(uciMove.value, moveCount))) >>
        IO.delay(StructuredLog.info("lichess-bridge-service", "game_move_submitted",
          "gameId" -> game.gameId,
          "move"   -> uciMove.value)) >>
        loopRef.get.flatMap { ls =>
          val updatedMoves =
            if ls.lastMoves == "__initial__" || ls.lastMoves.isBlank then uciMove.value
            else s"${ls.lastMoves.trim} ${uciMove.value}"
          publishSnapshot(ls.initialFen, updatedMoves, ls) >>
          announceCheck(ls.initialFen, updatedMoves, moveCount + 1, loopRef)
        }
      case Left(err) =>
        IO.delay(StructuredLog.warn("lichess-bridge-service", "game_move_submit_failed",
          "gameId" -> game.gameId,
          "error"  -> err.toString))
    }

  // ── Snapshot publication ──────────────────────────────────────────────────────

  private def publishSnapshot(initialFen: String, moves: String, ls: LoopState): IO[Unit] =
    ls.botSide match
      case None => IO.unit
      case Some(side) =>
        GamePositionAdapter.toGameState(initialFen, moves) match
          case Left(_) => IO.unit
          case Right(gameState) =>
            val fen      = GamePositionAdapter.toCurrentFen(gameState)
            val lastMove = moves.split(' ').filter(_.nonEmpty).lastOption
            snapshotHub.publish(BotGameSnapshot(
              gameId      = game.gameId,
              fen         = fen,
              moves       = moves,
              botColor    = side,
              wtime       = ls.lastWtime,
              btime       = ls.lastBtime,
              status      = ls.lastStatus,
              lastMove    = lastMove,
              lastUpdated = System.currentTimeMillis()
            ))

  // ── Chat helpers ──────────────────────────────────────────────────────────────

  private def sendChat(text: String): IO[Unit] =
    if !chatEnabled then IO.unit
    else
      lichessClient.sendChatMessage(token, game.gameId, text).attempt.flatMap {
        case Right(Right(_))  => IO.unit
        case Right(Left(err)) =>
          IO.delay(StructuredLog.warn("lichess-bridge-service", "game_chat_failed",
            "gameId" -> game.gameId,
            "error"  -> err.toString))
        case Left(ex) =>
          IO.delay(StructuredLog.warn("lichess-bridge-service", "game_chat_error",
            "gameId" -> game.gameId,
            "error"  -> Option(ex.getMessage).getOrElse("unknown error")))
      }

  private def announceCheck(initialFen: String, moves: String, resultMoveCount: Int, loopRef: Ref[IO, LoopState]): IO[Unit] =
    loopRef.get.flatMap { ls =>
      if ls.lastCheckAnnouncedAtMoveCount.contains(resultMoveCount) then IO.unit
      else
        GamePositionAdapter.toGameState(initialFen, moves) match
          case Left(_) => IO.unit
          case Right(state) =>
            GameStateRules.evaluateStatus(state) match
              case GameStatus.Ongoing(true) =>
                loopRef.update(_.copy(lastCheckAnnouncedAtMoveCount = Some(resultMoveCount))) >>
                sendChat("Schach!")
              case _ => IO.unit
    }

  private def announceBlunder(initialFen: String, moves: String, moveCount: Int, loopRef: Ref[IO, LoopState]): IO[Unit] =
    if moveCount == 0 then IO.unit
    else
      loopRef.get.flatMap { ls =>
        if ls.lastBlunderAnnouncedAtMoveCount.contains(moveCount) then IO.unit
        else
          GamePositionAdapter.toGameState(initialFen, moves) match
            case Left(_) => IO.unit
            case Right(state) =>
              BlunderDetector.detect(state) match
                case None => IO.unit
                case Some(msg) =>
                  loopRef.update(_.copy(lastBlunderAnnouncedAtMoveCount = Some(moveCount))) >>
                  sendChat(msg)
      }
