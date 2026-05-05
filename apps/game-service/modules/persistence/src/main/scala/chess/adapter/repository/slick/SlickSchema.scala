package chess.adapter.repository.slick

import _root_.slick.jdbc.JdbcProfile

import scala.concurrent.Await
import scala.concurrent.duration.Duration

object SlickSchema:

  def createSessionsIfNotExists(
      profile: JdbcProfile,
      db: profile.backend.Database,
      tables: SlickTables,
      timeout: Duration = Duration.Inf
  ): Unit =
    import profile.api.*
    Await.result(
      db.run(tables.Sessions.schema.createIfNotExists),
      timeout
    )

  def recreateSessions(
      profile: JdbcProfile,
      db: profile.backend.Database,
      tables: SlickTables,
      timeout: Duration = Duration.Inf
  ): Unit =
    import profile.api.*
    Await.result(
      db.run(
        DBIO.seq(
          tables.Sessions.schema.dropIfExists,
          tables.Sessions.schema.create
        )
      ),
      timeout
    )

  def createGamesIfNotExists(
      profile: JdbcProfile,
      db: profile.backend.Database,
      tables: SlickTables,
      timeout: Duration = Duration.Inf
  ): Unit =
    import profile.api.*
    Await.result(
      db.run(tables.GameStates.schema.createIfNotExists),
      timeout
    )

  def recreateGames(
      profile: JdbcProfile,
      db: profile.backend.Database,
      tables: SlickTables,
      timeout: Duration = Duration.Inf
  ): Unit =
    import profile.api.*
    Await.result(
      db.run(
        DBIO.seq(
          tables.GameStates.schema.dropIfExists,
          tables.GameStates.schema.create
        )
      ),
      timeout
    )

  def createSessionGameIfNotExists(
      profile: JdbcProfile,
      db: profile.backend.Database,
      tables: SlickTables,
      timeout: Duration = Duration.Inf
  ): Unit =
    import profile.api.*
    Await.result(
      db.run(
        DBIO.seq(
          tables.Sessions.schema.createIfNotExists,
          tables.GameStates.schema.createIfNotExists
        )
      ),
      timeout
    )

  def recreateSessionGame(
      profile: JdbcProfile,
      db: profile.backend.Database,
      tables: SlickTables,
      timeout: Duration = Duration.Inf
  ): Unit =
    import profile.api.*
    Await.result(
      db.run(
        DBIO.seq(
          tables.GameStates.schema.dropIfExists,
          tables.Sessions.schema.dropIfExists,
          tables.Sessions.schema.create,
          tables.GameStates.schema.create
        )
      ),
      timeout
    )
