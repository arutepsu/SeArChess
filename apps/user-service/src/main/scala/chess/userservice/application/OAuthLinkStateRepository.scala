package chess.userservice.application

import chess.userservice.domain.OAuthLinkState

trait OAuthLinkStateRepository:
  def insert(state: OAuthLinkState): Either[String, Unit]
  /** Atomically finds and deletes the row. Returns None if not found (or already consumed). */
  def findAndDelete(state: String): Either[String, Option[OAuthLinkState]]
