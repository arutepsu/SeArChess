package chess.analytics

final case class PostgresConfig(
  url: String,
  user: String,
  password: String,
  schema: String,
  writeMode: String    = "overwrite",
  strictWrite: Boolean = false
)

object PostgresConfig {

  def fromEnv(): Option[PostgresConfig] = fromEnvMap(sys.env)

  private[analytics] def fromEnvMap(env: Map[String, String]): Option[PostgresConfig] = {
    val enabled = env.getOrElse("POSTGRES_WRITE_ENABLED", "false").toLowerCase == "true"
    if (!enabled) {
      println("[INFO] PostgreSQL write disabled (POSTGRES_WRITE_ENABLED != 'true'). Skipping DB output.")
      None
    } else {
      val url      = env.get("POSTGRES_URL")
      val user     = env.get("POSTGRES_USER")
      val password = env.get("POSTGRES_PASSWORD")
      val schema      = env.getOrElse("POSTGRES_SCHEMA",       "public")
      val writeMode   = env.getOrElse("POSTGRES_WRITE_MODE",   "overwrite")
      val strictWrite = env.getOrElse("POSTGRES_STRICT_WRITE", "false").toLowerCase == "true"

      (url, user, password) match {
        case (Some(u), Some(usr), Some(pwd)) =>
          Some(PostgresConfig(
            url         = u,
            user        = usr,
            password    = pwd,
            schema      = schema,
            writeMode   = writeMode,
            strictWrite = strictWrite
          ))
        case _ =>
          val missing = Seq(
            if (url.isEmpty) Some("POSTGRES_URL") else None,
            if (user.isEmpty) Some("POSTGRES_USER") else None,
            if (password.isEmpty) Some("POSTGRES_PASSWORD") else None
          ).flatten
          println(s"[WARN] POSTGRES_WRITE_ENABLED=true but env vars missing: ${missing.mkString(", ")}. Skipping DB output.")
          None
      }
    }
  }
}
