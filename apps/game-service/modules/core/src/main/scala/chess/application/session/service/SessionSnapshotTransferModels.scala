package chess.application.session.service

import java.time.Instant

/** Application-layer envelope for full session snapshot export/import.
  *
  * This model is deliberately transport-neutral: it contains no JSON, HTTP, or database-specific
  * types. Adapters are responsible for mapping it to their wire formats.
  */
final case class SessionSnapshotEnvelope(
    schema: String,
    version: Int,
    exportedAt: Instant,
    snapshot: PersistentSessionAggregate
)

enum SessionSnapshotTransferError:
  case BadInput(message: String)
  case NotFound(message: String)
  case Conflict(message: String)
  case StorageFailure(message: String)
