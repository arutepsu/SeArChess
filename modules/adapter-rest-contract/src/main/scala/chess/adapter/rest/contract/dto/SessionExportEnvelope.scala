package chess.adapter.rest.contract.dto

import ujson.Value

/** Versioned file-oriented envelope for full session snapshot export/import.
  *
  * The nested `snapshot` uses the same canonical shape as `GET /sessions/{id}/state`, including
  * session metadata, game state, castling rights, and en passant state.
  */
final case class SessionExportEnvelope(
    schema: String,
    version: Int,
    exportedAt: String,
    snapshot: SessionStateResponse
)

object SessionExportEnvelope:
  def toJson(r: SessionExportEnvelope): Value =
    ujson.Obj(
      "schema" -> ujson.Str(r.schema),
      "version" -> ujson.Num(r.version.toDouble),
      "exportedAt" -> ujson.Str(r.exportedAt),
      "snapshot" -> SessionStateResponse.toJson(r.snapshot)
    )

  def fromJson(body: String): Either[String, SessionExportEnvelope] =
    try
      val json = ujson.read(body)
      SessionStateResponse.fromJson(json("snapshot").render()).map { snapshot =>
        SessionExportEnvelope(
          schema = json("schema").str,
          version = json("version").num.toInt,
          exportedAt = json("exportedAt").str,
          snapshot = snapshot
        )
      }
    catch case _: Exception => Left("Malformed JSON in request body")
