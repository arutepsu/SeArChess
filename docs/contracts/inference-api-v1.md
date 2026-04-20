# Inference API v1 — Shared Contract

**Version:** 1.0.0  
**Status:** Draft  
**Owner:** Game Service (Scala) — authoritative for contract definition  
**Consumer:** AI Service (Python) — implements this contract  
**Scope:** Move suggestion inference only. Training, evaluation, model lifecycle, and engine
management are explicitly out of scope for v1.

---

## 1. Ownership and Authority Rules

| Concern | Authority |
|---------|-----------|
| Legal move set | Game Service — computed from domain rules, supplied in every request |
| Position encoding | Game Service — FEN is the canonical position format |
| Move legality | Game Service — re-validates the returned move before applying it |
| Game lifecycle (result, persistence, events) | Game Service — AI Service has no visibility |
| Engine selection, model loading, inference timing | AI Service |
| Whether a suggested move is applied | Game Service — AI proposes; Game Service decides |

AI Service MUST NOT assume its suggested move will be applied. It MUST NOT persist any game
state or emit side-effects that assume acceptance.

---

## 2. Wire Format

All JSON field names on the wire use **camelCase** (`requestId`, `gameId`, `sideToMove`,
`legalMoves`, `timeoutMillis`, etc.). Both sides MUST serialize and parse camelCase field
names. Internal code may use any naming convention; the camelCase requirement is at the
HTTP boundary only.

---

## 3. Endpoints

### 2.1 `POST /v1/move-suggestions`

Request a move suggestion for the current position.

### 2.2 `GET /health`

Liveness/readiness probe. Used by Game Service before routing inference traffic and by
infrastructure health checks.

`GET /v1/engines` is deferred. Engine enumeration is not required for the first integration;
`engine.engineId` in the request is sufficient for initial routing.

---

## 3. `POST /v1/move-suggestions`

### 3.1 Request

```json
{
  "requestId":  "550e8400-e29b-41d4-a716-446655440000",
  "gameId":     "123e4567-e89b-12d3-a456-426614174000",
  "sessionId":  "789abcde-f012-3456-b789-abcdef012345",
  "sideToMove": "white",
  "fen":        "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
  "legalMoves": [
    { "from": "e7", "to": "e5" },
    { "from": "e7", "to": "e6" },
    { "from": "g8", "to": "f6" }
  ],
  "engine": {
    "engineId": "random-legal"
  },
  "limits": {
    "timeoutMillis": 3000
  },
  "metadata": {
    "mode": "HumanVsAI"
  }
}
```

#### Required fields

| Field | Type | Description |
|-------|------|-------------|
| `requestId` | UUID string | Client-generated correlation ID. Echoed in every response (success and error). Game Service generates one per AI turn invocation. |
| `gameId` | UUID string | Game Service game identifier. Authoritative; AI Service uses it for diagnostics and logging only. |
| `sessionId` | UUID string | Game Service session identifier. Authoritative; AI Service uses it for diagnostics and logging only. |
| `sideToMove` | `"white"` \| `"black"` | The side whose turn it is. Always lowercase. |
| `fen` | string | Current position in Forsyth-Edwards Notation. AI Service treats this as the position input; Game Service is the authority on correctness. |
| `legalMoves` | array of MoveDto (min 1) | Complete set of legal moves for the side to move, computed by Game Service. AI Service MUST return a move from this set. |
| `limits.timeoutMillis` | integer ≥ 1 | Maximum milliseconds the AI Service may spend before responding. Game Service enforces an independent client-side timeout at the same value. |

#### Optional fields

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `engine.engineId` | string | AI Service default | Opaque identifier for a specific engine or model. If absent, AI Service selects its default. |
| `metadata.mode` | string | — | Session mode hint (e.g. `"HumanVsAI"`, `"AIVsAI"`). Informational; AI Service MAY use for routing or diagnostics. |

#### MoveDto

```json
{ "from": "e2", "to": "e4" }
{ "from": "e7", "to": "e8", "promotion": "queen" }
```

| Field | Type | Description |
|-------|------|-------------|
| `from` | `[a-h][1-8]` | Source square, lowercase algebraic (e.g. `"e2"`). |
| `to` | `[a-h][1-8]` | Destination square, lowercase algebraic (e.g. `"e4"`). |
| `promotion` | `"queen"` \| `"rook"` \| `"bishop"` \| `"knight"` | Present only for pawn promotions. Game Service emits one MoveDto per promotion choice; AI Service picks one. |

Move format is `from`/`to` plus optional `promotion`. This is structurally equivalent to UCI
long algebraic but split into explicit fields to avoid parsing ambiguity across languages.

---

### 3.2 Response (HTTP 200)

```json
{
  "requestId":     "550e8400-e29b-41d4-a716-446655440000",
  "move":          { "from": "e7", "to": "e5" },
  "engineId":      "random-legal",
  "engineVersion": "0.1.0",
  "elapsedMillis": 12,
  "confidence":    0.83
}
```

#### Required fields

| Field | Type | Description |
|-------|------|-------------|
| `requestId` | UUID string | Echoes the `requestId` from the request. |
| `move` | MoveDto | The move AI Service proposes. Must be a member of the supplied `legalMoves` set. Game Service re-validates before applying. |

#### Optional fields

| Field | Type | Description |
|-------|------|-------------|
| `engineId` | string | Identifier of the engine or model that produced this suggestion. |
| `engineVersion` | string | Version of the engine or model. |
| `elapsedMillis` | integer ≥ 0 | Wall-clock milliseconds spent on inference. |
| `confidence` | float [0.0, 1.0] | Engine confidence in the suggested move, if available. |

---

### 3.3 Error Response

All non-2xx responses use the same shape:

```json
{
  "requestId": "550e8400-e29b-41d4-a716-446655440000",
  "code":      "ENGINE_TIMEOUT",
  "message":   "engine did not respond within 3000ms"
}
```

| Field | Type | Description |
|-------|------|-------------|
| `requestId` | string | Echoes the `requestId` from the request if the body was parseable JSON containing that field. Empty string if the body could not be parsed (invalid JSON or missing `requestId`). |
| `code` | string | Machine-readable error code (see Section 4). |
| `message` | string | Human-readable description. Not for programmatic handling. |

---

## 4. Error Code Registry

| Code | HTTP Status | Retryable | Meaning |
|------|-------------|-----------|---------|
| `BAD_REQUEST` | 400 | No | Request is malformed: missing required field, wrong type, failed validation. |
| `BAD_POSITION` | 422 | No | Request is well-formed but the FEN or legal move set is internally inconsistent or unrecognisable by the engine. |
| `NO_LEGAL_MOVE` | 422 | No | The engine cannot find a legal move. Game Service guarantees `legalMoves` is non-empty in a valid request — an empty list is a `BAD_REQUEST`. `NO_LEGAL_MOVE` is a defensive signal from the engine layer when it determines no valid move exists in the supplied set. |
| `ENGINE_UNAVAILABLE` | 503 | Yes — with backoff | The requested (or default) engine is not loaded or not reachable internally. |
| `ENGINE_TIMEOUT` | 504 | Yes — with backoff | The engine did not respond within `limits.timeoutMillis`. |
| `ENGINE_FAILURE` | 500 | No | The engine encountered an unexpected internal error and could not produce a candidate. |

**Retry guidance:**
- `ENGINE_UNAVAILABLE` and `ENGINE_TIMEOUT` are safe to retry with the same `requestId`.
  One retry with a short backoff (≥ 200 ms) is sufficient for v1; retry policy beyond that
  is a caller concern.
- `BAD_REQUEST`, `BAD_POSITION`, `NO_LEGAL_MOVE`, and `ENGINE_FAILURE` will not succeed on
  retry without a change to the request or system state.

---

## 5. `GET /health`

### Response (HTTP 200)

```json
{
  "status":  "ok",
  "service": "searchess-ai-service",
  "version": "0.1.0"
}
```

HTTP 200 with `status: "ok"` means the service is up and able to accept inference requests.
HTTP 503 means the service is not ready. No fine-grained status codes for v1.

---

## 6. Timeout and Retry Expectations

- Game Service sets `limits.timeoutMillis` in the request and enforces an identical
  client-side HTTP timeout. The two values are the same; AI Service does not need to add
  buffer.
- Default `timeoutMillis` for v1: **3000 ms**. Configurable per deployment.
- AI Service SHOULD respond before `timeoutMillis` elapses. If the engine cannot finish in
  time, AI Service returns `ENGINE_TIMEOUT` itself rather than letting the HTTP connection
  time out.
- On `ENGINE_UNAVAILABLE` or `ENGINE_TIMEOUT`, one retry with a short delay (≥ 200 ms) is
  a reasonable default. Retry policy is a caller concern.

---

## 7. Versioning

- The path prefix `/v1/` is the version discriminator.
- Breaking changes (removed required fields, changed semantics) require a new prefix (`/v2/`).
- Additive changes to optional fields in request or response are non-breaking and do not
  require a version bump.
- Both sides MUST ignore unknown fields in responses and requests (permissive parsing).

---

## 8. Adjustments Required to Align With This Contract

The following divergences were identified between the current Scala and Python implementations
and must be resolved before the first real integration. They are recorded here so each team
has a clear checklist; no code changes are made in this contract-definition slice.

### Python AI Service

| Location | Current state | Required change |
|----------|---------------|-----------------|
| Route path | `POST /inference/move` | Change to `POST /v1/move-suggestions` |
| `MoveInferenceRequestDto.match_id` | `match_id: str` | Rename to `game_id` |
| `MoveInferenceRequestDto` | No `session_id` field | Add `session_id: str` (UUID) |
| `MoveInferenceRequestDto.board_state` | `board_state: str` | Rename to `fen` |
| `MoveInferenceRequestDto.legal_moves` | `list[str]` (opaque strings) | Change to `list[MoveDto]` with `from`, `to`, optional `promotion` fields |
| `MoveInferenceRequestDto.model_id` / `model_version` | Flat top-level fields | Move under `engine.engine_id`; `model_version` becomes `engine_version` in response |
| `MoveInferenceRequestDto.remaining_time_millis` | Flat top-level field | Move under `limits.timeout_millis` |
| `MoveInferenceRequestDto.policy_profile` | Flat top-level field | Move to `metadata.mode` (or discard in v1 if not needed for routing) |
| `MoveInferenceResponseDto.selected_move` | `str` (opaque) | Change to `MoveDto` object |
| `MoveInferenceResponseDto.decision_time_millis` | `decision_time_millis` | Rename to `elapsed_millis` |
| `MoveInferenceResponseDto.decision_type` | Always `"move"` | Remove from v1 contract; not needed |
| Error response shape | `{"error": ..., "type": ...}` | Change to `{"requestId": ..., "code": ..., "message": ...}` |
| Error codes | `"not_found"`, `"adapter_error"`, `"validation_error"` | Map to `BAD_REQUEST`, `BAD_POSITION`, `NO_LEGAL_MOVE`, `ENGINE_UNAVAILABLE`, `ENGINE_TIMEOUT`, `ENGINE_FAILURE` |

### Scala Game Service (`RemoteAiRequestMapper` / `RemoteAiMoveSuggestionClient`)

| Location | Current state | Required change |
|----------|---------------|-----------------|
| `RemoteAiRequestMapper` — `sideToMove` | `context.sideToMove.toString` → `"White"` / `"Black"` | Change to `.toString.toLowerCase` → `"white"` / `"black"` |
| `RemoteAiRequestMapper` — `promotion` | `move.promotion.map(_.toString)` → `"Queen"` etc. | Change to `.map(_.toString.toLowerCase)` → `"queen"` etc. |
| `RemoteAiMoveSuggestionClient.mapRemoteError` | Only maps `NO_LEGAL_MOVE` by exact code string | Add explicit mapping for all 6 error codes |
| `RemoteAiMoveSuggestionResponse` | Has `engineId`, `elapsedMillis` | Add `engineVersion: Option[String]` and `confidence: Option[Double]` |

---

## 9. Intentionally Deferred for v1

| Question | Reason deferred |
|----------|-----------------|
| `GET /v1/engines` — engine enumeration | Not required for first integration; `engine.engineId` is sufficient for routing |
| Authentication / API key | Internal service-to-service; network boundary security is sufficient for v1 |
| Multi-move or ranked suggestions (top-N) | Adds response complexity; single best move is sufficient for v1 gameplay |
| Streaming inference responses | Not needed at current latency budget |
| `metadata.mode` routing logic in AI Service | AI Service may ignore `mode` in v1; routing rules are an AI-internal concern |
| Exact `engineVersion` format | Either SemVer or opaque string; AI Service decides its own versioning scheme |
| Confidence score semantics | Defined as `[0.0, 1.0]` but normalization method is AI Service's choice |
| Error payload for partial parse failures (`requestId` may be unknown) | Return empty string for `requestId` in `BAD_REQUEST`; acceptable for v1 |
| Circuit breaker / bulkhead configuration | Deployment concern; not specified in the protocol contract |
