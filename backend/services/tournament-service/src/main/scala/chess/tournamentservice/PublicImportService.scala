package chess.tournamentservice

import cats.effect.IO
import chess.arena.events.{GameFinished, GameStarted, MovePlayed}
import chess.arena.writer.jsonl.JsonlFileWriter
import chess.tournamentservice.db.{PublicImportRecord, PublicImportRepository, TournamentJobRepository}

import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import java.util.UUID

final case class PublicImportRequest(serverBaseUrl: String, tournamentId: String)

final case class PublicImportResult(
    jobId: String,
    publicTournamentId: String,
    serverBaseUrl: String,
    jsonlPath: String,
    status: String,
)

final class PublicImportService(
    jobRepository: TournamentJobRepository,
    importRepository: PublicImportRepository,
    client: PublicTournamentClient,
    outputBasePath: String,
):

  def importTournament(request: PublicImportRequest): IO[Either[ImportError, PublicImportResult]] =
    client.fetchExport(request.serverBaseUrl, request.tournamentId).flatMap:
      case Left(err)        => IO.pure(Left(err))
      case Right(pubExport) =>
        for
          now   <- IO(Instant.now())
          jobId <- IO(UUID.randomUUID().toString)
          path   = eventPath(jobId)
          _     <- IO.blocking {
            Files.createDirectories(path.getParent)
            writeJsonl(pubExport, path)
          }
          job    = buildJob(jobId, now, pubExport, path, request.serverBaseUrl)
          _     <- jobRepository.createJob(job)
          record = PublicImportRecord(jobId, pubExport.tournamentId, request.serverBaseUrl, path.toString)
          _     <- importRepository.save(record)
        yield Right(PublicImportResult(jobId, pubExport.tournamentId, request.serverBaseUrl, path.toString, "Imported"))

  private def eventPath(jobId: String): Path =
    Paths.get(outputBasePath, jobId, "game-events.jsonl")

  private def buildJob(
      jobId: String,
      now: Instant,
      pubExport: PublicTournamentExport,
      path: Path,
      serverBaseUrl: String,
  ): TournamentJob =
    val botIds = pubExport.games.flatMap(g => List(g.whiteBotId, g.blackBotId)).distinct
    TournamentJob(
      jobId                 = jobId,
      name                  = Some(s"Public import: ${pubExport.tournamentId}"),
      status                = TournamentJobStatus.Succeeded,
      createdAt             = now,
      startedAt             = pubExport.startedAt.flatMap(s => scala.util.Try(Instant.parse(s)).toOption).orElse(Some(now)),
      finishedAt            = pubExport.finishedAt.flatMap(s => scala.util.Try(Instant.parse(s)).toOption).orElse(Some(now)),
      selectedBotIds        = botIds,
      mode                  = "public-import",
      repetitions           = 1,
      maxPly                = 0,
      seed                  = None,
      plannedGames          = pubExport.games.size,
      completedGames        = pubExport.games.size,
      outputPath            = Some(path.toString),
      errorMessage          = None,
      analysisStatus        = TournamentAnalysisStatus.NotRequested,
      analyticsRunId        = None,
      analyticsOutputPath   = None,
      analyticsErrorMessage = None,
      analyzeUrl            = Some(s"/api/tournaments/$jobId/analyze"),
      eventsUrl             = None,
      resultSummary         = Some(s"${pubExport.games.size} games imported from $serverBaseUrl"),
    )

  private def writeJsonl(pubExport: PublicTournamentExport, path: Path): Unit =
    val writer = JsonlFileWriter(path)
    pubExport.games.foreach: game =>
      val moves = parseMoves(game.moves)
      writer.emit(makeGameStarted(pubExport.tournamentId, game))
      moves.zipWithIndex.foreach: (move, idx) =>
        val plyNumber = idx + 1
        val botId     = if plyNumber % 2 == 1 then game.whiteBotId else game.blackBotId
        writer.emit(makeMovePlayed(pubExport.tournamentId, game.gameId, botId, move, plyNumber))
      writer.emit(makeGameFinished(pubExport.tournamentId, game))

  private def parseMoves(movesStr: String): List[String] =
    val trimmed = movesStr.trim
    if trimmed.isEmpty then Nil
    else trimmed.split(' ').toList.filter(_.nonEmpty)

  private def makeGameStarted(tournamentId: String, g: ExportGame): GameStarted =
    GameStarted(
      eventId           = UUID.randomUUID().toString,
      timestamp         = g.startedAt.getOrElse(Instant.now().toString),
      tournamentId      = tournamentId,
      gameId            = g.gameId,
      whiteBotId        = g.whiteBotId,
      blackBotId        = g.blackBotId,
      whiteBotFamily    = g.whiteBotFamily.getOrElse(""),
      blackBotFamily    = g.blackBotFamily.getOrElse(""),
      whiteStrategyType = g.whiteStrategyType.getOrElse(""),
      blackStrategyType = g.blackStrategyType.getOrElse(""),
      whiteEngineType   = "",
      blackEngineType   = "",
      whiteModelVersion = "",
      blackModelVersion = "",
    )

  private def makeMovePlayed(
      tournamentId: String,
      gameId: String,
      botId: String,
      moveUci: String,
      plyNumber: Int,
  ): MovePlayed =
    MovePlayed(
      eventId        = UUID.randomUUID().toString,
      timestamp      = Instant.now().toString,
      tournamentId   = tournamentId,
      gameId         = gameId,
      botId          = botId,
      moveUci        = moveUci,
      plyNumber      = plyNumber,
      durationMillis = 0L,
    )

  private def makeGameFinished(tournamentId: String, g: ExportGame): GameFinished =
    val (result, winnerBotId, loserBotId) = g.winner match
      case Some("white") => ("white", Some(g.whiteBotId), Some(g.blackBotId))
      case Some("black") => ("black", Some(g.blackBotId), Some(g.whiteBotId))
      case _             => ("draw", None, None)
    GameFinished(
      eventId           = UUID.randomUUID().toString,
      timestamp         = g.endedAt.getOrElse(Instant.now().toString),
      tournamentId      = tournamentId,
      gameId            = g.gameId,
      whiteBotId        = g.whiteBotId,
      blackBotId        = g.blackBotId,
      winnerBotId       = winnerBotId,
      loserBotId        = loserBotId,
      result            = result,
      whiteBotFamily    = g.whiteBotFamily.getOrElse(""),
      blackBotFamily    = g.blackBotFamily.getOrElse(""),
      whiteStrategyType = g.whiteStrategyType.getOrElse(""),
      blackStrategyType = g.blackStrategyType.getOrElse(""),
      whiteEngineType   = "",
      blackEngineType   = "",
      whiteModelVersion = "",
      blackModelVersion = "",
      totalMoves        = (g.totalPly + 1) / 2,
      totalPly          = g.totalPly,
      durationMillis    = g.durationMillis.getOrElse(0L),
      terminationReason = g.terminationReason,
    )
