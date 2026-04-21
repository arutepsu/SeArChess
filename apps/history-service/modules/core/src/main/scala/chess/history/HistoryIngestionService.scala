package chess.history

import chess.observability.StructuredLog

enum HistoryIngestionError:
  case InvalidEvent(message: String)
  case ArchiveFetchFailed(error: GameArchiveClientError)
  case MaterializationFailed(error: ArchiveMaterializeError)
  case PersistenceFailed(message: String)

class HistoryIngestionService(
  archiveClient: RemoteGameArchiveClient,
  materializer:  ArchiveMaterializer,
  repository:    ArchiveRepository
):

  /** Ingest one terminal Game event.
   *
   *  The Game -> History delivery contract is at-least-once, so the same event
   *  may arrive more than once. If an archive already exists for the event's
   *  game id, return it without refetching the Game snapshot.
   */
  def ingestEventJson(body: String): Either[HistoryIngestionError, ArchiveRecord] =
    TerminalGameEvent.fromJson(body) match
      case Left(message) =>
        StructuredLog.warn("history-service", "history_ingestion_invalid_event", "error" -> message)
        Left(HistoryIngestionError.InvalidEvent(message))
      case Right(event) =>
        logInfo("history_ingestion_received", event)
        repository.findByGameId(event.gameId) match
          case Left(ArchiveRepositoryError.StorageFailure(message)) =>
            logWarn("history_ingestion_repository_failed", event, "error" -> message)
            Left(HistoryIngestionError.PersistenceFailed(message))
          case Right(Some(record)) =>
            logInfo("history_ingestion_duplicate_ignored", event)
            Right(record)
          case Right(None) =>
            materializeAndStore(event)

  private def materializeAndStore(event: TerminalGameEvent): Either[HistoryIngestionError, ArchiveRecord] =
    archiveClient.fetch(event.gameId) match
      case Left(error) =>
        logWarn("history_ingestion_archive_fetch_failed", event, "error" -> error.toString)
        Left(HistoryIngestionError.ArchiveFetchFailed(error))
      case Right(snapshot) =>
        materializer.materialize(snapshot) match
          case Left(error) =>
            logWarn("history_ingestion_materialize_failed", event, "error" -> error.toString)
            Left(HistoryIngestionError.MaterializationFailed(error))
          case Right(record) =>
            repository.upsert(record) match
              case Left(ArchiveRepositoryError.StorageFailure(message)) =>
                logWarn("history_ingestion_persist_failed", event, "error" -> message)
                Left(HistoryIngestionError.PersistenceFailed(message))
              case Right(_) =>
                logInfo("history_ingestion_materialized", event)
                Right(record)

  private def logInfo(eventName: String, event: TerminalGameEvent, fields: (String, Any)*): Unit =
    StructuredLog.info("history-service", eventName, (eventFields(event) ++ fields)*)

  private def logWarn(eventName: String, event: TerminalGameEvent, fields: (String, Any)*): Unit =
    StructuredLog.warn("history-service", eventName, (eventFields(event) ++ fields)*)

  private def eventFields(event: TerminalGameEvent): Seq[(String, Any)] =
    Seq(
      "eventType" -> event.eventType,
      "gameId" -> event.gameId.value.toString,
      "sessionId" -> event.sessionId.value.toString
    )
