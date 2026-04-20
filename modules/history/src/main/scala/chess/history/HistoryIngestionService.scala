package chess.history

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

  def ingestEventJson(body: String): Either[HistoryIngestionError, ArchiveRecord] =
    for
      event    <- TerminalGameEvent.fromJson(body).left.map(HistoryIngestionError.InvalidEvent(_))
      snapshot <- archiveClient.fetch(event.gameId).left.map(HistoryIngestionError.ArchiveFetchFailed(_))
      record   <- materializer.materialize(snapshot).left.map(HistoryIngestionError.MaterializationFailed(_))
      _        <- repository.upsert(record).left.map {
                    case ArchiveRepositoryError.StorageFailure(message) =>
                      HistoryIngestionError.PersistenceFailed(message)
                  }
    yield record
