package chess.lichessbridge

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class UserServiceClientSpec extends AnyFlatSpec with Matchers:

  "JdkUserServiceClient.parseAuthResponse" should "return Authorized when allowed=true" in {
    val body = """{"allowed":true,"lichessUsername":"chessplayer","reason":"linked_user","searchessUserId":"abc123"}"""
    JdkUserServiceClient.parseAuthResponse(body) shouldBe ChallengeAuthResult.Authorized
  }

  it should "return NotLinked when allowed=false" in {
    val body = """{"allowed":false,"lichessUsername":"stranger","reason":"not_linked"}"""
    JdkUserServiceClient.parseAuthResponse(body) shouldBe ChallengeAuthResult.NotLinked
  }

  it should "return Unavailable on malformed JSON" in {
    JdkUserServiceClient.parseAuthResponse("{broken") shouldBe ChallengeAuthResult.Unavailable
  }

  it should "return Unavailable when 'allowed' field is missing" in {
    JdkUserServiceClient.parseAuthResponse("""{"reason":"not_linked"}""") shouldBe ChallengeAuthResult.Unavailable
  }

  it should "return Unavailable on empty body" in {
    JdkUserServiceClient.parseAuthResponse("") shouldBe ChallengeAuthResult.Unavailable
  }

  it should "return Authorized when allowed=true regardless of other fields" in {
    val body = """{"allowed":true}"""
    JdkUserServiceClient.parseAuthResponse(body) shouldBe ChallengeAuthResult.Authorized
  }
