package chess.adapter.repository.postgres

import slick.jdbc.PostgresProfile.api.*

import scala.concurrent.Await
import scala.concurrent.duration.Duration

object PostgresSessionGameSchema:

  def createIfNotExists(db: Database, timeout: Duration = Duration.Inf): Unit =
    Await.result(
      db.run(
        DBIO.seq(
          PostgresSessionRepository.Sessions.schema.createIfNotExists,
          PostgresGameRepository.GameStates.schema.createIfNotExists
        )
      ),
      timeout
    )

  def recreate(db: Database, timeout: Duration = Duration.Inf): Unit =
    Await.result(
      db.run(
        DBIO.seq(
          PostgresGameRepository.GameStates.schema.dropIfExists,
          PostgresSessionRepository.Sessions.schema.dropIfExists,
          PostgresSessionRepository.Sessions.schema.create,
          PostgresGameRepository.GameStates.schema.create
        )
      ),
      timeout
    )
