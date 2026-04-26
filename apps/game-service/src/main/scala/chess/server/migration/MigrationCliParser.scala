package chess.server.migration

import chess.application.migration.MigrationMode

object MigrationCliParser:
  private val DefaultBatchSize = 100

  def parse(args: Array[String]): Either[String, MigrationCommand] =
    parse(args.toList)

  def parse(args: List[String]): Either[String, MigrationCommand] =
    loop(
      args = args,
      source = None,
      target = None,
      mode = None,
      batchSize = DefaultBatchSize,
      reportFormat = ReportFormat.Text
    ).flatMap { parsed =>
      for
        from <- parsed.source.toRight(missing("--from"))
        to <- parsed.target.toRight(missing("--to"))
        migrationMode <- parsed.mode.toRight(missing("--mode"))
        _ <- Either.cond(from != to, (), "Source and target backends must differ")
      yield MigrationCommand(
        source = from,
        target = to,
        mode = migrationMode,
        batchSize = parsed.batchSize,
        reportFormat = parsed.reportFormat
      )
    }

  val usage: String =
    """Usage:
      |  --from postgres|mongo
      |  --to postgres|mongo
      |  --mode dry-run|execute|validate-only
      |  [--batch-size N]
      |  [--format text|json]
      |""".stripMargin

  private final case class Parsed(
      source: Option[Backend],
      target: Option[Backend],
      mode: Option[MigrationMode],
      batchSize: Int,
      reportFormat: ReportFormat
  )

  private def loop(
      args: List[String],
      source: Option[Backend],
      target: Option[Backend],
      mode: Option[MigrationMode],
      batchSize: Int,
      reportFormat: ReportFormat
  ): Either[String, Parsed] =
    args match
      case Nil =>
        Right(Parsed(source, target, mode, batchSize, reportFormat))
      case "--from" :: value :: rest =>
        Backend.parse(value).flatMap(parsed =>
          loop(rest, Some(parsed), target, mode, batchSize, reportFormat)
        )
      case "--to" :: value :: rest =>
        Backend.parse(value).flatMap(parsed =>
          loop(rest, source, Some(parsed), mode, batchSize, reportFormat)
        )
      case "--mode" :: value :: rest =>
        parseMode(value).flatMap(parsed =>
          loop(rest, source, target, Some(parsed), batchSize, reportFormat)
        )
      case "--batch-size" :: value :: rest =>
        parseBatchSize(value).flatMap(parsed =>
          loop(rest, source, target, mode, parsed, reportFormat)
        )
      case "--format" :: value :: rest =>
        ReportFormat.parse(value).flatMap(parsed =>
          loop(rest, source, target, mode, batchSize, parsed)
        )
      case option :: Nil if option.startsWith("--") =>
        Left(s"Missing value for $option")
      case option :: _ if option.startsWith("--") =>
        Left(s"Unknown option: $option")
      case value :: _ =>
        Left(s"Unexpected argument: $value")

  private def parseMode(value: String): Either[String, MigrationMode] =
    value.trim.toLowerCase match
      case "dry-run"       => Right(MigrationMode.DryRun)
      case "execute"       => Right(MigrationMode.Execute)
      case "validate-only" => Right(MigrationMode.ValidateOnly)
      case other           => Left(s"Unsupported mode: $other")

  private def parseBatchSize(value: String): Either[String, Int] =
    value.toIntOption match
      case Some(parsed) if parsed > 0 => Right(parsed)
      case _                          => Left("Batch size must be a positive integer")

  private def missing(option: String): String =
    s"Missing required option: $option"
