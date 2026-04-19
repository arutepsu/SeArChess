# AI Service Extraction Boundary

This note defines the target boundary for extracting AI move generation from the
modular monolith. It does not introduce a deployable service yet.

The key rule is unchanged: AI proposes, Game Service decides.

## Ownership

### Game Service Owns

- active game/session truth
- whose turn it is
- whether the current side is AI-controlled
- legal move validation
- applying moves to `GameState`
- active persistence
- lifecycle/result events
- HTTP semantics for `/games/{gameId}/ai-move`

Game Service must validate and apply every AI move through the same path used
for human moves. A move returned by AI is only a candidate.

### AI Service Owns

- engine/model execution
- engine selection within advertised capabilities
- search depth/time budget handling
- move suggestion
- engine diagnostics
- model/engine configuration
- internal caching or warmup

AI Service must not persist active game state, update session lifecycle, decide
game results, or publish authoritative game events.

## Recommended V1 Interaction

Use synchronous request/response for the first remote contract.

Reasons:

- Current `AIProvider.suggestMove` is synchronous.
- `POST /games/{gameId}/ai-move` expects an immediate answer.
- Game Service must validate/apply the returned move before responding.
- Async jobs would require request tracking, polling/callbacks, cancellation,
  idempotency, and more operational machinery than v1 needs.

An async model can be added later for long-running engines or analysis modes,
but v1 should be a bounded-time suggestion call.

## Candidate HTTP Contract

Endpoint:

```text
POST /v1/move-suggestions
```

Request:

```json
{
  "requestId": "uuid-or-correlation-id",
  "gameId": "uuid",
  "sessionId": "uuid",
  "sideToMove": "White",
  "fen": "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
  "legalMoves": [
    { "from": "e2", "to": "e4" },
    { "from": "g1", "to": "f3" }
  ],
  "engine": {
    "engineId": "stockfish-default",
    "skillLevel": 8
  },
  "limits": {
    "timeoutMillis": 2000,
    "maxDepth": 12
  },
  "metadata": {
    "mode": "HumanVsAI"
  }
}
```

Success response:

```json
{
  "requestId": "uuid-or-correlation-id",
  "move": { "from": "e2", "to": "e4" },
  "engine": {
    "engineId": "stockfish-default",
    "name": "Stockfish",
    "version": "16"
  },
  "diagnostics": {
    "depth": 10,
    "nodes": 123456,
    "elapsedMillis": 187,
    "scoreCp": 24
  }
}
```

Error response:

```json
{
  "requestId": "uuid-or-correlation-id",
  "code": "ENGINE_UNAVAILABLE",
  "message": "Requested engine is not available"
}
```

The Game Service adapter maps this remote contract back into the existing
`AIProvider` port. The port accepts an application-owned `AIRequestContext`, not
remote DTOs, so the application layer does not need to know whether the provider
is in-process or remote.

## FEN vs Rich Snapshot

V1 should send FEN and legal moves.

FEN is the smallest stable chess position format:

- compact
- engine-friendly
- already supported by the notation module
- captures side to move, castling, en passant, and clocks
- does not expose the internal `GameState` shape as a remote contract

Legal moves should be supplied by Game Service as a constraint and diagnostic
aid, not because AI is authoritative. This gives the AI Service a bounded set of
valid candidates and lets simple engines avoid duplicating move generation. A
strong engine may still derive moves internally, but its returned move must be
one of the legal candidates. Game Service validates again regardless.

A richer snapshot may be added later for analysis features, but it should be
additive:

- move history
- repetition/castling details if not represented well enough
- session mode/controller metadata
- opening book context
- variant/ruleset if variants are introduced

Do not send raw internal `GameState` over the remote boundary.

## Session And Controller Metadata

Game Service should send only metadata useful for selection/diagnostics:

- `gameId`
- `sessionId`
- `sideToMove`
- optional `mode`
- requested `engineId`, if configured by the session/controller
- search limits
- correlation/request id

AI Service does not need full controller policy. It should not decide whether it
is allowed to move. That guard belongs to Game Service before the AI call.

## Scala AI Port Decision

`AIProvider` should not remain state-only for the remote contract.

The state-only seam was sufficient for an in-process toy provider, but the
cross-service Python inference contract needs stable request correlation and
active Game Service identifiers. Placeholder `gameId` / `sessionId` values are
not acceptable because downstream logs, metrics, and diagnostics would encode
false facts.

The Scala application port therefore uses a small application-owned
`AIRequestContext` containing:

- `requestId`
- `sessionId`
- `gameId`
- `mode`
- `sideToMove`
- `state`
- optional `engineId`

Adapters may derive FEN and legal moves from this context, but remote DTOs remain
inside `adapter-ai`. Game Service authority remains unchanged: the provider
returns only a move candidate.

## Engine Selection

V1 engine selection should be optional:

- if `engine.engineId` is absent, AI Service uses its default engine
- if present and available, AI Service uses that engine
- if present and unavailable, AI Service returns `ENGINE_UNAVAILABLE`
- if present but disabled/misconfigured, AI Service returns `ENGINE_FAILURE`

The existing `SideController.AI(engineId: Option[String])` provides the
requested `engineId` when the active side is AI-controlled. The current REST v1
controller parsing does not need to change for this design note.

Capabilities can be exposed later through:

```text
GET /v1/engines
GET /health
```

## Failure Semantics

Recommended remote error codes:

- `NO_LEGAL_MOVE`: no candidate exists; usually terminal or inconsistent input.
- `ENGINE_UNAVAILABLE`: requested engine id is unknown or not loaded.
- `ENGINE_TIMEOUT`: engine exceeded the requested/adapter timeout.
- `ENGINE_FAILURE`: engine crashed or failed internally.
- `BAD_POSITION`: FEN/snapshot is malformed or unsupported.
- `BAD_REQUEST`: request contract is invalid.

Game Service handling:

- Timeout from the HTTP client maps to `AIError.EngineFailure("timeout")` or a
  future typed timeout error.
- `NO_LEGAL_MOVE` maps to `AIError.NoLegalMove`.
- Engine or bad-response failures map to `AIError.EngineFailure(...)`.
- If AI returns an illegal move, Game Service treats it as
  `AITurnError.MoveFailed` after normal validation rejects it.
- If AI Service is unavailable, Game Service returns provider failure through
  existing `/ai-move` error mapping.
- If AI is not configured in the runtime, Game Service still returns
  `AI_NOT_CONFIGURED` before any remote call.

Game Service should never persist or publish `MoveApplied` from the AI response
alone. Persistence and events occur only after the normal authoritative command
path accepts the move.

## Timeouts And Retries

V1 should use a short bounded timeout from Game Service to AI Service. The
timeout should be part of runtime configuration and included in the request as a
hint to the engine.

Retry policy should be conservative:

- no automatic retry for a completed engine response
- no retry after an illegal move suggestion
- at most one retry for transient transport failure, and only if the adapter can
  preserve the same `requestId`

Because the AI call is a suggestion and has no side effects in Game Service,
idempotency requirements are lighter than command APIs. Still, `requestId`
should be propagated so duplicate calls can be correlated.

## Observability

Every AI request should carry or derive:

- `requestId`
- trace/correlation id from the inbound Game Service request
- `gameId`
- `sessionId`
- `sideToMove`
- requested engine id
- timeout/depth limits

Game Service should observe:

- AI provider latency
- success/failure count by error code
- illegal suggestion count
- timeout count
- selected engine id

AI Service should observe:

- request latency
- engine compute time
- queue/wait time, if any
- engine id/version
- depth/nodes/score where available
- timeout/failure cause

Logs must not require dumping full board state for normal operation. FEN may be
included at debug level when acceptable, but identifiers and request ids should
be enough for routine correlation.

## Extraction Roadmap

1. Keep current in-process `AIProvider`.
   - It remains the application port.
   - `FirstLegalMoveProvider` remains a deterministic test/dev adapter.

2. Add a remote AI adapter that implements `AIProvider`.
   - It maps `AIRequestContext` to the remote request.
   - It serializes `GameState` to FEN.
   - It derives legal moves from Game Service/domain rules.
   - It calls `POST /v1/move-suggestions`.
   - It maps remote errors into `AIError`.

3. Add runtime configuration.
   - local deterministic provider
   - remote AI provider base URL
   - timeout and default engine id
   - disabled/unconfigured mode

4. Extract an AI Service process.
   - Start with a single engine implementation.
   - Provide `/health` and `/v1/move-suggestions`.
   - Add `/v1/engines` only when multiple engines/capabilities are real.

5. Harden operational concerns.
   - metrics
   - structured logs
   - trace propagation
   - bounded concurrency
   - engine process supervision

6. Consider async analysis later.
   - Use jobs/events only for long-running analysis or AI-vs-AI scheduling, not
     the first move-suggestion contract.

## Recommendation For The Contract-First Slice

Keep `AIProvider` as the application seam, but widen its input from raw
`GameState` to `AIRequestContext`.

This is the smallest stable contract that avoids fake identifiers while keeping
network DTOs out of the application layer. Python AI Service v1 should assume
Scala can provide real game/session identifiers, side to move, FEN, legal moves,
optional engine id, and a request id. Python should still treat the returned move
as a suggestion only; Scala Game Service remains authoritative for validation,
persistence, and results.
