package chess.analyticsservice

import chess.observability.StructuredLog

object AnalyticsServiceMain:

  def main(args: Array[String]): Unit =
    val config = AnalyticsServiceConfig.loadOrExit()
    StructuredLog.info(
      "analytics-service",
      "startup_config",
      "httpHost"       -> config.host,
      "httpPort"       -> config.port,
      "postgresSchema" -> config.postgresSchema
    )

    val runtime = AnalyticsServiceWiring.start(config)
    StructuredLog.info(
      "analytics-service",
      "started",
      "httpHost" -> config.host,
      "httpPort" -> config.port,
      "healthPath" -> "/health"
    )

    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      StructuredLog.info("analytics-service", "shutdown_started")
      runtime.shutdown()
      StructuredLog.info("analytics-service", "shutdown_completed")
    }))

    Thread.currentThread().join()
