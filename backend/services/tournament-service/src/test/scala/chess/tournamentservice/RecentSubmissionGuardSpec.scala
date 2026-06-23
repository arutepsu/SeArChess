package chess.tournamentservice

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RecentSubmissionGuardSpec extends AnyFlatSpec with Matchers:

  "RecentSubmissionGuard" should "not block an unknown key" in {
    val guard = RecentSubmissionGuard(ttlMillis = 5000L)
    guard.isRecentlySubmitted("k1") shouldBe false
  }

  it should "block immediately after markSubmitted" in {
    val guard = RecentSubmissionGuard(ttlMillis = 5000L)
    guard.markSubmitted("k1")
    guard.isRecentlySubmitted("k1") shouldBe true
  }

  it should "not block a different key when another key was submitted" in {
    val guard = RecentSubmissionGuard(ttlMillis = 5000L)
    guard.markSubmitted("k1")
    guard.isRecentlySubmitted("k2") shouldBe false
  }

  it should "unblock after the TTL window elapses" in {
    var now = 1000L
    val guard = RecentSubmissionGuard(ttlMillis = 100L, nowMillis = () => now)
    guard.markSubmitted("k1")
    guard.isRecentlySubmitted("k1") shouldBe true   // expires at 1100
    now = 1101L
    guard.isRecentlySubmitted("k1") shouldBe false
  }

  it should "re-block after a key that expired is re-submitted" in {
    var now = 1000L
    val guard = RecentSubmissionGuard(ttlMillis = 100L, nowMillis = () => now)
    guard.markSubmitted("k1")
    now = 1200L  // expired
    guard.isRecentlySubmitted("k1") shouldBe false
    guard.markSubmitted("k1")       // re-mark; expires at 1300
    guard.isRecentlySubmitted("k1") shouldBe true
    now = 1301L
    guard.isRecentlySubmitted("k1") shouldBe false
  }
