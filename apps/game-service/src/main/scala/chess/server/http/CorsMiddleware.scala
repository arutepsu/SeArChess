package chess.server.http

import cats.effect.IO
import chess.server.config.CorsConfig
import org.http4s.HttpApp
import org.http4s.headers.Origin
import org.http4s.server.middleware.CORS
import org.http4s.Header.ToRaw.modelledHeadersToRaw
import org.http4s.implicits.http4sHeaderSyntax

/** Applies http4s CORS middleware to the final composed [[HttpApp]].
 *
 *  This is an operational/runtime concern owned by `game-service`.
 *  No CORS logic belongs in business route handlers or application services.
 *
 *  === Origin matching ===
 *  - `allowedOrigin = "*"` → [[CORS.policy.withAllowOriginAll]] (allow any
 *    browser origin; suitable for open dev environments)
 *  - Any other value → [[CORS.policy.withAllowOriginHeader]] matching the
 *    configured origin string; for example `"http://localhost:3000"` allows
 *    only a React/Vite dev server on that port.
 *
 *  Origin matching compares the rendered `scheme://host:port` of the incoming
 *  `Origin` header against the configured string.
 *
 *  === Extension path ===
 *  Multiple allowed origins can be supported later by splitting
 *  [[CorsConfig.allowedOrigin]] on commas and checking membership.
 */
object CorsMiddleware:

  /** Wrap `app` with CORS headers if `config.enabled` is true.
   *
   *  Returns `app` unchanged when CORS is disabled.
   */
  def apply(config: CorsConfig, app: HttpApp[IO]): HttpApp[IO] =
    if !config.enabled then app
    else
      val policy = config.allowedOrigin.trim match
        case "*"    => CORS.policy.withAllowOriginAll
        case origin => CORS.policy.withAllowOriginHeader(matchesOrigin(_, origin))
      policy.apply(app)

  /** Render an [[Origin.Host]] to `"scheme://host"` or `"scheme://host:port"`
   *  and compare it to the configured origin string.
   */
  private def matchesOrigin(origin: Origin, allowedOrigin: String): Boolean =
    origin.value == allowedOrigin
