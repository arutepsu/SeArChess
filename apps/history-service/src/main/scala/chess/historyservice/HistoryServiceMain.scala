package chess.historyservice

object HistoryServiceMain:

  def main(args: Array[String]): Unit =
    val config = HistoryServiceConfig.loadOrExit()
    println(s"[history] Game Service archive base URL: ${config.gameServiceBaseUrl}")
    println(s"[history] Archive DB: ${config.dbPath}")

    val runtime = HistoryServiceWiring.start(config)
    Runtime.getRuntime.addShutdownHook(new Thread(() => runtime.shutdown()))

    Thread.currentThread().join()
