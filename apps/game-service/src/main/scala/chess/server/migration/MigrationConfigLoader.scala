package chess.server.migration

object MigrationConfigLoader:
  type MongoConfig = chess.server.config.MongoConfig
  type PostgresConfig = chess.server.config.PostgresConfig

  def loadPostgresConfig(
      env: String => Option[String] = key => Option(System.getenv(key))
  ): Either[String, PostgresConfig] =
    chess.server.config.PostgresConfigLoader.load(env)

  def loadMongoConfig(
      env: String => Option[String] = key => Option(System.getenv(key))
  ): Either[String, MongoConfig] =
    chess.server.config.MongoConfigLoader.load(env)

  def loadPostgres(
      env: String => Option[String] = key => Option(System.getenv(key))
  ): Either[String, PostgresConfig] =
    loadPostgresConfig(env)

  def loadMongo(
      env: String => Option[String] = key => Option(System.getenv(key))
  ): Either[String, MongoConfig] =
    loadMongoConfig(env)

