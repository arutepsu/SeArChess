package chess.tournamentservice

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PublicTournamentRunnerSchedulerSpec extends AnyFlatSpec with Matchers:

  // ── helpers ───────────────────────────────────────────────────────────────────

  private def emptyResult(id: String): PublicTournamentRunnerTickResult =
    PublicTournamentRunnerTickResult(id, 0, 0, Nil)

  private class FakeDiscovery(result: Either[String, List[String]]) extends PublicTournamentDiscoveryClient:
    def listRunnableTournamentIds(): IO[Either[String, List[String]]] = IO.pure(result)

  private class FakeTickRunner(
      tickResults: Map[String, Either[String, PublicTournamentRunnerTickResult]] = Map.empty,
      defaultResult: PublicTournamentRunnerTickResult = emptyResult("?")
  ) extends TournamentTickRunner:
    private val calledRef: Ref[IO, List[String]] = Ref.of[IO, List[String]](Nil).unsafeRunSync()

    def calledWith: List[String] = calledRef.get.unsafeRunSync()

    def tick(tournamentId: String): IO[PublicTournamentRunnerTickResult] =
      calledRef.update(_ :+ tournamentId) >> {
        tickResults.get(tournamentId) match
          case Some(Left(err))    => IO.raiseError(RuntimeException(err))
          case Some(Right(result)) => IO.pure(result)
          case None               => IO.pure(defaultResult.copy(tournamentId = tournamentId))
      }

  private def makeScheduler(
      runner:    TournamentTickRunner,
      discovery: PublicTournamentDiscoveryClient,
      max:       Int = 10
  ): PublicTournamentRunnerScheduler =
    PublicTournamentRunnerScheduler(
      runner         = runner,
      discovery      = discovery,
      intervalSecs   = 60,
      maxTournaments = max
    )

  // ── tests ─────────────────────────────────────────────────────────────────────

  "PublicTournamentRunnerScheduler.runCycle" should "tick each discovered tournament" in {
    val ids    = List("t1", "t2", "t3")
    val runner = FakeTickRunner()
    val sched  = makeScheduler(runner, FakeDiscovery(Right(ids)))
    sched.runCycle().unsafeRunSync()
    runner.calledWith should contain theSameElementsInOrderAs ids
  }

  it should "call no ticks when discovery returns an empty list" in {
    val runner = FakeTickRunner()
    val sched  = makeScheduler(runner, FakeDiscovery(Right(Nil)))
    sched.runCycle().unsafeRunSync()
    runner.calledWith shouldBe Nil
  }

  it should "limit tournaments to maxTournamentsPerCycle" in {
    val ids    = List("t1", "t2", "t3", "t4", "t5")
    val runner = FakeTickRunner()
    val sched  = makeScheduler(runner, FakeDiscovery(Right(ids)), max = 3)
    sched.runCycle().unsafeRunSync()
    runner.calledWith should have size 3
    runner.calledWith shouldBe List("t1", "t2", "t3")
  }

  it should "continue after one tournament tick raises an error" in {
    val runner = FakeTickRunner(
      tickResults = Map("t1" -> Left("boom"), "t2" -> Right(emptyResult("t2")))
    )
    val sched = makeScheduler(runner, FakeDiscovery(Right(List("t1", "t2"))))
    noException shouldBe thrownBy { sched.runCycle().unsafeRunSync() }
    runner.calledWith should contain("t1")
    runner.calledWith should contain("t2")
  }

  it should "not crash when discovery returns an error" in {
    val runner = FakeTickRunner()
    val sched  = makeScheduler(runner, FakeDiscovery(Left("server down")))
    noException shouldBe thrownBy { sched.runCycle().unsafeRunSync() }
    runner.calledWith shouldBe Nil
  }

  it should "tick tournaments in the order returned by discovery" in {
    val ids    = List("z-last", "a-first", "m-middle")
    val runner = FakeTickRunner()
    val sched  = makeScheduler(runner, FakeDiscovery(Right(ids)))
    sched.runCycle().unsafeRunSync()
    runner.calledWith shouldBe ids
  }

  // Non-overlapping cycle guarantee:
  // start() chains (runCycle().attempt >> IO.sleep(interval)).foreverM
  // The next sleep only begins after runCycle() completes, so two cycles
  // never execute concurrently regardless of how long a cycle takes.
  // This is a structural property of sequential IO composition and does not
  // require a timing-based test.
