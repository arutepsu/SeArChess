package chess.userservice.application

import cats.effect.IO
import chess.observability.StructuredLog
import chess.userservice.domain.{ExternalAccountLink, OAuthLinkState}
import fs2.Stream
import org.http4s.*
import org.http4s.client.Client
import org.http4s.headers.{Authorization, `Content-Type`}

import java.net.URLEncoder
import java.time.Instant
import java.util.UUID

class LichessOAuthService(
    stateRepo: OAuthLinkStateRepository,
    linkRepo: ExternalAccountLinkRepository,
    client: Client[IO],
    config: LichessOAuthConfig
):

  /** Generates PKCE credentials, persists the state row, and returns the Lichess authorization URL. */
  def createLinkStart(userId: UUID): Either[String, String] =
    if !config.isConfigured then
      Left("Lichess OAuth is not configured (LICHESS_OAUTH_CLIENT_ID or LICHESS_OAUTH_REDIRECT_URI is empty)")
    else
      val verifier  = PkceUtil.generateVerifier()
      val challenge = PkceUtil.computeChallenge(verifier)
      val state     = PkceUtil.generateState()
      val linkState = OAuthLinkState(
        state                = state,
        userId               = userId,
        codeVerifier         = verifier,
        redirectAfterSuccess = config.webUiSettingsUrl + "?lichess=linked",
        expiresAt            = Instant.now().plusSeconds(config.stateTtlSeconds),
        createdAt            = Instant.now()
      )
      stateRepo.insert(linkState).map { _ =>
        config.authorizeUrl +
          "?response_type=code" +
          s"&client_id=${encode(config.clientId)}" +
          s"&redirect_uri=${encode(config.redirectUri)}" +
          s"&scope=${encode("preference:read")}" +
          s"&state=${encode(state)}" +
          s"&code_challenge=${encode(challenge)}" +
          "&code_challenge_method=S256"
      }

  /** Validates state, exchanges the authorization code, fetches the Lichess account, and upserts the verified link.
    * The access token is used only to call /api/account and then discarded — never persisted.
    */
  def exchangeCallback(code: String, state: String): IO[Either[String, ExternalAccountLink]] =
    IO(stateRepo.findAndDelete(state)).flatMap {
      case Left(err)       => IO.pure(Left(err))
      case Right(None)     => IO.pure(Left("Unknown or already-used OAuth state"))
      case Right(Some(ls)) =>
        if Instant.now().isAfter(ls.expiresAt) then
          IO.pure(Left("OAuth state has expired"))
        else
          exchangeCodeAndLink(code, ls)
    }

  private def exchangeCodeAndLink(code: String, ls: OAuthLinkState): IO[Either[String, ExternalAccountLink]] =
    fetchToken(code, ls.codeVerifier).flatMap {
      case Left(err)    => IO.pure(Left(err))
      case Right(token) =>
        fetchLichessId(token).flatMap {
          case Left(err)  => IO.pure(Left(err))
          case Right(lid) => IO(upsertVerifiedLink(ls.userId, lid))
        }
    }

  private def fetchToken(code: String, codeVerifier: String): IO[Either[String, String]] =
    val body = formEncode(
      "grant_type"    -> "authorization_code",
      "code"          -> code,
      "code_verifier" -> codeVerifier,
      "redirect_uri"  -> config.redirectUri,
      "client_id"     -> config.clientId
    )
    val request = Request[IO](
      method  = Method.POST,
      uri     = Uri.unsafeFromString(config.tokenUrl),
      headers = Headers(`Content-Type`(MediaType.application.`x-www-form-urlencoded`)),
      body    = Stream.emits(body.getBytes("UTF-8")).covary[IO]
    )
    client.run(request).use { resp =>
      resp.bodyText.compile.string.map { raw =>
        if resp.status.isSuccess then
          try
            val json = ujson.read(raw)
            json.obj.get("access_token").flatMap(_.strOpt) match
              case Some(t) => Right(t)
              case None    => Left("No access_token in Lichess token response")
          catch case e: Exception => Left(s"Failed to parse token response: ${e.getMessage}")
        else
          Left(s"Lichess token exchange failed: HTTP ${resp.status.code}")
      }
    }.handleErrorWith(e => IO.pure(Left(s"Token exchange error: ${e.getMessage}")))

  private def fetchLichessId(accessToken: String): IO[Either[String, String]] =
    val request = Request[IO](
      method  = Method.GET,
      uri     = Uri.unsafeFromString(config.accountUrl),
      headers = Headers(Authorization(Credentials.Token(AuthScheme.Bearer, accessToken)))
    )
    client.run(request).use { resp =>
      resp.bodyText.compile.string.map { raw =>
        if resp.status.isSuccess then
          try
            val json = ujson.read(raw)
            json.obj.get("id").flatMap(_.strOpt)
              .orElse(json.obj.get("username").flatMap(_.strOpt)) match
              case Some(id) => Right(id)
              case None     => Left("No id/username in Lichess account response")
          catch case e: Exception => Left(s"Failed to parse account response: ${e.getMessage}")
        else
          Left(s"Lichess account fetch failed: HTTP ${resp.status.code}")
      }
    }.handleErrorWith(e => IO.pure(Left(s"Account fetch error: ${e.getMessage}")))

  private def upsertVerifiedLink(userId: UUID, lichessId: String): Either[String, ExternalAccountLink] =
    linkRepo.findByUserAndProvider(userId, "Lichess").flatMap {
      case Some(existing) =>
        val updated = existing.copy(
          externalId         = Some(lichessId),
          externalUsername   = lichessId,
          verified           = true,
          verificationSource = "OAuthPKCE",
          linkedAt           = Instant.now(),
          capability         = "identity_only",
          tokenEncrypted     = None,
          tokenScopes        = None,
          tokenStoredAt      = None
        )
        linkRepo.update(updated).map(_ => updated)
      case None =>
        val link = ExternalAccountLink(
          linkId             = UUID.randomUUID(),
          userId             = userId,
          provider           = "Lichess",
          externalId         = Some(lichessId),
          externalUsername   = lichessId,
          verified           = true,
          verificationSource = "OAuthPKCE",
          linkedAt           = Instant.now(),
          capability         = "identity_only",
          tokenEncrypted     = None,
          tokenScopes        = None,
          tokenStoredAt      = None
        )
        linkRepo.insert(link).map(_ => link)
    }

  private def formEncode(pairs: (String, String)*): String =
    pairs.map { case (k, v) => s"${encode(k)}=${encode(v)}" }.mkString("&")

  private def encode(s: String): String =
    URLEncoder.encode(s, "UTF-8")
