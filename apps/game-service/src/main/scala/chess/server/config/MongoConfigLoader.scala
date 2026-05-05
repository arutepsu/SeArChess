package chess.server.config

import java.net.URI

object MongoConfigLoader:

  def load(
      env: String => Option[String],
      contextMessage: Option[String] = None
  ): Either[String, MongoConfig] =
    required(env, "SEARCHESS_MONGO_URI", contextMessage).map { uri =>
      val overrideDatabase = env("SEARCHESS_MONGO_DATABASE").map(_.trim).filter(_.nonEmpty)
      MongoConfig(
        uri = uri,
        database = overrideDatabase.getOrElse(deriveDatabaseName(uri))
      )
    }

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

  private def deriveDatabaseName(uri: String): String =
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
