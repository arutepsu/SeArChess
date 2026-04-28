package chess.server.config

object PostgresConfigLoader:

  val MissingDefaultPostgresMessage: String =
    """Postgres is the default game-service persistence backend.
      |Set SEARCHESS_POSTGRES_URL, SEARCHESS_POSTGRES_USER, and SEARCHESS_POSTGRES_PASSWORD,
      |then start local databases with:
      |  docker compose -f docker-compose.persistence.yml up -d
      |For lightweight local runs, set PERSISTENCE_MODE=sqlite or PERSISTENCE_MODE=in-memory.
      |""".stripMargin.trim

  def load(
      env: String => Option[String],
      defaultUser: String = "postgres",
      defaultPassword: String = "postgres",
      requireCredentials: Boolean = false,
      contextMessage: Option[String] = None
  ): Either[String, PostgresConfig] =
    for
      url <- required(env, "SEARCHESS_POSTGRES_URL", contextMessage)
      user <-
        if requireCredentials then required(env, "SEARCHESS_POSTGRES_USER", contextMessage)
        else Right(env("SEARCHESS_POSTGRES_USER").filter(_.nonEmpty).getOrElse(defaultUser))
      password <-
        if requireCredentials then required(env, "SEARCHESS_POSTGRES_PASSWORD", contextMessage)
        else Right(env("SEARCHESS_POSTGRES_PASSWORD").filter(_.nonEmpty).getOrElse(defaultPassword))
    yield PostgresConfig(url = url, user = user, password = password)

  private def required(
      env: String => Option[String],
      key: String,
      contextMessage: Option[String]
  ): Either[String, String] =
    env(key)
      .map(_.trim)
      .filter(_.nonEmpty)
      .toRight(
        contextMessage match
          case Some(message) => s"Missing required environment variable: $key\n$message"
          case None          => s"Missing required environment variable: $key"
      )
