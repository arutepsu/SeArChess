package chess.application.session.model

import java.util.UUID

/** Opaque ID wrappers for session and game identity.
  *
  * These IDs cross service boundaries in Game Service HTTP payloads and event JSON, so they live in
  * the small game-contract module even though their package is preserved for low-churn
  * compatibility.
  */
object SessionIds:

  opaque type SessionId = UUID
  opaque type GameId = UUID

  object SessionId:
    def apply(uuid: UUID): SessionId = uuid
    def random(): SessionId = UUID.randomUUID()
    extension (id: SessionId) def value: UUID = id

  object GameId:
    def apply(uuid: UUID): GameId = uuid
    def random(): GameId = UUID.randomUUID()
    extension (id: GameId) def value: UUID = id
