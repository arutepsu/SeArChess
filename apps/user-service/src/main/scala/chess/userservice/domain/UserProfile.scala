package chess.userservice.domain

import java.time.Instant
import java.util.UUID

final case class UserProfile(
    userId: UUID,
    keycloakSubject: String,
    displayName: String,
    email: Option[String],
    nickname: Option[String],
    createdAt: Instant,
    updatedAt: Instant
):
  def onboardingRequired: Boolean = nickname.isEmpty
