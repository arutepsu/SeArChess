package chess.adapter.repository.slick

import slick.jdbc.JdbcProfile
import java.sql.Timestamp
import java.util.UUID

/** Slick table mapping for `external_game_bindings`.
  *
  * Mirrors the structure of [[SlickTables]] so that [[SlickExternalGameBindingRepository]] and
  * [[chess.adapter.repository.postgres.PostgresExternalGameCommandStore]] can follow the same
  * patterns as the existing session/game-state repositories.
  */
final class SlickExternalBindingTables(val profile: JdbcProfile, schema: Option[String] = None):
  import profile.api.*

  final case class SlickExternalBindingRow(
      bindingId: UUID,
      platform: String,
      externalGameId: String,
      internalGameId: UUID,
      sessionId: UUID,
      ourActorId: String,
      status: String,
      statusReason: Option[String],
      lastProcessedPly: Int,
      createdAt: Timestamp,
      updatedAt: Timestamp
  )

  final class SlickExternalBindingTable(tag: Tag)
      extends Table[SlickExternalBindingRow](tag, schema, "external_game_bindings"):

    def bindingId        = column[UUID]("binding_id", O.PrimaryKey)
    def platform         = column[String]("platform")
    def externalGameId   = column[String]("external_game_id")
    def internalGameId   = column[UUID]("internal_game_id")
    def sessionId        = column[UUID]("session_id")
    def ourActorId       = column[String]("our_actor_id")
    def status           = column[String]("status")
    def statusReason     = column[Option[String]]("status_reason")
    def lastProcessedPly = column[Int]("last_processed_ply")
    def createdAt        = column[Timestamp]("created_at")
    def updatedAt        = column[Timestamp]("updated_at")

    def * = (
      bindingId, platform, externalGameId, internalGameId, sessionId,
      ourActorId, status, statusReason, lastProcessedPly, createdAt, updatedAt
    ).mapTo[SlickExternalBindingRow]

  val ExternalBindings: TableQuery[SlickExternalBindingTable] =
    TableQuery[SlickExternalBindingTable]
