package chess.userservice.application

final case class LichessChallengeBotConfig(
    botUsername: String,
    challengeApiBaseUrl: String
):
  def challengeEndpointFor(targetUsername: String): String =
    s"$challengeApiBaseUrl/$targetUsername"
