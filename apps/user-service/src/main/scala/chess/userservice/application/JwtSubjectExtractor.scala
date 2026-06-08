package chess.userservice.application

import scala.util.control.NonFatal

/** Decodes the payload of a Keycloak-issued JWT forwarded by Envoy.
  *
  * Envoy's jwt_authn filter validates the JWT signature, issuer, audience, and expiry before
  * forwarding the request upstream. This extractor only base64-decodes the already-validated
  * payload — it does NOT re-verify the signature.
  *
  * Phase 2 note: if any internal service-to-service path ever bypasses Envoy (e.g. direct pod
  * calls during local dev), add JWKS signature verification here before enabling that path.
  */
object JwtSubjectExtractor:

  final case class JwtClaims(
      sub: String,
      preferredUsername: String,
      email: Option[String]
  )

  def fromBearerHeader(header: String): Either[String, JwtClaims] =
    if !header.startsWith("Bearer ") then
      Left("Authorization header must start with 'Bearer '")
    else
      fromToken(header.stripPrefix("Bearer ").trim)

  def fromToken(token: String): Either[String, JwtClaims] =
    token.split('.').toList match
      case _ :: payload :: _ :: Nil => decodePayload(payload)
      case _                        => Left("JWT format invalid: expected exactly 3 dot-separated segments")

  private def decodePayload(payload: String): Either[String, JwtClaims] =
    try
      val bytes   = java.util.Base64.getUrlDecoder.decode(pad(payload))
      val decoded = new String(bytes, "UTF-8")
      val json    = ujson.read(decoded)
      json.obj.get("sub").flatMap(_.strOpt) match
        case None      => Left("JWT payload missing required 'sub' claim")
        case Some(sub) =>
          Right(
            JwtClaims(
              sub               = sub,
              preferredUsername = json.obj.get("preferred_username").flatMap(_.strOpt).getOrElse(sub),
              email             = json.obj.get("email").flatMap(_.strOpt)
            )
          )
    catch
      case NonFatal(e) =>
        Left(s"JWT payload decoding failed: ${Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)}")

  private def pad(s: String): String =
    val rem = s.length % 4
    if rem == 0 then s else s + "=" * (4 - rem)
