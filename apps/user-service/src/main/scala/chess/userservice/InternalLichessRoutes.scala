package chess.userservice

import cats.effect.IO
import chess.userservice.application.ExternalAccountLinkRepository
import fs2.Stream
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString

/** Internal HTTP routes exposed only on the cluster-internal network.
  *
  * Path convention:
  *   GET /internal/lichess/challenge-auth/{lichessUsername}
  *       — Returns whether the given Lichess username is linked to a Searchess user.
  *         Protected by X-Internal-Api-Key header (service-to-service secret).
  *         Never exposed through Envoy or public ingress.
  *
  * Authorization:
  *   Callers must send: X-Internal-Api-Key: <USER_SERVICE_INTERNAL_API_KEY>
  *   If the server-side key is empty (not configured), all requests are denied.
  */
class InternalLichessRoutes(
    linkRepo: ExternalAccountLinkRepository,
    apiKey: String
):

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case req @ GET -> Root / "internal" / "lichess" / "challenge-auth" / lichessUsername =>
      verifyApiKey(req) match
        case Left(err) =>
          respond(Status.Unauthorized, ujson.Obj("error" -> err))
        case Right(_) =>
          linkRepo.findByLichessUsername(lichessUsername) match
            case Left(err) =>
              respond(Status.InternalServerError, ujson.Obj("error" -> err))
            case Right(None) =>
              respond(Status.Ok, ujson.Obj(
                "allowed"         -> ujson.Bool(false),
                "lichessUsername" -> lichessUsername,
                "reason"          -> "not_linked"
              ))
            case Right(Some(link)) =>
              respond(Status.Ok, ujson.Obj(
                "allowed"            -> ujson.Bool(true),
                "lichessUsername"    -> link.externalUsername,
                "searchessUserId"    -> link.userId.toString,
                "reason"             -> "linked_user",
                "verificationSource" -> link.verificationSource
              ))
  }

  private def verifyApiKey(req: Request[IO]): Either[String, Unit] =
    if apiKey.isEmpty then Left("Internal API key is not configured on this server")
    else
      req.headers.get(CIString("X-Internal-Api-Key")).map(_.head.value.trim) match
        case None                   => Left("X-Internal-Api-Key header is required")
        case Some(k) if k == apiKey => Right(())
        case _                      => Left("X-Internal-Api-Key is invalid")

  private def respond(status: Status, body: ujson.Value): IO[Response[IO]] =
    IO.pure(
      Response[IO](
        status  = status,
        headers = Headers(`Content-Type`(MediaType.application.json)),
        body    = Stream.emits(ujson.write(body).getBytes("UTF-8")).covary[IO]
      )
    )
