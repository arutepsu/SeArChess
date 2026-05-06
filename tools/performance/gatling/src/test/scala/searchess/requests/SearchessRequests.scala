package searchess.requests

import io.gatling.core.Predef._
import io.gatling.http.Predef._

object SearchessRequests {

  def createSession =
    http("create session")
      .post("/sessions")
      .body(StringBody("""{"mode":"#{sessionMode}"}"""))
      .check(
        status.is(201),
        jsonPath("$.session.sessionId").ofType[String].transform(_.trim).not("").saveAs("sessionId"),
        jsonPath("$.session.gameId").ofType[String].transform(_.trim).not(""),
        jsonPath("$.session.mode").is("#{sessionMode}"),
        jsonPath("$.game.gameId").ofType[String].transform(_.trim).not("").saveAs("gameId"),
        jsonPath("$.game.status").is("Ongoing")
      )

  def fetchLegalMoves(plyIndex: Int) =
    http(s"legal moves ply $plyIndex")
      .get("/games/#{gameId}/legal-moves")
      .check(
        status.is(200),
        jsonPath("$.gameId").is("#{gameId}"),
        jsonPath("$.currentPlayer").exists,
        jsonPath("$.moves[0].from").exists,
        jsonPath("$.moves[0].to").exists,
        jsonPath("$.moves[*].from").findAll.saveAs("moveFroms"),
        jsonPath("$.moves[*].to").findAll.saveAs("moveTos")
      )

  def submitMove(plyIndex: Int) =
    http(s"submit move ply $plyIndex")
      .post("/games/#{gameId}/moves")
      .body(StringBody("#{moveBody}"))
      .check(
        status.is(200),
        jsonPath("$.game.gameId").is("#{gameId}"),
        jsonPath("$.game.status").exists,
        jsonPath("$.game.moveHistory[0].from").exists,
        jsonPath("$.game.moveHistory[0].to").exists,
        jsonPath("$.sessionLifecycle").exists
      )

  def fetchUpdatedState(plyIndex: Int) =
    http(s"fetch state ply $plyIndex")
      .get("/sessions/#{sessionId}/state")
      .check(
        status.is(200),
        jsonPath("$.session.sessionId").is("#{sessionId}"),
        jsonPath("$.session.gameId").is("#{gameId}"),
        jsonPath("$.game.gameId").is("#{gameId}"),
        jsonPath("$.game.status").exists,
        jsonPath("$.game.currentPlayer").exists
      )
}
