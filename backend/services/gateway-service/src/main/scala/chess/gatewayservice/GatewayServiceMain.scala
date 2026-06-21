package chess.gatewayservice

import chess.observability.StructuredLog

object GatewayServiceMain:

  def main(args: Array[String]): Unit =
    val config = GatewayServiceConfig.loadOrExit()
    StructuredLog.info(
      "gateway-service",
      "startup_config",
      "httpHost"            -> config.host,
      "httpPort"            -> config.port,
      "tournamentServerUrl" -> config.tournamentServerUrl
    )

    val runtime = GatewayServiceWiring.start(config)
    StructuredLog.info(
      "gateway-service",
      "started",
      "httpHost"   -> config.host,
      "httpPort"   -> config.port,
      "healthPath" -> "/health"
    )

    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      StructuredLog.info("gateway-service", "shutdown_started")
      runtime.shutdown()
      StructuredLog.info("gateway-service", "shutdown_completed")
    }))

    Thread.currentThread().join()
