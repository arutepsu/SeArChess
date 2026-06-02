package searchess.feeders

import io.gatling.core.Predef._

object SearchessFeeders {

  // Every virtual user creates an isolated API session, while the mode remains
  // feeder-backed so additional session modes can be added without changing chains.
  val sessionModeFeeder = csv("searchess/session_modes.csv").circular
}
