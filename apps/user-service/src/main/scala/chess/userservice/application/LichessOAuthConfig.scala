package chess.userservice.application

final case class LichessOAuthConfig(
    clientId: String,
    authorizeUrl: String,
    tokenUrl: String,
    accountUrl: String,
    redirectUri: String,
    stateTtlSeconds: Long,
    webUiSettingsUrl: String
):
  def isConfigured: Boolean = clientId.nonEmpty && redirectUri.nonEmpty
