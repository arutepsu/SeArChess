package chess.tournamentservice.db

import cats.effect.IO
import chess.tournamentservice.*

trait TournamentJobRepository:

  def createJob(job: TournamentJob): IO[Unit]
  def listJobs(): IO[List[TournamentJob]]
  def getJob(jobId: String): IO[Option[TournamentJob]]

  def markRunning(jobId: String): IO[Boolean]
  def incrementCompletedGames(jobId: String): IO[Unit]
  def markSucceeded(jobId: String): IO[Unit]
  def markFailed(jobId: String, message: String): IO[Unit]
  def cancelJob(jobId: String): IO[Option[TournamentJob]]
  def finalizeCancelled(jobId: String): IO[Unit]

  def queueAnalysis(jobId: String, inputPath: String, analyticsOutputPath: String): IO[Unit]
  def markAnalysisRunning(jobId: String): IO[Option[TournamentAnalyticsRunRequest]]
  def markAnalysisSucceeded(jobId: String, result: TournamentAnalyticsRunResult): IO[Unit]
  def markAnalysisFailed(jobId: String, message: String): IO[Unit]
