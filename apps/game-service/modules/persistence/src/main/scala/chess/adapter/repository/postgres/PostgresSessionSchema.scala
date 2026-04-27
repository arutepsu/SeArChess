package chess.adapter.repository.postgres

import slick.jdbc.PostgresProfile.api.*

import scala.concurrent.Await
import scala.concurrent.duration.Duration

object PostgresSessionSchema:

  def createIfNotExists(db: Database, timeout: Duration = Duration.Inf): Unit =
    Await.result(
      db.run(PostgresSessionRepository.Sessions.schema.createIfNotExists),
      timeout
    )

  def recreate(db: Database, timeout: Duration = Duration.Inf): Unit =
    Await.result(
      db.run(
        DBIO.seq(
          PostgresSessionRepository.Sessions.schema.dropIfExists,
          PostgresSessionRepository.Sessions.schema.create
        )
      ),
      timeout
    )
