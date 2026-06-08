package chess.userservice.application

import chess.userservice.domain.ExternalAccountLink
import java.util.UUID

trait ExternalAccountLinkRepository:
  def findAllByUserId(userId: UUID): Either[String, List[ExternalAccountLink]]
  def findByUserAndProvider(userId: UUID, provider: String): Either[String, Option[ExternalAccountLink]]
  def insert(link: ExternalAccountLink): Either[String, Unit]
  def update(link: ExternalAccountLink): Either[String, Unit]
  def delete(userId: UUID, provider: String): Either[String, Unit]
