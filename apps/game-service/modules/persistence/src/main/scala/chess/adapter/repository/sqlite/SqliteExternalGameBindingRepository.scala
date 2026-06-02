package chess.adapter.repository.sqlite

import chess.application.external.{BindingId, BindingStatus, ExternalGameBinding}
import chess.application.port.repository.{ExternalGameBindingRepository, RepositoryError}
import chess.application.session.model.{ExternalPlatform, SessionMode}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import java.sql.{Connection, ResultSet}
import java.time.Instant
import java.util.UUID

/** SQLite-backed [[ExternalGameBindingRepository]].
  *
  * All reads use [[SqliteDataSource.withConnection]]. Status mutations use
  * [[SqliteDataSource.withConnection]] directly; atomic multi-table writes are handled at the
  * [[SqliteExternalGameCommandStore]] level.
  *
  * ===Uniqueness===
  * The `UNIQUE (platform, external_game_id)` constraint on the table enforces the idempotency
  * invariant. [[create]] returns [[RepositoryError.Conflict]] when a duplicate is detected.
  */
class SqliteExternalGameBindingRepository(ds: SqliteDataSource) extends ExternalGameBindingRepository:

  override def create(binding: ExternalGameBinding): Either[RepositoryError, Unit] =
    try
      ds.withConnection { conn =>
        insertBinding(conn, binding)
        Right(())
      }
    catch
      case e: java.sql.SQLException if isUniqueViolation(e) =>
        Left(RepositoryError.Conflict(
          s"Binding already exists for (${binding.platform}, ${binding.externalGameId})"
        ))
      case e: java.sql.SQLException =>
        Left(RepositoryError.StorageFailure(e.getMessage))

  override def findByExternalId(
      platform: ExternalPlatform,
      externalGameId: String
  ): Either[RepositoryError, Option[ExternalGameBinding]] =
    try
      ds.withConnection { conn =>
        val ps = conn.prepareStatement(
          "SELECT * FROM external_game_bindings WHERE platform = ? AND external_game_id = ?"
        )
        try
          ps.setString(1, platform.toString)
          ps.setString(2, externalGameId)
          val rs = ps.executeQuery()
          try
            if rs.next() then Right(Some(rowToBinding(rs)))
            else Right(None)
          finally rs.close()
        finally ps.close()
      }
    catch case e: java.sql.SQLException => Left(RepositoryError.StorageFailure(e.getMessage))

  override def findById(bindingId: BindingId): Either[RepositoryError, Option[ExternalGameBinding]] =
    try
      ds.withConnection { conn =>
        val ps = conn.prepareStatement(
          "SELECT * FROM external_game_bindings WHERE binding_id = ?"
        )
        try
          ps.setString(1, bindingId.value.toString)
          val rs = ps.executeQuery()
          try
            if rs.next() then Right(Some(rowToBinding(rs)))
            else Right(None)
          finally rs.close()
        finally ps.close()
      }
    catch case e: java.sql.SQLException => Left(RepositoryError.StorageFailure(e.getMessage))

  override def markFinished(bindingId: BindingId, now: Instant): Either[RepositoryError, Unit] =
    updateStatus(bindingId, "Finished", None, now)

  override def markFailed(bindingId: BindingId, reason: String, now: Instant): Either[RepositoryError, Unit] =
    updateStatus(bindingId, "Failed", Some(reason), now)

  // ── Package-private helpers ────────────────────────────────────────────────

  /** Insert a binding row within a caller-supplied JDBC connection.
    *
    * The connection must already be inside an open transaction managed by the caller. Does not
    * commit or roll back; propagates [[java.sql.SQLException]] to the caller.
    *
    * Used by [[SqliteExternalGameCommandStore]] to include the binding insert inside the same
    * transaction as session and game-state writes.
    */
  private[sqlite] def insertBinding(conn: Connection, binding: ExternalGameBinding): Unit =
    val (statusStr, reasonOpt) = statusColumns(binding.status)
    val ps = conn.prepareStatement(
      """INSERT INTO external_game_bindings
        |  (binding_id, platform, external_game_id, internal_game_id, session_id,
        |   our_actor_id, status, status_reason, last_processed_ply, created_at, updated_at)
        |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin
    )
    try
      ps.setString(1, binding.bindingId.value.toString)
      ps.setString(2, binding.platform.toString)
      ps.setString(3, binding.externalGameId)
      ps.setString(4, binding.internalGameId.value.toString)
      ps.setString(5, binding.sessionId.value.toString)
      ps.setString(6, binding.ourActorId)
      ps.setString(7, statusStr)
      ps.setString(8, reasonOpt.orNull)
      ps.setInt(9, binding.lastProcessedPly)
      ps.setString(10, binding.createdAt.toString)
      ps.setString(11, binding.updatedAt.toString)
      ps.executeUpdate()
    finally ps.close()

  /** Advance `lastProcessedPly` to `newPly` within a caller-supplied JDBC connection.
    *
    * Only updates if `status = 'Active'` and `last_processed_ply < newPly`. Returns the number
    * of rows updated (0 = stale or not active, 1 = success).
    *
    * Used by [[SqliteExternalGameCommandStore.saveMoveWithPlyAdvance]] inside a transaction.
    */
  private[sqlite] def advancePlyInTx(
      conn: Connection,
      bindingId: BindingId,
      newPly: Int,
      now: Instant
  ): Int =
    val ps = conn.prepareStatement(
      """UPDATE external_game_bindings
        |SET last_processed_ply = ?, updated_at = ?
        |WHERE binding_id = ? AND status = 'Active' AND last_processed_ply < ?""".stripMargin
    )
    try
      ps.setInt(1, newPly)
      ps.setString(2, now.toString)
      ps.setString(3, bindingId.value.toString)
      ps.setInt(4, newPly)
      ps.executeUpdate()
    finally ps.close()

  /** Mark binding as Finished within a caller-supplied JDBC connection. Returns rows updated. */
  private[sqlite] def markFinishedInTx(conn: Connection, bindingId: BindingId, now: Instant): Int =
    val ps = conn.prepareStatement(
      """UPDATE external_game_bindings
        |SET status = 'Finished', status_reason = NULL, updated_at = ?
        |WHERE binding_id = ? AND status = 'Active'""".stripMargin
    )
    try
      ps.setString(1, now.toString)
      ps.setString(2, bindingId.value.toString)
      ps.executeUpdate()
    finally ps.close()

  // ── private helpers ────────────────────────────────────────────────────────

  private def updateStatus(
      bindingId: BindingId,
      status: String,
      reason: Option[String],
      now: Instant
  ): Either[RepositoryError, Unit] =
    try
      ds.withConnection { conn =>
        val ps = conn.prepareStatement(
          """UPDATE external_game_bindings
            |SET status = ?, status_reason = ?, updated_at = ?
            |WHERE binding_id = ?""".stripMargin
        )
        try
          ps.setString(1, status)
          ps.setString(2, reason.orNull)
          ps.setString(3, now.toString)
          ps.setString(4, bindingId.value.toString)
          val rows = ps.executeUpdate()
          if rows == 0 then Left(RepositoryError.NotFound(bindingId.value.toString))
          else Right(())
        finally ps.close()
      }
    catch case e: java.sql.SQLException => Left(RepositoryError.StorageFailure(e.getMessage))

  private def rowToBinding(rs: ResultSet): ExternalGameBinding =
    val status = rs.getString("status")
    val reason = Option(rs.getString("status_reason"))
    ExternalGameBinding(
      bindingId        = BindingId(UUID.fromString(rs.getString("binding_id"))),
      platform         = parsePlatform(rs.getString("platform")),
      externalGameId   = rs.getString("external_game_id"),
      internalGameId   = GameId(UUID.fromString(rs.getString("internal_game_id"))),
      sessionId        = SessionId(UUID.fromString(rs.getString("session_id"))),
      ourActorId       = rs.getString("our_actor_id"),
      status           = parseStatus(status, reason),
      lastProcessedPly = rs.getInt("last_processed_ply"),
      createdAt        = Instant.parse(rs.getString("created_at")),
      updatedAt        = Instant.parse(rs.getString("updated_at"))
    )

  private def statusColumns(status: BindingStatus): (String, Option[String]) = status match
    case BindingStatus.Active          => ("Active", None)
    case BindingStatus.Finished        => ("Finished", None)
    case BindingStatus.Failed(reason)  => ("Failed", Some(reason))

  private def parseStatus(status: String, reason: Option[String]): BindingStatus = status match
    case "Active"   => BindingStatus.Active
    case "Finished" => BindingStatus.Finished
    case "Failed"   => BindingStatus.Failed(reason.getOrElse(""))
    case other      => throw IllegalStateException(s"Unknown binding status in DB: $other")

  private def parsePlatform(value: String): ExternalPlatform =
    ExternalPlatform.values
      .find(_.toString == value)
      .getOrElse(throw IllegalStateException(s"Unknown external platform in DB: $value"))

  private def isUniqueViolation(e: java.sql.SQLException): Boolean =
    e.getErrorCode == 19 || Option(e.getMessage).exists(_.contains("UNIQUE constraint failed"))
