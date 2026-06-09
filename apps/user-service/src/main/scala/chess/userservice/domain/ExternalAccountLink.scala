package chess.userservice.domain

import java.time.Instant
import java.util.UUID

final case class ExternalAccountLink(
    linkId: UUID,
    userId: UUID,
    provider: String,
    externalId: Option[String],
    externalUsername: String,
    verified: Boolean,
    verificationSource: String,
    linkedAt: Instant,
    capability: String,
    tokenEncrypted: Option[Array[Byte]],
    tokenScopes: Option[String],
    tokenStoredAt: Option[Instant]
)
