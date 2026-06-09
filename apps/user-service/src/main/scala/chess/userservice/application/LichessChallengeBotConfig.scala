package chess.userservice.application

import java.net.URLEncoder

final case class LichessChallengeBotConfig(
    botUsername: String,
    challengeApiBaseUrl: String
):
  def challengeEndpointFor(targetUsername: String): String =
    s"${challengeApiBaseUrl.stripSuffix("/")}/${encodePathSegment(targetUsername)}"

  def gameExportEndpointFor(gameId: String): String =
    val origin = challengeApiBaseUrl.stripSuffix("/api/challenge").stripSuffix("/")
    s"$origin/game/export/${encodePathSegment(gameId)}?moves=true&players=true&clocks=false&evals=false&opening=false"

  private def encodePathSegment(value: String): String =
    URLEncoder.encode(value, "UTF-8").replace("+", "%20")
