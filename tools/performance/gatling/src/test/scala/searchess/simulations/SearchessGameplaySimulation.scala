package searchess.simulations

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import searchess.config.GatlingConfig
import searchess.scenarios.GatlingScenarioPatterns
import searchess.workloads.WorkloadProfiles

class SearchessGameplaySimulation extends Simulation {

  private val httpProtocol = http
    .baseUrl(GatlingConfig.baseUrl)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")
    .header("X-Performance-Run-Id", GatlingConfig.performanceRunId)
    .header("X-Performance-Tool", GatlingConfig.performanceTool)
    .header("X-Performance-Workload", GatlingConfig.workloadProfile)
    .header("X-Performance-Phase", GatlingConfig.performancePhase)

  private val selectedScenario =
    GatlingScenarioPatterns.choose(GatlingConfig.scenarioPattern)

  private val workloadPopulation =
    WorkloadProfiles.choose(GatlingConfig.workloadProfile, selectedScenario)

  setUp(
    workloadPopulation
  )
    .protocols(httpProtocol)
    .assertions(
      global.failedRequests.percent.lt(1.0),
      global.responseTime.percentile3.lt(500)
    )
}
