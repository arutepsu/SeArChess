package chess.userservice.domain

import java.time.Instant
import java.util.UUID

final case class UserProfile(
    userId: UUID,
    keycloakSubject: String,
    displayName: String,
    email: Option[String],
    createdAt: Instant,
    updatedAt: Instant
)
