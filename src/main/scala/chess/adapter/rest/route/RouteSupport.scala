package chess.adapter.rest.route

import chess.adapter.rest.dto.ErrorResponse
import com.sun.net.httpserver.HttpExchange
import java.util.UUID

/** Shared low-level HTTP helpers used by all route handlers.
 *
 *  Keeps route classes free of boilerplate I/O code.
 *  All methods are synchronous; concurrency is handled by the server's
 *  executor (configured in [[chess.adapter.rest.RestServer]]).
 */
object RouteSupport:

  /** Read the full request body as a UTF-8 string. */
  def readBody(exchange: HttpExchange): Either[String, String] =
    try Right(new String(exchange.getRequestBody.readAllBytes(), "UTF-8"))
    catch case _: Exception => Left("Could not read request body")

  /** Write a JSON value as the HTTP response. */
  def sendJson(exchange: HttpExchange, status: Int, json: ujson.Value): Unit =
    val body = ujson.write(json).getBytes("UTF-8")
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(status, body.length.toLong)
    val out = exchange.getResponseBody
    try out.write(body) finally out.close()

  /** Write a structured error response. */
  def sendError(exchange: HttpExchange, status: Int, code: String, message: String): Unit =
    sendJson(exchange, status, ErrorResponse.toJson(ErrorResponse(code, message)))

  /** Parse a UUID string, returning Left with a message on failure. */
  def parseUUID(s: String): Either[String, UUID] =
    try Right(UUID.fromString(s))
    catch case _: IllegalArgumentException => Left(s"Invalid UUID: '$s'")
