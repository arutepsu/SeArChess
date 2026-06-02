package searchess.chains

import io.gatling.core.Predef._
import io.gatling.core.structure.ChainBuilder
import searchess.config.GatlingConfig
import searchess.feeders.SearchessFeeders
import searchess.requests.SearchessRequests

object GameplayChains {

  private def selectMoveBody(plyIndex: Int): ChainBuilder = exec { session =>
    val froms = session("moveFroms").asOption[Seq[String]].getOrElse(Seq.empty)
    val tos = session("moveTos").asOption[Seq[String]].getOrElse(Seq.empty)
    val pairs = froms.zip(tos).sortBy { case (f, t) => s"$f-$t" }

    if (pairs.isEmpty) {
      session.set("hasLegalMove", false)
    } else {
      val (from, to) = pairs(plyIndex % pairs.size)
      session
        .set("hasLegalMove", true)
        .set("moveBody", s"""{"from":"$from","to":"$to","controller":"HumanLocal"}""")
    }
  }

  val createSession: ChainBuilder =
    feed(SearchessFeeders.sessionModeFeeder)
      .group("Create session") {
        exec(SearchessRequests.createSession)
      }
      .pause(GatlingConfig.thinkTime)

  def fetchLegalMoves(plyIndex: Int): ChainBuilder =
    group("Fetch legal moves") {
      exec(SearchessRequests.fetchLegalMoves(plyIndex))
        .exec(selectMoveBody(plyIndex))
    }

  def submitMove(plyIndex: Int): ChainBuilder =
    doIf(session => session("hasLegalMove").asOption[Boolean].contains(true)) {
      group("Submit move") {
        exec(SearchessRequests.submitMove(plyIndex))
      }
    }

  def fetchUpdatedState(plyIndex: Int): ChainBuilder =
    group("Fetch updated state") {
      exec(SearchessRequests.fetchUpdatedState(plyIndex))
    }

  def gameplayTurn(plyIndex: Int): ChainBuilder =
    group("Gameplay turn") {
      exec(fetchLegalMoves(plyIndex))
        .pause(GatlingConfig.thinkTime)
        .exec(submitMove(plyIndex))
        .pause(GatlingConfig.thinkTime)
        .exec(fetchUpdatedState(plyIndex))
        .pause(GatlingConfig.thinkTime)
    }

  val completeGameplayFlow: ChainBuilder =
    (0 until GatlingConfig.gameplayPlyCount).foldLeft(exec(session => session)) { (chain, plyIndex) =>
      chain.exec(gameplayTurn(plyIndex))
    }

  val sessionCreationFlow: ChainBuilder =
    exec(createSession)

  val legalMovesFlow: ChainBuilder =
    exec(createSession)
      .exec((0 until GatlingConfig.gameplayPlyCount).foldLeft(exec(session => session)) { (chain, plyIndex) =>
        chain.exec(fetchLegalMoves(plyIndex)).pause(GatlingConfig.thinkTime)
      })

  val moveSubmissionFlow: ChainBuilder =
    exec(createSession)
      .exec((0 until GatlingConfig.gameplayPlyCount).foldLeft(exec(session => session)) { (chain, plyIndex) =>
        chain.exec(fetchLegalMoves(plyIndex))
          .pause(GatlingConfig.thinkTime)
          .exec(submitMove(plyIndex))
          .pause(GatlingConfig.thinkTime)
      })

  val readHeavyFlow: ChainBuilder =
    exec(createSession)
      .exec((0 until GatlingConfig.gameplayPlyCount).foldLeft(exec(session => session)) { (chain, plyIndex) =>
        chain.exec(fetchLegalMoves(plyIndex))
          .pause(GatlingConfig.thinkTime)
          .exec(fetchUpdatedState(plyIndex))
          .pause(GatlingConfig.thinkTime)
      })

  val writeHeavyFlow: ChainBuilder =
    exec(createSession)
      .exec((0 until GatlingConfig.gameplayPlyCount).foldLeft(exec(session => session)) { (chain, plyIndex) =>
        chain.exec(fetchLegalMoves(plyIndex))
          .exec(submitMove(plyIndex))
          .pause(GatlingConfig.thinkTime)
      })
}
