package chess.aiservice

/** Independent AI Service entry point. */
object AiServiceMain:

  def main(args: Array[String]): Unit =
    val config = AiServiceConfig.loadOrExit()
    println(s"[ai] Engine: ${config.engineId}")

    val runtime = AiServiceWiring.start(config)
    Runtime.getRuntime.addShutdownHook(new Thread(() => runtime.shutdown()))

    Thread.currentThread().join()
