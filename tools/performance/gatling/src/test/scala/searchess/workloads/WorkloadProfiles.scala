package searchess.workloads

import io.gatling.core.Predef._
import io.gatling.core.structure.{PopulationBuilder, ScenarioBuilder}
import searchess.config.GatlingConfig

object WorkloadProfiles {

  def choose(workload: String, scenario: ScenarioBuilder): PopulationBuilder =
    workload match {
      case "smoke" =>
        scenario.inject(rampUsers(GatlingConfig.smokeUsers).during(GatlingConfig.smokeRampDuration))
      case "load" =>
        scenario.inject(
          rampUsers(GatlingConfig.loadRampUsers).during(GatlingConfig.loadRampDuration),
          constantUsersPerSec(GatlingConfig.loadUsersPerSecond).during(GatlingConfig.loadConstantDuration)
        )
      case "stress" =>
        scenario.inject(
          rampUsers(GatlingConfig.stressRampUsers).during(GatlingConfig.stressRampDuration),
          constantUsersPerSec(GatlingConfig.stressUsersPerSecond).during(GatlingConfig.stressConstantDuration)
        )
      case unknown =>
        throw new IllegalArgumentException(
          s"Unknown Gatling workload: $unknown. Supported: ${GatlingConfig.supportedWorkloads.mkString(", ")}."
        )
    }

  def population(workload: String, scenario: ScenarioBuilder): PopulationBuilder =
    choose(workload, scenario)
}
