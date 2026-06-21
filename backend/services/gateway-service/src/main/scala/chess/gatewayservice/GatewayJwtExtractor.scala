package chess.gatewayservice

import scala.util.control.NonFatal

/** Decodes the payload of a Keycloak-issued JWT forwarded by Envoy.
  *
  * Envoy's jwt_authn filter validates the JWT signature, issuer, audience, and expiry before
  * forwarding the request upstream. This extractor only base64-decodes the already-validated
  * payload — it does NOT re-verify the signature.
  *
  * This mirrors the contract of JwtSubjectExtractor in user-service. Gateway-service keeps its
  * own copy to avoid a cross-service compile dependency.
  *
  * Note: if any path ever bypasses Envoy (e.g. direct pod calls during local dev with
  * authDisabled=false), add JWKS signature verification here before enabling that path.
  */
object GatewayJwtExtractor:

  final case class GatewayJwtClaims(
      sub: String,
      preferredUsername: String,
      email: Option[String]
  )

  def fromBearerHeader(header: String): Either[String, GatewayJwtClaims] =
    if !header.startsWith("Bearer ") then
      Left("Authorization header must start with 'Bearer '")
    else
      fromToken(header.stripPrefix("Bearer ").trim)

  def fromToken(token: String): Either[String, GatewayJwtClaims] =
    token.split('.').toList match
      case _ :: payload :: _ :: Nil => decodePayload(payload)
      case _                        => Left("JWT format invalid: expected exactly 3 dot-separated segments")

  private def decodePayload(payload: String): Either[String, GatewayJwtClaims] =
    try
      val bytes   = java.util.Base64.getUrlDecoder.decode(pad(payload))
      val decoded = new String(bytes, "UTF-8")
      val json    = ujson.read(decoded)
      json.obj.get("sub").flatMap(_.strOpt) match
        case None =>
          Left("JWT payload missing required 'sub' claim")
        case Some(sub) =>
          Right(
            GatewayJwtClaims(
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
