package chess.tournamentservice

import cats.effect.IO
import chess.observability.StructuredLog
import java.util.concurrent.ConcurrentHashMap
import java.util.Set as JSet

/** Single-tick bot runner for the public tournament integration.
  *
  * One call to [[tick]] processes one round of the given tournament:
  *  1. Fetches tournament detail → current round number.
  *  2. Fetches Searchess participants from user-service.
  *  3. Fetches round pairings from the tournament server.
  *  4. For each ongoing game, fetches the game snapshot, determines the active side,
  *     looks up the Searchess bot, generates a move, and submits it via the gateway.
  *
  * This is not a scheduler or a daemon. Callers control invocation frequency.
  *
  * Two guards protect against duplicate submissions:
  *  - inFlight (ConcurrentHashMap): blocks concurrent ticks within the same JVM call.
  *  - recentGuard (RecentSubmissionGuard, optional): blocks sequential ticks that see
  *    the same stale FEN before the external snapshot advances.
  * Both are keyed by tournamentId:gameId:botId:fenHash so different positions always pass.
  * Neither guard persists across process restarts.
  */
final class PublicTournamentBotRunner(
    registry:          BotRegistry,
    serverClient:      TournamentRunnerServerClient,
    participantClient: SearchessParticipantClient,
    moveClient:        GatewayMoveClient,
    recentGuard:       Option[RecentSubmissionGuard] = None
) extends TournamentTickRunner:

  private[tournamentservice] val inFlight: JSet[String] = ConcurrentHashMap.newKeySet[String]()

  def tick(tournamentId: String): IO[PublicTournamentRunnerTickResult] =
    serverClient.fetchTournamentDetail(tournamentId).flatMap {
      case Left(_) =>
        IO.pure(PublicTournamentRunnerTickResult(tournamentId, 0, 0, Nil))
      case Right(detail) =>
        processRound(tournamentId, detail)
    }

  private def processRound(
    tournamentId: String,
    detail:       RunnerTournamentDetail
  ): IO[PublicTournamentRunnerTickResult] =
    for
      participantsEither <- participantClient.fetchParticipants(tournamentId)
      pairingsEither     <- serverClient.fetchRoundPairings(tournamentId, detail.round)
      gamesFound = pairingsEither.fold(_ => 0, _.size)
      actions    <- buildActions(tournamentId, participantsEither, pairingsEither)
    yield PublicTournamentRunnerTickResult(tournamentId, detail.round, gamesFound, actions)

  private def buildActions(
    tournamentId:       String,
    participantsEither: Either[String, List[RunnerParticipant]],
    pairingsEither:     Either[String, List[RunnerRoundGame]]
  ): IO[List[RunnerAction]] =
    (participantsEither, pairingsEither) match
      case (Left(_), _) | (_, Left(_)) => IO.pure(Nil)
      case (Right(participants), Right(games)) =>
        val byBotId = participants.map(p => p.tournamentServerBotId -> p).toMap
        val ongoing = games.filter(_.status.toLowerCase == "ongoing")
        ongoing.foldLeft(IO.pure(List.empty[RunnerAction])) { (accIO, game) =>
          accIO.flatMap(acc => processGame(tournamentId, game, byBotId).map(acc ++ _))
        }

  private def processGame(
    tournamentId:  String,
    game:          RunnerRoundGame,
    participantMap: Map[String, RunnerParticipant]
  ): IO[List[RunnerAction]] =
    serverClient.fetchGameSnapshot(tournamentId, game.gameId).flatMap {
      case Left(_)       => IO.pure(Nil)
      case Right(snap)   => processSnapshot(tournamentId, game, snap, participantMap)
    }

  private def processSnapshot(
    tournamentId:  String,
    game:          RunnerRoundGame,
    snap:          RunnerGameSnapshot,
    participantMap: Map[String, RunnerParticipant]
  ): IO[List[RunnerAction]] =
    snap.fen.filter(_.trim.nonEmpty) match
      case None =>
        IO.pure(List(RunnerAction(game.gameId, "unknown", None, RunnerActionStatus.Skipped, Some("no_fen"))))
      case Some(fen) =>
        activeColorFromFen(fen) match
          case None =>
            IO.pure(List(RunnerAction(game.gameId, "unknown", None, RunnerActionStatus.Skipped, Some("unparseable_fen"))))
          case Some(activeColor) =>
            val botId = if activeColor == "w" then game.whiteBotId else game.blackBotId
            participantMap.get(botId) match
              case None =>
                IO.pure(List(RunnerAction(game.gameId, botId, None, RunnerActionStatus.Skipped, Some("not_a_searchess_participant"))))
              case Some(participant) =>
                participant.searchessCatalogBotId match
                  case None =>
                    IO.pure(List(RunnerAction(game.gameId, botId, None, RunnerActionStatus.Skipped, Some("missing_catalog_bot_id"))))
                  case Some(catalogBotId) =>
                    trySubmitMove(tournamentId, game.gameId, botId, participant.tournamentServerBotName, catalogBotId, fen, snap.status)

  private def trySubmitMove(
    tournamentId: String,
    gameId:       String,
    botId:        String,
    botName:      String,
    catalogBotId: String,
    fen:          String,
    gameStatus:   String
  ): IO[List[RunnerAction]] =
    val key        = runnerKey(tournamentId, gameId, botId, fen)
    val sideToMove = activeColorFromFen(fen).getOrElse("?")
    if recentGuard.exists(_.isRecentlySubmitted(key)) then
      IO.pure(List(RunnerAction(gameId, botId, None, RunnerActionStatus.Skipped, Some("recently_submitted"))))
    else if !tryAcquire(key) then
      IO.pure(List(RunnerAction(gameId, botId, None, RunnerActionStatus.Skipped, Some("already_in_flight"))))
    else
      IO(StructuredLog.info("tournament-service", "bot_move_attempt",
        "tournamentId" -> tournamentId,
        "gameId"       -> gameId,
        "botId"        -> botId,
        "catalogBotId" -> catalogBotId,
        "sideToMove"   -> sideToMove
      )) >>
      // IO.blocking: StockfishBot.selectMove spawns a subprocess and reads from its stdout.
      // Blocking the cats-effect compute pool would stall the scheduler; use blocking threads.
      IO.blocking {
        for
          bot <- registry.createBot(catalogBotId, seed = None)
          uci <- PublicBotMoveGenerator.generate(bot, PublicGameSnapshot(fen, gameStatus))
                   .left.map(_.describe)
        yield uci
      }.flatMap {
        case Left(err) =>
          IO(StructuredLog.error("tournament-service", "bot_move_failed",
            "tournamentId" -> tournamentId,
            "gameId"       -> gameId,
            "botId"        -> botId,
            "catalogBotId" -> catalogBotId,
            "error"        -> err
          )) >>
          IO.delay(release(key)) >>
          IO.pure(List(RunnerAction(gameId, botId, None, RunnerActionStatus.Failed, Some(err))))
        case Right(uci) =>
          IO(StructuredLog.info("tournament-service", "bot_move_generated",
            "tournamentId" -> tournamentId,
            "gameId"       -> gameId,
            "botId"        -> botId,
            "catalogBotId" -> catalogBotId,
            "uciMove"      -> uci,
            "sideToMove"   -> sideToMove
          )) >>
          moveClient.submitMove(tournamentId, gameId, botId, botName, uci).flatMap { submitResult =>
            IO.delay {
              if submitResult.isRight then recentGuard.foreach(_.markSubmitted(key))
              release(key)
              submitResult match
                case Right(()) =>
                  StructuredLog.info("tournament-service", "bot_move_submitted",
                    "tournamentId" -> tournamentId,
                    "gameId"       -> gameId,
                    "botId"        -> botId,
                    "uciMove"      -> uci
                  )
                  List(RunnerAction(gameId, botId, Some(uci), RunnerActionStatus.Submitted, None))
                case Left(err) =>
                  StructuredLog.error("tournament-service", "bot_move_submit_failed",
                    "tournamentId" -> tournamentId,
                    "gameId"       -> gameId,
                    "botId"        -> botId,
                    "uciMove"      -> uci,
                    "error"        -> err
                  )
                  List(RunnerAction(gameId, botId, Some(uci), RunnerActionStatus.Failed, Some(err)))
            }
          }
      }

  private def runnerKey(tournamentId: String, gameId: String, botId: String, fen: String): String =
    val hash = f"${fen.hashCode}%08x"
    s"$tournamentId:$gameId:$botId:$hash"

  private def activeColorFromFen(fen: String): Option[String] =
    fen.trim.split(' ').lift(1).map(_.toLowerCase.trim).filter(c => c == "w" || c == "b")

  private def tryAcquire(key: String): Boolean =
    inFlight.add(key)

  private def release(key: String): Unit =
    val _ = inFlight.remove(key)
