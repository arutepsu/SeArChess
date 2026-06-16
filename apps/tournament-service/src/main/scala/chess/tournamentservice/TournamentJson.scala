package chess.tournamentservice

import ujson.{Arr, Null, Obj, Str, Value}

object TournamentJson:

  def bot(bot: BotDescriptor): Value =
    Obj(
      "botId"             -> bot.botId,
      "displayName"       -> bot.displayName,
      "family"            -> bot.family,
      "strategyType"      -> bot.strategyType,
      "engineType"        -> bot.engineType,
      "modelVersion"      -> bot.modelVersion,
      "available"         -> bot.available,
      "unavailableReason" -> bot.unavailableReason.map(Str(_)).getOrElse(Null)
    )

  def jobSummary(job: TournamentJob): Value =
    Obj(
      "jobId"          -> job.jobId,
      "name"           -> job.name.map(Str(_)).getOrElse(Null),
      "status"         -> job.status.json,
      "createdAt"      -> job.createdAt.toString,
      "startedAt"      -> job.startedAt.map(i => Str(i.toString)).getOrElse(Null),
      "finishedAt"     -> job.finishedAt.map(i => Str(i.toString)).getOrElse(Null),
      "selectedBotIds" -> Arr(job.selectedBotIds.map(Str(_))*),
      "mode"           -> job.mode,
      "plannedGames"   -> job.plannedGames.toDouble,
      "completedGames" -> job.completedGames.toDouble
    )

  def job(job: TournamentJob): Value =
    Obj(
      "jobId"          -> job.jobId,
      "name"           -> job.name.map(Str(_)).getOrElse(Null),
      "status"         -> job.status.json,
      "createdAt"      -> job.createdAt.toString,
      "startedAt"      -> job.startedAt.map(i => Str(i.toString)).getOrElse(Null),
      "finishedAt"     -> job.finishedAt.map(i => Str(i.toString)).getOrElse(Null),
      "selectedBotIds" -> Arr(job.selectedBotIds.map(Str(_))*),
      "mode"           -> job.mode,
      "repetitions"    -> job.repetitions.toDouble,
      "maxPly"         -> job.maxPly.toDouble,
      "plannedGames"   -> job.plannedGames.toDouble,
      "completedGames" -> job.completedGames.toDouble,
      "outputPath"     -> job.outputPath.map(Str(_)).getOrElse(Null),
      "errorMessage"   -> job.errorMessage.map(Str(_)).getOrElse(Null),
      "analysisStatus" -> job.analysisStatus.json,
      "analyticsRunId" -> job.analyticsRunId.map(Str(_)).getOrElse(Null),
      "analyticsOutputPath" -> job.analyticsOutputPath.map(Str(_)).getOrElse(Null),
      "analyticsErrorMessage" -> job.analyticsErrorMessage.map(Str(_)).getOrElse(Null),
      "analyzeUrl"     -> job.analyzeUrl.map(Str(_)).getOrElse(Null),
      "eventsUrl"      -> job.eventsUrl.map(Str(_)).getOrElse(Null),
      "resultSummary"  -> job.resultSummary.map(Str(_)).getOrElse(Null)
    )

  def accepted(job: TournamentJob): Value =
    Obj(
      "jobId"     -> job.jobId,
      "status"    -> job.status.json,
      "createdAt" -> job.createdAt.toString,
      "statusUrl" -> s"/api/tournaments/${job.jobId}"
    )

  def analysisAccepted(job: TournamentJob): Value =
    Obj(
      "jobId" -> job.jobId,
      "analysisStatus" -> job.analysisStatus.json,
      "analyticsOutputPath" -> job.analyticsOutputPath.map(Str(_)).getOrElse(Null),
      "statusUrl" -> s"/api/tournaments/${job.jobId}"
    )

  def analysisFields(job: TournamentJob): Value =
    Obj(
      "jobId" -> job.jobId,
      "analysisStatus" -> job.analysisStatus.json,
      "analyticsRunId" -> job.analyticsRunId.map(Str(_)).getOrElse(Null),
      "analyticsOutputPath" -> job.analyticsOutputPath.map(Str(_)).getOrElse(Null),
      "analyticsErrorMessage" -> job.analyticsErrorMessage.map(Str(_)).getOrElse(Null),
      "analyzeUrl" -> job.analyzeUrl.map(Str(_)).getOrElse(Null)
    )

  def parseCreateRequest(body: String): Either[String, CreateTournamentRequest] =
    try
      val json = ujson.read(body)
      val obj  = json.obj
      val name = obj.get("name").flatMap {
        case Null => None
        case v    => Some(v.str)
      }
      val botIds = obj.get("botIds").toRight("botIds is required").map(_.arr.map(_.str).toList)
      val mode = obj.get("mode").map(_.str).getOrElse("double-round-robin")
      val repetitions = obj.get("repetitions").map(_.num.toInt).getOrElse(1)
      val maxPly = obj.get("maxPly").map(_.num.toInt).getOrElse(300)
      val seed = obj.get("seed").flatMap {
        case Null => None
        case v    => Some(v.num.toLong)
      }
      botIds.map(ids => CreateTournamentRequest(name, ids, mode, repetitions, maxPly, seed))
    catch case e: Exception =>
      Left(s"Invalid JSON request: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}")

  def parseAnalyzeRequest(body: String): Either[String, AnalyzeTournamentRequest] =
    if body.trim.isEmpty then Right(AnalyzeTournamentRequest(None))
    else
      try
        val json = ujson.read(body)
        val obj  = json.obj
        val outputPath = obj.get("outputPath").flatMap {
          case Null => None
          case v    => Some(v.str)
        }
        Right(AnalyzeTournamentRequest(outputPath))
      catch case e: Exception =>
        Left(s"Invalid JSON request: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}")
