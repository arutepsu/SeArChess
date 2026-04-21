package chess.server.http

import cats.effect.IO
import chess.adapter.event.{HistoryEventOutbox, HistoryOutboxEntry, HistoryOutboxSummary}
import fs2.Stream
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`

import java.time.Instant

/** Local/dev read-only visibility for the Game -> History outbox.
 *
 *  This is deliberately separate from the public gameplay API. It exposes the
 *  SQLite outbox state that already exists; it does not replay, retry, delete,
 *  or otherwise mutate delivery rows.
 */
class HistoryOutboxOpsRoutes(outbox: Option[HistoryEventOutbox]):

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "ops" / "history-outbox" =>
      withOutbox(_.summary().map(summaryJson))

    case GET -> Root / "ops" / "history-outbox" / "pending" =>
      withOutbox(_.pending(DefaultLimit).map(entries => ujson.Obj(
        "limit" -> DefaultLimit,
        "items" -> ujson.Arr.from(entries.map(entryJson(includePayload = false)))
      )))

    case GET -> Root / "ops" / "history-outbox" / LongVar(id) =>
      outbox match
        case None =>
          error(Status.NotFound, "OUTBOX_NOT_CONFIGURED", "History outbox is not configured")
        case Some(store) =>
          store.find(id) match
            case Right(Some(entry)) => json(Status.Ok, entryJson(includePayload = true)(entry))
            case Right(None)        => error(Status.NotFound, "OUTBOX_ENTRY_NOT_FOUND", s"History outbox entry not found: $id")
            case Left(err)          => error(Status.InternalServerError, "OUTBOX_READ_FAILED", err)
  }

  private val DefaultLimit = 50

  private def withOutbox(read: HistoryEventOutbox => Either[String, ujson.Value]): IO[Response[IO]] =
    outbox match
      case None =>
        error(Status.NotFound, "OUTBOX_NOT_CONFIGURED", "History outbox is not configured")
      case Some(store) =>
        read(store) match
          case Right(body) => json(Status.Ok, body)
          case Left(err)   => error(Status.InternalServerError, "OUTBOX_READ_FAILED", err)

  private def summaryJson(summary: HistoryOutboxSummary): ujson.Value =
    ujson.Obj(
      "sourceOfTruth"   -> "history_event_outbox",
      "delivery"        -> "at-least-once",
      "healthImpact"    -> "non-critical",
      "totalCount"      -> summary.totalCount,
      "pendingCount"    -> summary.pendingCount,
      "deliveredCount"  -> summary.deliveredCount,
      "retryingCount"   -> summary.retryingCount,
      "oldestPendingAt" -> instantOrNull(summary.oldestPendingAt),
      "newestPendingAt" -> instantOrNull(summary.newestPendingAt),
      "pendingByType"   -> ujson.Obj.from(summary.pendingByType.toSeq.sortBy(_._1).map {
        case (eventType, count) => eventType -> ujson.Num(count.toDouble)
      })
    )

  private def entryJson(includePayload: Boolean)(entry: HistoryOutboxEntry): ujson.Value =
    val base = ujson.Obj(
      "id"          -> ujson.Num(entry.id.toDouble),
      "eventType"   -> entry.eventType,
      "sessionId"   -> entry.sessionId,
      "gameId"      -> entry.gameId,
      "createdAt"   -> entry.createdAt.toString,
      "attempts"    -> entry.attempts,
      "lastAttemptedAt" -> instantOrNull(entry.lastAttemptedAt),
      "status"      -> status(entry),
      "pending"     -> entry.deliveredAt.isEmpty,
      "lastError"   -> entry.lastError.fold(ujson.Null: ujson.Value)(ujson.Str(_)),
      "deliveredAt" -> instantOrNull(entry.deliveredAt)
    )
    if includePayload then base("payloadJson") = ujson.read(entry.payloadJson)
    base

  private def status(entry: HistoryOutboxEntry): String =
    entry.deliveredAt match
      case Some(_) => "delivered"
      case None if entry.attempts > 0 => "retrying"
      case None => "pending"

  private def instantOrNull(value: Option[Instant]): ujson.Value =
    value.fold(ujson.Null: ujson.Value)(instant => ujson.Str(instant.toString))

  private def json(status: Status, body: ujson.Value): IO[Response[IO]] =
    IO.pure(Response[IO](
      status = status,
      headers = Headers(`Content-Type`(MediaType.application.json)),
      body = Stream.emits(ujson.write(body).getBytes("UTF-8")).covary[IO]
    ))

  private def error(status: Status, code: String, message: String): IO[Response[IO]] =
    json(status, ujson.Obj("code" -> code, "message" -> message))
