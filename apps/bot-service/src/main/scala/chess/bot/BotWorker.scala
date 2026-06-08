package chess.bot

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import java.util.UUID
import scala.util.control.NonFatal

/** Polls game-service for pending bot turns, asks AI service for a move, and submits it.
  *
  * All communication is internal: game-service via X-Bot-Api-Key, AI service unauthenticated.
  * Never contacts lichess.org or uses a Lichess token.
  */
class BotWorker(
    gameServiceUrl: String,
    aiServiceUrl: String,
    apiKey: String,
    actorId: String,
    moveTimeoutMillis: Int = 5000,
    client: HttpClient = HttpClient.newHttpClient()
):

  private val turnsEndpoint = s"${gameServiceUrl.stripSuffix("/")}/internal/bot/turns"
  private val aiEndpoint    = s"${aiServiceUrl.stripSuffix("/")}/v1/move-suggestions"

  /** Leases up to one pending turn, resolves a move, and submits it.
    *
    * @return true if a task was found and processed (whether the move succeeded or not)
    */
  def runOnce(): Boolean =
    leasePendingTurn() match
      case None       => false
      case Some(task) =>
        val move = resolveMove(task)
        submitMove(task.turnTaskId, move)
        true

  // ── game-service: lease ───────────────────────────────────────────────────

  private def leasePendingTurn(): Option[BotTurnLease] =
    val uri = URI.create(s"$turnsEndpoint?actorId=${encode(actorId)}&limit=1")
    val req = HttpRequest
      .newBuilder(uri)
      .timeout(Duration.ofMillis(moveTimeoutMillis.toLong))
      .header("X-Bot-Api-Key", apiKey)
      .GET()
      .build()
    try
      val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
      resp.statusCode() match
        case 204 => None
        case 200 => parseLease(resp.body())
        case status =>
          System.err.println(s"[BotWorker] lease returned HTTP $status: ${resp.body().take(200)}")
          None
    catch
      case NonFatal(e) =>
        System.err.println(s"[BotWorker] lease request failed: ${e.getMessage}")
        None

  private def parseLease(body: String): Option[BotTurnLease] =
    scala.util.Try {
      val json        = ujson.read(body)
      val turnTaskId  = json("turnTaskId").str
      val sessionId   = json("sessionId").str
      val gameId      = json("gameId").str
      val sideToMove  = json("sideToMove").str
      val fen         = json.obj.get("fen").flatMap(_.strOpt).getOrElse("")
      val legalMoves  = json("legalMoves").arr.toList.map { m =>
        LegalMove(
          from      = m("from").str,
          to        = m("to").str,
          promotion = m.obj.get("promotion").flatMap(_.strOpt)
        )
      }
      BotTurnLease(turnTaskId, sessionId, gameId, sideToMove, fen, legalMoves)
    }.toOption match
      case Some(lease) => Some(lease)
      case None =>
        System.err.println(s"[BotWorker] failed to parse lease response: ${body.take(200)}")
        None

  // ── AI service: suggest ───────────────────────────────────────────────────

  private def resolveMove(task: BotTurnLease): LegalMove =
    if task.fen.isEmpty || task.legalMoves.isEmpty then
      task.legalMoves.headOption.getOrElse(
        LegalMove("e1", "e1", None) // should never happen
      )
    else
      askAi(task).getOrElse {
        val fallback = task.legalMoves.head
        println(
          s"[BotWorker] AI unavailable, falling back to first legal move ${fallback.from}${fallback.to}" +
            s" (task=${task.turnTaskId} game=${task.gameId})"
        )
        fallback
      }

  private def askAi(task: BotTurnLease): Option[LegalMove] =
    val requestId = UUID.randomUUID().toString
    val movesJson = ujson.Arr.from(task.legalMoves.map { m =>
      val obj = ujson.Obj("from" -> m.from, "to" -> m.to)
      m.promotion.foreach(p => obj("promotion") = p)
      obj
    })
    val body = ujson.write(ujson.Obj(
      "requestId" -> requestId,
      "gameId"    -> task.gameId,
      "sessionId" -> task.sessionId,
      "sideToMove" -> task.sideToMove,
      "fen"       -> task.fen,
      "legalMoves" -> movesJson,
      "engine"    -> ujson.Obj(),
      "limits"    -> ujson.Obj("timeoutMillis" -> moveTimeoutMillis),
      "metadata"  -> ujson.Obj("mode" -> "HumanVsDeployedBot")
    ))
    val req = HttpRequest
      .newBuilder(URI.create(aiEndpoint))
      .timeout(Duration.ofMillis(moveTimeoutMillis.toLong))
      .header("Content-Type", "application/json")
      .header("Accept", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(body))
      .build()
    try
      val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
      if resp.statusCode() >= 200 && resp.statusCode() < 300 then
        scala.util.Try {
          val json = ujson.read(resp.body())
          val move = json("move")
          LegalMove(move("from").str, move("to").str, move.obj.get("promotion").flatMap(_.strOpt))
        }.toOption
      else
        System.err.println(s"[BotWorker] AI service returned HTTP ${resp.statusCode()} (task=${task.turnTaskId})")
        None
    catch
      case NonFatal(e) =>
        System.err.println(s"[BotWorker] AI request failed: ${e.getMessage} (task=${task.turnTaskId})")
        None

  // ── game-service: submit ─────────────────────────────────────────────────

  private def submitMove(turnTaskId: String, move: LegalMove): Unit =
    val moveObj = ujson.Obj("from" -> move.from, "to" -> move.to)
    move.promotion.foreach(p => moveObj("promotion") = p)
    val body = ujson.write(moveObj)
    val uri  = URI.create(s"$turnsEndpoint/${encode(turnTaskId)}/move")
    val req  = HttpRequest
      .newBuilder(uri)
      .timeout(Duration.ofMillis(moveTimeoutMillis.toLong))
      .header("Content-Type", "application/json")
      .header("X-Bot-Api-Key", apiKey)
      .POST(HttpRequest.BodyPublishers.ofString(body))
      .build()
    try
      val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
      resp.statusCode() match
        case s if s >= 200 && s < 300 =>
          println(s"[BotWorker] move submitted: ${move.from}${move.to} task=$turnTaskId status=$s")
        case s =>
          System.err.println(
            s"[BotWorker] move submission failed HTTP $s task=$turnTaskId: ${resp.body().take(200)}"
          )
    catch
      case NonFatal(e) =>
        System.err.println(s"[BotWorker] move submission threw: ${e.getMessage} task=$turnTaskId")

  private def encode(s: String): String =
    java.net.URLEncoder.encode(s, "UTF-8")

// ── value types ───────────────────────────────────────────────────────────────

private final case class BotTurnLease(
    turnTaskId: String,
    sessionId: String,
    gameId: String,
    sideToMove: String,
    fen: String,
    legalMoves: List[LegalMove]
)

private final case class LegalMove(from: String, to: String, promotion: Option[String])
