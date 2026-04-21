# Game Service HTTP Contract v1

Status: local/dev contract, extraction-oriented  
Owner: Game Service  
Base path: `/`  
Content type: `application/json`

This document describes the externally consumable HTTP surface of the Game
Service. It reflects the current http4s implementation; it is not a redesign.

## Versioning Expectations

The current routes are unprefixed. Treat this document as contract version
`game-service-http-v1`. A future incompatible HTTP shape should use either a
versioned route prefix or a new contract document before clients are migrated.

Identifiers are UUID strings. Invalid UUID path values return:

```json
{
  "code": "BAD_REQUEST",
  "message": "Invalid UUID: 'not-a-uuid'"
}
```

All structured errors use:

```json
{
  "code": "MACHINE_READABLE_CODE",
  "message": "Human-readable detail"
}
```

## Common Enums

`Color`: `"White" | "Black"`

`SessionMode`: `"HumanVsHuman" | "HumanVsAI" | "AIVsAI"`

`SessionLifecycle`: `"Created" | "Active" | "AwaitingPromotion" | "Finished"`

REST v1 normally returns `"Active"` or `"Finished"` from move/resign/AI
commands. Promotion is handled by rejecting a move without a promotion choice,
not by leaving the session in `AwaitingPromotion`.

`Controller` in responses: `"HumanLocal" | "HumanRemote" | "AI"`

`Controller` in requests: absent, `"HumanLocal"`, or `"HumanRemote"`.
Clients do not submit `"AI"` as a controller. AI seats are derived from
`SessionMode`.

`PieceType`: `"King" | "Queen" | "Rook" | "Bishop" | "Knight" | "Pawn"`

`PromotionPiece`: `"Queen" | "Rook" | "Bishop" | "Knight"`

Squares use algebraic notation such as `"e2"` and `"e4"`.

## Core JSON Shapes

### Session

```json
{
  "sessionId": "2e39c1e3-31ef-41c7-a783-f30c507a4232",
  "gameId": "9a07c6f3-70c2-4467-98af-34e0bbf6bde1",
  "mode": "HumanVsHuman",
  "lifecycle": "Active",
  "whiteController": "HumanLocal",
  "blackController": "HumanLocal",
  "createdAt": "2026-04-20T10:00:00Z",
  "updatedAt": "2026-04-20T10:00:00Z"
}
```

### Game

```json
{
  "gameId": "9a07c6f3-70c2-4467-98af-34e0bbf6bde1",
  "currentPlayer": "White",
  "status": "Ongoing",
  "inCheck": false,
  "winner": null,
  "drawReason": null,
  "fullmoveNumber": 1,
  "halfmoveClock": 0,
  "board": [
    { "square": "e2", "color": "White", "pieceType": "Pawn" }
  ],
  "moveHistory": [],
  "lastMove": null,
  "promotionPending": false,
  "legalTargetsByFrom": {
    "e2": ["e3", "e4"]
  }
}
```

`status` is currently one of `"Ongoing"`, `"Checkmate"`, `"Draw"`, or
`"Resigned"`. `winner` is present for checkmate/resignation. `drawReason` is
present for draws.

## Endpoints

### GET /health

Liveness endpoint owned by `apps/game-service`.

Response `200 OK`:

```json
{ "status": "ok" }
```

This is liveness, not deep readiness. It does not check SQLite, AI, or History.

### POST /sessions

Create a session and its initial game.

Request body: all fields optional.

```json
{
  "mode": "HumanVsHuman",
  "whiteController": "HumanLocal",
  "blackController": "HumanRemote"
}
```

Defaults:

| Mode | White | Black |
|---|---|---|
| omitted or `HumanVsHuman` | `HumanLocal`, unless provided | `HumanLocal`, unless provided |
| `HumanVsAI` | `HumanLocal`, unless provided | server AI |
| `AIVsAI` | server AI | server AI |

For `HumanVsAI`, omit `blackController`. For `AIVsAI`, omit both controller
fields. Passing `"AI"` as an inbound controller is invalid.

Response `201 Created`:

```json
{
  "session": { "...": "Session" },
  "game": { "...": "Game" }
}
```

Errors:

| Status | Code | Meaning |
|---|---|---|
| 400 | `BAD_REQUEST` | malformed JSON, unknown mode/controller, or invalid controller/mode combination |

### GET /sessions

List active sessions.

Response `200 OK`:

```json
{
  "sessions": [
    { "...": "Session" }
  ]
}
```

Errors:

| Status | Code | Meaning |
|---|---|---|
| 500 | `INTERNAL_ERROR` | storage/session query failure |

### GET /sessions/{sessionId}

Fetch one session by UUID.

Response `200 OK`: `Session`

Errors:

| Status | Code | Meaning |
|---|---|---|
| 400 | `BAD_REQUEST` | invalid UUID |
| 404 | `SESSION_NOT_FOUND` | no session exists for the UUID |
| 500 | `INTERNAL_ERROR` | storage/session query failure |

### POST /sessions/{sessionId}/cancel

Existing adjacent endpoint. Cancels a session and makes its archive snapshot
available.

Request body: none.

Response `200 OK`: `Session`

Errors:

| Status | Code | Meaning |
|---|---|---|
| 400 | `BAD_REQUEST` | invalid UUID |
| 404 | `SESSION_NOT_FOUND` | no session exists for the UUID |
| 409 | `SESSION_ALREADY_FINISHED` | session is already finished |
| 500 | `INTERNAL_ERROR` | storage/session query failure |

### GET /games/{gameId}

Fetch current game state by UUID.

Response `200 OK`: `Game`

Errors:

| Status | Code | Meaning |
|---|---|---|
| 400 | `BAD_REQUEST` | invalid UUID |
| 404 | `GAME_NOT_FOUND` | no game state exists for the UUID |
| 500 | `INTERNAL_ERROR` | storage/game query failure |

### GET /games/{gameId}/legal-moves

Existing adjacent endpoint. Fetch legal moves for the current player.

Response `200 OK`:

```json
{
  "gameId": "9a07c6f3-70c2-4467-98af-34e0bbf6bde1",
  "currentPlayer": "White",
  "moves": [
    { "from": "e2", "to": "e4", "promotion": null }
  ],
  "legalTargetsByFrom": {
    "e2": ["e3", "e4"]
  }
}
```

Errors:

| Status | Code | Meaning |
|---|---|---|
| 400 | `BAD_REQUEST` | invalid UUID |
| 404 | `GAME_NOT_FOUND` | no game state exists for the UUID |
| 500 | `INTERNAL_ERROR` | storage/game query failure |

### POST /games/{gameId}/moves

Submit a human/controller move.

Request body:

```json
{
  "from": "e2",
  "to": "e4",
  "promotion": null,
  "controller": "HumanLocal"
}
```

`from` and `to` are required. `promotion` is required only when a pawn reaches
the back rank. `controller` is optional and defaults to `HumanLocal`.

Response `200 OK`:

```json
{
  "game": { "...": "Game" },
  "sessionLifecycle": "Active"
}
```

Errors:

| Status | Code | Meaning |
|---|---|---|
| 400 | `BAD_REQUEST` | invalid UUID, malformed JSON, missing `from`/`to`, or unknown controller |
| 403 | `UNAUTHORIZED_CONTROLLER` | controller is not authorized for the side to move |
| 404 | `GAME_NOT_FOUND` | game/session cannot be found from the game ID |
| 404 | `SESSION_NOT_FOUND` | session cannot be found |
| 409 | `GAME_FINISHED` | game is already finished |
| 422 | `INVALID_MOVE` | square or promotion parsing failed |
| 422 | `NOT_YOUR_TURN` | move attempts to play out of turn |
| 422 | `PROMOTION_REQUIRED` | promotion piece is required |
| 422 | `ILLEGAL_MOVE` | domain rejected the move |
| 500 | `INTERNAL_ERROR` | storage/session failure |

### POST /games/{gameId}/ai-move

Ask the configured AI provider to generate and apply a move for the current
player. The endpoint is always mounted; deployments without AI return
`AI_NOT_CONFIGURED`.

Request body: none.

Response `200 OK`:

```json
{
  "game": { "...": "Game" },
  "sessionLifecycle": "Active"
}
```

Errors:

| Status | Code | Meaning |
|---|---|---|
| 400 | `BAD_REQUEST` | invalid UUID |
| 404 | `GAME_NOT_FOUND` | game/session cannot be found from the game ID |
| 422 | `AI_NOT_CONFIGURED` | deployment has no AI provider |
| 422 | `NOT_AI_TURN` | current player is not controlled by AI |
| 422 | `AI_MOVE_REJECTED` | generated AI move was rejected by Game Service |
| 503 | `AI_PROVIDER_FAILED` | AI provider call failed |
| 500 | `INTERNAL_ERROR` | session/game lookup failed unexpectedly |

AI moves still pass through the authoritative Game Service move path.

### POST /games/{gameId}/resign

Resign a game on behalf of one side.

Request body:

```json
{ "side": "White" }
```

Response `200 OK`:

```json
{
  "game": { "...": "Game" },
  "sessionLifecycle": "Finished"
}
```

Errors:

| Status | Code | Meaning |
|---|---|---|
| 400 | `BAD_REQUEST` | invalid UUID, malformed JSON, missing `side`, or unknown side |
| 404 | `GAME_NOT_FOUND` | game/session cannot be found from the game ID |
| 404 | `SESSION_NOT_FOUND` | session cannot be found |
| 409 | `GAME_ALREADY_FINISHED` | game is already finished |
| 500 | `INTERNAL_ERROR` | storage/session failure |

### GET /archive/games/{gameId}

Fetch the archive snapshot that downstream History uses to materialize archive
records. This is available only after the game/session is closed by checkmate,
draw, resignation, or cancellation.

Response `200 OK`:

```json
{
  "sessionId": "2e39c1e3-31ef-41c7-a783-f30c507a4232",
  "gameId": "9a07c6f3-70c2-4467-98af-34e0bbf6bde1",
  "mode": "HumanVsHuman",
  "whiteController": "HumanLocal",
  "blackController": "HumanLocal",
  "closure": {
    "kind": "Resigned",
    "winner": "Black",
    "drawReason": null
  },
  "finalState": {
    "game": { "...": "Game" },
    "castlingRights": {
      "whiteKingSide": true,
      "whiteQueenSide": true,
      "blackKingSide": true,
      "blackQueenSide": true
    },
    "enPassant": null
  },
  "createdAt": "2026-04-20T10:00:00Z",
  "closedAt": "2026-04-20T10:05:00Z"
}
```

`closure.kind` is one of `"Checkmate"`, `"Resigned"`, `"Draw"`, or
`"Cancelled"`.

Errors:

| Status | Code | Meaning |
|---|---|---|
| 400 | `BAD_REQUEST` | invalid UUID |
| 404 | `ARCHIVE_NOT_FOUND` | no archive snapshot source exists for the game |
| 409 | `ARCHIVE_NOT_READY` | game exists but is not closed |
| 500 | `INTERNAL_ERROR` | storage/archive lookup failure |

## Gap Report

Fully specified in this slice:

- Route paths, methods, JSON request/response shapes, UUID behavior, common
  enums, health semantics, AI capability behavior, archive snapshot readiness,
  and the current structured error body.

Still implicit in adapter code:

- Exact ordering of board entries and some list fields, except where code
  currently sorts legal moves.
- The full set of domain-specific `ILLEGAL_MOVE` messages.
- Internal AI engine identity. Responses collapse most AI controllers to
  `"AI"` except archive snapshots may expose `"AI:<engine>"` when present.
- Deep readiness semantics. `/health` is only liveness today.

Intentionally deferred:

- Route version prefix such as `/v1`.
- Auth, rate limits, tracing, metrics, idempotency keys, retries, pagination,
  and durable event-delivery guarantees.
- Splitting REST DTOs into a generated standalone client package.
