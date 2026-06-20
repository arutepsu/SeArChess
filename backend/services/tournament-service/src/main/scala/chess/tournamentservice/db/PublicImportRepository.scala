package chess.tournamentservice.db

import cats.effect.{IO, Ref}

final case class PublicImportRecord(
    jobId: String,
    publicTournamentId: String,
    serverBaseUrl: String,
    jsonlPath: String,
)

trait PublicImportRepository:
  def save(record: PublicImportRecord): IO[Unit]
  def findByJobId(jobId: String): IO[Option[PublicImportRecord]]
  def listAll(): IO[List[PublicImportRecord]]

final class InMemoryPublicImportRepository private (
    records: Ref[IO, Map[String, PublicImportRecord]]
) extends PublicImportRepository:

  def save(record: PublicImportRecord): IO[Unit] =
    records.update(_ + (record.jobId -> record))

  def findByJobId(jobId: String): IO[Option[PublicImportRecord]] =
    records.get.map(_.get(jobId))

  def listAll(): IO[List[PublicImportRecord]] =
    records.get.map(_.values.toList)

object InMemoryPublicImportRepository:
  def create(): IO[InMemoryPublicImportRepository] =
    Ref.of[IO, Map[String, PublicImportRecord]](Map.empty)
      .map(new InMemoryPublicImportRepository(_))
