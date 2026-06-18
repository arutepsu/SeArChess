package chess.userservice.domain

import java.time.Instant
import java.util.UUID

final case class OAuthLinkState(
    state: String,
    userId: UUID,
    codeVerifier: String,
    redirectAfterSuccess: String,
    expiresAt: Instant,
    createdAt: Instant,
    targetCapability: String
)
