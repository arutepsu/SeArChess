package gatling

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._

class GameSimulation extends Simulation {

  private val baseUrl = sys.props
    .get("BASE_URL")
    .orElse(sys.props.get("baseUrl"))
    .orElse(sys.env.get("BASE_URL"))
    .getOrElse("http://localhost:8080")

  val httpProtocol = http
    .baseUrl(baseUrl)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")
    .acceptEncodingHeader("gzip, deflate")
    .userAgentHeader("searchess-gatling/1.0")

  val scn = scenario("Game Service Backend Load Test")
    .exec(
      http("GET /health")
        .get("/health")
        .check(status.is(200))
        .check(jsonPath("$.status").is("ok"))
    )
    .pause(100.milliseconds, 300.milliseconds)
    .exec(
      http("POST /sessions")
        .post("/sessions")
        .body(StringBody("""{"mode":"HumanVsHuman"}"""))
        .check(status.is(201))
        .check(jsonPath("$.session.sessionId").saveAs("sessionId"))
        .check(jsonPath("$.game.gameId").saveAs("gameId"))
    )
    .pause(100.milliseconds, 300.milliseconds)
    .repeat(5, "readIteration") {
      exec(
        http("GET /sessions/{sessionId}")
          .get("/sessions/#{sessionId}")
          .check(status.is(200))
          .check(jsonPath("$.sessionId").is("#{sessionId}"))
      )
        .pause(100.milliseconds, 300.milliseconds)
        .exec(
          http("GET /sessions/{sessionId}/state")
            .get("/sessions/#{sessionId}/state")
            .check(status.is(200))
            .check(jsonPath("$.session.sessionId").is("#{sessionId}"))
        )
        .pause(100.milliseconds, 300.milliseconds)
        .exec(
          http("GET /games/{gameId}")
            .get("/games/#{gameId}")
            .check(status.is(200))
            .check(jsonPath("$.gameId").is("#{gameId}"))
        )
        .pause(100.milliseconds, 300.milliseconds)
        .exec(
          http("GET /games/{gameId}/legal-moves")
            .get("/games/#{gameId}/legal-moves")
            .check(status.is(200))
            .check(jsonPath("$.gameId").is("#{gameId}"))
            .check(jsonPath("$.moves").exists)
        )
        .pause(100.milliseconds, 300.milliseconds)
    }

  setUp(
    scn
      .inject(
        rampUsers(50).during(30.seconds)
      )
      .protocols(httpProtocol)
  ).assertions(
    global.responseTime.percentile3.lt(500),
    global.failedRequests.percent.lt(1.0)
  )
}
