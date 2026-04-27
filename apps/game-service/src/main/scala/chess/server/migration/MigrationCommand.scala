package chess.server.migration

import chess.application.migration.MigrationMode

enum Backend(val entryName: String):
  case Postgres extends Backend("postgres")
  case Mongo extends Backend("mongo")

object Backend:
  def parse(value: String): Either[String, Backend] =
    value.trim.toLowerCase match
      case "postgres" => Right(Backend.Postgres)
      case "mongo"    => Right(Backend.Mongo)
      case other      => Left(s"Unsupported backend: $other")

enum ReportFormat(val entryName: String):
  case Text extends ReportFormat("text")
  case Json extends ReportFormat("json")

object ReportFormat:
  def parse(value: String): Either[String, ReportFormat] =
    value.trim.toLowerCase match
      case "text" => Right(ReportFormat.Text)
      case "json" => Right(ReportFormat.Json)
      case other  => Left(s"Unsupported report format: $other")

final case class MigrationCommand(
    source: Backend,
    target: Backend,
    mode: MigrationMode,
    batchSize: Int,
    reportFormat: ReportFormat = ReportFormat.Text
)
