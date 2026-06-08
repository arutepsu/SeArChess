package chess.userservice.application

import chess.userservice.domain.UserProfile
import java.util.UUID

trait UserProfileRepository:
  def findBySubject(sub: String): Either[String, Option[UserProfile]]
  def insert(profile: UserProfile): Either[String, Unit]
  def updateDisplayNameAndEmail(userId: UUID, displayName: String, email: Option[String]): Either[String, Unit]
  def findByNicknameCi(nickname: String): Either[String, Option[UserProfile]]
  def setNickname(userId: UUID, nickname: String): Either[String, Unit]
