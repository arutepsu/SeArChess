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

  def boardMoveEndpointFor(gameId: String, move: String): String =
    val origin = challengeApiBaseUrl.stripSuffix("/api/challenge").stripSuffix("/")
    s"$origin/api/board/game/${encodePathSegment(gameId)}/move/${encodePathSegment(move)}"

  private def encodePathSegment(value: String): String =
    URLEncoder.encode(value, "UTF-8").replace("+", "%20")
