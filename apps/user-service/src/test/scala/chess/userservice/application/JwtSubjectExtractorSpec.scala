package chess.userservice.application

import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.util.Base64

class JwtSubjectExtractorSpec extends AnyFlatSpec with Matchers with EitherValues:

  private def makeToken(payload: String): String =
    val encodedPayload = Base64.getUrlEncoder.withoutPadding.encodeToString(payload.getBytes("UTF-8"))
    s"header.$encodedPayload.signature"

  "fromBearerHeader" should "extract sub, preferredUsername, and email from a valid token" in {
    val payload = """{"sub":"abc-123","preferred_username":"alice","email":"alice@example.com"}"""
    val claims = JwtSubjectExtractor.fromBearerHeader(s"Bearer ${makeToken(payload)}").value
    claims.sub               shouldBe "abc-123"
    claims.preferredUsername shouldBe "alice"
    claims.email             shouldBe Some("alice@example.com")
  }

  it should "fall back to sub when preferred_username is absent" in {
    val payload = """{"sub":"abc-123"}"""
    val claims = JwtSubjectExtractor.fromBearerHeader(s"Bearer ${makeToken(payload)}").value
    claims.preferredUsername shouldBe "abc-123"
    claims.email             shouldBe None
  }

  it should "return Left when the header does not start with 'Bearer '" in {
    JwtSubjectExtractor.fromBearerHeader("Basic dXNlcjpwYXNz").isLeft shouldBe true
  }

  it should "return Left when the token has fewer than 3 segments" in {
    JwtSubjectExtractor.fromBearerHeader("Bearer header.payload").isLeft shouldBe true
  }

  it should "return Left when the payload is not valid JSON" in {
    val badToken = "header.bm90anNvbg.signature"
    JwtSubjectExtractor.fromBearerHeader(s"Bearer $badToken").isLeft shouldBe true
  }

  it should "return Left when 'sub' claim is missing" in {
    val payload = """{"preferred_username":"alice"}"""
    JwtSubjectExtractor.fromBearerHeader(s"Bearer ${makeToken(payload)}").isLeft shouldBe true
  }

  it should "handle base64 padding variants correctly" in {
    // Payload length that requires padding
    val payload = """{"sub":"x","preferred_username":"x"}"""
    JwtSubjectExtractor.fromBearerHeader(s"Bearer ${makeToken(payload)}").isRight shouldBe true
  }
