package gatling

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._

class GameSimulation extends Simulation {

  val targetUrl = sys.env.getOrElse("TARGET_URL", "http://localhost:5173")

  val httpProtocol = http
    .baseUrl(targetUrl)
    .acceptHeader("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
    .acceptEncodingHeader("gzip, deflate")
    .acceptLanguageHeader("en-US,en;q=0.5")
    .userAgentHeader("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Gatling/3")

  val scn = scenario("Game Endpoint Load Test")
    .exec(
      http("GET /game")
        .get("/game")
        .check(status.is(200))
    )
    .pause(100.milliseconds) // Simulate user think time

  setUp(
    scn
      .inject(
        rampUsers(50).during(10.seconds) // Setup 50 users over 10 seconds
      )
      .protocols(httpProtocol)
  ).assertions(
    global.responseTime.percentile4.lt(500), // p95 < 500ms
    global.failedRequests.percent.lt(1.0) // error rate < 1%
  )
}
