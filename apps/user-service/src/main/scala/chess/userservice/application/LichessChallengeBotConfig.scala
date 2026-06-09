package chess.userservice.application

import java.net.URLEncoder

final case class LichessChallengeBotConfig(
    botUsername: String,
    challengeApiBaseUrl: String
):
  def challengeEndpointFor(targetUsername: String): String =
    s"${challengeApiBaseUrl.stripSuffix("/")}/${encodePathSegment(targetUsername)}"

  private def encodePathSegment(value: String): String =
    URLEncoder.encode(value, "UTF-8").replace("+", "%20")
