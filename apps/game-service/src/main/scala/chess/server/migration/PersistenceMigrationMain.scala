package chess.server.migration

object PersistenceMigrationMain:

  def main(args: Array[String]): Unit =
    val code: Int =
      MigrationCliParser.parse(args.toList) match
        case Left(error) =>
          Console.err.println(error)
          Console.err.println(MigrationCliParser.usage)
          1

        case Right(command) =>
          MigrationCliRunner.run(command)

    sys.exit(code)