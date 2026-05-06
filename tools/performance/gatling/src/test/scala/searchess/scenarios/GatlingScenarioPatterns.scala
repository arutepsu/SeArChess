package searchess.scenarios

import io.gatling.core.Predef._
import io.gatling.core.structure.ScenarioBuilder
import searchess.chains.GameplayChains
import searchess.config.GatlingConfig

object GatlingScenarioPatterns {

  def choose(pattern: String): ScenarioBuilder =
    pattern match {
      case "all" =>
        scenario("SearchessAll")
          .exec(GameplayChains.createSession)
          .exec(GameplayChains.completeGameplayFlow)
      case "gameplay" =>
        scenario("SearchessGameplay")
          .exec(GameplayChains.createSession)
          .exec(GameplayChains.completeGameplayFlow)
      case "session" =>
        scenario("SearchessSessionCreation")
          .exec(GameplayChains.sessionCreationFlow)
      case "legalMoves" =>
        scenario("SearchessLegalMoves")
          .exec(GameplayChains.legalMovesFlow)
      case "moveSubmission" =>
        scenario("SearchessMoveSubmission")
          .exec(GameplayChains.moveSubmissionFlow)
      case "readHeavy" =>
        scenario("SearchessReadHeavy")
          .exec(GameplayChains.readHeavyFlow)
      case "writeHeavy" =>
        scenario("SearchessWriteHeavy")
          .exec(GameplayChains.writeHeavyFlow)
      case unknown =>
        throw new IllegalArgumentException(
          s"Unknown Gatling pattern: $unknown. Supported: ${GatlingConfig.supportedScenarioPatterns.mkString(", ")}."
        )
    }
}
