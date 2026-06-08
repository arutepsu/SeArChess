package chess.adapter.http4s.route

import cats.effect.IO
import org.http4s.*
import org.typelevel.ci.CIString

/** Simple API-key authentication for the internal bot-worker endpoints.
  *
  * Unlike [[ExternalGameRouteAuth]], this class does not resolve a platform identity or a
  * [[chess.application.external.VerifiedExternalCaller]]. The internal bot routes have no concept
  * of external platforms — they authenticate only via a shared secret.
  */
final class BotWorkerAuth(apiKey: String):
  private val ApiKeyHeader = CIString("X-Bot-Api-Key")

  def verify(req: Request[IO]): Either[String, Unit] =
    req.headers
      .get(ApiKeyHeader)
      .map(_.head.value.trim)
      .filter(_.nonEmpty) match
      case None                   => Left("Bot API key is required.")
      case Some(k) if k == apiKey => Right(())
      case _                      => Left("Bot API key is invalid.")
