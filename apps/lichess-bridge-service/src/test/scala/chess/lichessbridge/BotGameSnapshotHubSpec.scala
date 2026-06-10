package chess.lichessbridge

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

class BotGameSnapshotHubSpec extends AnyFlatSpec with Matchers:

  private def makeHub(): BotGameSnapshotHub =
    BotGameSnapshotHub.create.unsafeRunSync()

  private def snap(gameId: String, status: String = "started", ts: Long = 1000L): BotGameSnapshot =
    BotGameSnapshot(gameId, "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
      "", "white", Some(300000), Some(300000), status, None, ts)

  // ── latest ────────────────────────────────────────────────────────────────────

  "BotGameSnapshotHub.latest" should "return None before any publish" in {
    val hub = makeHub()
    hub.latest("g1").unsafeRunSync() shouldBe None
  }

  it should "return Some after publishing a snapshot" in {
    val hub = makeHub()
    val s   = snap("g1")
    hub.publish(s).unsafeRunSync()
    hub.latest("g1").unsafeRunSync() shouldBe Some(s)
  }

  it should "store latest per gameId independently" in {
    val hub = makeHub()
    val s1  = snap("g1", ts = 1000L)
    val s2  = snap("g2", ts = 2000L)
    hub.publish(s1).unsafeRunSync()
    hub.publish(s2).unsafeRunSync()
    hub.latest("g1").unsafeRunSync() shouldBe Some(s1)
    hub.latest("g2").unsafeRunSync() shouldBe Some(s2)
    hub.latest("g3").unsafeRunSync() shouldBe None
  }

  it should "overwrite the stored snapshot on a second publish for the same gameId" in {
    val hub = makeHub()
    val s1  = snap("g1", ts = 1000L)
    val s2  = snap("g1", ts = 2000L)
    hub.publish(s1).unsafeRunSync()
    hub.publish(s2).unsafeRunSync()
    hub.latest("g1").unsafeRunSync() shouldBe Some(s2)
  }

  // ── subscribe ─────────────────────────────────────────────────────────────────

  "BotGameSnapshotHub.subscribe" should "only emit snapshots for the subscribed gameId" in {
    val snapG1 = snap("g1", ts = 2000L)
    val snapG2 = snap("g2", ts = 1000L)

    // Start the subscriber fiber first, then sleep to let it pull the stream and
    // register with the Topic before publishing.  IO.both races on fast schedulers
    // because publish1 drops events that arrive before the subscriber is ready.
    val received = (for
      hub <- BotGameSnapshotHub.create
      fib <- hub.subscribe("g1").take(1).compile.toList.start
      _   <- IO.sleep(200.millis)
      _   <- hub.publish(snapG2)
      _   <- hub.publish(snapG1)
      res <- fib.joinWithNever
    yield res).unsafeRunSync()

    received should have length 1
    received.head.gameId shouldBe "g1"
  }

  it should "emit multiple matching snapshots in order" in {
    val s1 = snap("g1", ts = 1000L)
    val s2 = snap("g1", ts = 2000L)

    val received = (for
      hub <- BotGameSnapshotHub.create
      fib <- hub.subscribe("g1").take(2).compile.toList.start
      _   <- IO.sleep(200.millis)
      _   <- hub.publish(s1)
      _   <- hub.publish(s2)
      res <- fib.joinWithNever
    yield res).unsafeRunSync()

    received should have length 2
    received.map(_.lastUpdated) shouldBe List(1000L, 2000L)
  }
