package chess.tournamentservice

import cats.effect.IO

import java.io.File
import scala.jdk.CollectionConverters.*

/** Invokes a pre-packaged Spark analytics executable directly (e.g. the
  * sbt-native-packager distribution staged by `sparkAnalytics/stage`), with
  * `inputPath` and `outputPath` passed as plain positional arguments.
  *
  * Unlike [[SparkTournamentAnalyticsProcessRunner]], `commandPrefix` is the
  * literal executable invocation — no sbt batch-command string wrapping.
  * This is the runner used when `TOURNAMENT_ANALYTICS_COMMAND` is configured,
  * letting container/K8s deployments avoid sbt and the monorepo source tree
  * at runtime.
  */
final class PackagedSparkAnalyticsProcessRunner(
    commandPrefix: List[String],
    workingDir: File
) extends TournamentAnalyticsRunner:
  override def runAnalytics(request: TournamentAnalyticsRunRequest): IO[TournamentAnalyticsRunResult] =
    IO.blocking {
      val command = PackagedSparkAnalyticsProcessRunner.command(commandPrefix, request)
      val builder = ProcessBuilder(command.asJava)
        .directory(workingDir)
        .redirectErrorStream(true)
      val process = builder.start()
      val output = new String(process.getInputStream.readAllBytes(), "UTF-8")
      val exitCode = process.waitFor()

      if exitCode != 0 then
        throw RuntimeException(s"Spark analytics process failed with exit code $exitCode:\n${tailLines(output, 40)}")

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

  private def tailLines(output: String, maxLines: Int): String =
    val lines = output.lines().iterator().asScala.toList
    if lines.isEmpty then "no process output"
    else lines.takeRight(maxLines).mkString("\n")

object PackagedSparkAnalyticsProcessRunner:
  def command(commandPrefix: List[String], request: TournamentAnalyticsRunRequest): List[String] =
    commandPrefix ++ List(request.inputPath, request.outputPath)
