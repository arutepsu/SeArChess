# History / Notation Boundary

This note defines the intended boundary between the active Game Service, the
existing notation capability, and a future History / Notation service. It does
not introduce a deployable service yet.

## Current Inventory

Relevant modules and capabilities:

- `domain`: owns canonical chess state, rules, `GameState`, moves, board, and
  result status.
- `application`: owns active sessions, Game Service orchestration, persistence
  ports, and `AppEvent` publication.
- `notation`: owns pure PGN/FEN parsing, import, replay, and export APIs.
- `adapter-rest-http4s`: exposes active game/session HTTP endpoints.
- `adapter-websocket` / `adapter-event`: fan out in-process `AppEvent`
  notifications.
- `adapter-persistence`: stores active session and active game state for the
  modular monolith.

The notation module is already format-focused rather than service-focused. Its
public facades (`PgnNotationFacade`, `FenNotationFacade`) operate on
`GameState`. PGN export currently serializes movetext and result from
`GameState`, but intentionally does not emit PGN headers because `GameState`
does not carry session metadata such as players, dates, event name, or site.

## Ownership

The active Game Service owns:

- active game truth and legal state transitions
- active session lifecycle
- active-game/session persistence
- authoritative move acceptance/rejection
- result-producing commands: checkmate, draw, resignation, cancellation
- publication of lifecycle/result events

The notation capability owns:

- PGN/FEN syntax, parsing, rendering, and import/export mechanics
- replay of notation into domain `GameState`
- notation warnings and notation-specific failures
- parser implementation choices

A future History / Notation service should own:

- durable archive records for completed or closed sessions
- replay-oriented views and timelines
- PGN/FEN export documents with metadata
- search indexes and historical query models
- long-term analytics derived from archived games

Game Service should not grow archive/search/replay read models long term. It may
produce an active-game export snapshot while the game is still in its active
store, but it should not become the archive.

## Terminal And Closure Conditions

The future archive policy should distinguish completed games from closed
sessions:

- Checkmate: archive as a completed decisive game.
- Draw, including stalemate: archive as a completed drawn game.
- Resignation: archive as a completed decisive game with resignation result.
- Cancellation: archive as a closed/cancelled session, not as a normal finished
  chess result.

Cancellation should not be ignored. It is useful for audit, tournament
administration, support/debugging, and bot/platform reconciliation. It should be
stored differently from a completed game because the underlying `GameState` is
left unchanged and no chess winner is recorded.

Promotion-pending and move-rejected events are not archive terminal conditions.
They may be useful for audit or analytics, but they should not create a finished
game archive record by themselves.

## Recommended Integration Model

Use a hybrid integration model:

1. Events detect that archival work is needed.
2. A pull/export contract materializes the full archive payload from the Game
   Service while the active records are still available.

Events alone are not sufficient for archive materialization today. `GameFinished`
and `GameResigned` carry identifiers and result facts, and `MoveApplied` carries
individual moves, but consumers do not receive a full ordered snapshot with
session metadata, final board, clocks, controllers, timestamps, and export-ready
metadata. Reconstructing archives solely from events would require durable event
ordering and replay guarantees that this project has intentionally not added
yet.

Pull-only integration would also be weaker: a history/archive component would
need polling or tight coupling to active persistence. Events are the right
trigger; a pull/export read is the right source for complete materialization.

## Candidate Contracts

The smallest stable event trigger set is:

- `game.finished` (`AppEvent.GameFinished`)
- `game.resigned` (`AppEvent.GameResigned`)
- `game.session.cancelled` (`AppEvent.SessionCancelled`)

A future archive/export read contract should return an application-owned
snapshot, not REST DTOs and not persistence records. Candidate shape:

```scala
final case class GameArchiveSnapshot(
  sessionId: SessionId,
  gameId: GameId,
  mode: SessionMode,
  whiteController: SideController,
  blackController: SideController,
  sessionLifecycle: SessionLifecycle,
  createdAt: Instant,
  updatedAt: Instant,
  finalState: GameState,
  closure: GameClosure
)

enum GameClosure:
  case Completed(status: GameStatus)
  case Resigned(winner: Color)
  case Cancelled
```

That snapshot would let a future History / Notation service:

- persist canonical archive metadata
- derive PGN movetext from `finalState.moveHistory`
- derive final FEN from `finalState`
- attach result and closure semantics without guessing from transport strings
- index by session/game id, lifecycle, result, and timestamps

PGN/FEN should remain a shared pure notation capability for now. Later, a
separate History / Notation service can own orchestration around archive export
documents, headers, storage, and search while reusing or extracting the pure
notation library.

## Current Payload Sufficiency

Current events are sufficient to trigger archival work because they include both
`sessionId` and `gameId`, plus the immediate result fact for finish/resign/cancel
paths.

Current events are not sufficient to build complete history records on their
own. Missing or intentionally external facts include:

- full final `GameState`
- full session metadata and timestamps
- complete ordered move history as one materialized record
- PGN header metadata
- occurrence timestamp / event version / durable event sequence
- cancellation reason, if that becomes a product requirement

Until durable eventing exists, history consumers should not rely on rebuilding
full archives from the event stream alone.

## Tradeoffs

Event-driven only:

- Good: low coupling, natural for future broker consumers.
- Bad: requires durable ordering and complete event payloads before it can
  produce reliable archives.

Pull/export only:

- Good: complete materialization from the authoritative Game Service.
- Bad: needs polling or tight scheduling and risks coupling consumers to active
  persistence availability.

Hybrid:

- Good: events provide low-coupling triggers; pull/export provides complete,
  authoritative materialization.
- Bad: requires a small future read/export contract and retry policy around the
  handoff.

The recommended path is hybrid.

## Recommendation For This Slice

Do not introduce a new code seam yet.

The current code already has the important ingredients: terminal events,
active-state reads, session reads, and pure notation export/import capabilities.
The missing piece is a deliberately designed application-owned archive snapshot.
Introducing it before the game-read projection work would risk creating another
parallel snapshot shape.

The next focused implementation slice should first define application-owned game
read/export projection for active Game Service. Once that shape is stable, add a
minimal `getArchiveSnapshot(gameId)` or equivalent Game Service read contract
that combines session metadata, final game state, and closure semantics for
history materialization.
