package chess.server.migration

import chess.server.config.PostgresConfigLoader

import java.net.URI

object MigrationConfigLoader:
  type PostgresConfig = chess.server.config.PostgresConfig

  final case class MongoConfig(
      uri: String,
      database: String
  ):
    def databaseName: String = database

  def loadPostgresConfig(
      env: String => Option[String] = key => Option(System.getenv(key))
  ): Either[String, PostgresConfig] =
    PostgresConfigLoader.load(env)

  def loadMongoConfig(
      env: String => Option[String] = key => Option(System.getenv(key))
  ): Either[String, MongoConfig] =
    required(env, "SEARCHESS_MONGO_URI").map { uri =>
      val overrideDatabase = env("SEARCHESS_MONGO_DATABASE").filter(_.nonEmpty)
      MongoConfig(
        uri = uri,
        database = overrideDatabase.getOrElse(deriveMongoDatabaseName(uri))
      )
    }

  def loadPostgres(
      env: String => Option[String] = key => Option(System.getenv(key))
  ): Either[String, PostgresConfig] =
    loadPostgresConfig(env)

  def loadMongo(
      env: String => Option[String] = key => Option(System.getenv(key))
  ): Either[String, MongoConfig] =
    loadMongoConfig(env)

  private def required(
      env: String => Option[String],
      key: String
  ): Either[String, String] =
    env(key)
      .map(_.trim)
      .filter(_.nonEmpty)
      .toRight(s"Missing required environment variable: $key")

  private def deriveMongoDatabaseName(uri: String): String =
    scala.util.Try(URI.create(uri)).toOption
      .flatMap { parsed =>
        Option(parsed.getPath)
          .map(_.trim)
          .filter(path => path.nonEmpty && path != "/")
          .map(_.stripPrefix("/"))
          .filter(_.nonEmpty)
      }
      .map(_.takeWhile(_ != '?'))
      .filter(_.nonEmpty)
      .getOrElse("searchess")
