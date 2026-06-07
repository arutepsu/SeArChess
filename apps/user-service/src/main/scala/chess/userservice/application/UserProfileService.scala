package chess.userservice.application

import chess.userservice.domain.{ExternalAccountLink, UserProfile}
import java.time.Instant
import java.util.UUID

class UserProfileService(
    profiles: UserProfileRepository,
    links: ExternalAccountLinkRepository
):

  def getOrCreateProfile(sub: String, displayName: String, email: Option[String]): Either[String, UserProfile] =
    profiles.findBySubject(sub).flatMap {
      case Some(existing) => Right(existing)
      case None =>
        val profile = UserProfile(
          userId          = UUID.randomUUID(),
          keycloakSubject = sub,
          displayName     = displayName,
          email           = email,
          nickname        = None,
          createdAt       = Instant.now(),
          updatedAt       = Instant.now()
        )
        profiles.insert(profile).map(_ => profile)
    }

  def getLinksForUser(userId: UUID): Either[String, List[ExternalAccountLink]] =
    links.findAllByUserId(userId)

  def setManualLichessLink(userId: UUID, lichessUsername: String): Either[String, ExternalAccountLink] =
    links.findByUserAndProvider(userId, "Lichess").flatMap {
      case Some(existing) =>
        val updated = existing.copy(externalUsername = lichessUsername, linkedAt = Instant.now())
        links.update(updated).map(_ => updated)
      case None =>
        val link = ExternalAccountLink(
          linkId             = UUID.randomUUID(),
          userId             = userId,
          provider           = "Lichess",
          externalId         = None,
          externalUsername   = lichessUsername,
          verified           = false,
          verificationSource = "ManualDev",
          linkedAt           = Instant.now()
        )
        links.insert(link).map(_ => link)
    }

  def setNickname(profile: chess.userservice.domain.UserProfile, rawNickname: String): Either[String, chess.userservice.domain.UserProfile] =
    NicknameValidator.validate(rawNickname).flatMap { nickname =>
      profiles.findByNicknameCi(nickname).flatMap {
        case Some(existing) if existing.userId != profile.userId =>
          Left(s"Nickname '$nickname' is already taken")
        case _ =>
          profiles.setNickname(profile.userId, nickname).map { _ =>
            profile.copy(nickname = Some(nickname), updatedAt = Instant.now())
          }
      }
    }

  def deleteLink(userId: UUID, provider: String): Either[String, Unit] =
    links.delete(userId, provider)
