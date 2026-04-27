package chess.adapter.repository.postgres

import slick.jdbc.PostgresProfile.api.*

import scala.concurrent.Await
import scala.concurrent.duration.Duration

object PostgresGameSchema:

  def createIfNotExists(db: Database, timeout: Duration = Duration.Inf): Unit =
    Await.result(
      db.run(PostgresGameRepository.GameStates.schema.createIfNotExists),
      timeout
    )

  def recreate(db: Database, timeout: Duration = Duration.Inf): Unit =
    Await.result(
      db.run(
        DBIO.seq(
          PostgresGameRepository.GameStates.schema.dropIfExists,
          PostgresGameRepository.GameStates.schema.create
        )
      ),
      timeout
    )
