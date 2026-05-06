package searchess.config

import scala.concurrent.duration._

object GatlingConfig {

  val supportedWorkloads: Seq[String] = Seq("smoke", "load", "stress")
  val supportedScenarioPatterns: Seq[String] =
    Seq("all", "gameplay", "session", "legalMoves", "moveSubmission", "readHeavy", "writeHeavy")

  private def configValue(propertyName: String, envNames: String*): Option[String] =
    Option(System.getProperty(propertyName))
      .orElse(envNames.iterator.map(System.getenv).find(_ != null))
      .map(_.trim)
      .filter(_.nonEmpty)

  private def stringValue(propertyName: String, default: String, envNames: String*): String =
    configValue(propertyName, envNames: _*).getOrElse(default)

  private def intValue(propertyName: String, default: Int, envNames: String*): Int =
    configValue(propertyName, envNames: _*).fold(default)(_.toInt)

  private def doubleValue(propertyName: String, default: Double, envNames: String*): Double =
    configValue(propertyName, envNames: _*).fold(default)(_.toDouble)

  val baseUrl: String =
    stringValue("searchess.baseUrl", "http://localhost:8080", "GATLING_BASE_URL", "BASE_URL")
      .stripSuffix("/")

  private def validate(value: String, supported: Seq[String], label: String): String =
    if (supported.contains(value)) value
    else throw new IllegalArgumentException(
      s"Unknown Gatling $label: $value. Supported: ${supported.mkString(", ")}."
    )

  val workloadProfile: String =
    validate(
      stringValue(
        "searchess.gatling.workload",
        "load",
        "GATLING_WORKLOAD",
        "PERFORMANCE_WORKLOAD"
      ).toLowerCase,
      supportedWorkloads,
      "workload"
    )

  val scenarioPattern: String =
    validate(
      stringValue(
        "searchess.gatling.pattern",
        "gameplay",
        "GATLING_PATTERN"
      ),
      supportedScenarioPatterns,
      "pattern"
    )

  val performanceRunId: String =
    stringValue("searchess.runId", "local-dev", "GATLING_RUN_ID", "PERFORMANCE_RUN_ID")

  val performanceTool: String =
    stringValue("searchess.tool", "gatling", "GATLING_TOOL", "PERFORMANCE_TOOL")

  val performancePhase: String =
    stringValue("searchess.phase", "local", "GATLING_PHASE", "PERFORMANCE_PHASE")

  val thinkTime: FiniteDuration =
    intValue("searchess.pauseMillis", 200, "GATLING_PAUSE_MILLIS").milliseconds

  val gameplayPlyCount: Int =
    intValue("searchess.gameplayPlyCount", 4, "GATLING_GAMEPLAY_PLY_COUNT")

  val smokeUsers: Int =
    intValue("searchess.smoke.users", 3, "GATLING_SMOKE_USERS")
  val smokeRampDuration: FiniteDuration =
    intValue("searchess.smoke.durationSeconds", 3, "GATLING_SMOKE_DURATION_SECONDS").seconds

  val loadRampUsers: Int =
    intValue("searchess.load.rampUsers", 50, "GATLING_LOAD_RAMP_USERS")
  val loadRampDuration: FiniteDuration =
    intValue("searchess.load.rampDurationSeconds", 10, "GATLING_LOAD_RAMP_DURATION_SECONDS").seconds
  val loadUsersPerSecond: Double =
    doubleValue("searchess.load.usersPerSecond", 5.0, "GATLING_LOAD_USERS_PER_SECOND")
  val loadConstantDuration: FiniteDuration =
    intValue("searchess.load.durationSeconds", 50, "GATLING_LOAD_DURATION_SECONDS").seconds

  val stressRampUsers: Int =
    intValue("searchess.stress.rampUsers", 100, "GATLING_STRESS_RAMP_USERS")
  val stressRampDuration: FiniteDuration =
    intValue("searchess.stress.rampDurationSeconds", 15, "GATLING_STRESS_RAMP_DURATION_SECONDS").seconds
  val stressUsersPerSecond: Double =
    doubleValue("searchess.stress.usersPerSecond", 10.0, "GATLING_STRESS_USERS_PER_SECOND")
  val stressConstantDuration: FiniteDuration =
    intValue("searchess.stress.durationSeconds", 60, "GATLING_STRESS_DURATION_SECONDS").seconds
}
