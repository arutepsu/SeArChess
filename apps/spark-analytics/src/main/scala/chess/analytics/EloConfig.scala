package chess.analytics

final case class EloConfig(
  initialRating: Double = 1000.0,
  kFactor:       Double = 32.0
)

object EloConfig {

  def fromEnv(): EloConfig = fromEnvMap(sys.env)

  private[analytics] def fromEnvMap(env: Map[String, String]): EloConfig = {
    val config = EloConfig(
      initialRating = parsePositiveDouble(env.getOrElse("ELO_INITIAL_RATING", "1000.0"), "ELO_INITIAL_RATING"),
      kFactor       = parsePositiveDouble(env.getOrElse("ELO_K_FACTOR", "32.0"), "ELO_K_FACTOR")
    )
    config
  }

  private def parsePositiveDouble(value: String, name: String): Double =
    try {
      val parsed = value.toDouble
      require(parsed > 0.0, s"$name must be positive")
      parsed
    } catch {
      case _: NumberFormatException =>
        throw new IllegalArgumentException(s"$name must be a positive number")
    }
}
