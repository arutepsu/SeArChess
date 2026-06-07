package chess.userservice

import cats.effect.IO
import chess.userservice.application.{JwtSubjectExtractor, UserProfileService}
import chess.userservice.domain.ExternalAccountLink
import fs2.Stream
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString

/** HTTP routes for user-service.
  *
  * Path convention (after Envoy prefix rewrite /api/users → /users):
  *   GET  /health                          — liveness (k8s probe, no JWT required)
  *   GET  /users/me                        — current user profile + links
  *   PUT  /users/me/links/lichess/manual   — set Lichess username manually (ManualDev, Phase 1)
  *   DELETE /users/me/links/lichess        — remove Lichess link
  */
class UserRoutes(service: UserProfileService):

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "health" =>
      respond(Status.Ok, ujson.Obj("status" -> "ok", "service" -> "searchess-user-service"))

    case req @ GET -> Root / "users" / "me" =>
      withProfile(req) { (_, profile, userLinks) =>
        respond(
          Status.Ok,
          ujson.Obj(
            "userId"      -> profile.userId.toString,
            "displayName" -> profile.displayName,
            "email"       -> profile.email.map(ujson.Str(_)).getOrElse(ujson.Null),
            "links"       -> ujson.Arr(userLinks.map(linkJson)*)
          )
        )
      }

    case req @ PUT -> Root / "users" / "me" / "links" / "lichess" / "manual" =>
      withSubject(req) { (_, profile) =>
        req.bodyText.compile.string.flatMap { body =>
          parseLichessUsername(body) match
            case Left(err) =>
              respond(Status.BadRequest, ujson.Obj("code" -> "BAD_REQUEST", "message" -> err))
            case Right(lichessUsername) =>
              service.setManualLichessLink(profile.userId, lichessUsername) match
                case Left(err)   => respond(Status.InternalServerError, ujson.Obj("code" -> "INTERNAL_ERROR", "message" -> err))
                case Right(link) => respond(Status.Ok, linkJson(link))
        }
      }

    case req @ DELETE -> Root / "users" / "me" / "links" / "lichess" =>
      withSubject(req) { (_, profile) =>
        service.deleteLink(profile.userId, "Lichess") match
          case Left(err) => respond(Status.InternalServerError, ujson.Obj("code" -> "INTERNAL_ERROR", "message" -> err))
          case Right(_)  => IO.pure(Response[IO](status = Status.NoContent))
      }
  }

  private def withSubject(req: Request[IO])(
      f: (JwtSubjectExtractor.JwtClaims, chess.userservice.domain.UserProfile) => IO[Response[IO]]
  ): IO[Response[IO]] =
    req.headers.get(CIString("Authorization")).map(_.head.value) match
      case None =>
        respond(Status.Unauthorized, ujson.Obj("code" -> "UNAUTHORIZED", "message" -> "Missing Authorization header"))
      case Some(headerValue) =>
        JwtSubjectExtractor.fromBearerHeader(headerValue) match
          case Left(err) =>
            respond(Status.Unauthorized, ujson.Obj("code" -> "UNAUTHORIZED", "message" -> err))
          case Right(claims) =>
            service.getOrCreateProfile(claims.sub, claims.preferredUsername, claims.email) match
              case Left(err)      => respond(Status.InternalServerError, ujson.Obj("code" -> "INTERNAL_ERROR", "message" -> err))
              case Right(profile) => f(claims, profile)

  private def withProfile(req: Request[IO])(
      f: (JwtSubjectExtractor.JwtClaims, chess.userservice.domain.UserProfile, List[ExternalAccountLink]) => IO[Response[IO]]
  ): IO[Response[IO]] =
    withSubject(req) { (claims, profile) =>
      service.getLinksForUser(profile.userId) match
        case Left(err)    => respond(Status.InternalServerError, ujson.Obj("code" -> "INTERNAL_ERROR", "message" -> err))
        case Right(userLinks) => f(claims, profile, userLinks)
    }

  private def parseLichessUsername(body: String): Either[String, String] =
    try
      val json = ujson.read(body)
      json.obj.get("lichessUsername").flatMap(_.strOpt).map(_.trim).filter(_.nonEmpty) match
        case None    => Left("Missing or empty 'lichessUsername' field")
        case Some(u) => Right(u)
    catch
      case _: Exception => Left("Request body must be valid JSON with a 'lichessUsername' string field")

  private def linkJson(link: ExternalAccountLink): ujson.Value =
    ujson.Obj(
      "linkId"             -> link.linkId.toString,
      "provider"           -> link.provider,
      "externalId"         -> link.externalId.map(ujson.Str(_)).getOrElse(ujson.Null),
      "externalUsername"   -> link.externalUsername,
      "verified"           -> link.verified,
      "verificationSource" -> link.verificationSource,
      "linkedAt"           -> link.linkedAt.toString
    )

  private def respond(status: Status, body: ujson.Value): IO[Response[IO]] =
    IO.pure(
      Response[IO](
        status  = status,
        headers = Headers(`Content-Type`(MediaType.application.json)),
        body    = Stream.emits(ujson.write(body).getBytes("UTF-8")).covary[IO]
      )
    )
