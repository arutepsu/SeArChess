package chess.server.http

/** Names the Game Service HTTP surfaces by intended audience. */
object GameServiceHttpSurface:
  val PublicHealthPath: String = "/health"

  /** Public edge-facing gameplay API mounted by [[chess.adapter.http4s.Http4sApp]]. */
  val PublicGameplayApi: String = "public-gameplay-api"

  /** Internal operational routes for local/dev inspection. Not routed by Envoy. */
  val InternalOpsApi: String = "internal-ops-api"
