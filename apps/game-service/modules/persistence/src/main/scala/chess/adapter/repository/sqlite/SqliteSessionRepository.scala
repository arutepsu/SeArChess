package chess.adapter.repository.sqlite

import chess.application.port.repository.{RepositoryError, SessionRepository}
import chess.application.session.model.{GameSession, SessionLifecycle, SessionMode, SideController}
import chess.application.session.model.SessionIds.{GameId, SessionId}

import java.sql.{Connection, ResultSet}
import java.time.Instant
import java.util.UUID

/** SQLite-backed [[SessionRepository]].
 *
 *  Session metadata is stored as flat columns in the `sessions` table.
 *  Transactional multi-table writes are handled at the [[SqliteSessionGameStore]] level
 *  for game-state operations and by [[saveCancelWithOutbox]] for session-cancel operations.
 */
class SqliteSessionRepository(ds: SqliteDataSource) extends SessionRepository:

  override def save(session: GameSession): Either[RepositoryError, Unit] =
    try
      ds.withConnection { conn =>
        upsertSession(conn, session)
        Right(())
      }
    catch
      case e: java.sql.SQLException =>
        Left(RepositoryError.StorageFailure(e.getMessage))

  override def load(id: SessionId): Either[RepositoryError, GameSession] =
    ds.withConnection { conn =>
      val ps = conn.prepareStatement("SELECT * FROM sessions WHERE session_id = ?")
      try
        ps.setString(1, id.value.toString)
        val rs = ps.executeQuery()
        try
          if rs.next() then Right(rowToSession(rs))
          else Left(RepositoryError.NotFound(id.value.toString))
        finally
          rs.close()
      finally
        ps.close()
    }

  override def loadByGameId(id: GameId): Either[RepositoryError, GameSession] =
    ds.withConnection { conn =>
      val ps = conn.prepareStatement("SELECT * FROM sessions WHERE game_id = ?")
      try
        ps.setString(1, id.value.toString)
        val rs = ps.executeQuery()
        try
          if rs.next() then Right(rowToSession(rs))
          else Left(RepositoryError.NotFound(id.value.toString))
        finally
          rs.close()
      finally
        ps.close()
    }

  override def listActive(): Either[RepositoryError, List[GameSession]] =
    ds.withConnection { conn =>
      val ps = conn.prepareStatement(
        "SELECT * FROM sessions WHERE lifecycle != 'Finished'"
      )
      try
        val rs = ps.executeQuery()
        try
          val buf = collection.mutable.ListBuffer.empty[GameSession]
          while rs.next() do buf += rowToSession(rs)
          Right(buf.toList)
        finally
          rs.close()
      finally
        ps.close()
    }

  /** Persist a cancelled session and its outbox payload in one JDBC transaction.
   *
   *  When `outboxPayload` is [[None]] (no-op serialiser or history disabled),
   *  delegates to [[save]] — same behaviour as before.
   *
   *  When `outboxPayload` is [[Some]], the session upsert and the outbox row
   *  insert share a single transaction.  If either write fails the other is also
   *  rolled back so the `sessions` row and the `history_event_outbox` row always
   *  agree.
   */
  override def saveCancelWithOutbox(
    session:       GameSession,
    outboxPayload: Option[String]
  ): Either[RepositoryError, Unit] =
    outboxPayload match
      case None => save(session)
      case Some(payload) =>
        ds.withTransaction { conn =>
          try
            upsertSession(conn, session)
            OutboxInsert(conn, payload)
          catch
            case e: java.sql.SQLException =>
              Left(RepositoryError.StorageFailure(e.getMessage))
        }

  // ── Package-private for use by SqliteSessionGameStore ─────────────────────

  private[sqlite] def upsertSession(conn: Connection, session: GameSession): Unit =
    val ps = conn.prepareStatement(
      """INSERT OR REPLACE INTO sessions
        |  (session_id, game_id, mode, white_controller, black_controller,
        |   lifecycle, created_at, updated_at)
        |VALUES (?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin
    )
    try
      ps.setString(1, session.sessionId.value.toString)
      ps.setString(2, session.gameId.value.toString)
      ps.setString(3, modeStr(session.mode))
      ps.setString(4, controllerStr(session.whiteController))
      ps.setString(5, controllerStr(session.blackController))
      ps.setString(6, lifecycleStr(session.lifecycle))
      ps.setString(7, session.createdAt.toString)
      ps.setString(8, session.updatedAt.toString)
      ps.executeUpdate()
    finally
      ps.close()

  // ── Row → model ───────────────────────────────────────────────────────────

  private def rowToSession(rs: ResultSet): GameSession =
    GameSession(
      sessionId       = SessionId(UUID.fromString(rs.getString("session_id"))),
      gameId          = GameId(UUID.fromString(rs.getString("game_id"))),
      mode            = parseMode(rs.getString("mode")),
      whiteController = parseController(rs.getString("white_controller")),
      blackController = parseController(rs.getString("black_controller")),
      lifecycle       = parseLifecycle(rs.getString("lifecycle")),
      createdAt       = Instant.parse(rs.getString("created_at")),
      updatedAt       = Instant.parse(rs.getString("updated_at"))
    )

  // ── Encoders ──────────────────────────────────────────────────────────────

  private def modeStr(m: SessionMode): String = m match
    case SessionMode.HumanVsHuman => "HumanVsHuman"
    case SessionMode.HumanVsAI    => "HumanVsAI"
    case SessionMode.AIVsAI       => "AIVsAI"

  private def controllerStr(c: SideController): String = c match
    case SideController.HumanLocal       => "HumanLocal"
    case SideController.HumanRemote      => "HumanRemote"
    case SideController.AI(Some(engine)) => s"AI:$engine"
    case SideController.AI(None)         => "AI"

  private def lifecycleStr(l: SessionLifecycle): String = l match
    case SessionLifecycle.Created           => "Created"
    case SessionLifecycle.Active            => "Active"
    case SessionLifecycle.AwaitingPromotion => "AwaitingPromotion"
    case SessionLifecycle.Finished          => "Finished"

  // ── Decoders ──────────────────────────────────────────────────────────────

  private def parseMode(s: String): SessionMode = s match
    case "HumanVsHuman" => SessionMode.HumanVsHuman
    case "HumanVsAI"    => SessionMode.HumanVsAI
    case "AIVsAI"       => SessionMode.AIVsAI
    case other          => throw IllegalStateException(s"Unknown session mode in DB: $other")

  private def parseController(s: String): SideController = s match
    case "HumanLocal"  => SideController.HumanLocal
    case "HumanRemote" => SideController.HumanRemote
    case "AI"          => SideController.AI(None)
    case ai if ai.startsWith("AI:") => SideController.AI(Some(ai.stripPrefix("AI:")))
    case other         => throw IllegalStateException(s"Unknown controller in DB: $other")

  private def parseLifecycle(s: String): SessionLifecycle = s match
    case "Created"           => SessionLifecycle.Created
    case "Active"            => SessionLifecycle.Active
    case "AwaitingPromotion" => SessionLifecycle.AwaitingPromotion
    case "Finished"          => SessionLifecycle.Finished
    case other               => throw IllegalStateException(s"Unknown lifecycle in DB: $other")
