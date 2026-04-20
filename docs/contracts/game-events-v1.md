# Game Service Events v1 — Wire Contract

**Version:** 1.0  
**Status:** Draft  
**Owner:** Game Service (Scala) — authoritative for contract definition  
**Consumers:** History Service, Analytics, WebSocket adapter, any future event subscriber  
**Scope:** Five boundary-crossing events only. All other `AppEvent` variants are
internal to the Game Service process and must not be relied on by external consumers.

---

## 1. Contract Scope

Only these five events are part of the v1 wire contract:

| Scala type | Wire `type` field | Purpose |
|---|---|---|
| `AppEvent.SessionCreated` | `game.session.created.v1` | New game session is ready for play |
| `AppEvent.MoveApplied` | `game.move.applied.v1` | A move was successfully applied |
| `AppEvent.GameFinished` | `game.finished.v1` | Game ended by checkmate or draw |
| `AppEvent.GameResigned` | `game.resigned.v1` | A player resigned |
| `AppEvent.SessionCancelled` | `game.session.cancelled.v1` | Session was administratively cancelled |

Events not listed above (`MoveRejected`, `PromotionPending`, `SessionLifecycleChanged`,
`AITurn*`) are internal-only. They carry no delivery or schema guarantee for external
consumers.

---

## 2. Shared Field Conventions

Every event carries these fields:

| Field | Type | Notes |
|---|---|---|
| `type` | string | Stable event type identifier with embedded version (`v1`) |
| `sessionId` | string (UUID) | Opaque session identity — do not parse |
| `gameId` | string (UUID) | Opaque game identity — do not parse |

**IDs are opaque strings.** Consumers must treat them as equality keys only.
Internal UUID structure is not part of the contract.

---

## 3. Event Schemas

### 3.1 `game.session.created.v1`

Published when a new game session is persisted and ready for play.

```json
{
  "type": "game.session.created.v1",
  "sessionId": "550e8400-e29b-41d4-a716-446655440000",
  "gameId":    "550e8400-e29b-41d4-a716-446655440001",
  "mode":            "HumanVsHuman",
  "whiteController": "HumanLocal",
  "blackController": "AI"
}
```

**Field values:**

`mode`: `"HumanVsHuman"` | `"HumanVsAI"` | `"AIVsAI"`

`whiteController`, `blackController`: `"HumanLocal"` | `"HumanRemote"` | `"AI"` | `"AI:<engineId>"`  
The `AI:<engineId>` form is used when a specific engine identifier was requested
(e.g. `"AI:stockfish-15"`). When no engine is specified the value is `"AI"`.

---

### 3.2 `game.move.applied.v1`

Published after each successful move. Does not indicate whether the game is still in
progress — consumers must check `game.finished.v1` or `game.resigned.v1` separately.

```json
{
  "type": "game.move.applied.v1",
  "sessionId":      "550e8400-e29b-41d4-a716-446655440000",
  "gameId":         "550e8400-e29b-41d4-a716-446655440001",
  "move": {
    "from":      "e2",
    "to":        "e4",
    "promotion": null
  },
  "playerWhoMoved": "White"
}
```

**`move.from` / `move.to`:** Algebraic square notation, always two characters: file
`a`–`h` followed by rank `1`–`8`.

**`move.promotion`:** `null` for non-promotion moves. One of `"Queen"` | `"Rook"` |
`"Bishop"` | `"Knight"` when the move promotes a pawn.

**`playerWhoMoved`:** `"White"` | `"Black"` — the side that made the move. After this
event the turn belongs to the opposite color.

---

### 3.3 `game.finished.v1`

Published when a move produces a terminal game state (checkmate or draw). Always
accompanies a `game.move.applied.v1` event in the same operation.

**Checkmate:**
```json
{
  "type":       "game.finished.v1",
  "sessionId":  "550e8400-e29b-41d4-a716-446655440000",
  "gameId":     "550e8400-e29b-41d4-a716-446655440001",
  "result":     "Checkmate",
  "winner":     "White",
  "drawReason": null
}
```

**Draw:**
```json
{
  "type":       "game.finished.v1",
  "sessionId":  "550e8400-e29b-41d4-a716-446655440000",
  "gameId":     "550e8400-e29b-41d4-a716-446655440001",
  "result":     "Draw",
  "winner":     null,
  "drawReason": "Stalemate"
}
```

**`result`:** `"Checkmate"` | `"Draw"`

**`winner`:** `"White"` | `"Black"` when `result` is `"Checkmate"`. Always `null`
when `result` is `"Draw"`.

**`drawReason`:** `"Stalemate"` when `result` is `"Draw"`. Always `null` when
`result` is `"Checkmate"`. Additional draw reasons may be added in a future version.

> **Note:** `GameStatus.Resigned` in the Scala domain is **not** surfaced through
> `game.finished.v1`. Resignation produces `game.resigned.v1` (see 3.4).

---

### 3.4 `game.resigned.v1`

Published when a player explicitly resigns.

```json
{
  "type":      "game.resigned.v1",
  "sessionId": "550e8400-e29b-41d4-a716-446655440000",
  "gameId":    "550e8400-e29b-41d4-a716-446655440001",
  "winner":    "Black"
}
```

**`winner`:** `"White"` | `"Black"` — the side that did **not** resign (the
winning side). The resigning side is the opposite color.

---

### 3.5 `game.session.cancelled.v1`

Published when a session is cancelled administratively before any terminal game
result is reached. The underlying game state is not changed — no winner is recorded.

```json
{
  "type":      "game.session.cancelled.v1",
  "sessionId": "550e8400-e29b-41d4-a716-446655440000",
  "gameId":    "550e8400-e29b-41d4-a716-446655440001"
}
```

Consumers that build game archives should treat this as `result: "*"` (unfinished) in
PGN terminology.

---

## 4. Scala-to-Wire Naming Adjustments

| Scala | Wire |
|---|---|
| `AppEvent.SessionCreated` | `game.session.created.v1` |
| `AppEvent.MoveApplied` | `game.move.applied.v1` |
| `AppEvent.GameFinished` | `game.finished.v1` |
| `AppEvent.GameResigned` | `game.resigned.v1` |
| `AppEvent.SessionCancelled` | `game.session.cancelled.v1` |
| `GameStatus.Checkmate(winner)` | `result: "Checkmate", winner: "White"\|"Black"` |
| `GameStatus.Draw(reason)` | `result: "Draw", drawReason: "Stalemate"` |
| `Color.White` / `Color.Black` | `"White"` / `"Black"` |
| `SessionMode.HumanVsHuman` | `"HumanVsHuman"` (unchanged) |
| `SideController.HumanLocal` | `"HumanLocal"` (unchanged) |
| `SideController.AI(None)` | `"AI"` |
| `SideController.AI(Some(id))` | `"AI:<id>"` |
| `SessionId` / `GameId` (opaque UUID) | UUID string, no wrapper |
| `Position.toString` | `"e2"` algebraic — already correct |
| `PieceType.Queen` etc. | `"Queen"` etc. — unchanged |

---

## 5. Versioning Policy

The `type` field carries the version (`v1`). When a backward-incompatible change is
required, a new type name with `v2` suffix is introduced. Both versions are produced
in parallel during a migration window. Consumers select the version they support.

Additive changes (new optional fields) within a version are permitted without a
version bump. Consumers must ignore unknown fields.

---

## 6. What Is NOT in v1

The following are explicitly deferred:

- Event envelope metadata (timestamp, source, correlation id)
- Delivery guarantees / at-least-once semantics
- Broker/topic routing
- Consumer group semantics
- `game.move.rejected.v1` (internal audit event; not a boundary concern in v1)
- `game.ai_turn.*` events (AI monitoring; internal in v1)
