package chess.application.session.model

import java.util.UUID

/** Opaque ID wrappers for session and game identity.
 *
 *  Using Scala 3 opaque types: zero runtime overhead, strong compile-time
 *  type safety, and no extra churn on call sites that construct IDs.
 *
 *  A [[SessionId]] identifies the orchestration lifecycle around a game.
 *  A [[GameId]] identifies the game record itself (the sequence of moves /
 *  board truth).  They are separate because one session might restart or
 *  rematch under a new game while keeping the same session participants.
 */
object SessionIds:

  opaque type SessionId = UUID
  opaque type GameId    = UUID

  object SessionId:
    def apply(uuid: UUID): SessionId = uuid
    def random(): SessionId          = UUID.randomUUID()
    extension (id: SessionId) def value: UUID = id

  object GameId:
    def apply(uuid: UUID): GameId = uuid
    def random(): GameId          = UUID.randomUUID()
    extension (id: GameId) def value: UUID = id
