package chess.lichessbridge

import cats.effect.{IO, Ref}
import fs2.Stream
import fs2.concurrent.Topic

/** Stores the latest snapshot per game and provides live subscription.
  *
  * Combines a Ref (latest-value cache) with a Topic (live pub/sub) so that
  * late subscribers can immediately receive the current game state instead of
  * waiting for the next published event.
  *
  * Thread-safe: all operations are IO-based with no shared mutable state.
  */
final class BotGameSnapshotHub private (
    latestRef: Ref[IO, Map[String, BotGameSnapshot]],
    topic: Topic[IO, BotGameSnapshot]
):
  def publish(snapshot: BotGameSnapshot): IO[Unit] =
    latestRef.update(_ + (snapshot.gameId -> snapshot)) >>
    topic.publish1(snapshot).void

  def latest(gameId: String): IO[Option[BotGameSnapshot]] =
    latestRef.get.map(_.get(gameId))

  def subscribe(gameId: String): Stream[IO, BotGameSnapshot] =
    topic.subscribe(128).filter(_.gameId == gameId)

object BotGameSnapshotHub:
  def create: IO[BotGameSnapshotHub] =
    for
      latestRef <- Ref.of[IO, Map[String, BotGameSnapshot]](Map.empty)
      topic     <- Topic[IO, BotGameSnapshot]
    yield new BotGameSnapshotHub(latestRef, topic)
