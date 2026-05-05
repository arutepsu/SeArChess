package searchess

import io.gatling.core.Predef._
import io.gatling.core.structure.PopulationBuilder
import io.gatling.core.structure.ChainBuilder
import io.gatling.http.Predef._
import scala.concurrent.duration._

class SearchessGameplaySimulation extends Simulation {

  private def envValue(names: String*): Option[String] =
    names.iterator
      .map(name => Option(System.getenv(name)).map(_.trim).filter(_.nonEmpty))
      .collectFirst { case Some(value) => value }

  private val baseUrl: String = envValue("GATLING_BASE_URL", "BASE_URL")
    .getOrElse("http://localhost:8080")
    .stripSuffix("/")

  private val thinkTime = 200.milliseconds

  private val workloadProfile: String = envValue("GATLING_WORKLOAD", "PERFORMANCE_WORKLOAD")
    .map(_.trim.toLowerCase)
    .getOrElse("load")

  private val performanceRunId: String = envValue("GATLING_RUN_ID", "PERFORMANCE_RUN_ID").getOrElse("local-dev")
  private val performanceTool: String = envValue("GATLING_TOOL", "PERFORMANCE_TOOL").getOrElse("gatling")
  private val performancePhase: String = envValue("GATLING_PHASE", "PERFORMANCE_PHASE").getOrElse("local")

  private val httpProtocol = http
    .baseUrl(baseUrl)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")
    .header("X-Performance-Run-Id", performanceRunId)
    .header("X-Performance-Tool", performanceTool)
    .header("X-Performance-Workload", workloadProfile)
    .header("X-Performance-Phase", performancePhase)

  // Small feeder example: every virtual user still creates an isolated API session,
  // but the session mode is injected as test data instead of hard-coded in the request.
  // Larger scenarios could feed authenticated users, imported positions, or openings.
  private val modeFeeder = csv("searchess/session_modes.csv").circular

  private def selectMoveBody(plyIndex: Int): ChainBuilder = exec { session =>
    val froms = session("moveFroms").asOption[Seq[String]].getOrElse(Seq.empty)
    val tos = session("moveTos").asOption[Seq[String]].getOrElse(Seq.empty)
    val pairs = froms.zip(tos).sortBy { case (f, t) => s"$f-$t" }
    if (pairs.isEmpty) {
      // The 4-ply happy path should always have legal moves. If the backend ever
      // returns an empty list, skip this move cleanly and still fetch state.
      session.set("hasLegalMove", false)
    } else {
      val (from, to) = pairs(plyIndex % pairs.size)
      session
        .set("hasLegalMove", true)
        .set("moveBody", s"""{"from":"$from","to":"$to","controller":"HumanLocal"}""")
    }
  }

  // Gatling is code-first: this scenario is ordinary Scala, so each step can be
  // named, composed, parameterized, reviewed, and refactored like application code.
  private val createSession: ChainBuilder =
    feed(modeFeeder)
      .group("Create session") {
        exec(
          http("create session")
            .post("/sessions")
            .body(StringBody("""{"mode":"#{sessionMode}"}"""))
            .check(
              status.is(201),
              jsonPath("$.session.sessionId").ofType[String].transform(_.trim).not("").saveAs("sessionId"),
              jsonPath("$.session.gameId").ofType[String].transform(_.trim).not(""),
              jsonPath("$.session.mode").is("#{sessionMode}"),
              jsonPath("$.game.gameId").ofType[String].transform(_.trim).not("").saveAs("gameId"),
              jsonPath("$.game.status").is("Ongoing")
            )
        )
      }
      .pause(thinkTime)

  private def fetchLegalMoves(plyIndex: Int): ChainBuilder =
    group("Fetch legal moves") {
      exec(
        http(s"legal moves ply $plyIndex")
          .get("/games/#{gameId}/legal-moves")
          .check(
            status.is(200),
            jsonPath("$.gameId").is("#{gameId}"),
            jsonPath("$.currentPlayer").exists,
            jsonPath("$.moves[0].from").exists,
            jsonPath("$.moves[0].to").exists,
            jsonPath("$.moves[*].from").findAll.saveAs("moveFroms"),
            jsonPath("$.moves[*].to").findAll.saveAs("moveTos")
          )
      )
      .exec(selectMoveBody(plyIndex))
    }

  private def submitMove(plyIndex: Int): ChainBuilder =
    doIf(session => session("hasLegalMove").asOption[Boolean].contains(true)) {
      group("Submit move") {
        exec(
          http(s"submit move ply $plyIndex")
            .post("/games/#{gameId}/moves")
            .body(StringBody("#{moveBody}"))
            .check(
              status.is(200),
              jsonPath("$.game.gameId").is("#{gameId}"),
              jsonPath("$.game.status").exists,
              jsonPath("$.game.moveHistory[0].from").exists,
              jsonPath("$.game.moveHistory[0].to").exists,
              jsonPath("$.sessionLifecycle").exists
            )
        )
      }
    }

  private def fetchUpdatedState(plyIndex: Int): ChainBuilder =
    group("Fetch updated state") {
      exec(
        http(s"fetch state ply $plyIndex")
          .get("/sessions/#{sessionId}/state")
          .check(
            status.is(200),
            jsonPath("$.session.sessionId").is("#{sessionId}"),
            jsonPath("$.session.gameId").is("#{gameId}"),
            jsonPath("$.game.gameId").is("#{gameId}"),
            jsonPath("$.game.status").exists,
            jsonPath("$.game.currentPlayer").exists
          )
      )
    }

  private def gameplayTurn(plyIndex: Int): ChainBuilder =
    group("Gameplay turn") {
      exec(fetchLegalMoves(plyIndex))
        .pause(thinkTime)
        .exec(submitMove(plyIndex))
        .pause(thinkTime)
        .exec(fetchUpdatedState(plyIndex))
        .pause(thinkTime)
    }

  private val gameplayLoop: ChainBuilder =
    (0 until 4).foldLeft(exec(session => session)) { (chain, plyIndex) =>
      chain.exec(gameplayTurn(plyIndex))
    }

  private val gameplayScenario = scenario("SearchessGameplay")
    .exec(createSession)
    .exec(gameplayLoop)

  private val workloadPopulation: PopulationBuilder = workloadProfile match {
    case "smoke" =>
      gameplayScenario.inject(rampUsers(3).during(3.seconds))
    case "load" =>
      gameplayScenario.inject(
        rampUsers(50).during(10.seconds),
        constantUsersPerSec(5).during(50.seconds)
      )
    case "stress" =>
      gameplayScenario.inject(
        rampUsers(100).during(15.seconds),
        constantUsersPerSec(10).during(60.seconds)
      )
    case unknown =>
      throw new IllegalArgumentException(
        s"Unknown Gatling workload profile: $unknown. Expected smoke, load, or stress."
      )
  }

  setUp(
    // The injection profile is visible and version-controlled with the scenario.
    workloadPopulation
  )
    .protocols(httpProtocol)
    .assertions(
      global.failedRequests.percent.lt(1.0),
      global.responseTime.percentile3.lt(500)
    )
}
