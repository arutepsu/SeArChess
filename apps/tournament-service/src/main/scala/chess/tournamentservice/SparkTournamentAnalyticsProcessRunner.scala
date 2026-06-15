package chess.tournamentservice

import cats.effect.IO

import java.io.File
import scala.jdk.CollectionConverters.*

final class SparkTournamentAnalyticsProcessRunner(
    sbtCommandPrefix: List[String],
    projectRoot: File = File(System.getProperty("user.dir"))
) extends TournamentAnalyticsRunner:
  override def runAnalytics(request: TournamentAnalyticsRunRequest): IO[TournamentAnalyticsRunResult] =
    IO.blocking {
      val command = SparkTournamentAnalyticsProcessRunner.command(sbtCommandPrefix, request)
      val builder = ProcessBuilder(command.asJava)
        .directory(projectRoot)
        .redirectErrorStream(true)
      val process = builder.start()
      val output = new String(process.getInputStream.readAllBytes(), "UTF-8")
      val exitCode = process.waitFor()

      if exitCode != 0 then
        throw RuntimeException(s"Spark analytics process failed with exit code $exitCode: ${lastLine(output)}")

      val runId = output.lines().iterator().asScala
        .find(_.startsWith("ANALYTICS_RUN_RESULT "))
        .flatMap(extractRunId)
        .getOrElse(throw RuntimeException("Spark analytics completed without reporting an analytics run ID"))

      TournamentAnalyticsRunResult(runId, request.inputPath, request.outputPath)
    }

  private def extractRunId(line: String): Option[String] =
    line
      .stripPrefix("ANALYTICS_RUN_RESULT ")
      .split(" ")
      .toList
      .find(_.startsWith("runId="))
      .map(_.stripPrefix("runId="))
      .filter(_.nonEmpty)

  private def lastLine(output: String): String =
    output.lines().iterator().asScala.toList.lastOption.getOrElse("no process output")

object SparkTournamentAnalyticsProcessRunner:
  def command(sbtCommandPrefix: List[String], request: TournamentAnalyticsRunRequest): List[String] =
    sbtCommandPrefix ++ List(
      "sparkAnalytics/runMain",
      "chess.analytics.app.GameAnalyticsJob",
      request.inputPath,
      request.outputPath
    )
