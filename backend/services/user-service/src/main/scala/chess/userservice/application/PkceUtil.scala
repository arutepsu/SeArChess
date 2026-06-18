package chess.userservice.application

import java.security.{MessageDigest, SecureRandom}
import java.util.Base64

object PkceUtil:

  private val urlEncoder = Base64.getUrlEncoder.withoutPadding

  /** 32-byte random value, base64url-encoded without padding (~43 chars). */
  def generateVerifier(): String =
    val bytes = new Array[Byte](32)
    SecureRandom().nextBytes(bytes)
    urlEncoder.encodeToString(bytes)

  /** SHA-256 of the verifier bytes, base64url-encoded without padding (S256 method). */
  def computeChallenge(verifier: String): String =
    val hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes("UTF-8"))
    urlEncoder.encodeToString(hash)

  /** 16-byte random opaque state value, base64url-encoded without padding. */
  def generateState(): String =
    val bytes = new Array[Byte](16)
    SecureRandom().nextBytes(bytes)
    urlEncoder.encodeToString(bytes)
