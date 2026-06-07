package chess.adapter.http4s.route

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import java.util.UUID
import scala.util.control.NonFatal

final case class AuthenticatedSearchessUser(
    userId: UUID,
    nickname: Option[String],
    onboardingRequired: Boolean,
    links: List[AuthenticatedExternalAccountLink] = Nil
)

final case class AuthenticatedExternalAccountLink(
    provider: String,
    externalId: Option[String],
    externalUsername: String,
    verified: Boolean,
    verificationSource: String
)

trait AuthenticatedUserClient:
  def getCurrentUser(authHeader: String): Either[AuthenticatedUserClientError, AuthenticatedSearchessUser]

enum AuthenticatedUserClientError:
  case Unauthorized(message: String)
  case UpstreamFailure(message: String)
  case InvalidResponse(message: String)

final class HttpAuthenticatedUserClient(
    baseUrl: String,
    timeoutMillis: Int = 2000,
    client: HttpClient = HttpClient.newHttpClient()
) extends AuthenticatedUserClient:

  override def getCurrentUser(
      authHeader: String
  ): Either[AuthenticatedUserClientError, AuthenticatedSearchessUser] =
    try
      val request = HttpRequest
        .newBuilder(URI.create(s"${baseUrl.stripSuffix("/")}/users/me"))
        .timeout(Duration.ofMillis(timeoutMillis.toLong))
        .header("Authorization", authHeader)
        .GET()
        .build()
      val response = client.send(request, HttpResponse.BodyHandlers.ofString())
      response.statusCode() match
        case 200 => parseUser(response.body())
        case 401 | 403 =>
          Left(AuthenticatedUserClientError.Unauthorized("Authorization was rejected by user-service"))
        case status =>
          Left(AuthenticatedUserClientError.UpstreamFailure(s"user-service returned HTTP $status"))
    catch case NonFatal(e) =>
      Left(AuthenticatedUserClientError.UpstreamFailure(Option(e.getMessage).getOrElse(e.getClass.getSimpleName)))

  private def parseUser(body: String): Either[AuthenticatedUserClientError, AuthenticatedSearchessUser] =
    try
      val json = ujson.read(body)
      Right(
        AuthenticatedSearchessUser(
          userId = UUID.fromString(json("userId").str),
          nickname = json.obj.get("nickname").flatMap {
            case ujson.Null => None
            case value      => Some(value.str)
          },
          onboardingRequired = json("onboardingRequired").bool,
          links = json.obj
            .get("links")
            .map(_.arr.toList.flatMap(parseLink))
            .getOrElse(Nil)
        )
      )
    catch case NonFatal(e) =>
      Left(AuthenticatedUserClientError.InvalidResponse(s"Invalid user-service response: ${e.getMessage}"))

  private def parseLink(json: ujson.Value): Option[AuthenticatedExternalAccountLink] =
    try
      Some(
        AuthenticatedExternalAccountLink(
          provider = json("provider").str,
          externalId = json.obj.get("externalId").flatMap {
            case ujson.Null => None
            case value      => Some(value.str)
          },
          externalUsername = json("externalUsername").str,
          verified = json("verified").bool,
          verificationSource = json("verificationSource").str
        )
      )
    catch case NonFatal(_) => None
