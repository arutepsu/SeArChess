package chess.userservice.application

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.Base64

class PkceUtilSpec extends AnyFlatSpec with Matchers:

  "generateVerifier" should "produce a string of at least 40 characters (32 bytes base64url)" in {
    PkceUtil.generateVerifier().length should be >= 40
  }

  it should "produce unique values on each call" in {
    PkceUtil.generateVerifier() should not equal PkceUtil.generateVerifier()
  }

  it should "contain only URL-safe base64 characters without padding" in {
    val v = PkceUtil.generateVerifier()
    v should not contain "="
    v should not contain "+"
    v should not contain "/"
  }

  "computeChallenge" should "match the RFC 7636 appendix B test vector" in {
    // https://www.rfc-editor.org/rfc/rfc7636#appendix-B
    val verifier  = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
    val expected  = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
    PkceUtil.computeChallenge(verifier) shouldBe expected
  }

  it should "produce a URL-safe base64 string without padding" in {
    val c = PkceUtil.computeChallenge(PkceUtil.generateVerifier())
    c should not contain "="
    c should not contain "+"
    c should not contain "/"
  }

  it should "produce a 43-character string (256-bit SHA-256 as base64url)" in {
    PkceUtil.computeChallenge(PkceUtil.generateVerifier()).length shouldBe 43
  }

  "generateState" should "produce unique values on each call" in {
    PkceUtil.generateState() should not equal PkceUtil.generateState()
  }

  it should "contain only URL-safe base64 characters without padding" in {
    val s = PkceUtil.generateState()
    s should not contain "="
    s should not contain "+"
    s should not contain "/"
  }
